package dev.rlcraft.ice.optimizer.compat.bettercaves;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;

/** Runtime gate shared by the structurally validated Better Caves adapters. */
public final class BetterCavesOptimizationBridge {
    private static final OptimizationModule MODULE = OptimizationModule.BETTER_CAVES_NOISE;
    private static volatile boolean activated;
    private static volatile int pipelineCapability;

    private BetterCavesOptimizationBridge() {
    }

    public static boolean isEnabled() {
        try {
            if (!OptimizerRegistry.isOperational(MODULE)) return false;
            if (!activated) activateOnce();
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
                FatalErrors.rethrowIfFatal(incompatible);
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
        synchronized (BetterCavesOptimizationBridge.class) {
            if (activated) return;
            OptimizerRegistry.breaker(MODULE).activate(
                "Better Caves 原始 double 噪声、连续列、单次插值和重复角点缓存已启用");
            activated = true;
        }
    }

    private static void fail(Throwable error) {
        try {
            OptimizerRegistry.breaker(MODULE).recordFailure(error);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
        }
    }
}
