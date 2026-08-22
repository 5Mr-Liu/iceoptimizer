package dev.rlcraft.ice.optimizer.render.visibility;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import java.nio.FloatBuffer;
import java.util.Arrays;
import org.junit.Test;

public final class ConservativeHzbBudgetTest {
    @Test
    public void mappedReadbackBecomesOneBudgetedOwnedHierarchy() {
        float[] depth = new float[35];
        Arrays.fill(depth, 0.25F);
        CacheBudget budget = new CacheBudget(1L << 20, 1L, 1L);
        ConservativeHzb hzb = ConservativeHzb.buildStandardDepth(
            FloatBuffer.wrap(depth), 7, 5, budget);
        assertEquals(ConservativeHzb.heapBytesForDimensions(7, 5),
            budget.snapshot().getHeapUsed());
        assertFalse(hzb.isClosed());

        ConservativeOcclusionHistory history = new ConservativeOcclusionHistory();
        history.publish(new HzbHistoryKey(0, 7, 5, 70.0F, 1L,
            1L, 1L, true), hzb);
        history.invalidate();
        assertEquals(0L, budget.snapshot().getHeapUsed());
    }
}
