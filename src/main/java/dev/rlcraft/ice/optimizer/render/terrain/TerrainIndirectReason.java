package dev.rlcraft.ice.optimizer.render.terrain;

/** Stable diagnostic categories for rejected or uncertain MDI attempts. */
public enum TerrainIndirectReason {
    UNKNOWN_BINDING,
    SLOT_UNAVAILABLE_OR_BUSY,
    DIRECT_COMMAND_CAPACITY,
    GPU_BUDGET_OR_SLOT_ALLOCATION,
    FENCE_TIMEOUT,
    DRIVER_OR_SUBMISSION_FAILURE
}
