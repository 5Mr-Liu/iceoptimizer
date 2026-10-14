package dev.rlcraft.ice.optimizer.compat.events;

/** Original optional-mod filter/map callbacks, preserving capability reads and result casts. */
public interface ServerProcessorAccess {
    boolean ice$hasProcessor(Object tile);
    Object ice$getProcessor(Object tile);
}
