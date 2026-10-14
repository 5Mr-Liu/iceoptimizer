package dev.rlcraft.ice.optimizer.render.terrain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;

public class NativeTerrainAllocatorTest {
    @Test public void joinsAdjacentHolesAndTrimsTheTail() {
        NativeTerrainAllocator arena = new NativeTerrainAllocator();
        NativeTerrainAllocator.Range a = arena.allocate(16), b = arena.allocate(32);
        NativeTerrainAllocator.Range c = arena.allocate(64), d = arena.allocate(8);
        arena.free(a); arena.free(c); arena.free(b);
        NativeTerrainAllocator.Range merged = arena.allocate(112);
        assertEquals(0, merged.first);
        arena.free(d); arena.free(merged);
        assertEquals(0, arena.allocate(120).first);
    }

    @Test public void chunkReplacementChurnNeverOverlapsLiveGeometry() {
        NativeTerrainAllocator arena = new NativeTerrainAllocator();
        List<NativeTerrainAllocator.Range> live = new ArrayList<NativeTerrainAllocator.Range>();
        Random random = new Random(130);
        for (int step = 0; step < 10000; step++) {
            if (!live.isEmpty() && (live.size() >= 150 || random.nextBoolean())) {
                arena.free(live.remove(random.nextInt(live.size())));
            } else {
                NativeTerrainAllocator.Range range = arena.allocate((1 + random.nextInt(8192)) * 4);
                for (NativeTerrainAllocator.Range other : live)
                    assertTrue(range.first + range.count <= other.first || other.first + other.count <= range.first);
                live.add(range);
            }
        }
        for (NativeTerrainAllocator.Range range : live) arena.free(range);
        assertEquals(0, arena.allocate(4).first);
    }

    @Test(expected = ArithmeticException.class) public void vertexAddressOverflowIsAnError() {
        NativeTerrainAllocator arena = new NativeTerrainAllocator();
        arena.allocate(Integer.MAX_VALUE - 3);
        arena.allocate(4);
    }
}
