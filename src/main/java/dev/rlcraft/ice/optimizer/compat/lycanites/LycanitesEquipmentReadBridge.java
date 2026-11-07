package dev.rlcraft.ice.optimizer.compat.lycanites;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;

/** The injected read helpers share one lazily decoded list per top-level calculation. */
public final class LycanitesEquipmentReadBridge {
    private static final int MODULE = OptimizationModule.LYCANITES_EQUIPMENT_READ.ordinal();
    private static volatile boolean activated;

    private LycanitesEquipmentReadBridge() { }

    public static boolean useEquipmentReadView() {
        if (!OptimizerBridge.isEnabled(MODULE)) return false;
        if (!activated) {
            activated = true;
            OptimizerBridge.activate(MODULE, "Lycanites 装备属性与说明在单次计算中复用部件解析");
        }
        return true;
    }

    /** Owned by one invocation; null means the original reader has not run yet. */
    public static final class View {
        public NonNullList<ItemStack> parts;
    }
}
