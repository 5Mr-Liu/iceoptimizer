package dev.rlcraft.ice.profiler.stats;

public final class DistributionSnapshot {
    public static final DistributionSnapshot EMPTY = new DistributionSnapshot(0L, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);

    private final long count;
    private final double averageMs;
    private final double p50Ms;
    private final double p95Ms;
    private final double p99Ms;
    private final double maximumMs;

    public DistributionSnapshot(
        long count,
        double averageMs,
        double p50Ms,
        double p95Ms,
        double p99Ms,
        double maximumMs
    ) {
        this.count = count;
        this.averageMs = averageMs;
        this.p50Ms = p50Ms;
        this.p95Ms = p95Ms;
        this.p99Ms = p99Ms;
        this.maximumMs = maximumMs;
    }

    public long getCount() {
        return count;
    }

    public double getAverageMs() {
        return averageMs;
    }

    public double getP50Ms() {
        return p50Ms;
    }

    public double getP95Ms() {
        return p95Ms;
    }

    public double getP99Ms() {
        return p99Ms;
    }

    public double getMaximumMs() {
        return maximumMs;
    }
}
