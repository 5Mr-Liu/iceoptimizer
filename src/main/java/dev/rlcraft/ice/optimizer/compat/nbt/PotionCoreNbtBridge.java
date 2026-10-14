package dev.rlcraft.ice.optimizer.compat.nbt;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTPrimitive;
import net.minecraft.nbt.NBTTagCompound;

/** One map lookup per PotionCore numeric read, with vanilla conversions and defaults. */
public final class PotionCoreNbtBridge {
    private static final int MODULE = OptimizationModule.POTION_CORE_NBT.ordinal();
    private PotionCoreNbtBridge() { }

    public static boolean isEnabled() { return OptimizerBridge.isEnabled(MODULE); }

    private static NBTPrimitive number(NBTTagCompound tag, String key) {
        NBTBase value = tag.getTag(key);
        if (value == null) return null;
        int type = value.getId();
        return type >= 1 && type <= 6 && value instanceof NBTPrimitive
            ? (NBTPrimitive) value : null;
    }

    public static int getInteger(NBTTagCompound tag, String key) {
        if (tag.getClass() != NBTTagCompound.class) return tag.getInteger(key);
        NBTPrimitive value = number(tag, key);
        return value == null ? 0 : value.getInt();
    }

    public static boolean getBoolean(NBTTagCompound tag, String key) {
        if (tag.getClass() != NBTTagCompound.class) return tag.getBoolean(key);
        NBTPrimitive value = number(tag, key);
        return value != null && value.getByte() != 0;
    }

    public static double getDouble(NBTTagCompound tag, String key) {
        if (tag.getClass() != NBTTagCompound.class) return tag.getDouble(key);
        NBTPrimitive value = number(tag, key);
        return value == null ? 0.0D : value.getDouble();
    }
}
