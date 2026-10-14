package dev.rlcraft.ice.optimizer.compat.chunk;

/** Direct section data owned by the native terrain renderer. */
public interface NativeTerrainChunkAccessor {
    Object ice$nativeSection();
    void ice$nativeSection(Object section);
}
