package dev.rlcraft.ice.optimizer.compat.srp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import org.junit.Test;

public final class SrpSpawnFilterBridgeTest {
    @Test
    public void preservesOrderAndMemoizesRepeatedChecksWithinOneFilterCall() {
        enable();
        SrpSpawnFilterBridge.invalidate();
        CountingCallbacks callbacks = new CountingCallbacks();
        List<Wrapper> source = new SrpSpawnFilterBridge.SpawnList<Wrapper>(Arrays.asList(
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
    public void shortListsAndSequentialListsShareColonyWorkWithoutAnAdmissionThreshold() {
        enable();
        try {
            for (int size = 0; size <= 3; size++) {
                for (boolean sequential : new boolean[] {false, true}) {
                    CountingCallbacks callbacks = new CountingCallbacks();
                    callbacks.requireColony = true;
                    List<Wrapper> source = sequential ? new SequentialList() : new SrpSpawnFilterBridge.SpawnList<Wrapper>();
                    List<String> expected = new ArrayList<String>();
                    for (int i = 0; i < size; i++) {
                        source.add(new Wrapper(3 + i, "entry" + i, String.class));
                        expected.add("entry" + i);
                    }
                    int[] colonies = {100};
                    assertEquals(expected, SrpSpawnFilterBridge.tryFilter(
                        callbacks, source, new Object(), colonies, false, 0));
                    assertEquals(size == 0 ? 0 : 1, callbacks.colonyTotals);
                    assertEquals(expected, SrpSpawnFilterBridge.tryFilter(
                        callbacks, source, new Object(), colonies, false, 0));
                    assertEquals(size * (sequential ? 2 : 1), callbacks.compiledEntries);
                    if (size > 0) {
                        source.set(0, new Wrapper(3, "replacement", String.class));
                        expected.set(0, "replacement");
                        assertEquals(expected, SrpSpawnFilterBridge.tryFilter(
                            callbacks, source, new Object(), colonies, false, 0));
                        assertEquals(size * (sequential ? 3 : 2), callbacks.compiledEntries);
                    }
                }
            }
            assertEquals(Collections.emptyList(), SrpSpawnFilterBridge.tryFilter(null,
                Collections.emptyList(), null, null, false, 0));
        } finally {
            OptimizerRegistry.breaker(OptimizationModule.SRP_SPAWN_FILTER).configure(false, 3);
            SrpSpawnFilterBridge.invalidate();
        }
    }

    @Test public void runtimePauseStillUsesOriginalFilterAndResumeHandlesAShortList() {
        enable();
        List<Wrapper> source = Arrays.asList(new Wrapper(1, "a", String.class));
        try {
            OptimizerRegistry.setRuntimePaused(true);
            CountingCallbacks callbacks = new CountingCallbacks();
            assertNull(SrpSpawnFilterBridge.tryFilter(callbacks, source, new Object(), new Object(), false, 0));
            assertEquals(0, callbacks.compiledEntries);
            OptimizerRegistry.setRuntimePaused(false);
            assertNotNull(SrpSpawnFilterBridge.tryFilter(callbacks, source, new Object(), new Object(), false, 0));
        } finally {
            OptimizerRegistry.setRuntimePaused(false);
            OptimizerRegistry.breaker(OptimizationModule.SRP_SPAWN_FILTER).configure(false, 3);
            SrpSpawnFilterBridge.invalidate();
        }
    }

    private static final class SequentialList extends LinkedList<Wrapper> {
        @Override public Wrapper get(int index) {
            throw new AssertionError("A sequential list must be walked with an iterator");
        }
    }

    @Test public void ownedListsInvalidateOnSubListIteratorAndBulkMutation() {
        enable();
        try {
            java.util.Map<Byte, List<?>> phases = new SrpSpawnFilterBridge.SpawnMap();
            @SuppressWarnings("unchecked") List<Wrapper> source = (List<Wrapper>) phases.computeIfAbsent(
                Byte.valueOf((byte) 3), ignored -> new ArrayList<Wrapper>());
            source.add(new Wrapper(1, "a", String.class));
            source.add(new Wrapper(1, "b", String.class));
            CountingCallbacks callbacks = new CountingCallbacks();
            assertEquals(Arrays.asList("a", "b"), filter(callbacks, source));
            filter(callbacks, source);
            assertEquals(2, callbacks.compiledEntries);
            source.subList(0, 1).set(0, new Wrapper(1, "c", String.class));
            assertEquals(Arrays.asList("c", "b"), filter(callbacks, source));
            java.util.ListIterator<Wrapper> it = source.listIterator();
            it.next();
            it.remove();
            assertEquals(Arrays.asList("b"), filter(callbacks, source));
            source.replaceAll(value -> new Wrapper(1, "d", String.class));
            assertEquals(Arrays.asList("d"), filter(callbacks, source));
            source.subList(0, 1).clear();
            assertEquals(Collections.emptyList(), filter(callbacks, source));
            source.addAll(Arrays.asList(new Wrapper(1, "e", String.class),
                new Wrapper(1, "f", String.class)));
            assertEquals(Arrays.asList("e", "f"), filter(callbacks, source));
            int before = callbacks.compiledEntries;
            SrpSpawnFilterBridge.invalidate();
            filter(callbacks, source);
            assertEquals(before + 2, callbacks.compiledEntries);
        } finally {
            OptimizerRegistry.breaker(OptimizationModule.SRP_SPAWN_FILTER).configure(false, 3);
            SrpSpawnFilterBridge.invalidate();
        }
    }

    private static List<?> filter(CountingCallbacks callbacks, List<Wrapper> source) {
        return SrpSpawnFilterBridge.tryFilter(callbacks, source, null, null, false, 0);
    }

    @Test public void nestedFilterDoesNotOverwriteOuterInvocationChecks() {
        enable();
        try {
            final int[] outerChecks = {0};
            SrpSpawnFilterCallbacks callbacks = new SrpSpawnFilterCallbacks() {
                public Object ice$spawnEntry(Object value) { return value; }
                public int ice$spawnParaId(Object value) { return ((Integer) value).intValue(); }
                public Class<?> ice$spawnEntityClass(Object value) { return Integer.class; }
                public boolean ice$spawnCheckParasiteId(Object data, int id) {
                    if (Boolean.TRUE.equals(data)) return true;
                    outerChecks[0]++;
                    assertEquals(Collections.emptyList(), SrpSpawnFilterBridge.tryFilter(this,
                        Arrays.asList(1, 3), Boolean.TRUE, null, false, 0));
                    return false;
                }
                public boolean ice$spawnColonyLocked(int id, Object data, boolean biome, Object context) { return false; }
                public int ice$spawnColonyPoints(Object data, int age) { return 0; }
                public boolean ice$spawnSubCapLocked(Class<?> type, int dim) { return false; }
            };
            assertEquals(Arrays.asList(1, 3, 1), SrpSpawnFilterBridge.tryFilter(callbacks,
                Arrays.asList(1, 3, 1), Boolean.FALSE, null, false, 0));
            assertEquals(2, outerChecks[0]);
        } finally {
            OptimizerRegistry.breaker(OptimizationModule.SRP_SPAWN_FILTER).configure(false, 3);
        }
    }

    @Test
    public void distinctIdsShareOneLazyColonyTotalButSeparateInvocationsSeeUpdates() {
        enable();
        try {
            CountingCallbacks callbacks = new CountingCallbacks();
            callbacks.requireColony = true;
            List<Wrapper> source = Arrays.asList(new Wrapper(1, "a", String.class),
                new Wrapper(3, "b", String.class), new Wrapper(4, "c", String.class),
                new Wrapper(5, "d", String.class));
            int[] colonies = { 4 };
            assertEquals(Arrays.asList("a", "b", "c"), SrpSpawnFilterBridge.tryFilter(
                callbacks, source, new Object(), colonies, false, 0));
            assertEquals(1, callbacks.colonyTotals);
            colonies[0] = 2;
            assertEquals(Arrays.asList("a"), SrpSpawnFilterBridge.tryFilter(
                callbacks, source, new Object(), colonies, false, 0));
            assertEquals(2, callbacks.colonyTotals);
            callbacks.requireColony = false;
            assertEquals(4, SrpSpawnFilterBridge.tryFilter(
                callbacks, source, new Object(), colonies, false, 0).size());
            assertEquals("early-return colony checks must not compute totals", 2, callbacks.colonyTotals);
        } finally {
            OptimizerRegistry.breaker(OptimizationModule.SRP_SPAWN_FILTER).configure(false, 3);
            SrpSpawnFilterBridge.invalidate();
        }
    }

    private static void enable() {
        OptimizerRegistry.breaker(OptimizationModule.SRP_SPAWN_FILTER).configure(true, 3);
        OptimizerRegistry.breaker(OptimizationModule.SRP_SPAWN_FILTER)
            .patchInstalled("srpmixins.handlers.SpawnPotentialsHandler", "test");
    }

    @Test
    public void denseKeysMatchOriginalDecisionsAcrossSparseIdsClassesAndInvocationChanges() {
        enable();
        Random random = new Random(0x51f7cafeL);
        final Class<?>[] types = {String.class, Integer.class, Number.class, Object.class, null};
        final int[] ids = {Integer.MIN_VALUE, Integer.MIN_VALUE + 1, Integer.MAX_VALUE,
            -4096, -1, 0, 1, 7, 65536};
        final int[] generation = {0};
        SrpSpawnFilterCallbacks callbacks = new SrpSpawnFilterCallbacks() {
            @Override public Object ice$spawnEntry(Object wrapper) { return wrapper; }
            @Override public int ice$spawnParaId(Object wrapper) { return ((Wrapper) wrapper).id; }
            @Override public Class<?> ice$spawnEntityClass(Object entry) {
                return ((Wrapper) entry).entityClass;
            }
            @Override public boolean ice$spawnCheckParasiteId(Object data, int id) {
                return ((id ^ generation[0]) & 7) == 0;
            }
            @Override public boolean ice$spawnColonyLocked(int id, Object data,
                                                          boolean biome, Object invocation) {
                return !biome && (id & 7) > SrpSpawnFilterBridge.colonyPoints(data, 0, invocation);
            }
            @Override public int ice$spawnColonyPoints(Object data, int age) {
                return generation[0] & 7;
            }
            @Override public boolean ice$spawnSubCapLocked(Class<?> type, int dimension) {
                return type == types[dimension];
            }
        };
        try {
            for (int round = 0; round < 500; round++) {
                List<Wrapper> values = (round & 1) == 0
                    ? new ArrayList<Wrapper>() : new LinkedList<Wrapper>();
                int count = random.nextInt(130);
                for (int i = 0; i < count; i++) {
                    values.add(new Wrapper(ids[random.nextInt(ids.length)], null,
                        types[random.nextInt(types.length)]));
                }
                for (int call = 0; call < 3; call++) {
                    generation[0] = random.nextInt();
                    int dimension = random.nextInt(types.length);
                    boolean biome = random.nextBoolean();
                    List<Wrapper> expected = new ArrayList<Wrapper>();
                    for (Wrapper value : values) {
                        if (value.id == Integer.MIN_VALUE || ((value.id ^ generation[0]) & 7) == 0)
                            continue;
                        if (!biome && (value.id & 7) > (generation[0] & 7)) continue;
                        if (value.entityClass == types[dimension]) continue;
                        expected.add(value);
                    }
                    assertEquals(expected, SrpSpawnFilterBridge.tryFilter(
                        callbacks, values, null, null, biome, dimension));
                }
            }
        } finally {
            OptimizerRegistry.breaker(OptimizationModule.SRP_SPAWN_FILTER).configure(false, 3);
            SrpSpawnFilterBridge.invalidate();
        }
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
        private int colonyTotals;
        private boolean requireColony;

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
                                                       boolean parasiteBiome, Object invocation) {
            colonyChecks++;
            return requireColony && SrpSpawnFilterBridge.colonyPoints(worldData, 0, invocation) < parasiteId;
        }

        @Override public int ice$spawnColonyPoints(Object worldData, int age) {
            colonyTotals++;
            return ((int[]) worldData)[0];
        }

        @Override public boolean ice$spawnSubCapLocked(Class<?> entityClass, int dimensionId) {
            subCapChecks++;
            return false;
        }
    }
}
