package dev.rlcraft.ice.optimizer.memory;

public final class CacheBudgetStatus {
    private final long heapUsed;
    private final long heapLimit;
    private final long directUsed;
    private final long directLimit;
    private final long gpuUsed;
    private final long gpuLimit;
    private final long rejectedReservations;

    public CacheBudgetStatus(long heapUsed, long heapLimit, long directUsed, long directLimit,
                             long gpuUsed, long gpuLimit, long rejectedReservations) {
        this.heapUsed = heapUsed;
        this.heapLimit = heapLimit;
        this.directUsed = directUsed;
        this.directLimit = directLimit;
        this.gpuUsed = gpuUsed;
        this.gpuLimit = gpuLimit;
        this.rejectedReservations = rejectedReservations;
    }

    public long getHeapUsed() { return heapUsed; }
    public long getHeapLimit() { return heapLimit; }
    public long getDirectUsed() { return directUsed; }
    public long getDirectLimit() { return directLimit; }
    public long getGpuUsed() { return gpuUsed; }
    public long getGpuLimit() { return gpuLimit; }
    public long getRejectedReservations() { return rejectedReservations; }
}
