package dev.rlcraft.ice.optimizer.render.telemetry;

public enum CpuWorkKind {
    WORK,
    WAIT,
    UPLOAD,
    ALLOCATION,
    CACHE_LOOKUP,
    SUBMISSION
}
