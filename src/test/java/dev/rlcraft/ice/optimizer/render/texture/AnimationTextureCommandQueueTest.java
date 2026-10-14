package dev.rlcraft.ice.optimizer.render.texture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.IntBuffer;
import org.junit.Test;

public final class AnimationTextureCommandQueueTest {
    @Test public void snapshotBudgetRefusalKeepsAcceptedPrefixAndAllowsSlotReuse() {
        long metadata = AnimationTextureCommandQueue.heapBytesForCapacity(16);
        long snapshot = dev.rlcraft.ice.optimizer.memory.RetainedHeap.referenceArray(1)
            + dev.rlcraft.ice.optimizer.memory.RetainedHeap.intArray(4);
        dev.rlcraft.ice.optimizer.memory.CacheBudget budget =
            new dev.rlcraft.ice.optimizer.memory.CacheBudget(metadata + snapshot, 0, 0);
        AnimationTextureCommandQueue queue = new AnimationTextureCommandQueue(16, 4096, budget);
        int[][] pixels = {{1,2,3,4}};
        assertTrue(queue.offer(pixels, 2, 2, 0, 0, false, false, true, true));
        assertFalse(queue.offer(pixels, 2, 2, 2, 0, false, false, true, true));
        assertEquals(1, queue.size());
        IntBuffer output = IntBuffer.allocate(4);
        queue.copyPixels(output);
        assertArrayEquals(new int[]{1,2,3,4}, output.array());
        queue.clear();
        assertTrue(queue.offer(pixels, 2, 2, 2, 0, false, false, true, true));
        queue.close();
        assertEquals(0, budget.snapshot().getHeapUsed());
    }

    @Test public void snapshotSurvivesInterpolationReuseAndKeepsExplicitMipFilter() {
        AnimationTextureCommandQueue queue = new AnimationTextureCommandQueue(16, 4096);
        int[][] pixels = {{1, 2, 3, 4}};
        assertTrue(queue.offer(pixels, 2, 2, 0, 0, false, false, true, true));
        pixels[0][0] = 99;
        IntBuffer output = IntBuffer.allocate(4);
        queue.copyPixels(output);
        assertArrayEquals(new int[]{1, 2, 3, 4}, output.array());
        assertTrue(queue.mipFilter(0));
        int[][] retained = queue.data(0);
        queue.clear();
        assertTrue(queue.offer(pixels, 2, 2, 0, 0, false, false, false, true));
        org.junit.Assert.assertSame(retained, queue.data(0));
        assertEquals(99, retained[0][0]);
        assertFalse(queue.mipFilter(0));
        queue.close();
    }

    @Test
    public void copiesCommandsAndMipsInOriginalOrderWithoutPixelObjects() {
        AnimationTextureCommandQueue queue =
            new AnimationTextureCommandQueue(16, 4096);
        assertTrue(queue.offer(new int[][] {
            {1, 2, 3, 4}, {5}
        }, 2, 2, 0, 0, false, false));
        assertTrue(queue.offer(new int[][] {
            {6, 7}
        }, 2, 1, 2, 0, true, true));
        assertEquals(2, queue.size());
        assertEquals(3, queue.getMipLevels());
        assertEquals(28L, queue.getBytes());
        IntBuffer output = IntBuffer.allocate(7);
        assertEquals(7, queue.copyPixels(output));
        assertArrayEquals(new int[] {1, 2, 3, 4, 5, 6, 7}, output.array());
    }

    @Test
    public void rejectsMalformedOrOverBudgetCommandsWithoutPartialInsertion() {
        AnimationTextureCommandQueue queue =
            new AnimationTextureCommandQueue(16, 4096);
        assertFalse(queue.offer(new int[][] {{1}}, 2, 2,
            0, 0, false, false));
        assertFalse(queue.offer(new int[][] {{1}}, 1, 1,
            -1, 0, false, false));
        assertFalse(queue.offer(null, 1, 1, 0, 0, false, false));
        assertEquals(0, queue.size());
        assertEquals(3L, queue.getRejected());
    }

    @Test
    public void clearDropsAllSpriteArrayReferencesAndAllowsReuse() {
        AnimationTextureCommandQueue queue =
            new AnimationTextureCommandQueue(16, 4096);
        assertTrue(queue.offer(new int[][] {{9}}, 1, 1,
            0, 0, false, false));
        queue.clear();
        assertEquals(0, queue.size());
        assertEquals(0L, queue.getBytes());
        assertTrue(queue.offer(new int[][] {{10}}, 1, 1,
            0, 0, false, false));
    }
}
