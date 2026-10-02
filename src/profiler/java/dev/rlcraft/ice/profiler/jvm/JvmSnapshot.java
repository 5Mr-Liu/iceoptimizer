package dev.rlcraft.ice.profiler.jvm;

public final class JvmSnapshot {
    private final long heapUsedBytes;
    private final long heapCommittedBytes;
    private final long heapMaxBytes;
    private final long gcCountDelta;
    private final long gcPauseMillisDelta;
    private final double processCpuLoad;

    public JvmSnapshot(
        long heapUsedBytes,
        long heapCommittedBytes,
        long heapMaxBytes,
        long gcCountDelta,
        long gcPauseMillisDelta,
        double processCpuLoad
    ) {
        this.heapUsedBytes = heapUsedBytes;
        this.heapCommittedBytes = heapCommittedBytes;
        this.heapMaxBytes = heapMaxBytes;
        this.gcCountDelta = gcCountDelta;
        this.gcPauseMillisDelta = gcPauseMillisDelta;
        this.processCpuLoad = processCpuLoad;
    }

    public long getHeapUsedBytes() {
        return heapUsedBytes;
    }

    public long getHeapCommittedBytes() {
        return heapCommittedBytes;
    }

    public long getHeapMaxBytes() {
        return heapMaxBytes;
    }

    public long getGcCountDelta() {
        return gcCountDelta;
    }

    public long getGcPauseMillisDelta() {
        return gcPauseMillisDelta;
    }

    public double getProcessCpuLoad() {
        return processCpuLoad;
    }
}
