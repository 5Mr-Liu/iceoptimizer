package dev.rlcraft.ice.optimizer;

/** Common immutable configuration contract used by client and dedicated-server bootstraps. */
public interface OptimizerRuntimeConfig {
    OptimizerRuntimeSide getRuntimeSide();
    boolean isEnabled();
    boolean isStrictPackLock();
    boolean isDevelopmentDiskOutput();
    int getCircuitBreakerFailures();
    boolean enabled(OptimizationModule module);
}
