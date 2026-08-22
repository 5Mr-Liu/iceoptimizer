package dev.rlcraft.ice.optimizer.compat.chunk;

import java.util.List;

/**
 * Early-CoreMod ABI exposed by RenderGlobal. Object descriptors keep the
 * standalone core JAR independent from Minecraft's runtime class path.
 */
public interface TerrainVisibilityAccessor {
    List ice$renderInfos();
    List ice$renderInfosEntities();
    List ice$renderInfosTileEntities();
    boolean ice$isOptifineTraversal();
    void ice$appendRenderInfo(Object info, Object chunk);
    Object[] ice$renderChunks();
    Object[] ice$directions();
    Object ice$getRenderChunkOffset(Object origin, Object chunk, Object direction,
                                    boolean fog, int renderDistance);
    Object ice$oppositeDirection(Object direction);
    Object ice$newRenderInfo(Object chunk, Object direction, int counter);
    boolean ice$isInFrustum(Object chunk, Object camera, Object bounds,
                            int frameIndex);
}
