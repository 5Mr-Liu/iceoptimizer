package dev.rlcraft.ice.optimizer.compat.recurrent;

import com.sun.management.ThreadMXBean;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import ivorius.ivtoolkit.random.BlurredValueField;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/** Offline timing/allocation measurement against the real IvToolkit implementation. */
public final class RecurrentValueWorkspaceProbe {
    private static volatile long sink;

    public static void main(String[] args) {
        boolean optimized = args.length > 0 && args[0].equals("new");
        OptimizerRegistry.breaker(OptimizationModule.RECURRENT_VALUE_WORKSPACE).configure(true, 3);
        OptimizerRegistry.breaker(OptimizationModule.RECURRENT_VALUE_WORKSPACE).patchInstalled("ruins", "probe");
        ThreadMXBean allocation = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        allocation.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        int[][] queries = new int[2048][3];
        Random coordinates = new Random(43291);
        for (int[] position : queries) {
            for (int axis = 0; axis < 3; axis++) position[axis] = coordinates.nextInt(90) - 20;
        }
        for (int count : new int[] {0, 1, 8, 32, 128, 512}) {
            BlurredValueField field = optimized ? new RecurrentValueField(37, 23, 19)
                : new BlurredValueField(37, 23, 19);
            Random values = new Random(count);
            for (int i = 0; i < count; i++) field.addValue(values.nextDouble(), values);
            long[] times = new long[9], bytes = new long[9];
            int repeats = 50000;
            for (int round = -5; round < times.length; round++) {
                long checksum = 0;
                long beforeBytes = allocation.getThreadAllocatedBytes(thread);
                long beforeTime = System.nanoTime();
                for (int i = 0; i < repeats; i++) {
                    checksum += Double.doubleToRawLongBits(field.getValue(queries[i & (queries.length - 1)]));
                }
                long elapsed = System.nanoTime() - beforeTime;
                long allocated = allocation.getThreadAllocatedBytes(thread) - beforeBytes;
                sink = checksum;
                if (round >= 0) { times[round] = elapsed; bytes[round] = allocated; }
            }
            Arrays.sort(times); Arrays.sort(bytes);
            System.out.printf(Locale.ROOT,
                "%s,pivots=%d,ns_per_query=%.1f,allocated_bytes_per_query=%.1f,checksum=%d%n",
                optimized ? "new" : "old", count, times[times.length / 2] / (double) repeats,
                bytes[bytes.length / 2] / (double) repeats, sink);
        }
    }
}
