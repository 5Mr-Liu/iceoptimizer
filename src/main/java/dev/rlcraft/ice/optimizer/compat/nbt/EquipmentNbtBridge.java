package dev.rlcraft.ice.optimizer.compat.nbt;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;

/** Replaces only ItemStack's compound comparisons; item/count/damage/caps stay original. */
public final class EquipmentNbtBridge {
    private static final int MODULE = OptimizationModule.VANILLA_EQUIPMENT_NBT.ordinal();
    private EquipmentNbtBridge() { }

    public static boolean equal(NBTTagCompound left, Object right) {
        if (!OptimizerBridge.isEnabled(MODULE) || left.getClass() != NBTTagCompound.class) return left.equals(right);
        return right instanceof NBTBase && NbtValueComparison.equal(left, (NBTBase) right);
    }
}
