package dev.rlcraft.ice.profiler.sampling;

public final class StackSample {
    private final long timestampNanos;
    private final long threadId;
    private final String threadName;
    private final ThreadRole role;
    private final int stackTraceId;
    private final long cpuNanosDelta;
    private final long allocatedBytesDelta;
    private final Thread.State state;

    public StackSample(
        long timestampNanos,
        long threadId,
        String threadName,
        ThreadRole role,
        int stackTraceId,
        long cpuNanosDelta,
        long allocatedBytesDelta,
        Thread.State state
    ) {
        this.timestampNanos = timestampNanos;
        this.threadId = threadId;
        this.threadName = threadName;
        this.role = role;
        this.stackTraceId = stackTraceId;
        this.cpuNanosDelta = Math.max(0L, cpuNanosDelta);
        this.allocatedBytesDelta = Math.max(0L, allocatedBytesDelta);
        this.state = state;
    }

    public long getTimestampNanos() { return timestampNanos; }
    public long getThreadId() { return threadId; }
    public String getThreadName() { return threadName; }
    public ThreadRole getRole() { return role; }
    public int getStackTraceId() { return stackTraceId; }
    public long getCpuNanosDelta() { return cpuNanosDelta; }
    public long getAllocatedBytesDelta() { return allocatedBytesDelta; }
    public Thread.State getState() { return state; }
}
