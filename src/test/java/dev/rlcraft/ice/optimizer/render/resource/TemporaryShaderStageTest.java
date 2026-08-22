package dev.rlcraft.ice.optimizer.render.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class TemporaryShaderStageTest {
    private static final long CHARGE = ResourceLedger.nativeObjectCharge(
        RenderResourceKind.SHADER);

    @Test
    public void successfulDetachedDeletionReleasesTokenOnce() {
        CacheBudget budget = budget(CHARGE);
        AtomicInteger deletes = new AtomicInteger();
        TemporaryShaderStage stage = TemporaryShaderStage.reserve(budget,
            id -> deletes.incrementAndGet());
        assertNotNull(stage);
        assertEquals(7, stage.create(() -> 7));
        stage.markAttached();
        stage.markDetached();
        stage.close();
        stage.close();
        assertEquals(1, deletes.get());
        assertEquals(0L, budget.snapshot().getGpuUsed());
        assertFalse(stage.isReservationPoisoned());
    }

    @Test
    public void attachedOrThrowingDeletionPoisonsBoundedToken() {
        CacheBudget attachedBudget = budget(CHARGE);
        TemporaryShaderStage attached = TemporaryShaderStage.reserve(
            attachedBudget, id -> { });
        attached.create(() -> 9);
        attached.markAttached();
        attached.close();
        assertTrue(attached.isReservationPoisoned());
        assertEquals(CHARGE, attachedBudget.snapshot().getGpuUsed());

        CacheBudget failedBudget = budget(CHARGE);
        TemporaryShaderStage failed = TemporaryShaderStage.reserve(failedBudget,
            id -> { throw new IllegalStateException("delete"); });
        failed.create(() -> 11);
        boolean threw = false;
        try { failed.close(); }
        catch (IllegalStateException expected) { threw = true; }
        assertTrue(threw);
        assertTrue(failed.isReservationPoisoned());
        assertEquals(CHARGE, failedBudget.snapshot().getGpuUsed());
    }

    @Test
    public void throwingAllocatorPoisonsAndIsNeverRetried() {
        CacheBudget budget = budget(CHARGE);
        AtomicInteger calls = new AtomicInteger();
        TemporaryShaderStage stage = TemporaryShaderStage.reserve(budget,
            id -> { throw new AssertionError("must not delete unknown name"); });
        try {
            stage.create(() -> {
                calls.incrementAndGet();
                throw new IllegalStateException("create");
            });
        } catch (IllegalStateException expected) {
            assertEquals("create", expected.getMessage());
        }
        stage.close();
        assertEquals(1, calls.get());
        assertTrue(stage.isReservationPoisoned());
        assertEquals(CHARGE, budget.snapshot().getGpuUsed());
    }

    private static CacheBudget budget(long gpu) {
        return new CacheBudget(1L, 1L, gpu);
    }
}
