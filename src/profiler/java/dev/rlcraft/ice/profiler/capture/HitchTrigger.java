package dev.rlcraft.ice.profiler.capture;

public final class HitchTrigger {
    private final TriggerType type;
    private final long timestampNanos;
    private final long epochMillis;
    private final long durationNanos;
    private final long thresholdNanos;
    private final String detail;

    public HitchTrigger(TriggerType type, long timestampNanos, long epochMillis, long durationNanos, long thresholdNanos, String detail) {
        this.type = type;
        this.timestampNanos = timestampNanos;
        this.epochMillis = epochMillis;
        this.durationNanos = Math.max(0L, durationNanos);
        this.thresholdNanos = Math.max(0L, thresholdNanos);
        this.detail = detail == null ? "" : detail;
    }

    public TriggerType getType() { return type; }
    public long getTimestampNanos() { return timestampNanos; }
    public long getEpochMillis() { return epochMillis; }
    public long getDurationNanos() { return durationNanos; }
    public long getThresholdNanos() { return thresholdNanos; }
    public String getDetail() { return detail; }
    public double getDurationMillis() { return durationNanos / 1_000_000.0D; }
}
