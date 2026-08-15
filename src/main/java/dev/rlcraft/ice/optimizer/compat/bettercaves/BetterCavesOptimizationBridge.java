package dev.rlcraft.ice.optimizer.compat.bettercaves;

import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;

/** Runtime gate shared by the structurally validated Better Caves adapters. */
public final class BetterCavesOptimizationBridge {
    private static final String MODULE = "better-caves-noise";
    private static volatile boolean activated;
    private static volatile int pipelineCapability;

    private BetterCavesOptimizationBridge() {
    }

    public static boolean isEnabled() {
        try {
            if (!OptimizerBridge.isEnabled(MODULE)) return false;
            activateOnce();
            return true;
        } catch (Throwable error) {
            fail(error);
            return false;
        }
    }

    /**
     * NoiseGen depends on the tuple and column ABIs installed by two other
     * independently fail-open adapters. Resolve those methods before taking
     * the fused/cache path so a partially compatible Better Caves build still
     * executes its untouched implementation.
     */
    public static boolean isPipelineEnabled() {
        if (!isEnabled()) return false;
        int known = pipelineCapability;
        if (known != 0) return known > 0;
        synchronized (BetterCavesOptimizationBridge.class) {
            known = pipelineCapability;
            if (known != 0) return known > 0;
            try {
                ClassLoader loader = Thread.currentThread().getContextClassLoader();
                if (loader == null) loader = BetterCavesOptimizationBridge.class.getClassLoader();
                Class<?> tuple = Class.forName(
                    "com.yungnickyoung.minecraft.bettercaves.noise.NoiseTuple", false, loader);
                Class<?> column = Class.forName(
                    "com.yungnickyoung.minecraft.bettercaves.noise.NoiseColumn", false, loader);
                tuple.getDeclaredMethod("ice$blend", tuple, Float.TYPE, tuple, Float.TYPE);
                tuple.getDeclaredMethod("ice$copy");
                column.getDeclaredMethod("ice$copy");
                pipelineCapability = 1;
                return true;
            } catch (Throwable incompatible) {
                pipelineCapability = -1;
                return false;
            }
        }
    }

    /** Exact two-int key used by the injected direct-mapped column cache. */
    public static long pair(int first, int second) {
        return ((long) first << 32) ^ (second & 0xffffffffL);
    }

    /** Stable 64-slot index; full keys are still compared before a hit. */
    public static int cacheIndex(long positionKey, long rangeKey) {
        long mixed = positionKey ^ (positionKey >>> 32) ^ rangeKey ^ (rangeKey >>> 32);
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        return (int) mixed & 63;
    }

    private static void activateOnce() {
        if (activated) return;
        synchronized (BetterCavesOptimizationBridge.class) {
            if (activated) return;
            OptimizerBridge.activate(MODULE,
                "Better Caves 原始 double 噪声、连续列、单次插值和重复角点缓存已启用");
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
