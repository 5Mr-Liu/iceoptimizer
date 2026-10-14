package dev.rlcraft.ice.optimizer.compat.lighting;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;

/** Runtime switch for reusing Phosphor's decoded local coordinates. */
public final class PhosphorLocalPropagationBridge {
    private static final int MODULE = OptimizationModule.PHOSPHOR_LOCAL_PROPAGATION.ordinal();
    private PhosphorLocalPropagationBridge() { }
    public static boolean isEnabled() { return OptimizerBridge.isEnabled(MODULE); }
}
