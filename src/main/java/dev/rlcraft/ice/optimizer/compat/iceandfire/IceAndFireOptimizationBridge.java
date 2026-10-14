package dev.rlcraft.ice.optimizer.compat.iceandfire;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;

/** Exact gates and immutable particle argument scratch for Ice and Fire. */
public final class IceAndFireOptimizationBridge {
    private static final int POSE_MODULE = OptimizationModule.ICEANDFIRE_POSE_LOOKUP.ordinal();
    private static final int PARTICLE_MODULE = OptimizationModule.ICEANDFIRE_PARTICLE_SCRATCH.ordinal();
    private static final int[] EMPTY_PARTICLE_ARGS = new int[0];
    private static final int[] ZERO_PARTICLE_ARGS = new int[] { 0 };
    private static volatile boolean poseActivated;
    private static volatile boolean particleActivated;

    private IceAndFireOptimizationBridge() {
    }

    public static boolean usePoseLookup() {
        boolean enabled = OptimizerBridge.isEnabled(POSE_MODULE);
        if (enabled && !poseActivated) {
            poseActivated = true;
            OptimizerBridge.activate(POSE_MODULE,
                "Ice and Fire Tabula 单次姿态局部查询已启用");
        }
        return enabled;
    }

    public static int[] emptyParticleArgs() {
        if (!OptimizerBridge.isEnabled(PARTICLE_MODULE)) return new int[0];
        activateParticles();
        return EMPTY_PARTICLE_ARGS;
    }

    public static int[] zeroParticleArgs() {
        if (!OptimizerBridge.isEnabled(PARTICLE_MODULE)) return new int[] { 0 };
        ZERO_PARTICLE_ARGS[0] = 0;
        activateParticles();
        return ZERO_PARTICLE_ARGS;
    }

    private static void activateParticles() {
        if (!particleActivated) {
            particleActivated = true;
            OptimizerBridge.activate(PARTICLE_MODULE,
                "Ice and Fire 海蛇粒子参数数组已安全复用");
        }
    }
}
