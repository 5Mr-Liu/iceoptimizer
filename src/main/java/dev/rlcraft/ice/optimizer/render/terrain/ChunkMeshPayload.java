package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;

/** Immutable worker-produced chunk mesh. It contains no GL object or callback. */
public final class ChunkMeshPayload {
    private final long meshKey;
    private final FrameStamp generation;
    private final TerrainLayer layer;
    private final byte[] vertexData;
    private final int vertexCount;
    private final int strideBytes;
    private final int chunkX;
    private final int chunkY;
    private final int chunkZ;
    private final double[] bounds;
    private final MaterialSegment[] materialSegments;
    private final int[] transparentQuadOrder;
    private final long sequence;
    private final long checksum;

    public ChunkMeshPayload(long meshKey, FrameStamp generation, TerrainLayer layer,
                            byte[] vertexData, int vertexCount, int strideBytes,
                            int chunkX, int chunkY, int chunkZ, double[] bounds,
                            MaterialSegment[] materialSegments,
                            int[] transparentQuadOrder, long sequence) {
        this(meshKey, generation, layer, vertexData, vertexCount, strideBytes,
            chunkX, chunkY, chunkZ, bounds, materialSegments,
            transparentQuadOrder, sequence, true);
    }

    /** Package-local transfer for a freshly allocated array with no aliases. */
    static ChunkMeshPayload takeOwnership(long meshKey, FrameStamp generation,
                                           TerrainLayer layer, byte[] vertexData,
                                           int vertexCount, int strideBytes,
                                           int chunkX, int chunkY, int chunkZ,
                                           double[] bounds,
                                           MaterialSegment[] materialSegments,
                                           int[] transparentQuadOrder,
                                           long sequence) {
        return new ChunkMeshPayload(meshKey, generation, layer, vertexData,
            vertexCount, strideBytes, chunkX, chunkY, chunkZ, bounds,
            materialSegments, transparentQuadOrder, sequence, false);
    }

    private ChunkMeshPayload(long meshKey, FrameStamp generation,
                             TerrainLayer layer, byte[] vertexData,
                             int vertexCount, int strideBytes, int chunkX,
                             int chunkY, int chunkZ, double[] bounds,
                             MaterialSegment[] materialSegments,
                             int[] transparentQuadOrder, long sequence,
                             boolean copyVertexData) {
        if (meshKey <= 0L || generation == null || layer == null || vertexData == null
            || vertexCount < 0 || strideBytes <= 0 || sequence < 0L) {
            throw new IllegalArgumentException("chunk payload");
        }
        long expected = (long) vertexCount * (long) strideBytes;
        if (expected != vertexData.length || expected > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("vertex byte count mismatch");
        }
        if (bounds == null || bounds.length != 6 || !finite(bounds)
            || bounds[0] > bounds[3] || bounds[1] > bounds[4] || bounds[2] > bounds[5]) {
            throw new IllegalArgumentException("bounds");
        }
        MaterialSegment[] segments = materialSegments == null
            ? new MaterialSegment[0] : materialSegments.clone();
        validateSegments(segments, vertexCount);
        int[] quadOrder = transparentQuadOrder == null ? new int[0]
            : transparentQuadOrder.clone();
        if (!layer.isTranslucent() && quadOrder.length != 0) {
            throw new IllegalArgumentException("opaque layer has transparent order");
        }
        if (layer.isTranslucent()) validateQuadOrder(quadOrder, vertexCount);
        this.meshKey = meshKey;
        this.generation = generation;
        this.layer = layer;
        this.vertexData = copyVertexData ? vertexData.clone() : vertexData;
        this.vertexCount = vertexCount;
        this.strideBytes = strideBytes;
        this.chunkX = chunkX;
        this.chunkY = chunkY;
        this.chunkZ = chunkZ;
        this.bounds = bounds.clone();
        this.materialSegments = segments;
        this.transparentQuadOrder = quadOrder;
        this.sequence = sequence;
        this.checksum = checksum(this.vertexData);
    }

    public long getMeshKey() { return meshKey; }
    public FrameStamp getGeneration() { return generation; }
    public TerrainLayer getLayer() { return layer; }
    public byte[] copyVertexData() { return vertexData.clone(); }
    public int getVertexCount() { return vertexCount; }
    public int getStrideBytes() { return strideBytes; }
    public int getChunkX() { return chunkX; }
    public int getChunkY() { return chunkY; }
    public int getChunkZ() { return chunkZ; }
    public double[] getBounds() { return bounds.clone(); }
    public MaterialSegment[] getMaterialSegments() { return materialSegments.clone(); }
    public int[] getTransparentQuadOrder() { return transparentQuadOrder.clone(); }
    public long getSequence() { return sequence; }
    public long getChecksum() { return checksum; }
    public int getByteCount() { return vertexData.length; }

    public boolean matchesGeneration(FrameStamp current) {
        if (current == null) return false;
        FrameStamp payload = generation;
        return payload.getWorldGeneration() == current.getWorldGeneration()
            && payload.getResourceGeneration() == current.getResourceGeneration()
            && payload.getGlContextGeneration() == current.getGlContextGeneration()
            && payload.getShaderPackGeneration() == current.getShaderPackGeneration()
            && payload.getShaderPermutationGeneration() == current.getShaderPermutationGeneration()
            && payload.getVertexFormatGeneration() == current.getVertexFormatGeneration()
            && payload.getViewFrustumGeneration() == current.getViewFrustumGeneration();
    }

    private static void validateSegments(MaterialSegment[] segments, int vertexCount) {
        int previousEnd = 0;
        for (MaterialSegment segment : segments) {
            if (segment == null) throw new IllegalArgumentException("null material segment");
            long end = (long) segment.getFirstVertex() + segment.getVertexCount();
            if (segment.getFirstVertex() < previousEnd || end > vertexCount) {
                throw new IllegalArgumentException("overlapping/out-of-range material segment");
            }
            previousEnd = (int) end;
        }
    }

    private static void validateQuadOrder(int[] order, int vertexCount) {
        if (vertexCount == 0 && order.length == 0) return;
        if ((vertexCount & 3) != 0 || order.length != vertexCount / 4) {
            throw new IllegalArgumentException("transparent quad order size");
        }
        boolean[] seen = new boolean[order.length];
        for (int value : order) {
            if (value < 0 || value >= order.length || seen[value]) {
                throw new IllegalArgumentException("transparent quad order permutation");
            }
            seen[value] = true;
        }
    }

    private static long checksum(byte[] bytes) {
        long value = 0xcbf29ce484222325L;
        for (byte current : bytes) {
            value ^= current & 0xffL;
            value *= 0x100000001b3L;
        }
        return value;
    }

    private static boolean finite(double[] values) {
        for (double value : values) {
            if (Double.isNaN(value) || Double.isInfinite(value)) return false;
        }
        return true;
    }
}
