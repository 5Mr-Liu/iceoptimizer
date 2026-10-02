package dev.rlcraft.ice.optimizer.compat.chunk;

/** Early-CoreMod ABI for RenderGlobal.ContainerLocalRenderInformation. */
public interface TerrainRenderInfoAccessor {
    Object ice$renderChunk();
    Object ice$incomingDirection();
    byte ice$pathDirections();
    int ice$counter();
    void ice$setDirection(byte previousDirections, Object direction);
    boolean ice$isCanonicalRenderInfo();
}
