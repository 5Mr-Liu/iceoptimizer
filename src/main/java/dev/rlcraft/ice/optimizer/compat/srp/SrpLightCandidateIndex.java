package dev.rlcraft.ice.optimizer.compat.srp;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEndGateway;
import net.minecraft.block.BlockEndPortalFrame;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.BlockPortal;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.chunk.BlockStateContainer;

/** Lazily learns only queried cells; mutation hooks maintain a section's candidate bits. */
public final class SrpLightCandidateIndex {
    private static final AtomicInteger BLOCK_LIGHT_GENERATION = new AtomicInteger();
    private static final ClassValue<Boolean> DYNAMIC = new ClassValue<Boolean>() {
        @Override protected Boolean computeValue(Class<?> type) {
            return Boolean.valueOf(declaring(type, "getLightValue", "func_149750_m") != Block.class
                || declaring(type, "getMaterial", "func_149688_o") != Block.class);
        }
    };
    private static final ClassValue<Boolean> DYNAMIC_STATE = new ClassValue<Boolean>() {
        @Override protected Boolean computeValue(Class<?> type) {
            String standard = "net.minecraft.block.state.BlockStateContainer$StateImplementation";
            Class<?> light = declaring(type, "getLightValue", "func_185906_d", new Class<?>[0]);
            Class<?> material = declaring(type, "getMaterial", "func_185904_a", new Class<?>[0]);
            Class<?> block = declaring(type, "getBlock", "func_177230_c", new Class<?>[0]);
            return Boolean.valueOf(light == null || material == null || block == null
                || !standard.equals(light.getName()) || !standard.equals(material.getName())
                || !standard.equals(block.getName()));
        }
    };
    private final long[] known = new long[64];
    private final long[] candidates = new long[64];
    private int lightGeneration = BLOCK_LIGHT_GENERATION.get();

    private SrpLightCandidateIndex() { }

    public static SrpLightCandidateIndex forSection(BlockStateContainer states) {
        SrpLightSectionAccess access = (SrpLightSectionAccess) (Object) states;
        SrpLightCandidateIndex index = (SrpLightCandidateIndex) access.ice$lightCandidates();
        if (index == null) {
            index = new SrpLightCandidateIndex();
            access.ice$lightCandidates(index);
        }
        index.refreshGeneration();
        return index;
    }

    /** Z is the low coordinate, matching SRP's innermost loop and its tie order. */
    public int row(int x, int y, int minZ, int maxZ) {
        int word = (x << 2) | (y >> 2);
        int shift = (y & 3) << 4;
        int range = (0xffff << minZ) & (0xffff >>> (15 - maxZ));
        return range & (int) ((candidates[word] | ~known[word]) >>> shift);
    }

    public boolean isKnown(int x, int y, int z) {
        int cell = (x << 8) | (y << 4) | z;
        return (known[cell >>> 6] & (1L << (cell & 63))) != 0;
    }

    public void remember(int x, int y, int z, IBlockState state) {
        int cell = (x << 8) | (y << 4) | z;
        int word = cell >>> 6;
        long bit = 1L << (cell & 63);
        known[word] |= bit;
        if (possible(state)) candidates[word] |= bit;
        else candidates[word] &= ~bit;
    }

    public static void changed(BlockStateContainer states, int vanillaIndex, IBlockState state) {
        SrpLightCandidateIndex index = (SrpLightCandidateIndex)
            ((SrpLightSectionAccess) (Object) states).ice$lightCandidates();
        if (index != null) {
            index.refreshGeneration();
            index.remember(vanillaIndex & 15, (vanillaIndex >>> 8) & 15,
                (vanillaIndex >>> 4) & 15, state);
        }
    }

    public static void blockLightChanged() { BLOCK_LIGHT_GENERATION.incrementAndGet(); }

    private void refreshGeneration() {
        int current = BLOCK_LIGHT_GENERATION.get();
        if (current != lightGeneration) {
            Arrays.fill(known, 0L);
            Arrays.fill(candidates, 0L);
            lightGeneration = current;
        }
    }

    private static boolean possible(IBlockState state) {
        if (DYNAMIC_STATE.get(state.getClass()).booleanValue()) return true;
        Block block = state.getBlock();
        if (block instanceof BlockLiquid || block instanceof BlockPortal
            || block instanceof BlockEndGateway || block instanceof BlockEndPortalFrame) return false;
        if (DYNAMIC.get(block.getClass()).booleanValue()) return true;
        Material material = state.getMaterial();
        return material != Material.PORTAL && (material == Material.CIRCUITS || state.getLightValue() > 0);
    }

    private static Class<?> declaring(Class<?> type, String mapped, String srg) {
        return declaring(type, mapped, srg, new Class<?>[] { IBlockState.class });
    }

    private static Class<?> declaring(Class<?> type, String mapped, String srg, Class<?>[] parameters) {
        for (Method method : type.getMethods()) {
            if ((mapped.equals(method.getName()) || srg.equals(method.getName()))
                && Arrays.equals(method.getParameterTypes(), parameters)) return method.getDeclaringClass();
        }
        return null;
    }
}
