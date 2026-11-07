package dev.rlcraft.ice.optimizer.compat.nbt;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.Map;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;

/** Uses the measured comparison path only for the pack's exact NormalASM map type. */
public final class CapabilityNbtBridge {
    private static final int MODULE = OptimizationModule.VANILLA_EQUIPMENT_NBT.ordinal();
    private static final String NORMAL_MAP = "mirror.normalasm.api.datastructures.NormalTagMap";

    private CapabilityNbtBridge() { }

    public static boolean equal(NBTTagCompound left, Object right) {
        if (!OptimizerBridge.isEnabled(MODULE) || left.getClass() != NBTTagCompound.class
            || right == null || right.getClass() != NBTTagCompound.class
            || !(left instanceof NbtCompoundAccess) || !(right instanceof NbtCompoundAccess)) {
            return left.equals(right);
        }
        Map<String, NBTBase> leftMap = ((NbtCompoundAccess) left).ice$tagMap();
        Map<String, NBTBase> rightMap = ((NbtCompoundAccess) right).ice$tagMap();
        if (!NORMAL_MAP.equals(leftMap.getClass().getName()) || !NORMAL_MAP.equals(rightMap.getClass().getName())) {
            return left.equals(right);
        }
        return NbtValueComparison.mapsEqual(leftMap, rightMap);
    }
}
