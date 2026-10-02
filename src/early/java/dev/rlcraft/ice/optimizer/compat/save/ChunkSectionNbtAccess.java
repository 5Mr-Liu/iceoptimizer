package dev.rlcraft.ice.optimizer.compat.save;

import net.minecraft.util.BitArray;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.chunk.IBlockStatePalette;
import net.minecraft.world.chunk.NibbleArray;

/** Save-local palette access; no state or registry IDs survive a serialization. */
public interface ChunkSectionNbtAccess {
    BitArray ice$nbtStorage();
    IBlockStatePalette ice$nbtPalette();
    int ice$nbtBits();
    IBlockState ice$nbtAir();
    NibbleArray ice$originalSectionNbt(byte[] blocks, NibbleArray metadata);
}
