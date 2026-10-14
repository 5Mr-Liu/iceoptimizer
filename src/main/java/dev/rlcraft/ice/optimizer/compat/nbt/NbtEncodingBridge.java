package dev.rlcraft.ice.optimizer.compat.nbt;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;

/** Writes the existing iteration order without a second key lookup for every tag. */
public final class NbtEncodingBridge {
    private static final int MODULE = OptimizationModule.VANILLA_CHUNK_COMPRESSION.ordinal();
    private NbtEncodingBridge() { }

    public static void write(NBTTagCompound compound, DataOutput output) throws IOException {
        NbtCompoundAccess access = (NbtCompoundAccess) compound;
        if (!OptimizerBridge.isEnabled(MODULE)) {
            access.ice$originalNbtWrite(output);
            return;
        }
        writeEntries(access, output);
    }

    static void writeEntries(NbtCompoundAccess access, DataOutput output) throws IOException {
        Iterator<Map.Entry<String, NBTBase>> iterator = NbtValueComparison.entries(access.ice$tagMap());
        while (iterator.hasNext()) {
            Map.Entry<String, NBTBase> entry = iterator.next();
            access.ice$writeNbtEntry(entry.getKey(), entry.getValue(), output);
        }
        output.writeByte(0);
    }
}
