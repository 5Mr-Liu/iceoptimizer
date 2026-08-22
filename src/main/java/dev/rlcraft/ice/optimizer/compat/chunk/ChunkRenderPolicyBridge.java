package dev.rlcraft.ice.optimizer.compat.chunk;

import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;

/** Hardware-identity-free bound for vanilla's chunk dispatcher. */
public final class ChunkRenderPolicyBridge {
    private static final String MODULE = "vanilla-chunk-dispatch";
    private static final int SAFE_WORKER_CAP = 2;
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
        int tuned = computeWorkerCount(observedVanillaWorkers);
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
                : SAFE_WORKER_CAP;
            int tuned = computeBuilderCount(original, workers);
            if (tuned != original) dispatcher.ice$setBuilderCount(tuned);
            effectiveBuilders = tuned;
            activateIfNeeded();
            OptimizerBridge.success(MODULE);
        } catch (Throwable error) {
            OptimizerBridge.failure(MODULE, error);
        }
    }

    static int computeWorkerCount(int vanillaWorkers) {
        return Math.max(1, Math.min(Math.max(1, vanillaWorkers),
            SAFE_WORKER_CAP));
    }

    /** Compatibility overload proving that CPU count cannot influence policy. */
    static int computeWorkerCount(int vanillaWorkers,
                                  int ignoredLogicalProcessors) {
        return computeWorkerCount(vanillaWorkers);
    }

    /** Compatibility overload proving that CPU count and heap size are ignored. */
    static int computeWorkerCount(int vanillaWorkers,
                                  int ignoredLogicalProcessors,
                                  long ignoredMaximumHeapBytes) {
        return computeWorkerCount(vanillaWorkers);
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
