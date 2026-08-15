package dev.rlcraft.ice.optimizer.runtime;

public final class EpochToken {
    private final long frameId;
    private final long clientTickId;
    private final long worldGeneration;
    private final long resourceGeneration;
    private final long glContextGeneration;

    EpochToken(long frameId, long clientTickId, long worldGeneration, long resourceGeneration, long glContextGeneration) {
        this.frameId = frameId;
        this.clientTickId = clientTickId;
        this.worldGeneration = worldGeneration;
        this.resourceGeneration = resourceGeneration;
        this.glContextGeneration = glContextGeneration;
    }

    public long getFrameId() { return frameId; }
    public long getClientTickId() { return clientTickId; }
    public long getWorldGeneration() { return worldGeneration; }
    public long getResourceGeneration() { return resourceGeneration; }
    public long getGlContextGeneration() { return glContextGeneration; }
}
