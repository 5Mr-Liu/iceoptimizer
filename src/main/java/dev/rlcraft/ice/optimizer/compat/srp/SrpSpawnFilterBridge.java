package dev.rlcraft.ice.optimizer.compat.srp;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.RandomAccess;
import org.agrona.collections.Int2IntHashMap;

/** Result-equivalent compiled path for SRPMixins' per-spawn-list filter. */
public final class SrpSpawnFilterBridge {
    private static final int MODULE_ORDINAL = OptimizationModule.SRP_SPAWN_FILTER.ordinal();
    private static final int UNSEEN = -1;
    private static final int FALSE = 0;
    private static final int TRUE = 1;
    private static final int MIN_COMPILED_ENTRIES = 4;
    private static final Cache<Object, CompiledList> COMPILED = Caffeine.newBuilder()
        .maximumSize(128L)
        .weakKeys()
        .build();
    private static final ThreadLocal<Scratch> SCRATCH = new ThreadLocal<Scratch>() {
        @Override protected Scratch initialValue() { return new Scratch(); }
    };
    private static volatile boolean activated;

    private SrpSpawnFilterBridge() {
    }

    /** Returns null when the untouched SRPMixins implementation must run. */
    public static List<?> tryFilter(SrpSpawnFilterCallbacks callbacks, List<?> source,
                                    Object saveData, Object worldData,
                                    boolean parasiteBiome, int dimensionId) {
        if (!OptimizerBridge.isEnabled(MODULE_ORDINAL) || callbacks == null || source == null
            || saveData == null || worldData == null || !(source instanceof RandomAccess)
            || source.size() < MIN_COMPILED_ENTRIES) return null;
        try {
            CompiledList compiled = COMPILED.getIfPresent(source);
            if (compiled == null || !compiled.matches(source)) {
                compiled = compile(callbacks, source);
                COMPILED.put(source, compiled);
            }
            Scratch scratch = SCRATCH.get();
            scratch.reset();
            List<Object> result = new ArrayList<Object>(compiled.size);
            for (int i = 0; i < compiled.size; i++) {
                int parasiteId = compiled.parasiteIds[i];
                if (parasiteId == Integer.MIN_VALUE) continue;
                int blocked = scratch.parasiteChecks.get(parasiteId);
                if (blocked == UNSEEN) {
                    blocked = callbacks.ice$spawnCheckParasiteId(saveData, parasiteId)
                        ? TRUE : FALSE;
                    scratch.parasiteChecks.put(parasiteId, blocked);
                }
                if (blocked == TRUE) continue;

                int colonyLocked = scratch.colonyChecks.get(parasiteId);
                if (colonyLocked == UNSEEN) {
                    colonyLocked = callbacks.ice$spawnColonyLocked(
                        parasiteId, worldData, parasiteBiome) ? TRUE : FALSE;
                    scratch.colonyChecks.put(parasiteId, colonyLocked);
                }
                if (colonyLocked == TRUE) continue;

                Class<?> entityClass = compiled.entityClasses[i];
                Boolean subCapLocked = scratch.subCapChecks.get(entityClass);
                if (subCapLocked == null) {
                    subCapLocked = Boolean.valueOf(
                        callbacks.ice$spawnSubCapLocked(entityClass, dimensionId));
                    scratch.subCapChecks.put(entityClass, subCapLocked);
                }
                if (!subCapLocked.booleanValue()) result.add(compiled.entries[i]);
            }
            if (!activated) {
                activated = true;
                OptimizerBridge.activate(MODULE_ORDINAL,
                    "SRPMixins 刷怪列表已使用顺序不变的编译数组和单调用 primitive 检查缓存");
            }
            OptimizerBridge.success(MODULE_ORDINAL);
            return result;
        } catch (Throwable error) {
            OptimizerBridge.failure(MODULE_ORDINAL, error);
            return null;
        }
    }

    public static void invalidate() {
        COMPILED.invalidateAll();
    }

    static int minimumCompiledEntriesForTest() {
        return MIN_COMPILED_ENTRIES;
    }

    private static CompiledList compile(SrpSpawnFilterCallbacks callbacks, List<?> source) {
        int size = source.size();
        Object[] wrappers = new Object[size];
        Object[] entries = new Object[size];
        int[] parasiteIds = new int[size];
        Class<?>[] entityClasses = new Class<?>[size];
        for (int i = 0; i < size; i++) {
            Object wrapper = source.get(i);
            Object entry = callbacks.ice$spawnEntry(wrapper);
            wrappers[i] = wrapper;
            entries[i] = entry;
            parasiteIds[i] = callbacks.ice$spawnParaId(wrapper);
            entityClasses[i] = callbacks.ice$spawnEntityClass(entry);
        }
        return new CompiledList(wrappers, entries, parasiteIds, entityClasses);
    }

    private static final class CompiledList {
        private final Object[] wrappers;
        private final Object[] entries;
        private final int[] parasiteIds;
        private final Class<?>[] entityClasses;
        private final int size;

        private CompiledList(Object[] wrappers, Object[] entries, int[] parasiteIds,
                             Class<?>[] entityClasses) {
            this.wrappers = wrappers;
            this.entries = entries;
            this.parasiteIds = parasiteIds;
            this.entityClasses = entityClasses;
            this.size = wrappers.length;
        }

        private boolean matches(List<?> source) {
            if (source.size() != size) return false;
            for (int i = 0; i < size; i++) {
                if (source.get(i) != wrappers[i]) return false;
            }
            return true;
        }
    }

    private static final class Scratch {
        private final Int2IntHashMap parasiteChecks =
            new Int2IntHashMap(16, 0.65F, UNSEEN, true);
        private final Int2IntHashMap colonyChecks =
            new Int2IntHashMap(16, 0.65F, UNSEEN, true);
        private final IdentityHashMap<Class<?>, Boolean> subCapChecks =
            new IdentityHashMap<Class<?>, Boolean>(16);

        private void reset() {
            parasiteChecks.clear();
            colonyChecks.clear();
            subCapChecks.clear();
        }
    }
}
