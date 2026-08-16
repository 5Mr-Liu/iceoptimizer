package dev.rlcraft.ice.optimizer.compat.srp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class SrpSpawnFilterBridgeTest {
    @Test
    public void preservesOrderAndMemoizesRepeatedChecksWithinOneFilterCall() {
        enable();
        SrpSpawnFilterBridge.invalidate();
        CountingCallbacks callbacks = new CountingCallbacks();
        List<Wrapper> source = new ArrayList<Wrapper>(Arrays.asList(
            new Wrapper(1, "a", String.class),
            new Wrapper(1, "b", String.class),
            new Wrapper(2, "blocked-a", Integer.class),
            new Wrapper(1, "c", String.class),
            new Wrapper(2, "blocked-b", Integer.class),
            new Wrapper(Integer.MIN_VALUE, "invalid", Object.class)));
        try {
            List<?> result = SrpSpawnFilterBridge.tryFilter(
                callbacks, source, new Object(), new Object(), false, 0);
            assertEquals(Arrays.asList("a", "b", "c"), result);
            assertEquals(2, callbacks.parasiteChecks);
            assertEquals(1, callbacks.colonyChecks);
            assertEquals(1, callbacks.subCapChecks);
            assertEquals(source.size(), callbacks.compiledEntries);

            SrpSpawnFilterBridge.tryFilter(
                callbacks, source, new Object(), new Object(), false, 0);
            assertEquals("stable source must reuse compiled wrapper fields",
                source.size(), callbacks.compiledEntries);

            source.set(0, new Wrapper(1, "replacement", String.class));
            List<?> changed = SrpSpawnFilterBridge.tryFilter(
                callbacks, source, new Object(), new Object(), false, 0);
            assertEquals("replacement", changed.get(0));
            assertEquals(source.size() * 2, callbacks.compiledEntries);
        } finally {
            OptimizerRegistry.breaker(OptimizationModule.SRP_SPAWN_FILTER).configure(false, 3);
            SrpSpawnFilterBridge.invalidate();
        }
    }

    @Test
    public void tinyOrNonRandomAccessListsUseOriginalFallback() {
        enable();
        try {
            CountingCallbacks callbacks = new CountingCallbacks();
            assertEquals(4, SrpSpawnFilterBridge.minimumCompiledEntriesForTest());
            assertNull(SrpSpawnFilterBridge.tryFilter(callbacks,
                Arrays.asList(new Wrapper(1, "a", String.class)),
                new Object(), new Object(), false, 0));
            assertEquals(0, callbacks.compiledEntries);
        } finally {
            OptimizerRegistry.breaker(OptimizationModule.SRP_SPAWN_FILTER).configure(false, 3);
        }
    }

    private static void enable() {
        OptimizerRegistry.breaker(OptimizationModule.SRP_SPAWN_FILTER).configure(true, 3);
        OptimizerRegistry.breaker(OptimizationModule.SRP_SPAWN_FILTER)
            .patchInstalled("srpmixins.handlers.SpawnPotentialsHandler", "test");
    }

    private static final class Wrapper {
        private final int id;
        private final Object entry;
        private final Class<?> entityClass;

        private Wrapper(int id, Object entry, Class<?> entityClass) {
            this.id = id;
            this.entry = entry;
            this.entityClass = entityClass;
        }
    }

    private static final class CountingCallbacks implements SrpSpawnFilterCallbacks {
        private int parasiteChecks;
        private int colonyChecks;
        private int subCapChecks;
        private int compiledEntries;

        @Override public int ice$spawnParaId(Object wrapper) {
            return ((Wrapper) wrapper).id;
        }

        @Override public Object ice$spawnEntry(Object wrapper) {
            compiledEntries++;
            return ((Wrapper) wrapper).entry;
        }

        @Override public Class<?> ice$spawnEntityClass(Object entry) {
            if (entry instanceof String) return String.class;
            return Integer.class;
        }

        @Override public boolean ice$spawnCheckParasiteId(Object saveData, int parasiteId) {
            parasiteChecks++;
            return parasiteId == 2;
        }

        @Override public boolean ice$spawnColonyLocked(int parasiteId, Object worldData,
                                                       boolean parasiteBiome) {
            colonyChecks++;
            return false;
        }

        @Override public boolean ice$spawnSubCapLocked(Class<?> entityClass, int dimensionId) {
            subCapChecks++;
            return false;
        }
    }
}
