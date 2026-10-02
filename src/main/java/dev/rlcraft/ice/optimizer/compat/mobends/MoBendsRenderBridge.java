package dev.rlcraft.ice.optimizer.compat.mobends;

import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import dev.rlcraft.ice.optimizer.OptimizationModule;

/** Configuration/circuit-breaker gates used by exact Mo' Bends bytecode. */
public final class MoBendsRenderBridge {
    private static final int MODEL_MODULE = OptimizationModule.MOBENDS_MODEL_RENDER.ordinal();
    private static final int QUATERNION_MODULE = OptimizationModule.MOBENDS_QUATERNION_CACHE.ordinal();
    private static final int ENTITY_MODULE = OptimizationModule.MOBENDS_ENTITY_ANIMATION.ordinal();
    private static volatile boolean modelActivated;
    private static volatile boolean quaternionActivated;
    private static volatile boolean entityActivated;

    private MoBendsRenderBridge() {
    }

    public static boolean useModelRender() {
        boolean enabled = OptimizerBridge.isEnabled(MODEL_MODULE);
        if (enabled && !modelActivated) {
            modelActivated = true;
            OptimizerBridge.activate(MODEL_MODULE,
                "Mo' Bends 父链拓扑与子模型索引遍历已启用");
        }
        return enabled;
    }

    public static boolean useQuaternionCache() {
        boolean enabled = OptimizerBridge.isEnabled(QUATERNION_MODULE);
        if (enabled && !quaternionActivated) {
            quaternionActivated = true;
            OptimizerBridge.activate(QUATERNION_MODULE,
                "Mo' Bends 四元数原始位验证矩阵缓存已启用");
        }
        return enabled;
    }

    public static boolean useEntityAnimation() {
        boolean enabled = OptimizerBridge.isEnabled(ENTITY_MODULE);
        if (enabled && !entityActivated) {
            entityActivated = true;
            OptimizerBridge.activate(ENTITY_MODULE,
                "Mo' Bends 非攀爬实体的冗余方块查询已短路");
        }
        return enabled;
    }
}
