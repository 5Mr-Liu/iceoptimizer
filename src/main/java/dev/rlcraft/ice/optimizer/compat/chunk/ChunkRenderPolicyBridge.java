package dev.rlcraft.ice.optimizer.compat.chunk;

import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;

/** Hardware-adaptive bounds for vanilla's otherwise unbounded logical-CPU chunk dispatcher. */
public final class ChunkRenderPolicyBridge {
    private static final String MODULE = "vanilla-chunk-dispatch";
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
            Math.max(1, Runtime.getRuntime().availableProcessors()));
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

    static int computeWorkerCount(int vanillaWorkers, int logicalProcessors) {
        int vanilla = Math.max(1, vanillaWorkers);
        int logical = Math.max(1, logicalProcessors);
        if (vanilla <= 1 || logical <= 1) return 1;
        int reserved;
        if (logical >= 13) reserved = 4;
        else if (logical >= 9) reserved = 3;
        else if (logical >= 5) reserved = 2;
        else reserved = 1;
        int available = Math.max(2, logical - reserved);
        return Math.max(2, Math.min(vanilla, Math.min(8, available)));
    }

    static int computeBuilderCount(int vanillaBuilders, int workers) {
        int original = Math.max(1, vanillaBuilders);
        int minimumPipeline = Math.max(4, Math.max(1, workers) * 4);
        return Math.min(original, minimumPipeline);
    }

    static int vanillaWorkers() { return observedVanillaWorkers; }
    static int workers() { return effectiveWorkers; }
    static int builders() { return effectiveBuilders; }

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
