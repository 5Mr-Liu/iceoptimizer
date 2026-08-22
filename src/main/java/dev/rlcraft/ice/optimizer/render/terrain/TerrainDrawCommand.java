package dev.rlcraft.ice.optimizer.render.terrain;

/** Binary-compatible logical form of DrawArraysIndirectCommand plus metadata. */
public final class TerrainDrawCommand {
    private final int count;
    private final int instanceCount;
    private final int first;
    private final int baseInstance;
    private final int originX;
    private final int originY;
    private final int originZ;
    private final long sequence;
    private final long checksum;

    TerrainDrawCommand(int count, int first, int baseInstance,
                       int originX, int originY, int originZ,
                       long sequence, long checksum) {
        this.count = count;
        this.instanceCount = 1;
        this.first = first;
        this.baseInstance = baseInstance;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.sequence = sequence;
        this.checksum = checksum;
    }

    public int getCount() { return count; }
    public int getInstanceCount() { return instanceCount; }
    public int getFirst() { return first; }
    public int getBaseInstance() { return baseInstance; }
    public int getOriginX() { return originX; }
    public int getOriginY() { return originY; }
    public int getOriginZ() { return originZ; }
    public long getSequence() { return sequence; }
    public long getChecksum() { return checksum; }
}
