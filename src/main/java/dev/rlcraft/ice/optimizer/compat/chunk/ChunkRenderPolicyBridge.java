package dev.rlcraft.ice.optimizer.compat.chunk;

import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;

/** Hardware-adaptive bounds for vanilla's otherwise unbounded logical-CPU chunk dispatcher. */
public final class ChunkRenderPolicyBridge {
    private static final String MODULE = "vanilla-chunk-dispatch";
    private static final long MEBIBYTE = 1024L * 1024L;
    private static volatile int observedVanillaWorkers;
    private static volatile int effectiveWorkers;
    private static volatile int effectiveBuilders;
    private static volatile boolean activated;

    private ChunkRenderPolicyBridge() {
    }

    public static int tuneWorkerCount(int vanillaWorkers) {
        observedVanillaWorkers = Math.max(1, vanillaWorkers);
        if (!OptimizerBridge.isEnabled(MODULE)) {
            effectiveWorkers = observedVanillaWorkers;
            return observedVanillaWorkers;
        }
        int tuned = computeWorkerCount(observedVanillaWorkers,
            Math.max(1, Runtime.getRuntime().availableProcessors()),
            Runtime.getRuntime().maxMemory());
        effectiveWorkers = tuned;
        return tuned;
    }

    public static int tuneBuilderCount(int vanillaBuilders, int workers) {
        int original = Math.max(1, vanillaBuilders);
        if (!OptimizerBridge.isEnabled(MODULE)) {
            effectiveBuilders = original;
            return original;
        }
        int tuned = computeBuilderCount(original, Math.max(1, workers));
        effectiveBuilders = tuned;
        activateIfNeeded();
        OptimizerBridge.success(MODULE);
        return tuned;
    }

    /**
     * Runs after the constructor's builder assignment. This deliberately sees
     * the value produced by Fermium/NormalASM instead of replacing its policy.
     */
    public static void clampBuilderCount(ChunkDispatcherPolicyAccessor dispatcher) {
        if (dispatcher == null || !OptimizerBridge.isEnabled(MODULE)) return;
        try {
            int original = Math.max(1, dispatcher.ice$builderCount());
            int workers = effectiveWorkers > 0 ? effectiveWorkers
                : computeWorkerCount(Math.max(1, Runtime.getRuntime().availableProcessors()),
                    Math.max(1, Runtime.getRuntime().availableProcessors()),
                    Runtime.getRuntime().maxMemory());
            int tuned = computeBuilderCount(original, workers);
            if (tuned != original) dispatcher.ice$setBuilderCount(tuned);
            effectiveBuilders = tuned;
            activateIfNeeded();
            OptimizerBridge.success(MODULE);
        } catch (Throwable error) {
            OptimizerBridge.failure(MODULE, error);
        }
    }

    static int computeWorkerCount(int vanillaWorkers, int logicalProcessors) {
        return computeWorkerCount(vanillaWorkers, logicalProcessors, Long.MAX_VALUE);
    }

    static int computeWorkerCount(int vanillaWorkers, int logicalProcessors, long maximumHeapBytes) {
        int vanilla = Math.max(1, vanillaWorkers);
        int logical = Math.max(1, logicalProcessors);
        if (vanilla <= 1 || logical <= 2) return 1;

        int cpuLimit;
        if (logical <= 4) {
            cpuLimit = logical - 1;
        } else if (logical <= 8) {
            cpuLimit = logical - 2;
        } else if (logical <= 23) {
            int reserved = logical <= 12 ? 3 : 4;
            cpuLimit = Math.min(8, logical - reserved);
        } else if (logical <= 31) {
            cpuLimit = Math.min(12, logical - 4);
        } else {
            int reserved = Math.max(4, logical / 8);
            cpuLimit = Math.min(16, logical - reserved);
        }

        int memoryLimit = memoryWorkerLimit(maximumHeapBytes);
        return Math.max(1, Math.min(vanilla, Math.min(cpuLimit, memoryLimit)));
    }

    private static int memoryWorkerLimit(long maximumHeapBytes) {
        if (maximumHeapBytes <= 0L || maximumHeapBytes == Long.MAX_VALUE) return 16;
        long mebibytes = maximumHeapBytes / MEBIBYTE;
        if (mebibytes < 1536L) return 2;
        if (mebibytes < 2560L) return 4;
        if (mebibytes < 4096L) return 6;
        if (mebibytes < 6144L) return 8;
        if (mebibytes < 8192L) return 12;
        return 16;
    }

    static int computeBuilderCount(int vanillaBuilders, int workers) {
        int original = Math.max(1, vanillaBuilders);
        int minimumPipeline = Math.max(4, Math.max(1, workers) * 4);
        return Math.min(original, minimumPipeline);
    }

    static int vanillaWorkers() { return observedVanillaWorkers; }
    static int workers() { return effectiveWorkers; }
    static int builders() { return effectiveBuilders; }
    static void setEffectiveWorkersForTest(int workers) { effectiveWorkers = Math.max(1, workers); }

    private static void activateIfNeeded() {
        if (activated) return;
        synchronized (ChunkRenderPolicyBridge.class) {
            if (activated) return;
            activated = true;
            OptimizerBridge.activate(MODULE, "区块线程 " + observedVanillaWorkers + "→"
                + effectiveWorkers + "，复用构建器 " + effectiveBuilders);
        }
    }
}
