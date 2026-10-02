package dev.rlcraft.ice.profiler.stats;

import java.util.Arrays;

public final class AdaptiveThreshold {
    private final long[] recent;
    private final long[] values;
    private final long[] deviations;
    private int cursor;
    private int size;

    public AdaptiveThreshold(int capacity) {
        recent = new long[Math.max(8, capacity)];
        values = new long[recent.length];
        deviations = new long[recent.length];
    }

    public synchronized void record(long nanos) {
        recent[cursor] = Math.max(0L, nanos);
        cursor = (cursor + 1) % recent.length;
        size = Math.min(size + 1, recent.length);
    }

    public synchronized long thresholdNanos(long absoluteNanos) {
        if (size < 20) {
            return absoluteNanos;
        }
        for (int i = 0; i < size; i++) {
            int index = cursor - size + i;
            if (index < 0) {
                index += recent.length;
            }
            values[i] = recent[index];
        }
        Arrays.sort(values, 0, size);
        long median = values[size / 2];
        for (int i = 0; i < size; i++) {
            deviations[i] = Math.abs(values[i] - median);
        }
        Arrays.sort(deviations, 0, size);
        long mad = deviations[size / 2];
        // A stable low-FPS cap (for example 10 FPS = ~100 ms) is not itself a hitch.
        // Require either a robust MAD outlier or roughly twice the recent median.
        long madThreshold = median + 6L * Math.max(1L, mad);
        long ratioThreshold = median > Long.MAX_VALUE / 2L ? Long.MAX_VALUE : median * 2L;
        long adaptive = Math.max(madThreshold, ratioThreshold);
        return Math.max(absoluteNanos, adaptive);
    }
}
