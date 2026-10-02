package dev.rlcraft.ice.optimizer.compat.chunk;

/** Early-CoreMod ABI for CompiledChunk's conservative connectivity fast path. */
public interface TerrainCompiledChunkAccessor {
    long ice$visibilityMask();
    boolean ice$isVisible(Object from, Object to);
}
