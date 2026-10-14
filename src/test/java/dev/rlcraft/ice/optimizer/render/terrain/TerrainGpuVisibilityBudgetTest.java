package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TerrainGpuVisibilityBudgetTest {
    @Test public void incompleteWorkspaceReservationDoesNotConsumeOtherCachesBudget() {
        CacheBudget budget = new CacheBudget(1024, 1024, 512L * 1024L);
        LwjglTerrainGpuVisibility renderer = new LwjglTerrainGpuVisibility(ledger(budget), budget);
        assertEquals(0L, budget.snapshot().getGpuUsed());
        renderer.close(false);
        assertEquals(0L, budget.snapshot().getGpuUsed());
    }

    @Test public void worldResetKeepsUncreatedWorkspaceReadyForFirstUpload() {
        CacheBudget budget = new CacheBudget(1024, 1024, 1024L * 1024L);
        LwjglTerrainGpuVisibility renderer = new LwjglTerrainGpuVisibility(ledger(budget), budget);
        long prepaid = budget.snapshot().getGpuUsed();
        assertTrue(prepaid > 0L);
        renderer.reset(false);
        assertEquals(prepaid, budget.snapshot().getGpuUsed());
        renderer.close(false);
        assertEquals(0L, budget.snapshot().getGpuUsed());
        renderer.close(false);
        assertEquals(0L, budget.snapshot().getGpuUsed());
    }

    private static ResourceLedger ledger(CacheBudget budget) {
        return new ResourceLedger(RenderThreadGuard.captureCurrent(), budget,
            (kind, id) -> { throw new AssertionError("constructor/reset must not allocate GL objects"); }, 16);
    }
}
