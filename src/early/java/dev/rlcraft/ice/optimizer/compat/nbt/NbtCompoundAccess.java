package dev.rlcraft.ice.optimizer.compat.nbt;

import java.io.DataOutput;
import java.io.IOException;
import java.util.Map;
import net.minecraft.nbt.NBTBase;

/** Direct access to the existing compound contents; no cached NBT state. */
public interface NbtCompoundAccess {
    Map<String, NBTBase> ice$tagMap();
    void ice$originalNbtWrite(DataOutput output) throws IOException;
    void ice$writeNbtEntry(String key, NBTBase value, DataOutput output) throws IOException;
}
