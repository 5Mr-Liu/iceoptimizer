package dev.rlcraft.ice.optimizer.compat.trinkets;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagString;

/**
 * Reuses only standard scalar encodings while the original writer reads live state.
 * Scalar payloads have no public mutators in 1.12. Every mutable compound, list and
 * array remains owned by its original write invocation; no NBT subtree is cached.
 */
public final class TrinketsStateBridge {
    private static final int MODULE = OptimizationModule.TRINKETS_STATE_NBT.ordinal();
    private static volatile boolean activated;

    private TrinketsStateBridge() { }

    public static ScalarCache cacheForWrite(Object owner, int slots) {
        if (!OptimizerBridge.isEnabled(MODULE) || !(owner instanceof TrinketsStateAccess)) return null;
        TrinketsStateAccess access = (TrinketsStateAccess) owner;
        Object existing = access.ice$trinketsScalarCache();
        ScalarCache cache;
        if (existing instanceof ScalarCache && ((ScalarCache) existing).values.length == slots) {
            cache = (ScalarCache) existing;
        } else {
            cache = new ScalarCache(slots);
            access.ice$trinketsScalarCache(cache);
        }
        if (!activated) {
            activated = true;
            OptimizerBridge.activate(MODULE, "Trinkets 按现态复用标量编码，完整独立写出饰品、计时器和元素状态");
        }
        return cache;
    }

    public static void setInteger(NBTTagCompound tag, String key, int value, ScalarCache cache, int slot) {
        if (cache == null) { tag.setInteger(key, value); return; }
        NBTBase previous = cache.values[slot];
        NBTTagInt encoded = previous instanceof NBTTagInt && ((NBTTagInt) previous).getInt() == value
            ? (NBTTagInt) previous : new NBTTagInt(value);
        cache.values[slot] = encoded;
        tag.setTag(key, encoded);
    }

    public static void setFloat(NBTTagCompound tag, String key, float value, ScalarCache cache, int slot) {
        if (cache == null) { tag.setFloat(key, value); return; }
        NBTBase previous = cache.values[slot];
        // Preserve signed zero and non-canonical NaN payloads in the in-memory NBT value.
        NBTTagFloat encoded = previous instanceof NBTTagFloat
            && Float.floatToRawIntBits(((NBTTagFloat) previous).getFloat()) == Float.floatToRawIntBits(value)
            ? (NBTTagFloat) previous : new NBTTagFloat(value);
        cache.values[slot] = encoded;
        tag.setTag(key, encoded);
    }

    public static void setBoolean(NBTTagCompound tag, String key, boolean value, ScalarCache cache, int slot) {
        if (cache == null) { tag.setBoolean(key, value); return; }
        byte numeric = (byte) (value ? 1 : 0);
        NBTBase previous = cache.values[slot];
        NBTTagByte encoded = previous instanceof NBTTagByte && ((NBTTagByte) previous).getByte() == numeric
            ? (NBTTagByte) previous : new NBTTagByte(numeric);
        cache.values[slot] = encoded;
        tag.setTag(key, encoded);
    }

    public static void setString(NBTTagCompound tag, String key, String value, ScalarCache cache, int slot) {
        if (cache == null) { tag.setString(key, value); return; }
        NBTBase previous = cache.values[slot];
        NBTTagString encoded = previous instanceof NBTTagString && ((NBTTagString) previous).getString().equals(value)
            ? (NBTTagString) previous : new NBTTagString(value);
        cache.values[slot] = encoded;
        tag.setTag(key, encoded);
    }

    /** One current encoding per schema field; changing values never grow this cache. */
    public static final class ScalarCache {
        private final NBTBase[] values;
        public ScalarCache(int slots) { values = new NBTBase[slots]; }
    }
}
