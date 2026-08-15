package dev.rlcraft.ice.optimizer.compat.renderlib;

import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.agrona.collections.ObjectHashSet;

/** Exact replacement for RenderLib's pending tile-entity merge hot path. */
public final class RenderLibTileEntityBridge {
    private static final String MODULE = "renderlib-visibility";
    private static final int MIN_LOADED_FOR_SET = 64;
    private static final int MIN_PENDING_FOR_SET = 4;
    private static final int MAX_SET_CAPACITY = 1 << 20;
    private static final AtomicBoolean MEMBERSHIP_IN_USE = new AtomicBoolean();
    private static final ClassValue<Boolean> HASH_MEMBERSHIP_SAFE = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                Method equals = type.getMethod("equals", Object.class);
                Method hashCode = type.getMethod("hashCode");
                return Boolean.valueOf(equals.getDeclaringClass() == Object.class
                    && hashCode.getDeclaringClass() == Object.class);
            } catch (Throwable ignored) {
                return Boolean.FALSE;
            }
        }
    };

    private static Field processingLoadedTilesField;
    private static Field addedTileEntityListField;
    private static boolean fieldsResolved;
    private static volatile Class<?> holderGetterType;
    private static volatile Method holderGetter;
    private static ObjectHashSet<TileEntity> membership;
    private static int membershipCapacity;
    private static CacheBudget.Reservation membershipReservation;
    private static boolean activated;

    private RenderLibTileEntityBridge() {
    }

    @SuppressWarnings("unchecked")
    public static boolean tryProcess(World world, Consumer<List<TileEntity>> consumer) {
        return tryProcess(world, consumer, false);
    }

    @SuppressWarnings("unchecked")
    public static boolean tryProcessHolder(World world, Consumer<List<TileEntity>> consumer) {
        return tryProcess(world, consumer, true);
    }

    @SuppressWarnings("unchecked")
    private static boolean tryProcess(World world, Consumer<List<TileEntity>> consumer, boolean useHolderList) {
        if (!OptimizerBridge.isEnabled(MODULE) || world == null || consumer == null) return false;

        final List<TileEntity> pending;
        final List<TileEntity> loaded;
        final List<TileEntity> processing;
        try {
            resolveFields();
            pending = (List<TileEntity>) addedTileEntityListField.get(world);
            loaded = world.loadedTileEntityList;
            processing = useHolderList ? holderTiles(world) : loaded;
            if (pending == null || loaded == null || processing == null) {
                throw new IllegalStateException("RenderLib tile entity list is null");
            }
        } catch (Throwable error) {
            OptimizerBridge.failure(MODULE, error);
            return false;
        }
        if (!MEMBERSHIP_IN_USE.compareAndSet(false, true)) return false;

        try {
            processingLoadedTilesField.setBoolean(world, true);
            try {
                consumer.accept(processing);
            } finally {
                processingLoadedTilesField.setBoolean(world, false);
            }

            ObjectHashSet<TileEntity> loadedMembership;
            try {
                loadedMembership = prepareMembership(loaded, pending.size());
            } catch (Throwable cacheError) {
                OptimizerBridge.failure(MODULE, cacheError);
                loadedMembership = null;
            }
            int indexedLoadedSize = loadedMembership == null ? -1 : loaded.size();
            boolean membershipUsed = false;
            Iterator<TileEntity> iterator = pending.iterator();
            while (iterator.hasNext()) {
                TileEntity tileEntity = iterator.next();
                if (tileEntity.isInvalid()) continue;

                if (loadedMembership != null && loaded.size() != indexedLoadedSize) loadedMembership = null;
                boolean acceleratedContains = loadedMembership != null && supportsHashMembership(tileEntity);
                boolean alreadyLoaded;
                if (acceleratedContains) {
                    try {
                        alreadyLoaded = loadedMembership.contains(tileEntity);
                        membershipUsed = true;
                    } catch (Throwable cacheError) {
                        OptimizerBridge.failure(MODULE, cacheError);
                        loadedMembership = null;
                        alreadyLoaded = loaded.contains(tileEntity);
                    }
                } else {
                    alreadyLoaded = loaded.contains(tileEntity);
                }
                if (!alreadyLoaded) {
                    int previousLoadedSize = loaded.size();
                    boolean added = world.addTileEntity(tileEntity);
                    if (loadedMembership != null) {
                        int currentLoadedSize = loaded.size();
                        boolean expectedAppend = added
                            && (long) currentLoadedSize == (long) previousLoadedSize + 1L
                            && loaded.get(currentLoadedSize - 1) == tileEntity;
                        if (expectedAppend) {
                            indexedLoadedSize = currentLoadedSize;
                            if (supportsHashMembership(tileEntity)) {
                                try {
                                    loadedMembership.add(tileEntity);
                                } catch (Throwable cacheError) {
                                    OptimizerBridge.failure(MODULE, cacheError);
                                    loadedMembership = null;
                                }
                            }
                        } else if (added || currentLoadedSize != previousLoadedSize) {
                            loadedMembership = null;
                        }
                    }
                }

                if (world.isBlockLoaded(tileEntity.getPos())) {
                    Chunk chunk = world.getChunk(tileEntity.getPos());
                    IBlockState state = chunk.getBlockState(tileEntity.getPos());
                    chunk.addTileEntity(tileEntity.getPos(), tileEntity);
                    world.notifyBlockUpdate(tileEntity.getPos(), state, state, 3);
                }
            }
            pending.clear();
            if (membershipUsed && !activated) {
                activated = true;
                OptimizerBridge.activate(MODULE, "RenderLib 方块实体合并已使用有界 Agrona 成员表");
            }
            return true;
        } catch (Throwable error) {
            OptimizerBridge.failure(MODULE, error);
            throw propagate(error);
        } finally {
            MEMBERSHIP_IN_USE.set(false);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<TileEntity> holderTiles(World world) throws Exception {
        Method getter = holderGetter;
        Class<?> type = world.getClass();
        if (getter == null || holderGetterType != type) getter = resolveHolderGetter(type);
        Object value = getter.invoke(world);
        if (!(value instanceof List)) {
            throw new IllegalStateException("RenderLib ITileEntityHolder returned "
                + (value == null ? "null" : value.getClass().getName()));
        }
        return (List<TileEntity>) value;
    }

    private static synchronized Method resolveHolderGetter(Class<?> type) throws NoSuchMethodException {
        if (holderGetter != null && holderGetterType == type) return holderGetter;
        Method getter = type.getMethod("getTileEntities");
        if (!List.class.isAssignableFrom(getter.getReturnType())) {
            throw new NoSuchMethodException(type.getName() + ".getTileEntities does not return List");
        }
        getter.setAccessible(true);
        holderGetterType = type;
        holderGetter = getter;
        return getter;
    }

    private static ObjectHashSet<TileEntity> prepareMembership(List<TileEntity> loaded, int pendingCount) {
        if (loaded.size() < MIN_LOADED_FOR_SET || pendingCount < MIN_PENDING_FOR_SET) return null;
        long required = (long) loaded.size() + (long) pendingCount + 1L;
        if (required > MAX_SET_CAPACITY) return null;
        long targetCapacity = required * 2L;
        int capacity = 16;
        while ((long) capacity < targetCapacity && capacity < MAX_SET_CAPACITY) capacity <<= 1;
        if (capacity < required || capacity > MAX_SET_CAPACITY) return null;

        if (membership == null || membershipCapacity < capacity) {
            long estimatedBytes = (long) capacity * 8L + 1024L;
            CacheBudget.Reservation replacement = ClientOptimizerRuntime.INSTANCE.tryReserve(BudgetKind.HEAP, estimatedBytes);
            if (replacement == null) return null;
            ObjectHashSet<TileEntity> replacementSet;
            try {
                replacementSet = new ObjectHashSet<TileEntity>(capacity, 0.65F);
            } catch (Throwable error) {
                replacement.close();
                throw error;
            }
            CacheBudget.Reservation previous = membershipReservation;
            membership = replacementSet;
            membershipCapacity = capacity;
            membershipReservation = replacement;
            if (previous != null) previous.close();
        }
        membership.clear();
        for (TileEntity tileEntity : loaded) {
            if (supportsHashMembership(tileEntity)) membership.add(tileEntity);
        }
        return membership;
    }

    private static boolean supportsHashMembership(TileEntity tileEntity) {
        return tileEntity != null && HASH_MEMBERSHIP_SAFE.get(tileEntity.getClass()).booleanValue();
    }

    private static synchronized void resolveFields() throws Exception {
        if (fieldsResolved) return;
        Field processing = findField(World.class, "processingLoadedTiles", "field_147481_N");
        Field added = findField(World.class, "addedTileEntityList", "field_147484_a");
        processing.setAccessible(true);
        added.setAccessible(true);
        processingLoadedTilesField = processing;
        addedTileEntityListField = added;
        fieldsResolved = true;
    }

    private static Field findField(Class<?> owner, String... names) throws NoSuchFieldException {
        for (String name : names) {
            try {
                return owner.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(owner.getName() + " " + java.util.Arrays.toString(names));
    }

    private static RuntimeException propagate(Throwable error) {
        if (error instanceof RuntimeException) return (RuntimeException) error;
        if (error instanceof Error) throw (Error) error;
        return new IllegalStateException(error);
    }
}
