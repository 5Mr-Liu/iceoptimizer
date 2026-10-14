package dev.rlcraft.ice.optimizer.render.terrain;

import net.minecraft.client.renderer.chunk.CompiledChunk;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativeTerrainDrawQueueTest {
    @Test public void queuesKeepLayerOrderAndRebuildAfterReplacementOrCancellation() {
        NativeTerrainDrawQueue queue = new NativeTerrainDrawQueue();
        NativeTerrainRenderer.Section a = section(1, 3), b = section(0, 1, 3);
        queue.add(a); queue.add(b);
        assertArrayEquals(new int[]{1, 2, 0, 2}, queue.counts);
        assertSame(a.layers[1], queue.layers[1][0]); assertSame(b.layers[1], queue.layers[1][1]);
        assertSame(a.layers[3], queue.layers[3][0]); assertSame(b.layers[3], queue.layers[3][1]);
        NativeTerrainRenderer.Mesh replacement = NativeTerrainSectionTest.mesh(a, 1);
        a.put(replacement); b.publish(CompiledChunk.DUMMY);
        queue.clear(); queue.add(a); queue.add(b);
        assertArrayEquals(new int[]{0, 1, 0, 1}, queue.counts);
        assertSame(replacement, queue.layers[1][0]);
        assertNull(queue.layers[0][0]); assertNull(queue.layers[1][1]); assertNull(queue.layers[3][1]);
    }

    @Test public void queuesGrowWithoutDroppingEntries() {
        NativeTerrainDrawQueue queue = new NativeTerrainDrawQueue();
        for (int i = 0; i < 600; i++) queue.add(section(0, 3));
        assertEquals(600, queue.counts[0]); assertEquals(600, queue.counts[3]);
        for (int i = 0; i < 600; i++) assertSame(queue.layers[0][i].section, queue.layers[3][i].section);
    }

    private static NativeTerrainRenderer.Section section(int... layers) {
        NativeTerrainRenderer.Section section = new NativeTerrainRenderer.Section(null);
        for (int layer : layers) { section.put(NativeTerrainSectionTest.mesh(section, layer)); section.activeMask |= 1 << layer; }
        return section;
    }
}
