package dev.rlcraft.ice.profiler.probe;

public final class ProbeMetric {
    private final int probeId;
    private final String subjectClass;
    private final long calls;
    private final long totalNanos;
    private final long maximumNanos;

    public ProbeMetric(int probeId, String subjectClass, long calls, long totalNanos, long maximumNanos) {
        this.probeId = probeId;
        this.subjectClass = subjectClass;
        this.calls = calls;
        this.totalNanos = totalNanos;
        this.maximumNanos = maximumNanos;
    }

    public int getProbeId() { return probeId; }
    public String getProbeName() { return ProbeIds.name(probeId); }
    public String getSubjectClass() { return subjectClass; }
    public long getCalls() { return calls; }
    public long getTotalNanos() { return totalNanos; }
    public long getMaximumNanos() { return maximumNanos; }
    public double getTotalMillis() { return totalNanos / 1_000_000.0D; }
    public double getMaximumMillis() { return maximumNanos / 1_000_000.0D; }
}
