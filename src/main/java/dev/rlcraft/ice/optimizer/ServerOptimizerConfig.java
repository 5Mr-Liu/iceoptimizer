package dev.rlcraft.ice.optimizer;

import java.util.EnumSet;

/** Immutable dedicated-server view of the shared optimizer configuration. */
public final class ServerOptimizerConfig implements OptimizerRuntimeConfig {
    private final boolean enabled;
    private final boolean strictPackLock;
    private final boolean developmentDiskOutput;
    private final int circuitBreakerFailures;
    private final EnumSet<OptimizationModule> enabledModules;

    private ServerOptimizerConfig(OptimizerConfig.Settings source) {
        enabled = source.enabled;
        strictPackLock = source.strictPackLock;
        developmentDiskOutput = source.developmentDiskOutput;
        circuitBreakerFailures = Math.max(1, source.circuitBreakerFailures);
        enabledModules = OptimizerModuleSelection.capture(source, OptimizerRuntimeSide.DEDICATED_SERVER);
    }

    public static ServerOptimizerConfig capture() {
        return new ServerOptimizerConfig(OptimizerConfig.settings);
    }

    @Override public OptimizerRuntimeSide getRuntimeSide() { return OptimizerRuntimeSide.DEDICATED_SERVER; }
    @Override public boolean isEnabled() { return enabled; }
    @Override public boolean isStrictPackLock() { return strictPackLock; }
    @Override public boolean isDevelopmentDiskOutput() { return developmentDiskOutput; }
    @Override public int getCircuitBreakerFailures() { return circuitBreakerFailures; }

    @Override
    public boolean enabled(OptimizationModule module) {
        return enabled && module != null && enabledModules.contains(module);
    }
}
