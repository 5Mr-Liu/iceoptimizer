package dev.rlcraft.ice.optimizer.compat.save;

import static org.junit.Assert.*;
import java.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.chunk.Chunk;
import org.junit.Test;

public final class SaveTickIndexBridgeTest {
    @Test public void liveRangeViewsAndCloneKeepIndependentBuckets() {
        World world = new World();
        NextTickListEntry a=entry(0,0,1), b=entry(16,0,2), c=entry(14,14,3), d=entry(-2,-2,4);
        world.tree.addAll(Arrays.asList(a,b,c,d));
        World clone = new World((SaveTickIndexBridge.TickTree) world.tree.clone());
        NavigableSet<NextTickListEntry> view=world.tree.tailSet(b,true).headSet(d,true).descendingSet();
        assertSame(b,view.pollLast());
        Iterator<NextTickListEntry> iterator=view.iterator(); assertSame(d,iterator.next()); iterator.remove();
        NextTickListEntry e=entry(30,0,3); view.add(e);
        assertIndexed(world); assertIndexed(clone);
        view.clear(); assertIndexed(world); assertEquals(1,world.tree.size());
        assertEquals(4,clone.tree.size());
        clone.tree.headSet(c).clear(); assertIndexed(clone); assertIndexed(world);
        clone.tree.descendingSet().pollFirst(); assertIndexed(clone);
    }

    @Test public void replacingCurrentTickEntryDoesNotInvalidateExistingIterator() {
        World world = new World();
        NextTickListEntry a=entry(0,0,1), b=entry(16,0,2), replacement=entry(30,14,3);
        world.current.add(a); world.current.add(b);
        Iterator<NextTickListEntry> iterator=world.current.iterator();
        assertSame(a,iterator.next()); world.current.set(1,replacement);
        assertSame(replacement,iterator.next()); assertIndexed(world);
    }

    private static void assertIndexed(World world) {
        for(int x=-2;x<=3;x++) for(int z=-2;z<=3;z++) {
            List<NextTickListEntry> expected=new ArrayList<NextTickListEntry>();
            append(expected,world.tree,x,z); append(expected,world.current,x,z);
            assertIdentity(expected.isEmpty()?null:expected,SaveTickIndexBridge.pendingForChunk(world,x,z));
        }
    }
    @Test public void firstLookupAlreadyHasBothOrderedCollectionsAndNegativeBorders() {
        World world = new World();
        NextTickListEntry a = entry(-2, -2, 20), b = entry(0, 0, 10), c = entry(-1, -1, 1);
        world.tree.add(a); world.tree.add(b); world.current.add(c);
        assertIdentity(Arrays.asList(b, a, c), SaveTickIndexBridge.pendingForChunk(world, 0, 0));
        assertIdentity(Arrays.asList(a, c), SaveTickIndexBridge.pendingForChunk(world, -1, -1));
        assertNull(SaveTickIndexBridge.pendingForChunk(world, -2, -2));
        List<NextTickListEntry> copy = SaveTickIndexBridge.pendingForChunk(world, 0, 0);
        copy.clear(); assertEquals(3, SaveTickIndexBridge.pendingForChunk(world, 0, 0).size());
        assertEquals(0, world.originalCalls);
    }
    @Test public void randomActualMutationsAlwaysMatchOriginalFullScanAndIdentityOrder() {
        Random random = new Random(481152L); World world = new World();
        TreeSet<NextTickListEntry> tree = new TreeSet<NextTickListEntry>();
        ArrayList<NextTickListEntry> current = new ArrayList<NextTickListEntry>();
        for (int step = 0; step < 1800; step++) {
            int operation = random.nextInt(9);
            if (operation < 3) {
                NextTickListEntry value = entry(random.nextInt(128) - 64, random.nextInt(128) - 64, random.nextInt(10));
                assertEquals(tree.add(value), world.tree.add(value));
                assertFalse(world.tree.add(value));
            } else if (operation == 3 && !tree.isEmpty()) {
                NextTickListEntry value = tree.first(); tree.remove(value); world.tree.remove(value);
                int index = random.nextInt(current.size() + 1); current.add(index, value); world.current.add(index, value);
            } else if (operation == 4 && !tree.isEmpty()) {
                Iterator<NextTickListEntry> a = tree.iterator(), b = world.tree.iterator();
                int skip = random.nextInt(tree.size()); for (int i = 0; i <= skip; i++) assertSame(a.next(), b.next());
                a.remove(); b.remove();
            } else if (operation == 5 && !current.isEmpty()) {
                int index = random.nextInt(current.size()); assertSame(current.remove(index), world.current.remove(index));
            } else if (operation == 6 && !tree.isEmpty()) {
                assertSame(tree.pollLast(), world.tree.pollLast());
            } else if (operation == 7 && !current.isEmpty()) {
                NextTickListEntry value = entry(random.nextInt(128) - 64, random.nextInt(128) - 64, 1);
                int index = random.nextInt(current.size()); assertSame(current.set(index, value), world.current.set(index, value));
            } else if (operation == 8 && step % 53 == 0) {
                tree.clear(); current.clear(); world.tree.clear(); world.current.clear();
            }
            for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) {
                List<NextTickListEntry> expected = new ArrayList<NextTickListEntry>();
                append(expected, tree, x, z); append(expected, current, x, z);
                assertIdentity(expected.isEmpty() ? null : expected, SaveTickIndexBridge.pendingForChunk(world, x, z));
            }
        }
    }
    private static void append(List<NextTickListEntry> out, Iterable<NextTickListEntry> entries, int x, int z) {
        for (NextTickListEntry e : entries) if (e.position.getX() >= (x << 4) - 2 && e.position.getX() < (x << 4) + 16
            && e.position.getZ() >= (z << 4) - 2 && e.position.getZ() < (z << 4) + 16) out.add(e);
    }
    private static void assertIdentity(List<NextTickListEntry> expected, List<NextTickListEntry> actual) {
        if (expected == null) { assertNull(actual); return; }
        assertNotNull(actual); assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) assertSame(expected.get(i), actual.get(i));
    }
    private static NextTickListEntry entry(int x, int z, long time) {
        return new NextTickListEntry(new BlockPos(x, 64, z), null).setScheduledTime(time);
    }
    private static final class World implements PendingTickAccessor {
        final SaveTickIndexBridge.TickTree tree;
        World() { this(new SaveTickIndexBridge.TickTree()); }
        World(SaveTickIndexBridge.TickTree tree) { this.tree=tree; }
        final SaveTickIndexBridge.TickList current = new SaveTickIndexBridge.TickList();
        int originalCalls;
        public Iterable<NextTickListEntry> ice$pendingTickTree() { return tree; }
        public List<NextTickListEntry> ice$pendingTicksThisTick() { return current; }
        public List<NextTickListEntry> ice$originalPendingBlockUpdates(Chunk chunk, boolean remove) { originalCalls++; return null; }
    }
}
