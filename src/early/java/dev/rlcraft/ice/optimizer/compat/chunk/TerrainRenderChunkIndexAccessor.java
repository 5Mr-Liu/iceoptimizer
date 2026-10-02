package dev.rlcraft.ice.optimizer.compat.chunk;

/** Early-CoreMod ABI for the stable RenderChunk index and traversal calls. */
public interface TerrainRenderChunkIndexAccessor {
    int ice$renderChunkIndex();
    boolean ice$setFrameIndex(int frameIndex);
    Object ice$bounds();
    long ice$visibilityMask();
    boolean ice$isVisible(Object from, Object to);
}
