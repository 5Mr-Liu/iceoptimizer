package dev.rlcraft.ice.optimizer.runtime;

public final class RenderQueueStatus {
    private final int capacity;
    private final int size;
    private final long submitted;
    private final long executed;
    private final long rejected;
    private final long stale;
    private final long failures;

    public RenderQueueStatus(int capacity, int size, long submitted, long executed, long rejected, long stale, long failures) {
        this.capacity = capacity;
        this.size = size;
        this.submitted = submitted;
        this.executed = executed;
        this.rejected = rejected;
        this.stale = stale;
        this.failures = failures;
    }

    public int getCapacity() { return capacity; }
    public int getSize() { return size; }
    public long getSubmitted() { return submitted; }
    public long getExecuted() { return executed; }
    public long getRejected() { return rejected; }
    public long getStale() { return stale; }
    public long getFailures() { return failures; }
}
