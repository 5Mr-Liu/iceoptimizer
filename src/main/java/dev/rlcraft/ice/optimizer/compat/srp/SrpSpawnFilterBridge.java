package dev.rlcraft.ice.optimizer.compat.srp;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.ArrayList;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.RandomAccess;
import java.util.function.Function;
import org.agrona.collections.Int2IntHashMap;

/** Result-equivalent compiled path for SRPMixins' per-spawn-list filter. */
public final class SrpSpawnFilterBridge {
    private static final int MODULE_ORDINAL = OptimizationModule.SRP_SPAWN_FILTER.ordinal();
    private static final int UNSEEN = -1;
    private static final byte FALSE = 1;
    private static final byte TRUE = 2;
    private static final CompiledList EMPTY = new CompiledList(new Object[0], new int[0],
        new Class<?>[0], new int[0], new int[0], 0, 0);
    private static volatile int configurationEpoch;
    private static final ThreadLocal<ScratchPool> SCRATCH = new ThreadLocal<ScratchPool>() {
        @Override protected ScratchPool initialValue() { return new ScratchPool(); }
    };
    private static volatile boolean activated;

    private SrpSpawnFilterBridge() {
    }

    /** Returns null when the untouched SRPMixins implementation must run. */
    public static List<?> tryFilter(SrpSpawnFilterCallbacks callbacks, List<?> source,
                                    Object saveData, Object worldData,
                                    boolean parasiteBiome, int dimensionId) {
        if (!OptimizerBridge.isEnabled(MODULE_ORDINAL)) return null;
        CompiledList compiled = source instanceof SpawnList
            ? ((SpawnList<?>) source).compiled(callbacks) : compile(callbacks, source);
        ScratchPool pool = SCRATCH.get();
        Scratch scratch = pool.acquire();
        try {
            scratch.reset(callbacks, compiled.parasiteCount, compiled.classCount);
            List<Object> result = new ArrayList<Object>(compiled.size);
            for (int i = 0; i < compiled.size; i++) {
                int parasiteId = compiled.parasiteIds[i];
                if (parasiteId == Integer.MIN_VALUE) continue;
                int parasiteIndex = compiled.parasiteIndices[i];
                byte blocked = scratch.parasiteChecks[parasiteIndex];
                if (blocked == 0) {
                    blocked = callbacks.ice$spawnCheckParasiteId(saveData, parasiteId)
                        ? TRUE : FALSE;
                    scratch.parasiteChecks[parasiteIndex] = blocked;
                }
                if (blocked == TRUE) continue;

                byte colonyLocked = scratch.colonyChecks[parasiteIndex];
                if (colonyLocked == 0) {
                    colonyLocked = callbacks.ice$spawnColonyLocked(
                        parasiteId, worldData, parasiteBiome, scratch) ? TRUE : FALSE;
                    scratch.colonyChecks[parasiteIndex] = colonyLocked;
                }
                if (colonyLocked == TRUE) continue;

                Class<?> entityClass = compiled.entityClasses[i];
                int classIndex = compiled.classIndices[i];
                byte subCapLocked = scratch.subCapChecks[classIndex];
                if (subCapLocked == 0) {
                    subCapLocked = callbacks.ice$spawnSubCapLocked(entityClass, dimensionId)
                        ? TRUE : FALSE;
                    scratch.subCapChecks[classIndex] = subCapLocked;
                }
                if (subCapLocked == FALSE) result.add(compiled.entries[i]);
            }
            if (!activated) {
                activated = true;
                OptimizerBridge.activate(MODULE_ORDINAL,
                    "SRPMixins 刷怪列表已使用顺序不变的编译数组和单调用 primitive 检查缓存");
            }
            return result;
        } finally {
            scratch.callbacks = null;
            pool.release(scratch);
        }
    }

    public static void invalidate() {
        configurationEpoch++;
    }

    /** Called once where SRPMixins publishes a newly collected list. */
    public static <E> List<E> own(List<E> source) {
        return source instanceof SpawnList ? source : new SpawnList<E>(source);
    }

    /** These lists own their compiled representation; mutation invalidates it at the source. */
    public static final class SpawnList<E> extends AbstractList<E> implements RandomAccess {
        private final ArrayList<E> values;
        private CompiledList compiled;
        private int epoch;

        public SpawnList() { values = new ArrayList<E>(); }
        public SpawnList(Collection<? extends E> source) { values = new ArrayList<E>(source); }
        @Override public E get(int index) { return values.get(index); }
        @Override public int size() { return values.size(); }
        @Override public E set(int index, E value) {
            E previous = values.set(index, value);
            compiled = null;
            return previous;
        }
        @Override public void add(int index, E value) {
            values.add(index, value);
            changed();
        }
        @Override public E remove(int index) {
            E previous = values.remove(index);
            changed();
            return previous;
        }
        @Override public boolean addAll(int index, Collection<? extends E> source) {
            boolean modified = values.addAll(index, source);
            if (modified) changed();
            return modified;
        }
        @Override public void clear() { values.clear(); changed(); }
        @Override protected void removeRange(int from, int to) {
            values.subList(from, to).clear();
            changed();
        }
        private void changed() { compiled = null; modCount++; }
        private CompiledList compiled(SrpSpawnFilterCallbacks callbacks) {
            if (compiled == null || epoch != configurationEpoch) {
                compiled = compile(callbacks, values);
                epoch = configurationEpoch;
            }
            return compiled;
        }
    }

    /** Phase lists are constructed by SRPMixins' computeIfAbsent mapping function. */
    public static final class SpawnMap extends HashMap<Byte, List<?>> {
        @Override public List<?> computeIfAbsent(Byte phase,
                Function<? super Byte, ? extends List<?>> factory) {
            return super.computeIfAbsent(phase, key -> {
                List<?> created = factory.apply(key);
                return created == null ? null : own(created);
            });
        }
    }

    /** Only called at the original colony-total instruction, after its existing early exits. */
    public static int colonyPoints(Object worldData, int age, Object invocation) {
        Scratch scratch = (Scratch) invocation;
        if (!scratch.colonyTotalKnown) {
            scratch.colonyTotal = scratch.callbacks.ice$spawnColonyPoints(worldData, age);
            scratch.colonyTotalKnown = true;
        }
        return scratch.colonyTotal;
    }

    private static CompiledList compile(SrpSpawnFilterCallbacks callbacks, List<?> source) {
        int size = source.size();
        if (size == 0) return EMPTY;
        Object[] entries = new Object[size];
        int[] parasiteIds = new int[size];
        int[] parasiteIndices = new int[size];
        int[] classIndices = new int[size];
        Class<?>[] entityClasses = new Class<?>[size];
        Int2IntHashMap parasiteIndex = new Int2IntHashMap(16, 0.65F, UNSEEN, true);
        IdentityHashMap<Class<?>, Integer> classIndex = new IdentityHashMap<Class<?>, Integer>();
        int i = 0;
        for (Object wrapper : source) {
            Object entry = callbacks.ice$spawnEntry(wrapper);
            entries[i] = entry;
            parasiteIds[i] = callbacks.ice$spawnParaId(wrapper);
            entityClasses[i] = callbacks.ice$spawnEntityClass(entry);
            int idIndex = parasiteIndex.get(parasiteIds[i]);
            if (idIndex == UNSEEN) {
                idIndex = parasiteIndex.size();
                parasiteIndex.put(parasiteIds[i], idIndex);
            }
            parasiteIndices[i] = idIndex;
            Integer typeIndex = classIndex.get(entityClasses[i]);
            if (typeIndex == null) {
                typeIndex = Integer.valueOf(classIndex.size());
                classIndex.put(entityClasses[i], typeIndex);
            }
            classIndices[i] = typeIndex.intValue();
            i++;
        }
        return new CompiledList(entries, parasiteIds, entityClasses,
            parasiteIndices, classIndices, parasiteIndex.size(), classIndex.size());
    }

    private static final class CompiledList {
        private final Object[] entries;
        private final int[] parasiteIds;
        private final Class<?>[] entityClasses;
        private final int[] parasiteIndices, classIndices;
        private final int parasiteCount, classCount;
        private final int size;

        private CompiledList(Object[] entries, int[] parasiteIds,
                             Class<?>[] entityClasses, int[] parasiteIndices,
                             int[] classIndices, int parasiteCount, int classCount) {
            this.entries = entries;
            this.parasiteIds = parasiteIds;
            this.entityClasses = entityClasses;
            this.parasiteIndices = parasiteIndices;
            this.classIndices = classIndices;
            this.parasiteCount = parasiteCount;
            this.classCount = classCount;
            this.size = entries.length;
        }
    }

    private static final class ScratchPool {
        private Scratch free;
        Scratch acquire() {
            Scratch result = free;
            if (result == null) return new Scratch();
            free = result.next;
            result.next = null;
            return result;
        }
        void release(Scratch scratch) { scratch.next = free; free = scratch; }
    }

    private static final class Scratch {
        private Scratch next;
        private SrpSpawnFilterCallbacks callbacks;
        private boolean colonyTotalKnown;
        private int colonyTotal;
        private byte[] parasiteChecks = new byte[0];
        private byte[] colonyChecks = new byte[0];
        private byte[] subCapChecks = new byte[0];

        private void reset(SrpSpawnFilterCallbacks callbacks, int parasiteCount, int classCount) {
            this.callbacks = callbacks;
            colonyTotalKnown = false;
            // The list's captured keys were compressed once at compilation.
            // Dynamic decisions live only in this invocation's dense slots.
            if (parasiteChecks.length < parasiteCount) {
                parasiteChecks = new byte[parasiteCount];
                colonyChecks = new byte[parasiteCount];
            } else {
                Arrays.fill(parasiteChecks, 0, parasiteCount, (byte) 0);
                Arrays.fill(colonyChecks, 0, parasiteCount, (byte) 0);
            }
            if (subCapChecks.length < classCount) subCapChecks = new byte[classCount];
            else Arrays.fill(subCapChecks, 0, classCount, (byte) 0);
        }
    }
}
