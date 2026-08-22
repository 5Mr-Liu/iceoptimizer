package dev.rlcraft.ice.optimizer.render.backend;

public enum BackendLifecycleState {
    LEGACY,
    CAPABILITY_SELF_TEST,
    WARMUP,
    OUTPUT_VALIDATE,
    PAIRED_MEASURE,
    MODERN,
    REGRESSION_MONITOR,
    QUARANTINED
}
