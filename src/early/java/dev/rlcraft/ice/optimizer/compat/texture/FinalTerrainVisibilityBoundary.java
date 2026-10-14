package dev.rlcraft.ice.optimizer.compat.texture;

/** Installed only with an unconditional visibility callback before terrain draws. */
public interface FinalTerrainVisibilityBoundary {
    /** False for subclasses whose virtual draw may bypass the reviewed emitter. */
    boolean ice$hasFinalSpriteVisibilityBoundary();
}
