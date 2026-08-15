package dev.rlcraft.ice.optimizer.runtime;

public final class WorkerStatus {
    private final int threads;
    private final int queued;
    private final int queueCapacity;
    private final long submitted;
    private final long completed;
    private final long rejected;
    private final long stale;

    public WorkerStatus(int threads, int queued, int queueCapacity, long submitted, long completed, long rejected, long stale) {
        this.threads = threads;
        this.queued = queued;
        this.queueCapacity = queueCapacity;
        this.submitted = submitted;
        this.completed = completed;
        this.rejected = rejected;
        this.stale = stale;
    }

    public int getThreads() { return threads; }
    public int getQueued() { return queued; }
    public int getQueueCapacity() { return queueCapacity; }
    public long getSubmitted() { return submitted; }
    public long getCompleted() { return completed; }
    public long getRejected() { return rejected; }
    public long getStale() { return stale; }
}
