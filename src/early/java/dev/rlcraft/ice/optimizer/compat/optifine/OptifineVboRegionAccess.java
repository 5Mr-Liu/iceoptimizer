package dev.rlcraft.ice.optimizer.compat.optifine;

/**
 * Read-only ABI injected into OptiFine's VboRegion after its exact G5 draw
 * graph has been verified.  No mutable buffer is exposed to ICE.
 */
public interface OptifineVboRegionAccess {
    Object ice$layer();
    int ice$indexPosition();
    int ice$countPosition();
    int ice$commandCapacity();
    int ice$drawMode();
    int ice$bufferId();
    int ice$positionTop();
    int ice$sizeUsed();
}
