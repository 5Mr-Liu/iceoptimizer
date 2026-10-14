package dev.rlcraft.ice.optimizer.compat.srp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEndGateway;
import net.minecraft.block.BlockEndPortalFrame;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.BlockPortal;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import org.junit.BeforeClass;
import org.junit.Test;

public class SrpLightSearchBridgeTest {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    @Test
    public void matchesOriginalScanAtNegativeCoordinatesAcrossChangingWorlds() {
        Random random = new Random(49312L);
        IBlockState[] states = { Blocks.STONE.getDefaultState(), Blocks.GLOWSTONE.getDefaultState(),
            Blocks.TORCH.getDefaultState(), Blocks.REDSTONE_WIRE.getDefaultState(),
            Blocks.WATER.getDefaultState(), Blocks.PORTAL.getDefaultState(),
            Blocks.END_PORTAL_FRAME.getDefaultState(), Blocks.END_GATEWAY.getDefaultState() };
        for (int trial = 0; trial < 40; trial++) {
            FakeAccess world = new FakeAccess();
            double px = -20.75D + trial * 0.1D;
            double py = 60.25D;
            double pz = -0.4D;
            List<Long> cant = new ArrayList<Long>();
            for (int x = -3; x <= 3; x++) for (int y = -4; y <= 2; y++) {
                for (int z = -3; z <= 3; z++) {
                    BlockPos position = new BlockPos(px + x, py + y, pz + z);
                    world.blocks.put(position, states[random.nextInt(states.length)]);
                    if (random.nextInt(7) == 0) cant.add(position.toLong());
                }
            }
            Rules rules = new Rules();
            rules.blocked = trial % 2 == 0 ? Blocks.GLOWSTONE : Blocks.TORCH;
            BlockPos expected = original(world, px, py, pz, 2.3F, 3, 5, cant, rules);
            List<BlockPos> originalOrder = new ArrayList<BlockPos>(world.reads);
            world.resetReads();
            rules.calls = 0;
            BlockPos result = SrpLightSearchBridge.scan(world, px, py, pz, 2.3F, 3, 5, cant, rules);
            assertEquals(expected, result);
            assertEquals(originalOrder, world.reads);
            assertEquals("one query cursor, copied only for the returned target", 1, world.identities.size());
            assertEquals(4, rules.calls);
            if (result != null) assertFalse(result instanceof BlockPos.MutableBlockPos);
        }
    }

    @Test
    public void keepsFirstEquidistantTargetAndCantExclusion() {
        FakeAccess world = new FakeAccess();
        BlockPos first = new BlockPos(-1, 60, 0);
        BlockPos second = new BlockPos(1, 60, 0);
        world.blocks.put(first, Blocks.GLOWSTONE.getDefaultState());
        world.blocks.put(second, Blocks.GLOWSTONE.getDefaultState());
        assertEquals(first, SrpLightSearchBridge.scan(world, 0, 60, 0, 1.8F, 2, 5,
            Collections.<Long>emptyList(), new Rules()));
        assertEquals(second, SrpLightSearchBridge.scan(world, 0, 60, 0, 1.8F, 2, 5,
            Arrays.asList(first.toLong(), first.toLong()), new Rules()));
        assertNull(SrpLightSearchBridge.scan(world, 0, 60, 0, 1.8F, 2, 5,
            Arrays.asList(first.toLong(), second.toLong()), new Rules()));
    }

    @Test
    public void readsLightAndRulesAgainOnEverySearch() {
        FakeAccess world = new FakeAccess();
        DynamicLight block = new DynamicLight();
        BlockPos position = new BlockPos(0, 60, 0);
        world.blocks.put(position, block.getDefaultState());
        Rules rules = new Rules();
        block.light = 15;
        assertEquals(position, SrpLightSearchBridge.scan(world, 0, 60, 0, 1.8F, 1, 5,
            Collections.<Long>emptyList(), rules));
        block.light = 0;
        assertNull(SrpLightSearchBridge.scan(world, 0, 60, 0, 1.8F, 1, 5,
            Collections.<Long>emptyList(), rules));
        block.light = 15;
        rules.blocked = block;
        assertNull(SrpLightSearchBridge.scan(world, 0, 60, 0, 1.8F, 1, 5,
            Collections.<Long>emptyList(), rules));
        rules.blocked = null;
        assertEquals(position, SrpLightSearchBridge.scan(world, 0, 60, 0, 1.8F, 1, 5,
            Collections.<Long>emptyList(), rules));
    }

    @Test
    public void scansFullConfiguredRangeWithOneCursor() {
        FakeAccess world = new FakeAccess();
        BlockPos edge = new BlockPos(20, 61, 20);
        world.blocks.put(edge, Blocks.GLOWSTONE.getDefaultState());
        Rules rules = new Rules();
        assertEquals(edge, SrpLightSearchBridge.scan(world, 0, 60, 0, 1.8F, 20, 5,
            Collections.<Long>emptyList(), rules));
        assertEquals(10086, world.reads.size());
        assertEquals(1, world.identities.size());
        assertEquals(2, rules.calls);
    }

    @Test
    public void respectsRuntimePauseWithoutChangingConfiguration() {
        OptimizationModule module = OptimizationModule.SRP_LIGHT_SEARCH;
        boolean previousPause = OptimizerRegistry.isRuntimePaused();
        OptimizerRegistry.breaker(module).configure(true, 3);
        OptimizerRegistry.breaker(module).patchInstalled("light-task", "test");
        try {
            OptimizerRegistry.setRuntimePaused(false);
            assertTrue(SrpLightSearchBridge.isEnabled());
            OptimizerRegistry.setRuntimePaused(true);
            assertFalse(SrpLightSearchBridge.isEnabled());
            OptimizerRegistry.setRuntimePaused(false);
            assertTrue(SrpLightSearchBridge.isEnabled());
        } finally {
            OptimizerRegistry.breaker(module).configure(false, 3);
            OptimizerRegistry.setRuntimePaused(previousPause);
        }
    }

    /** Direct transcription of the reviewed SRP 1.9.21 findSource scan and final selection. */
    private static BlockPos original(FakeAccess world, double px, double py, double pz,
                                     float height, int range, int trigger, List<Long> cant, Rules rules) {
        List<BlockPos> candidates = new ArrayList<BlockPos>();
        for (int x = -range; x <= range; x++) for (int y = -4; y <= height; y++) {
            for (int z = -range; z <= range; z++) {
                BlockPos position = new BlockPos(px + x, py + y, pz + z);
                IBlockState state = world.getBlockState(position);
                Block block = state.getBlock();
                if (block instanceof BlockLiquid || block instanceof BlockPortal
                    || block instanceof BlockEndGateway || block instanceof BlockEndPortalFrame
                    || state.getMaterial() == Material.PORTAL) continue;
                if (rules.ice$lightBlocked(block)) continue;
                if (state.getLightValue() < trigger && state.getMaterial() != Material.CIRCUITS) continue;
                boolean allowed = true;
                for (Long forbidden : cant) if (forbidden.longValue() == position.toLong()) {
                    allowed = false;
                    break;
                }
                if (allowed) candidates.add(position);
            }
        }
        BlockPos best = null;
        double distance = 40000.0D;
        for (BlockPos candidate : candidates) {
            double next = candidate.distanceSq(px, py, pz);
            if (next < distance) { distance = next; best = candidate; }
        }
        return best;
    }

    private static final class Rules implements SrpLightSearchCallbacks {
        Block blocked;
        int calls;
        @Override public boolean ice$lightBlocked(Object block) { calls++; return block == blocked; }
    }

    private static final class DynamicLight extends Block {
        int light;
        DynamicLight() { super(Material.ROCK); }
        @Override public int getLightValue(IBlockState state) { return light; }
    }

    private static final class FakeAccess implements IBlockAccess {
        final Map<BlockPos, IBlockState> blocks = new HashMap<BlockPos, IBlockState>();
        final List<BlockPos> reads = new ArrayList<BlockPos>();
        final IdentityHashMap<BlockPos, Boolean> identities = new IdentityHashMap<BlockPos, Boolean>();
        void resetReads() { reads.clear(); identities.clear(); }
        @Override public IBlockState getBlockState(BlockPos position) {
            identities.put(position, Boolean.TRUE);
            reads.add(position.toImmutable());
            IBlockState state = blocks.get(position);
            return state == null ? Blocks.STONE.getDefaultState() : state;
        }
        @Override public TileEntity getTileEntity(BlockPos position) { return null; }
        @Override public int getCombinedLight(BlockPos position, int value) { return value; }
        @Override public boolean isAirBlock(BlockPos position) { return false; }
        @Override public Biome getBiome(BlockPos position) { return null; }
        @Override public int getStrongPower(BlockPos position, EnumFacing face) { return 0; }
        @Override public WorldType getWorldType() { return WorldType.DEFAULT; }
        @Override public boolean isSideSolid(BlockPos position, EnumFacing face, boolean defaultValue) {
            return defaultValue;
        }
    }
}
