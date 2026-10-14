package dev.rlcraft.ice.optimizer.compat.gl;

import java.nio.FloatBuffer;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public final class EarlyModelBatchBarrierTest {
    @Test public void onlyUncapturedMatrixChangesFlushAndPoseCopiesRemainStable() {
        FloatBuffer identity = FloatBuffer.wrap(new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1});
        EarlyMatrixStateTracker.seed(5888, identity, identity);
        final int[] flushes = {0};
        EarlyModelBatchBarrier.enter(new Runnable() { public void run() { flushes[0]++; } },
            new Runnable() { public void run() { } });
        try {
            EarlyModelBatchBarrier.matrixBarrier();
            assertEquals(0, flushes[0]);
            EarlyMatrixStateTracker.translate(2,3,4);
            float[] poses = new float[32]; Arrays.fill(poses, -8);
            assertTrue(EarlyMatrixStateTracker.copyModelView(poses, 16));
            assertEquals(2F, poses[28], 0F); assertEquals(-8F, poses[0], 0F);
            EarlyMatrixStateTracker.translate(10,0,0);
            assertEquals(2F, poses[28], 0F);
            EarlyMatrixStateTracker.matrixMode(5889);
            EarlyModelBatchBarrier.matrixBarrier();
            EarlyModelBatchBarrier.barrier();
            assertEquals(2, flushes[0]);
            EarlyMatrixStateTracker.invalidate();
            assertEquals(3, flushes[0]);
            assertFalse(EarlyMatrixStateTracker.copyModelView(poses, 0));
            assertEquals(-8F, poses[0], 0F);
        } finally { EarlyModelBatchBarrier.leave(); }
        EarlyModelBatchBarrier.barrier(); assertEquals(3, flushes[0]);
    }
}
