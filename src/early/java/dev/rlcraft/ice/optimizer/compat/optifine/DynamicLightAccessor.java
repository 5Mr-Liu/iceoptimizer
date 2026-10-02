package dev.rlcraft.ice.optimizer.compat.optifine;

/** Early-load ABI injected into OptiFine's DynamicLight implementation. */
public interface DynamicLightAccessor {
    int ice$lastLightLevel();
    double ice$lastPosX();
    double ice$lastPosY();
    double ice$lastPosZ();
    boolean ice$isUnderwater();
}
