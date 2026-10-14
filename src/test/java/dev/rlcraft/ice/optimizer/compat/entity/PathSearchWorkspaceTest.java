package dev.rlcraft.ice.optimizer.compat.entity;

import static org.junit.Assert.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import net.minecraft.pathfinding.PathNodeType;
import org.junit.Test;

public class PathSearchWorkspaceTest {
    @Test public void scratchMatchesMapAcrossGrowthCollisionsAndSmallSearchAfterLargeSearch() {
        LongObjectScratch<Integer> scratch = new LongObjectScratch<Integer>();
        Map<Long, Integer> expected = new HashMap<Long, Integer>();
        Random random = new Random(51162L);
        for (int round = 0; round < 8; round++) {
            int count = round == 0 ? 70000 : 61;
            for (int i = 0; i < count; i++) {
                long key = i % 7 == 0 ? i : random.nextLong();
                Integer value = Integer.valueOf(i);
                scratch.put(key, value);
                expected.put(key, value);
            }
            for (Map.Entry<Long, Integer> entry : expected.entrySet())
                assertEquals(entry.getValue(), scratch.get(entry.getKey().longValue()));
            assertEquals(expected.size(), scratch.size());
            scratch.clear();
            assertEquals(0, scratch.size());
            for (Long key : expected.keySet()) assertNull(scratch.get(key.longValue()));
            expected.clear();
        }
    }

    @Test public void nestedSearchOwnsItsDataAndReleasedMemoryCanBeReusedWithoutResults() {
        Object firstOwner = new Object(), secondOwner = new Object();
        PathSearchWorkspace outer = PathSearchWorkspace.acquire(firstOwner, null);
        outer.rawTypes.put(52L, PathNodeType.WALKABLE);
        PathSearchWorkspace inner = PathSearchWorkspace.acquire(secondOwner, null);
        assertNotSame(outer, inner);
        assertNull(inner.rawTypes.get(52L));
        inner.rawTypes.put(52L, PathNodeType.BLOCKED);
        assertSame(PathNodeType.WALKABLE, outer.rawTypes.get(52L));
        inner.release();
        PathSearchWorkspace recycled = PathSearchWorkspace.acquire(secondOwner, null);
        assertSame(inner, recycled);
        assertNull(recycled.rawTypes.get(52L));
        assertTrue(outer.matches(firstOwner, null));
        assertFalse(outer.matches(secondOwner, null));
        recycled.release();
        outer.release();
        assertFalse(outer.matches(firstOwner, null));
        PathSearchWorkspace newSearch = PathSearchWorkspace.acquire(new Object(), null);
        assertSame(outer, newSearch);
        assertNull(newSearch.rawTypes.get(52L));
        newSearch.release();
    }
}
