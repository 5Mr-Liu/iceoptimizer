package dev.rlcraft.ice.optimizer.compat.mobends;

import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;

/** Configuration/circuit-breaker gates used by exact Mo' Bends bytecode. */
public final class MoBendsRenderBridge {
    private static volatile boolean modelActivated;
    private static volatile boolean quaternionActivated;
    private static volatile boolean entityActivated;

    private MoBendsRenderBridge() {
    }

    public static boolean useModelRender() {
        boolean enabled = OptimizerBridge.isEnabled("mobends-model-render");
        if (enabled && !modelActivated) {
            modelActivated = true;
            OptimizerBridge.activate("mobends-model-render",
                "Mo' Bends 父链拓扑与子模型索引遍历已启用");
        }
        return enabled;
    }

    public static boolean useQuaternionCache() {
        boolean enabled = OptimizerBridge.isEnabled("mobends-quaternion-cache");
        if (enabled && !quaternionActivated) {
            quaternionActivated = true;
            OptimizerBridge.activate("mobends-quaternion-cache",
                "Mo' Bends 四元数原始位验证矩阵缓存已启用");
        }
        return enabled;
    }

    public static boolean useEntityAnimation() {
        boolean enabled = OptimizerBridge.isEnabled("mobends-entity-animation");
        if (enabled && !entityActivated) {
            entityActivated = true;
            OptimizerBridge.activate("mobends-entity-animation",
                "Mo' Bends 非攀爬实体的冗余方块查询已短路");
        }
        return enabled;
    }
}
