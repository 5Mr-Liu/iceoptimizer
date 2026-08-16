package dev.rlcraft.ice.optimizer.compat.save;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.util.ArrayList;
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
    public void rebuildsAfterTrackedMutationAndMasksNestedNonFullSaves() {
        FakeAccessor world = new FakeAccessor();
        NextTickListEntry first = entry(0, 64, 0, 10L);
        NextTickListEntry second = entry(1, 64, 1, 20L);
        world.tree.add(first);
        enable();
        long outer = SaveTickIndexBridge.begin("provider", world, true);
        try {
            assertEquals(1, SaveTickIndexBridge.pendingForChunk(world, 0, 0).size());
            world.tree.add(second);
            world.version++;
            assertEquals(2, SaveTickIndexBridge.pendingForChunk(world, 0, 0).size());

            long nested = SaveTickIndexBridge.begin("provider", world, false);
            try {
                assertNull(SaveTickIndexBridge.pendingForChunk(world, 0, 0));
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

        @Override public Iterable<NextTickListEntry> ice$pendingTickTree() { return tree; }
        @Override public List<NextTickListEntry> ice$pendingTicksThisTick() { return current; }
        @Override public long ice$pendingTickVersion() { return version; }
        @Override public List<NextTickListEntry> ice$originalPendingBlockUpdates(Chunk chunk, boolean remove) {
            originalCalls++;
            return original;
        }
    }
}
