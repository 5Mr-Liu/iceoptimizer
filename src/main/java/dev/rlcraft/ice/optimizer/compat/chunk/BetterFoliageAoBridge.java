package dev.rlcraft.ice.optimizer.compat.chunk;

import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;

/** Tiny hot-path guard for the exact Better Foliage AoFaceData patch. */
public final class BetterFoliageAoBridge {
    private static final String MODULE = "chunk-mesh-ao";
    private static volatile boolean activated;

    private BetterFoliageAoBridge() {
    }

    public static boolean useScratch() {
        try {
            if (!OptimizerBridge.isEnabled(MODULE)) return false;
            activateOnce();
            return true;
        } catch (Throwable error) {
            fail(error);
            return false;
        }
    }

    private static void activateOnce() {
        if (activated) return;
        synchronized (BetterFoliageAoBridge.class) {
            if (activated) return;
            OptimizerBridge.activate(MODULE,
                "Better Foliage 每个 Chunk Worker 的 AO float[12] 与 BitSet 已复用");
            activated = true;
        }
    }

    private static void fail(Throwable error) {
        try {
            OptimizerBridge.failure(MODULE, error);
        } catch (Throwable ignored) {
        }
    }
}
