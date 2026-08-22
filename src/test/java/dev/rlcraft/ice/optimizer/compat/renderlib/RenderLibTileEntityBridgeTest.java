package dev.rlcraft.ice.optimizer.compat.renderlib;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;
import org.agrona.collections.ObjectHashSet;
import org.junit.Test;
import sun.misc.Unsafe;

public class RenderLibTileEntityBridgeTest {
    @Test
    public void resetReleasesMembershipReferenceBudgetAndUseGuard()
        throws Exception {
        RenderLibTileEntityBridge.reset();
        CacheBudget budget = new CacheBudget(8192L, 1L, 1L);
        CacheBudget.Reservation reservation = budget.tryReserve(
            BudgetKind.HEAP, 4096L);
        ObjectHashSet<TileEntity> owned =
            new ObjectHashSet<TileEntity>(16, 0.65F);
        owned.add(new TileEntityChest());
        setStatic("membership", owned);
        setStatic("membershipCapacity", Integer.valueOf(16));
        setStatic("membershipReservation", reservation);
        AtomicBoolean inUse = (AtomicBoolean) getStatic("MEMBERSHIP_IN_USE");
        inUse.set(false);
        try {
            assertEquals(4096L, budget.snapshot().getHeapUsed());

            RenderLibTileEntityBridge.reset();

            assertSame(null, getStatic("membership"));
            assertEquals(0, ((Integer) getStatic("membershipCapacity")).intValue());
            assertSame(null, getStatic("membershipReservation"));
            assertTrue(owned.isEmpty());
            assertEquals(0L, budget.snapshot().getHeapUsed());
            assertFalse(inUse.get());
        } finally {
            inUse.set(false);
            RenderLibTileEntityBridge.reset();
            reservation.close();
        }
    }

    @Test
    public void legacyAndHolderVariantsDeliverTheExactOriginalListShape() throws Exception {
        OptimizerRegistry.breaker(OptimizationModule.RENDERLIB_VISIBILITY).configure(true, 3);
        OptimizerRegistry.breaker(OptimizationModule.RENDERLIB_VISIBILITY)
            .patchInstalled("TileEntityUtil", "test");
        try {
            List<TileEntity> loaded = new ArrayList<TileEntity>();
            List<TileEntity> holder = new ArrayList<TileEntity>();
            List<TileEntity> pending = new ArrayList<TileEntity>();
            HolderWorld world = allocate(loaded, holder, pending);
            final AtomicReference<List<TileEntity>> accepted = new AtomicReference<List<TileEntity>>();
            Consumer<List<TileEntity>> consumer = new Consumer<List<TileEntity>>() {
                @Override public void accept(List<TileEntity> value) { accepted.set(value); }
            };

            assertTrue(RenderLibTileEntityBridge.tryProcess(world, consumer));
            assertSame(loaded, accepted.get());
            assertFalse(processing(world));

            accepted.set(null);
            assertTrue(RenderLibTileEntityBridge.tryProcessHolder(world, consumer));
            assertSame(holder, accepted.get());
            assertFalse(processing(world));
        } finally {
            OptimizerRegistry.breaker(OptimizationModule.RENDERLIB_VISIBILITY).configure(false, 3);
        }
    }

    @Test
    public void callbackPrimarySurvivesProcessingFlagRestoreFailure()
        throws Exception {
        OptimizerRegistry.breaker(OptimizationModule.RENDERLIB_VISIBILITY)
            .configure(true, 3);
        OptimizerRegistry.breaker(OptimizationModule.RENDERLIB_VISIBILITY)
            .patchInstalled("TileEntityUtil", "test");
        List<TileEntity> loaded = new ArrayList<TileEntity>();
        List<TileEntity> pending = new ArrayList<TileEntity>();
        final HolderWorld world = allocate(loaded,
            new ArrayList<TileEntity>(), pending);
        MethodAccess.resolveBridgeFields();
        final Field bridgeField = RenderLibTileEntityBridge.class
            .getDeclaredField("processingLoadedTilesField");
        bridgeField.setAccessible(true);
        final Field original = (Field) bridgeField.get(null);
        final Field incompatible = RestoreFailureOwner.class
            .getDeclaredField("processing");
        incompatible.setAccessible(true);
        final IllegalStateException primary = new IllegalStateException(
            "callback primary");
        try {
            Consumer<List<TileEntity>> consumer =
                new Consumer<List<TileEntity>>() {
                    @Override public void accept(List<TileEntity> ignored) {
                        try {
                            bridgeField.set(null, incompatible);
                        } catch (IllegalAccessException impossible) {
                            throw new AssertionError(impossible);
                        }
                        throw primary;
                    }
                };
            try {
                RenderLibTileEntityBridge.tryProcess(world, consumer);
                throw new AssertionError("callback failure was swallowed");
            } catch (IllegalStateException expected) {
                assertSame(primary, expected);
                assertEquals(1, expected.getSuppressed().length);
                assertTrue(expected.getSuppressed()[0]
                    instanceof IllegalArgumentException);
            }
        } finally {
            bridgeField.set(null, original);
            original.setBoolean(world, false);
            OptimizerRegistry.breaker(OptimizationModule.RENDERLIB_VISIBILITY)
                .configure(false, 3);
        }
    }

    private static HolderWorld allocate(List<TileEntity> loaded, List<TileEntity> holder,
                                        List<TileEntity> pending) throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        HolderWorld world = (HolderWorld) unsafe.allocateInstance(HolderWorld.class);
        world.holderTiles = holder;
        set(World.class, world, "loadedTileEntityList", loaded);
        set(World.class, world, "addedTileEntityList", pending);
        Field processing = World.class.getDeclaredField("processingLoadedTiles");
        processing.setAccessible(true);
        processing.setBoolean(world, false);
        return world;
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void setStatic(String name, Object value) throws Exception {
        set(RenderLibTileEntityBridge.class, null, name, value);
    }

    private static Object getStatic(String name) throws Exception {
        Field field = RenderLibTileEntityBridge.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static boolean processing(World world) throws Exception {
        Field field = World.class.getDeclaredField("processingLoadedTiles");
        field.setAccessible(true);
        return field.getBoolean(world);
    }

    private static final class HolderWorld extends World {
        private List<TileEntity> holderTiles;

        private HolderWorld() {
            super(null, null, null, null, false);
        }

        public List<TileEntity> getTileEntities() {
            return holderTiles;
        }

        @Override protected IChunkProvider createChunkProvider() { return null; }
        @Override protected boolean isChunkLoaded(int x, int z, boolean allowEmpty) { return false; }
    }

    private static final class RestoreFailureOwner {
        @SuppressWarnings("unused") private boolean processing;
    }

    private static final class MethodAccess {
        private static void resolveBridgeFields() throws Exception {
            java.lang.reflect.Method resolve = RenderLibTileEntityBridge.class
                .getDeclaredMethod("resolveFields");
            resolve.setAccessible(true);
            resolve.invoke(null);
        }
    }
}
