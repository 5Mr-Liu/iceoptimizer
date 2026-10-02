package dev.rlcraft.ice.profiler.metrics;

/** Immutable per-dimension deltas for one profiler timeline interval. */
public final class ChunkChurnDimensionSnapshot {
    private final int dimension;
    private final long dataBackedLoads;
    private final long loadsWithoutDataEvent;
    private final long reloadWithinOneSecond;
    private final long reloadWithinFiveSeconds;
    private final long reloadWithinThirtySeconds;
    private final long shortUnloadWithinOneSecond;
    private final long shortUnloadWithinFiveSeconds;
    private final long shortUnloadWithinThirtySeconds;

    public ChunkChurnDimensionSnapshot(
        int dimension,
        long dataBackedLoads,
        long loadsWithoutDataEvent,
        long reloadWithinOneSecond,
        long reloadWithinFiveSeconds,
        long reloadWithinThirtySeconds,
        long shortUnloadWithinOneSecond,
        long shortUnloadWithinFiveSeconds,
        long shortUnloadWithinThirtySeconds
    ) {
        this.dimension = dimension;
        this.dataBackedLoads = nonNegative(dataBackedLoads);
        this.loadsWithoutDataEvent = nonNegative(loadsWithoutDataEvent);
        this.reloadWithinOneSecond = nonNegative(reloadWithinOneSecond);
        this.reloadWithinFiveSeconds = nonNegative(reloadWithinFiveSeconds);
        this.reloadWithinThirtySeconds = nonNegative(reloadWithinThirtySeconds);
        this.shortUnloadWithinOneSecond = nonNegative(shortUnloadWithinOneSecond);
        this.shortUnloadWithinFiveSeconds = nonNegative(shortUnloadWithinFiveSeconds);
        this.shortUnloadWithinThirtySeconds = nonNegative(shortUnloadWithinThirtySeconds);
    }

    public int getDimension() { return dimension; }
    public long getDataBackedLoads() { return dataBackedLoads; }
    public long getLoadsWithoutDataEvent() { return loadsWithoutDataEvent; }
    public long getReloadWithinOneSecond() { return reloadWithinOneSecond; }
    public long getReloadWithinFiveSeconds() { return reloadWithinFiveSeconds; }
    public long getReloadWithinThirtySeconds() { return reloadWithinThirtySeconds; }
    public long getShortUnloadWithinOneSecond() { return shortUnloadWithinOneSecond; }
    public long getShortUnloadWithinFiveSeconds() { return shortUnloadWithinFiveSeconds; }
    public long getShortUnloadWithinThirtySeconds() { return shortUnloadWithinThirtySeconds; }

    private static long nonNegative(long value) {
        return Math.max(0L, value);
    }
}
