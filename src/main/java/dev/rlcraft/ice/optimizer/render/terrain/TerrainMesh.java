package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.render.arena.ArenaRange;

public final class TerrainMesh {
    private final ChunkMeshPayload payload;
    private final ArenaRange range;
    private final TerrainLayer layer;
    private final int vertexCount;
    private final int strideBytes;
    private final int chunkX;
    private final int chunkY;
    private final int chunkZ;
    private final long sequence;
    private final long checksum;

    public TerrainMesh(ChunkMeshPayload payload, ArenaRange range) {
        if (payload == null || range == null || range.getLength() != payload.getByteCount()) {
            throw new IllegalArgumentException("terrain mesh");
        }
        this.payload = payload;
        this.range = range;
        this.layer = payload.getLayer();
        this.vertexCount = payload.getVertexCount();
        this.strideBytes = payload.getStrideBytes();
        this.chunkX = payload.getChunkX();
        this.chunkY = payload.getChunkY();
        this.chunkZ = payload.getChunkZ();
        this.sequence = payload.getSequence();
        this.checksum = payload.getChecksum();
    }

    /** Production metadata view; uploaded CPU vertex bytes are not retained. */
    static TerrainMesh metadataOnly(TerrainLayer layer, int vertexCount,
                                    int strideBytes, int chunkX, int chunkY,
                                    int chunkZ, long sequence, long checksum,
                                    ArenaRange range) {
        return new TerrainMesh(layer, vertexCount, strideBytes, chunkX, chunkY,
            chunkZ, sequence, checksum, range);
    }

    private TerrainMesh(TerrainLayer layer, int vertexCount, int strideBytes,
                        int chunkX, int chunkY, int chunkZ, long sequence,
                        long checksum, ArenaRange range) {
        long bytes = (long) vertexCount * (long) strideBytes;
        if (layer == null || vertexCount < 0 || strideBytes <= 0
            || sequence < 0L || range == null || bytes < 0L
            || bytes != range.getLength()) {
            throw new IllegalArgumentException("terrain mesh metadata");
        }
        this.payload = null;
        this.range = range;
        this.layer = layer;
        this.vertexCount = vertexCount;
        this.strideBytes = strideBytes;
        this.chunkX = chunkX;
        this.chunkY = chunkY;
        this.chunkZ = chunkZ;
        this.sequence = sequence;
        this.checksum = checksum;
    }

    /** Non-null only for the public payload-preserving test/worker path. */
    public ChunkMeshPayload getPayload() { return payload; }
    public ArenaRange getRange() { return range; }
    public TerrainLayer getLayer() { return layer; }
    public int getVertexCount() { return vertexCount; }
    public int getStrideBytes() { return strideBytes; }
    public int getChunkX() { return chunkX; }
    public int getChunkY() { return chunkY; }
    public int getChunkZ() { return chunkZ; }
    public long getSequence() { return sequence; }
    public long getChecksum() { return checksum; }
}
