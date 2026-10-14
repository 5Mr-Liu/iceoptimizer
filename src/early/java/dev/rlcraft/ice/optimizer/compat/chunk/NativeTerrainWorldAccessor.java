package dev.rlcraft.ice.optimizer.compat.chunk;

/** Native camera root lookup through the world's existing section grid. */
public interface NativeTerrainWorldAccessor {
    Object ice$cameraSection(Object position);
    default void ice$stopChunkUpdates() {
        throw new IllegalStateException("ICE terrain pause hook is missing");
    }
}
