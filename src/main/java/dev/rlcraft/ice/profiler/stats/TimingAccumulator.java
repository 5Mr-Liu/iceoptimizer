package dev.rlcraft.ice.profiler.stats;

import java.util.Arrays;

public final class TimingAccumulator {
    private final long[] samples;
    private int size;
    private int reservoirCursor;
    private long count;
    private long sumNanos;
    private long maximumNanos;

    public TimingAccumulator(int sampleCapacity) {
        if (sampleCapacity <= 0) {
            throw new IllegalArgumentException("sampleCapacity must be positive");
        }
        samples = new long[sampleCapacity];
    }

    public synchronized void record(long nanos) {
        long safe = Math.max(0L, nanos);
        count++;
        sumNanos += safe;
        maximumNanos = Math.max(maximumNanos, safe);
        if (size < samples.length) {
            samples[size++] = safe;
        } else {
            // A rotating bounded reservoir avoids allocations and still tracks long high-FPS sessions.
            samples[reservoirCursor] = safe;
            reservoirCursor = (reservoirCursor + 1) % samples.length;
        }
    }

    public synchronized DistributionSnapshot drain() {
        if (count == 0L) {
            return DistributionSnapshot.EMPTY;
        }
        long[] sorted = Arrays.copyOf(samples, size);
        Arrays.sort(sorted);
        DistributionSnapshot result = new DistributionSnapshot(
            count,
            nanosToMillis((double) sumNanos / count),
            percentile(sorted, 0.50D),
            percentile(sorted, 0.95D),
            percentile(sorted, 0.99D),
            nanosToMillis(maximumNanos)
        );
        size = 0;
        reservoirCursor = 0;
        count = 0L;
        sumNanos = 0L;
        maximumNanos = 0L;
        return result;
    }

    private static double percentile(long[] sorted, double percentile) {
        if (sorted.length == 0) {
            return 0.0D;
        }
        int index = (int) Math.ceil(sorted.length * percentile) - 1;
        index = Math.max(0, Math.min(index, sorted.length - 1));
        return nanosToMillis(sorted[index]);
    }

    private static double nanosToMillis(double nanos) {
        return nanos / 1_000_000.0D;
    }
}
