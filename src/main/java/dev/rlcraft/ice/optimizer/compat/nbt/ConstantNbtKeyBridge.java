package dev.rlcraft.ice.optimizer.compat.nbt;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

/** Six Anvil literals, cached by the string-pool instance that owns their canonical values. */
public final class ConstantNbtKeyBridge {
    private ConstantNbtKeyBridge() { }
    public static Object addOrGet(ObjectOpenHashSet<String> pool, String key, String[] constants) {
        if (!OptimizerBridge.isEnabled(OptimizationModule.NBT_CONSTANT_KEYS.ordinal())) return pool.addOrGet(key);
        int slot = key == "Blocks" ? 0 : key == "Data" ? 1 : key == "Add" ? 2
            : key == "BlockLight" ? 3 : key == "SkyLight" ? 4 : key == "Biomes" ? 5 : -1;
        if (slot < 0) return pool.addOrGet(key);
        String value = constants[slot];
        if (value == null) constants[slot] = value = pool.addOrGet(key);
        return value;
    }
}
