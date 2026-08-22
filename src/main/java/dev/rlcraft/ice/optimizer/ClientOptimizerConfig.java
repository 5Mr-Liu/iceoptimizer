package dev.rlcraft.ice.optimizer;

import java.util.EnumSet;

/** Immutable runtime copy of Forge's mutable configuration object. */
public final class ClientOptimizerConfig implements OptimizerRuntimeConfig {
    private static final int SAFE_INITIAL_WORKERS = 1;
    private final boolean enabled;
    private final boolean strictPackLock;
    private final boolean developmentDiskOutput;
    private final int workerThreads;
    private final int workerQueueCapacity;
    private final int renderQueueCapacity;
    private final long renderDrainBudgetNanos;
    private final long heapCacheBudgetBytes;
    private final long directCacheBudgetBytes;
    private final long gpuCacheBudgetBytes;
    private final int circuitBreakerFailures;
    private final int skullProfileCacheEntries;
    private final int skullProfilePositiveTtlMinutes;
    private final int skullProfileNegativeTtlSeconds;
    private final int skullProfileQueueCapacity;
    private final EnumSet<OptimizationModule> enabledModules;

    private ClientOptimizerConfig(OptimizerConfig.Settings source) {
        enabled = source.enabled;
        strictPackLock = source.strictPackLock;
        developmentDiskOutput = source.developmentDiskOutput;
        workerThreads = source.workerThreads <= 0 ? SAFE_INITIAL_WORKERS
            : Math.max(1, Math.min(12, source.workerThreads));
        workerQueueCapacity = Math.max(64, source.workerQueueCapacity);
        renderQueueCapacity = Math.max(64, source.renderQueueCapacity);
        renderDrainBudgetNanos = Math.max(100L, source.renderDrainBudgetMicros) * 1000L;
        heapCacheBudgetBytes = toBytes(source.heapCacheBudgetMiB);
        directCacheBudgetBytes = toBytes(source.directCacheBudgetMiB);
        gpuCacheBudgetBytes = toBytes(source.gpuCacheBudgetMiB);
        circuitBreakerFailures = Math.max(1, source.circuitBreakerFailures);
        skullProfileCacheEntries = Math.max(64, Math.min(8192, source.skullProfileCacheEntries));
        skullProfilePositiveTtlMinutes = Math.max(5, Math.min(1440, source.skullProfilePositiveTtlMinutes));
        skullProfileNegativeTtlSeconds = Math.max(10, Math.min(3600, source.skullProfileNegativeTtlSeconds));
        skullProfileQueueCapacity = Math.max(16, Math.min(1024, source.skullProfileQueueCapacity));
        enabledModules = OptimizerModuleSelection.capture(source, OptimizerRuntimeSide.CLIENT);
    }

    public static ClientOptimizerConfig capture() {
        return new ClientOptimizerConfig(OptimizerConfig.settings);
    }

    @Override public OptimizerRuntimeSide getRuntimeSide() { return OptimizerRuntimeSide.CLIENT; }
    @Override public boolean isEnabled() { return enabled; }
    @Override public boolean isStrictPackLock() { return strictPackLock; }
    @Override public boolean isDevelopmentDiskOutput() { return developmentDiskOutput; }
    public int getWorkerThreads() { return workerThreads; }
    public int getWorkerQueueCapacity() { return workerQueueCapacity; }
    public int getRenderQueueCapacity() { return renderQueueCapacity; }
    public long getRenderDrainBudgetNanos() { return renderDrainBudgetNanos; }
    public long getHeapCacheBudgetBytes() { return heapCacheBudgetBytes; }
    public long getDirectCacheBudgetBytes() { return directCacheBudgetBytes; }
    public long getGpuCacheBudgetBytes() { return gpuCacheBudgetBytes; }
    @Override public int getCircuitBreakerFailures() { return circuitBreakerFailures; }
    public int getSkullProfileCacheEntries() { return skullProfileCacheEntries; }
    public int getSkullProfilePositiveTtlMinutes() { return skullProfilePositiveTtlMinutes; }
    public int getSkullProfileNegativeTtlSeconds() { return skullProfileNegativeTtlSeconds; }
    public int getSkullProfileQueueCapacity() { return skullProfileQueueCapacity; }

    @Override
    public boolean enabled(OptimizationModule module) {
        return enabled && module != null && enabledModules.contains(module);
    }

    private static long toBytes(int mebibytes) {
        return Math.max(1L, mebibytes) * 1024L * 1024L;
    }
}
