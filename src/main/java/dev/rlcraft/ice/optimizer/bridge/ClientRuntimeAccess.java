package dev.rlcraft.ice.optimizer.bridge;

/** Small side-neutral view registered only by the physical client runtime. */
public interface ClientRuntimeAccess {
    long currentFrameId();
    long currentClientTickId();
    long currentWorldGeneration();
    long currentResourceGeneration();
    long currentGlContextGeneration();
}
