package dev.rlcraft.ice.optimizer.render.texture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.IntBuffer;
import org.junit.Test;

public final class AnimationTextureCommandQueueTest {
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
