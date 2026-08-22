package dev.rlcraft.ice.optimizer.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.memory.CacheBudgetStatus;
import java.nio.FloatBuffer;
import org.junit.Test;

public class GlStateQueryWorkspaceTest {
    @Test
    public void reservesReusesAndReleasesBoundedStorage() {
        CacheBudget budget = new CacheBudget(1024L, 1024L, 1L);
        GlStateQueryWorkspace workspace = new GlStateQueryWorkspace(budget);
        CacheBudgetStatus allocated = budget.snapshot();
        assertEquals(GlStateQueryWorkspace.HEAP_BYTES, allocated.getHeapUsed());
        assertEquals(GlStateQueryWorkspace.DIRECT_BYTES,
            allocated.getDirectUsed());

        FloatBuffer first = workspace.depthRange();
        FloatBuffer second = workspace.depthRange();
        assertSame(first, second);
        assertNotSame(first, workspace.currentColor());
        assertEquals(GlStateQueryWorkspace.QUERY_ELEMENTS, first.capacity());
        assertEquals(GlStateQueryWorkspace.QUERY_ELEMENTS,
            workspace.currentColor().capacity());
        assertEquals(GlStateQueryWorkspace.QUERY_ELEMENTS,
            workspace.colorMask().capacity());
        assertEquals(GlStateQueryWorkspace.QUERY_ELEMENTS,
            workspace.viewport().capacity());
        assertEquals(GlStateQueryWorkspace.QUERY_ELEMENTS,
            workspace.scissorBox().capacity());
        assertEquals(16, workspace.modelView().capacity());
        assertEquals(32, workspace.textures2d().length);
        assertEquals(32, workspace.texture2dEnabled().length);

        workspace.close();
        workspace.close();
        assertTrue(workspace.isClosed());
        CacheBudgetStatus released = budget.snapshot();
        assertEquals(0L, released.getHeapUsed());
        assertEquals(0L, released.getDirectUsed());
    }

    @Test
    public void partialBudgetFailureDoesNotLeakReservation() {
        CacheBudget budget = new CacheBudget(1L, 1024L, 1L);
        boolean failed = false;
        try {
            new GlStateQueryWorkspace(budget);
        } catch (IllegalStateException expected) {
            failed = true;
        }
        assertTrue(failed);
        assertEquals(0L, budget.snapshot().getDirectUsed());
        assertFalse(budget.snapshot().getRejectedReservations() == 0L);
    }
}
