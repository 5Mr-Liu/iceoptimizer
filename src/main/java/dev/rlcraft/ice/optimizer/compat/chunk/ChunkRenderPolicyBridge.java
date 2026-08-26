package dev.rlcraft.ice.optimizer.compat.chunk;

import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;

/** Preserves the already hardware-aware Vanilla/external dispatcher policy. */
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
                : Math.max(1, observedVanillaWorkers);
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
        // This hook runs after Vanilla and performance coremods have already
        // incorporated CPU topology. Reducing their result cannot react to a
        // later backlog and was the source of the permanent two-worker cap.
        return Math.max(1, vanillaWorkers);
    }

    /** Compatibility overload retained for transformer/test ABI stability. */
    static int computeWorkerCount(int vanillaWorkers,
                                  int ignoredLogicalProcessors) {
        return computeWorkerCount(vanillaWorkers);
    }

    /** Compatibility overload retained for transformer/test ABI stability. */
    static int computeWorkerCount(int vanillaWorkers,
                                  int ignoredLogicalProcessors,
                                  long ignoredMaximumHeapBytes) {
        return computeWorkerCount(vanillaWorkers);
    }

    static int computeBuilderCount(int vanillaBuilders, int workers) {
        // Builders are the dispatcher's back-pressure reservoir. Shrinking an
        // externally tuned pool here hides upstream chunksToUpdate pressure
        // and starves exploration; never override the authoritative result.
        return Math.max(1, vanillaBuilders);
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
            OptimizerBridge.activate(MODULE, "保留原版/外部区块线程策略：线程 "
                + effectiveWorkers + "，构建器 " + effectiveBuilders);
        }
    }
}
