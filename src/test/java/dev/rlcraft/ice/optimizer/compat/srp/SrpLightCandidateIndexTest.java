package dev.rlcraft.ice.optimizer.compat.srp;

import static org.junit.Assert.*;
import dev.rlcraft.ice.optimizer.compat.entity.EntityQueryTestWorld;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Random;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.BlockStateContainer;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.junit.BeforeClass;
import org.junit.Test;

public class SrpLightCandidateIndexTest {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    @Test public void rowsLearnOnlyVisitedCellsAndMutationsReplaceBothPositiveAndNegativeCandidates() {
        TrackedStates states = new TrackedStates();
        SrpLightCandidateIndex index = SrpLightCandidateIndex.forSection(states);
        assertEquals(0xffff, index.row(3, 4, 0, 15));
        assertEquals(0, states.gets);
        index.remember(3, 4, 2, Blocks.STONE.getDefaultState());
        assertEquals(0xffff & ~(1 << 2), index.row(3, 4, 0, 15));
        states.set(3, 4, 2, Blocks.GLOWSTONE.getDefaultState());
        assertEquals(0xffff, index.row(3, 4, 0, 15));
        states.set(3, 4, 2, Blocks.STONE.getDefaultState());
        assertEquals(0xffff & ~(1 << 2), index.row(3, 4, 0, 15));
        assertEquals((0xffff & ~(1 << 2)) & 0x7c, index.row(3, 4, 2, 6));
        states.ice$lightCandidates(null);
        assertNotSame(index, SrpLightCandidateIndex.forSection(states));
        assertEquals(0xffff, SrpLightCandidateIndex.forSection(states).row(3, 4, 0, 15));
    }

    @Test public void dynamicBlocksAndCustomStatesRemainCandidatesEvenWhenCurrentlyDark() {
        TrackedStates states = new TrackedStates();
        SrpLightCandidateIndex index = SrpLightCandidateIndex.forSection(states);
        DynamicLight dynamic = new DynamicLight();
        index.remember(0, 0, 0, dynamic.getDefaultState());
        assertEquals(1, index.row(0, 0, 0, 0));
        final int[] light = { 0 };
        IBlockState proxy = (IBlockState) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] { IBlockState.class }, (object, method, args) -> {
                if ("getLightValue".equals(method.getName()) && method.getParameterTypes().length == 0) return light[0];
                return method.invoke(Blocks.STONE.getDefaultState(), args);
            });
        index.remember(0, 0, 1, proxy);
        assertEquals(2, index.row(0, 0, 1, 1));
        light[0] = 15;
        assertEquals(2, index.row(0, 0, 1, 1));
        index.remember(0, 0, 2, Blocks.STONE.getDefaultState());
        assertEquals(0, index.row(0, 0, 2, 2));
        SrpLightCandidateIndex.blockLightChanged();
        assertEquals(4, SrpLightCandidateIndex.forSection(states).row(0, 0, 2, 2));
    }

    @Test public void customStateCanChangeItsBlockWithoutLosingTheCandidate() {
        TrackedStates states = new TrackedStates();
        SrpLightCandidateIndex index = SrpLightCandidateIndex.forSection(states);
        final IBlockState[] current = { Blocks.WATER.getDefaultState() };
        IBlockState proxy = (IBlockState) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] { IBlockState.class }, (object, method, args) -> method.invoke(current[0], args));
        index.remember(1, 2, 3, proxy);
        assertEquals(1 << 3, index.row(1, 2, 3, 3));
        current[0] = Blocks.GLOWSTONE.getDefaultState();
        assertEquals(Blocks.GLOWSTONE, proxy.getBlock());
        assertTrue(proxy.getLightValue() > 0);
        assertEquals(1 << 3, index.row(1, 2, 3, 3));
        index.remember(1, 2, 4, Blocks.WATER.getDefaultState());
        assertEquals(0, index.row(1, 2, 4, 4));
    }

    @Test public void indexedSearchMatchesFullScanWhileMovingAndTracksDynamicLightAndSectionReplacement() throws Exception {
        EntityQueryTestWorld world = EntityQueryTestWorld.create();
        Random random = new Random(772619L);
        IBlockState[] choices = { Blocks.STONE.getDefaultState(), Blocks.AIR.getDefaultState(),
            Blocks.GLOWSTONE.getDefaultState(), Blocks.REDSTONE_WIRE.getDefaultState(), Blocks.WATER.getDefaultState() };
        for (int cx = -2; cx <= 1; cx++) for (int cz = -2; cz <= 1; cz++) {
            TrackedStorage storage = new TrackedStorage(48);
            world.getChunk(cx, cz).getBlockStorageArray()[3] = storage;
            for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++)
                storage.states.set(x, y, z, random.nextInt(9) == 0 ? choices[random.nextInt(choices.length)] : choices[0]);
        }
        SrpLightSearchCallbacks rules = block -> block == Blocks.REDSTONE_WIRE;
        for (int step = 0; step < 30; step++) {
            double x = -18.75 + step * 0.8, z = -11.3 + step * 0.45;
            BlockPos expected = SrpLightSearchBridge.scan(world, x, 58.5, z, 1.8F, 6, 5,
                Collections.<Long>emptyList(), rules);
            int before = world.reads;
            BlockPos actual = SrpLightSearchBridge.scanIndexed(world, x, 58.5, z, 1.8F, 6, 5,
                Collections.<Long>emptyList(), rules);
            assertEquals(expected, actual);
            assertTrue("cold index never reads outside the original search", world.reads - before <= 13 * 6 * 13);
        }
        TrackedStorage isolated = new TrackedStorage(48);
        world.getChunk(4, 4).getBlockStorageArray()[3] = isolated;
        DynamicLight dynamic = new DynamicLight();
        isolated.states.set(1, 10, 1, dynamic.getDefaultState());
        assertNull(SrpLightSearchBridge.scanIndexed(world, 65, 58, 65, 1.8F, 1, 5,
            Collections.<Long>emptyList(), block -> false));
        dynamic.light = 15;
        assertEquals(new BlockPos(65, 58, 65), SrpLightSearchBridge.scanIndexed(world, 65, 58, 65, 1.8F, 1, 5,
            Collections.<Long>emptyList(), block -> false));
        world.getChunk(4, 4).getBlockStorageArray()[3] = new TrackedStorage(48);
        assertNull(SrpLightSearchBridge.scanIndexed(world, 65, 58, 65, 1.8F, 1, 5,
            Collections.<Long>emptyList(), block -> false));
    }

    private static final class DynamicLight extends Block {
        int light;
        DynamicLight() { super(Material.ROCK); }
        @Override public int getLightValue(IBlockState state) { return light; }
    }
    private static final class TrackedStorage extends ExtendedBlockStorage {
        final TrackedStates states = new TrackedStates();
        TrackedStorage(int y) { super(y, false); }
        @Override public BlockStateContainer getData() { return states; }
        @Override public IBlockState get(int x, int y, int z) { return states.get(x, y, z); }
    }
    private static final class TrackedStates extends BlockStateContainer implements SrpLightSectionAccess {
        Object index;
        int gets;
        @Override public Object ice$lightCandidates() { return index; }
        @Override public void ice$lightCandidates(Object value) { index = value; }
        @Override protected void set(int position, IBlockState state) {
            super.set(position, state);
            SrpLightCandidateIndex.changed(this, position, state);
        }
        @Override public IBlockState get(int x, int y, int z) { gets++; return super.get(x, y, z); }
    }
}
