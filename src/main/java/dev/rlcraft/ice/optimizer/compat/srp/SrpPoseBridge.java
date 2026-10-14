package dev.rlcraft.ice.optimizer.compat.srp;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;

/** One module-mask read per pose; the reusable values live in method locals. */
public final class SrpPoseBridge {
    private static final int MODULE = OptimizationModule.SRP_POSE_CACHE.ordinal();
    private static boolean activated;

    private SrpPoseBridge() {
    }

    public static boolean usePoseReuse() {
        if (!OptimizerBridge.isEnabled(MODULE)) return false;
        if (!activated) {
            activated = true;
            OptimizerBridge.activate(MODULE, "SRP Heblu 同次姿态计算已复用相同余弦表达式");
        }
        return true;
    }
}
