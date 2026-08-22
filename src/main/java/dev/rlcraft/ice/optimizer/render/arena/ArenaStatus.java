package dev.rlcraft.ice.optimizer.render.arena;

public final class ArenaStatus {
    private final long generation;
    private final long committedBytes;
    private final long usedBytes;
    private final int allocations;
    private final int freeSegments;
    private final long rejected;
    private final long invalidFrees;

    ArenaStatus(long generation, long committedBytes, long usedBytes,
                int allocations, int freeSegments, long rejected,
                long invalidFrees) {
        this.generation = generation;
        this.committedBytes = committedBytes;
        this.usedBytes = usedBytes;
        this.allocations = allocations;
        this.freeSegments = freeSegments;
        this.rejected = rejected;
        this.invalidFrees = invalidFrees;
    }

    public long getGeneration() { return generation; }
    public long getCommittedBytes() { return committedBytes; }
    public long getUsedBytes() { return usedBytes; }
    public int getAllocations() { return allocations; }
    public int getFreeSegments() { return freeSegments; }
    public long getRejected() { return rejected; }
    public long getInvalidFrees() { return invalidFrees; }
}
