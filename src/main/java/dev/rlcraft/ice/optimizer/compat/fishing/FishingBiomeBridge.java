package dev.rlcraft.ice.optimizer.compat.fishing;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.BiomeDictionary;

/** Fish metadata is shared; mutable biome observations live only in one regeneration call. */
public final class FishingBiomeBridge {
    private static final int[] X = { 0, 8, 15, 0, 0, 0, 8, 15 };
    private static final int[] Z = { 0, 0, 0, 0, 8, 15, 15, 8 };
    private static Map<String, BiomeDictionary.Type> names = Collections.emptyMap();
    private static int knownTypes = -1;
    private static final ThreadLocal<Frames> FRAMES = new ThreadLocal<Frames>() {
        @Override protected Frames initialValue() { return new Frames(); }
    };
    private FishingBiomeBridge() { }
    public static boolean isEnabled() { return OptimizerBridge.isEnabled(OptimizationModule.FISHING_BIOME_QUERY.ordinal()); }
    public static void begin() { FRAMES.get().push(); }
    public static void end() { FRAMES.get().pop(); }

    /** Forge Type's private name registry only appends; each chunk keeps its original first-use snapshot. */
    public static synchronized Map<String, BiomeDictionary.Type> sharedNames() {
        Collection<BiomeDictionary.Type> types = BiomeDictionary.Type.getAll();
        if (knownTypes != types.size()) {
            Map<String, BiomeDictionary.Type> next = new HashMap<String, BiomeDictionary.Type>();
            for (BiomeDictionary.Type type : types) next.put(type.getName(), type);
            names = Collections.unmodifiableMap(next);
            knownTypes = types.size();
        }
        return names;
    }

    public static boolean matches(Chunk chunk, World world, Map<String, BiomeDictionary.Type> byName,
                                  List<String> rules) {
        Frames frames = FRAMES.get();
        Frame frame = frames.depth == 0 ? new Frame() : frames.current();
        ChunkPos origin = chunk.getPos();
        BiomeProvider provider = world.getBiomeProvider();
        for (String rule : rules) {
            BiomeDictionary.Type type = byName.get(rule);
            if (type == null) continue;
            for (int i = 0; i < X.length; i++) {
                Biome biome = frame.biome(chunk, origin, i, provider);
                if (BiomeDictionary.hasType(biome, type)) return true;
            }
        }
        return false;
    }

    private static final class Frames {
        final ArrayList<Frame> frames = new ArrayList<Frame>(2);
        int depth;
        void push() { if (depth == frames.size()) frames.add(new Frame()); depth++; }
        Frame current() { return frames.get(depth - 1); }
        void pop() { frames.get(--depth).clear(); }
    }
    private static final class Frame {
        Chunk chunk;
        BiomeProvider provider;
        final Biome[] biomes = new Biome[8];
        int observed;
        Biome biome(Chunk value, ChunkPos origin, int coordinate, BiomeProvider source) {
            // Keep custom Chunk.getBiome callbacks live; the standard Chunk implementation is the audited path.
            if (value.getClass() != Chunk.class) return value.getBiome(origin.getBlock(X[coordinate], 60, Z[coordinate]), source);
            if (chunk != value || provider != source) { clear(); chunk = value; provider = source; }
            int slot = coordinate == 3 ? 0 : coordinate;
            int bit = 1 << slot;
            if ((observed & bit) != 0) return biomes[slot];
            Biome result = value.getBiome(origin.getBlock(X[coordinate], 60, Z[coordinate]), source);
            biomes[slot] = result; observed |= bit;
            return result;
        }
        void clear() { Arrays.fill(biomes, null); chunk = null; provider = null; observed = 0; }
    }
}
