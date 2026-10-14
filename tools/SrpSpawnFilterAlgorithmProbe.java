package dev.rlcraft.ice.optimizer.compat.srp;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Offline same-call-graph comparison; run with baseline or new classes first. */
public final class SrpSpawnFilterAlgorithmProbe {
    private static volatile long sink;
    private static final Class<?>[] TYPES = {String.class, Integer.class, Number.class,
        Long.class, Object.class, List.class};

    public static void main(String[] args) {
        OptimizerRegistry.breaker(OptimizationModule.SRP_SPAWN_FILTER).configure(true, 3);
        OptimizerRegistry.breaker(OptimizationModule.SRP_SPAWN_FILTER)
            .patchInstalled("srpmixins.handlers.SpawnPotentialsHandler", "offline");
        for (int size : new int[] {0, 1, 8, 32, 128, 512}) {
            for (boolean duplicate : new boolean[] {true, false}) {
                List<Entry> entries = new ArrayList<Entry>();
                for (int i = 0; i < size; i++) {
                    entries.add(new Entry((duplicate ? i % 7 : i) * 4099 - 1000000,
                        TYPES[i % TYPES.length]));
                }
                Callbacks callbacks = new Callbacks();
                long[] times = new long[13];
                int iterations = Math.max(10000, 300000 / (1 + size / 8));
                for (int round = -7; round < times.length; round++) {
                    long sum = 0;
                    long start = System.nanoTime();
                    for (int i = 0; i < iterations; i++) {
                        callbacks.generation = i;
                        List<?> values = SrpSpawnFilterBridge.tryFilter(
                            callbacks, entries, null, null, false, 0);
                        if (values == null) throw new AssertionError("filter failed");
                        sum += values.size();
                        if (!values.isEmpty()) sum += ((Entry) values.get(0)).id;
                    }
                    long elapsed = System.nanoTime() - start;
                    sink = sum;
                    if (round >= 0) times[round] = elapsed;
                }
                Arrays.sort(times);
                System.out.printf(Locale.ROOT,
                    "mode=%s size=%d duplicated=%s median_ns_per_call=%.1f checksum=%d%n",
                    args.length == 0 ? "new" : args[0], size, duplicate,
                    times[times.length / 2] / (double) iterations, sink);
            }
        }
    }

    private static final class Entry {
        final int id;
        final Class<?> type;
        Entry(int id, Class<?> type) { this.id = id; this.type = type; }
    }

    private static final class Callbacks implements SrpSpawnFilterCallbacks {
        int generation;
        @Override public Object ice$spawnEntry(Object wrapper) { return wrapper; }
        @Override public int ice$spawnParaId(Object wrapper) { return ((Entry) wrapper).id; }
        @Override public Class<?> ice$spawnEntityClass(Object entry) { return ((Entry) entry).type; }
        @Override public boolean ice$spawnCheckParasiteId(Object data, int id) {
            return ((id ^ generation) & 7) == 0;
        }
        @Override public boolean ice$spawnColonyLocked(int id, Object data,
                                                      boolean biome, Object invocation) {
            return (id & 7) > SrpSpawnFilterBridge.colonyPoints(data, 0, invocation);
        }
        @Override public int ice$spawnColonyPoints(Object data, int age) { return generation & 7; }
        @Override public boolean ice$spawnSubCapLocked(Class<?> type, int dimension) {
            return type == Integer.class && (generation & 1) == 0;
        }
    }
}
