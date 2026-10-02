package dev.rlcraft.ice.optimizer.compat.save;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.IdentityHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.BitArray;
import net.minecraft.util.ObjectIntIdentityMap;
import net.minecraft.world.chunk.BlockStateContainer;
import net.minecraft.world.chunk.BlockStatePaletteHashMap;
import net.minecraft.world.chunk.BlockStatePaletteLinear;
import net.minecraft.world.chunk.IBlockStatePalette;
import net.minecraft.world.chunk.NibbleArray;

/** Resolves ordinary local palettes once and packs the same legacy NBT arrays. */
public final class ChunkSectionNbtBridge {
    private static final int MODULE = OptimizationModule.VANILLA_CHUNK_COMPRESSION.ordinal();
    private static final ThreadLocal<int[]> PALETTE_IDS = new ThreadLocal<int[]>() {
        @Override protected int[] initialValue() { return new int[256]; }
    };

    private ChunkSectionNbtBridge() { }

    public static NibbleArray write(BlockStateContainer section, byte[] blocks,
                                    NibbleArray metadata) {
        ChunkSectionNbtAccess access = (ChunkSectionNbtAccess) section;
        ObjectIntIdentityMap<IBlockState> registry = Block.BLOCK_STATE_IDS;
        if (!OptimizerBridge.isEnabled(MODULE) || section.getClass() != BlockStateContainer.class
            || blocks == null || blocks.length < 4096 || metadata == null
            || metadata.getClass() != NibbleArray.class
            || !(registry instanceof BlockStateIdMapAccess) || !isDirectRegistry(registry)) {
            return access.ice$originalSectionNbt(blocks, metadata);
        }
        BitArray storage = access.ice$nbtStorage();
        IBlockStatePalette palette = access.ice$nbtPalette();
        int bits = access.ice$nbtBits();
        if (bits < 4 || bits > 8 || storage == null || storage.getClass() != BitArray.class
            || storage.size() != 4096 || palette == null
            || (palette.getClass() != BlockStatePaletteLinear.class
                && palette.getClass() != BlockStatePaletteHashMap.class)) {
            return access.ice$originalSectionNbt(blocks, metadata);
        }
        int[] ids = PALETTE_IDS.get();
        IdentityHashMap<IBlockState, Integer> direct =
            ((BlockStateIdMapAccess) registry).ice$directStateIds();
        if (!resolvePalette(palette, bits, direct, ids, access.ice$nbtAir())) {
            // Forge resolves missing states through mod getMetaFromState callbacks.
            // Do not memoize those callbacks or partially write before falling back.
            return access.ice$originalSectionNbt(blocks, metadata);
        }
        return pack(storage.getBackingLongArray(), bits, ids, blocks, metadata.getData());
    }

    private static boolean isDirectRegistry(Object registry) {
        Class<?> type = registry.getClass();
        return type == ObjectIntIdentityMap.class
            || "net.minecraftforge.registries.GameData$BlockCallbacks$1".equals(type.getName());
    }

    static boolean resolvePalette(IBlockStatePalette palette, int bits,
                                  IdentityHashMap<IBlockState, Integer> direct, int[] ids,
                                  IBlockState air) {
        for (int i = 0, count = 1 << bits; i < count; i++) {
            IBlockState state = palette.getBlockState(i);
            Integer id = direct.get(state == null ? air : state);
            if (id == null) return false;
            ids[i] = id.intValue();
        }
        return true;
    }

    static NibbleArray pack(long[] storage, int bits, int[] ids, byte[] blocks,
                            byte[] metadata) {
        byte[] extension = null;
        int mask = (1 << bits) - 1;
        for (int i = 0; i < 4096; i += 2) {
            int first = ids[read(storage, bits, mask, i)];
            int second = ids[read(storage, bits, mask, i + 1)];
            blocks[i] = (byte) (first >> 4);
            blocks[i + 1] = (byte) (second >> 4);
            metadata[i >> 1] = (byte) ((first & 15) | ((second & 15) << 4));
            int high = ((first >> 12) & 15) | (((second >> 12) & 15) << 4);
            if (high != 0) {
                if (extension == null) extension = new byte[2048];
                extension[i >> 1] = (byte) high;
            }
        }
        return extension == null ? null : new NibbleArray(extension);
    }

    private static int read(long[] storage, int bits, int mask, int index) {
        int bit = index * bits;
        int word = bit >>> 6;
        int shift = bit & 63;
        long value = storage[word] >>> shift;
        if (shift + bits > 64) value |= storage[word + 1] << (64 - shift);
        return (int) value & mask;
    }
}
