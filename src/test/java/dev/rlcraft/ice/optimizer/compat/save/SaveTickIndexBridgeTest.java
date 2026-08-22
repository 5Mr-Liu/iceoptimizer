package dev.rlcraft.ice.optimizer.compat.save;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.chunk.Chunk;
import org.junit.Test;

public final class SaveTickIndexBridgeTest {
    @Test
    public void preservesTreeThenCurrentOrderAndVanillaTwoBlockChunkOverlap() {
        FakeAccessor world = new FakeAccessor();
        NextTickListEntry first = entry(14, 64, 14, 10L);
        NextTickListEntry second = entry(16, 64, 16, 20L);
        NextTickListEntry current = entry(15, 70, 15, 5L);
        world.tree.add(first);
        world.tree.add(second);
        world.current.add(current);
        enable();
        long token = SaveTickIndexBridge.begin("provider", world, true);
        try {
            assertNull(SaveTickIndexBridge.pendingForChunk(world, 1, 1));
            List<NextTickListEntry> southeast = SaveTickIndexBridge.pendingForChunk(world, 1, 1);
            assertEquals(3, southeast.size());
            assertSame(first, southeast.get(0));
            assertSame(second, southeast.get(1));
            assertSame(current, southeast.get(2));

            List<NextTickListEntry> origin = SaveTickIndexBridge.pendingForChunk(world, 0, 0);
            assertEquals(2, origin.size());
            assertSame(first, origin.get(0));
            assertSame(current, origin.get(1));

            southeast.clear();
            assertEquals(3, SaveTickIndexBridge.pendingForChunk(world, 1, 1).size());
            assertNull(SaveTickIndexBridge.pendingForChunk(world, -1, -1));
        } finally {
            SaveTickIndexBridge.end(token);
            disable();
        }
    }

    @Test
    public void rebuildsAfterTrackedMutationAndKeepsNestedIncrementalScopesIndependent() {
        FakeAccessor world = new FakeAccessor();
        NextTickListEntry first = entry(0, 64, 0, 10L);
        NextTickListEntry second = entry(1, 64, 1, 20L);
        world.tree.add(first);
        enable();
        long outer = SaveTickIndexBridge.begin("provider", world, true);
        try {
            assertNull(SaveTickIndexBridge.pendingForChunk(world, 0, 0));
            assertEquals(1, SaveTickIndexBridge.pendingForChunk(world, 0, 0).size());
            world.tree.add(second);
            world.version++;
            assertEquals(2, SaveTickIndexBridge.pendingForChunk(world, 0, 0).size());

            long nested = SaveTickIndexBridge.begin("provider", world, false);
            try {
                assertNull(SaveTickIndexBridge.pendingForChunk(world, 0, 0));
                assertEquals(2, SaveTickIndexBridge.pendingForChunk(world, 0, 0).size());
            } finally {
                SaveTickIndexBridge.end(nested);
            }
            assertEquals(2, SaveTickIndexBridge.pendingForChunk(world, 0, 0).size());
        } finally {
            SaveTickIndexBridge.end(outer);
            disable();
        }
    }

    @Test
    public void incrementalScopeUsesOriginalFirstQueryBeforeBuildingGlobalIndex() {
        FakeAccessor world = new FakeAccessor();
        world.tree.add(entry(0, 64, 0, 10L));
        enable();
        long token = SaveTickIndexBridge.begin("provider", world, false);
        try {
            assertNull(SaveTickIndexBridge.pendingForChunk(world, 0, 0));
            assertEquals(0, world.treeReads);
            assertEquals(0, world.currentReads);
            assertEquals(1, SaveTickIndexBridge.pendingForChunk(world, 0, 0).size());
            assertEquals(1, world.treeReads);
            assertEquals(1, world.currentReads);
        } finally {
            SaveTickIndexBridge.end(token);
            disable();
        }
    }

    @Test
    public void outsideFullSaveUsesOriginalMethod() {
        FakeAccessor world = new FakeAccessor();
        world.original.add(entry(0, 1, 0, 1L));
        enable();
        try {
            List<NextTickListEntry> result = SaveTickIndexBridge.pendingBlockUpdates(world, null, false);
            assertSame(world.original, result);
            assertEquals(1, world.originalCalls);
        } finally {
            disable();
        }
    }

    @Test
    public void indexedResultsMatchVanillaBoundsAndIterationOrderExactly() {
        FakeAccessor world = new FakeAccessor();
        int sequence = 0;
        for (int x : new int[] { -34, -33, -32, -18, -17, -16, -2, -1,
                                 0, 13, 14, 15, 16, 29, 30, 31, 32 }) {
            for (int z : new int[] { -18, -17, -16, -2, -1, 0, 13, 14,
                                     15, 16, 29, 30, 31, 32 }) {
                world.tree.add(entry(x, 64, z, sequence++));
            }
        }
        world.current.addAll(Arrays.asList(
            entry(-2, 80, 16, sequence++),
            entry(14, 81, 14, sequence++),
            entry(31, 82, -17, sequence++)));
        enable();
        long token = SaveTickIndexBridge.begin("provider", world, false);
        try {
            assertNull(SaveTickIndexBridge.pendingForChunk(world, -9, -9));
            for (int chunkX = -3; chunkX <= 2; chunkX++) {
                for (int chunkZ = -2; chunkZ <= 2; chunkZ++) {
                    assertSameEntries("chunk " + chunkX + ',' + chunkZ,
                        vanilla(world, chunkX, chunkZ),
                        SaveTickIndexBridge.pendingForChunk(world, chunkX,
                            chunkZ));
                }
            }
        } finally {
            SaveTickIndexBridge.end(token);
            disable();
        }
    }

    private static List<NextTickListEntry> vanilla(FakeAccessor world,
                                                    int chunkX, int chunkZ) {
        int minX = (chunkX << 4) - 2;
        int maxX = minX + 18;
        int minZ = (chunkZ << 4) - 2;
        int maxZ = minZ + 18;
        List<NextTickListEntry> result = new ArrayList<NextTickListEntry>();
        appendVanilla(result, world.tree, minX, maxX, minZ, maxZ);
        appendVanilla(result, world.current, minX, maxX, minZ, maxZ);
        return result.isEmpty() ? null : result;
    }

    private static void appendVanilla(List<NextTickListEntry> result,
                                      Iterable<NextTickListEntry> source,
                                      int minX, int maxX, int minZ, int maxZ) {
        for (NextTickListEntry entry : source) {
            int x = entry.position.getX();
            int z = entry.position.getZ();
            if (x >= minX && x < maxX && z >= minZ && z < maxZ) {
                result.add(entry);
            }
        }
    }

    private static void assertSameEntries(String message,
                                          List<NextTickListEntry> expected,
                                          List<NextTickListEntry> actual) {
        if (expected == null || actual == null) {
            assertSame(message, expected, actual);
            return;
        }
        assertEquals(message + " size", expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            assertSame(message + " entry " + index, expected.get(index),
                actual.get(index));
        }
    }

    private static NextTickListEntry entry(int x, int y, int z, long time) {
        return new NextTickListEntry(new BlockPos(x, y, z), null).setScheduledTime(time);
    }

    private static void enable() {
        OptimizerRegistry.breaker(OptimizationModule.VANILLA_SAVE_TICK_INDEX).configure(true, 3);
        OptimizerRegistry.breaker(OptimizationModule.VANILLA_SAVE_TICK_INDEX)
            .patchInstalled("test", "test");
    }

    private static void disable() {
        OptimizerRegistry.breaker(OptimizationModule.VANILLA_SAVE_TICK_INDEX).configure(false, 3);
    }

    private static final class FakeAccessor implements PendingTickAccessor {
        private final TreeSet<NextTickListEntry> tree = new TreeSet<NextTickListEntry>();
        private final List<NextTickListEntry> current = new ArrayList<NextTickListEntry>();
        private final List<NextTickListEntry> original = new ArrayList<NextTickListEntry>();
        private long version;
        private int originalCalls;
        private int treeReads;
        private int currentReads;

        @Override public Iterable<NextTickListEntry> ice$pendingTickTree() {
            treeReads++;
            return tree;
        }
        @Override public List<NextTickListEntry> ice$pendingTicksThisTick() {
            currentReads++;
            return current;
        }
        @Override public long ice$pendingTickVersion() { return version; }
        @Override public List<NextTickListEntry> ice$originalPendingBlockUpdates(Chunk chunk, boolean remove) {
            originalCalls++;
            return original;
        }
    }
}
