package dev.rlcraft.ice.optimizer.render.visibility;

import org.junit.Test;
import static org.junit.Assert.*;

public final class OcclusionWorkSchedulerTest {
    private static float[] facing(float x, float y, float z) {
        float[] matrix = new float[16];
        matrix[2] = x; matrix[6] = y; matrix[10] = z;
        return matrix;
    }

    @Test public void uselessViewsBecomeSparseButRecoverWithinThirtyTwoFrames() {
        OcclusionWorkScheduler scheduler = new OcclusionWorkScheduler();
        float[] matrix = facing(0, 0, 1);
        int probes = 0, sinceProbe = 0;
        for (int frame = 0; frame < 1000; frame++) {
            sinceProbe++;
            if (scheduler.shouldRun(0, 0, 0, matrix)) {
                assertTrue(sinceProbe <= 32);
                sinceProbe = 0; probes++; scheduler.result(0);
            }
        }
        assertTrue(probes < 40);
        assertTrue(scheduler.skipped() > 960);
        for (int frame = 0; frame < 32; frame++) {
            if (scheduler.shouldRun(0, 0, 0, matrix)) {
                scheduler.result(1);
                for (int next = 0; next < 100; next++) {
                    assertTrue(scheduler.shouldRun(0, 0, 0, matrix));
                    scheduler.result(1);
                }
                return;
            }
        }
        fail("newly useful view was never probed");
    }

    @Test public void largeMoveTurnAndLifecycleResetPromptlyReprobe() {
        OcclusionWorkScheduler scheduler = new OcclusionWorkScheduler();
        float[] forward = facing(0, 0, 1), turned = facing(1, 0, 0);
        assertTrue(scheduler.shouldRun(0, 0, 0, forward)); scheduler.result(0);
        assertTrue(scheduler.shouldRun(8, 0, 0, forward)); scheduler.result(0);
        assertTrue(scheduler.shouldRun(8, 0, 0, turned)); scheduler.result(0);
        assertFalse(scheduler.shouldRun(8.01, 0, 0, turned));
        scheduler.result(0);
        scheduler.reset();
        assertTrue(scheduler.shouldRun(8.01, 0, 0, turned));
    }
}
