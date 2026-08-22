package dev.rlcraft.ice.optimizer.render.visibility;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.backend.CapabilityReport;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import java.lang.reflect.Field;
import org.junit.Test;

public final class LwjglDepthHistoryInvalidationTest {
    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Test
    public void unknownGlMirrorDefersWithoutConsumingCompletedPayload()
        throws Exception {
        CacheBudget budget = new CacheBudget(1024L, 1024L, 1024L);
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger ledger = new ResourceLedger(guard, budget,
            new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind,
                                              int nativeId) { }
            }, 4);
        LwjglDepthHistory history = new LwjglDepthHistory(guard, ledger,
            CapabilityReport.builder().build(),
            new ConservativeOcclusionHistory(), budget);
        final int[] fencePolls = new int[1];
        try {
            Field field = LwjglDepthHistory.class.getDeclaredField("readbacks");
            field.setAccessible(true);
            DelayedDepthReadbackRing ring =
                (DelayedDepthReadbackRing) field.get(history);
            int slot = ring.tryAcquire();
            assertTrue(ring.submit(slot, new Object(),
                new DelayedDepthReadbackRing.Fence() {
                    @Override public boolean isSignaled() {
                        fencePolls[0]++;
                        return true;
                    }
                    @Override public void destroy(boolean contextValid) { }
                }));
            EarlyGlStateTracker.invalidate();
            assertEquals(0, history.poll());
            assertEquals(0, fencePolls[0]);
            assertTrue(history.hasPendingReadback());
            assertEquals(1L, history.getDeferredPolls());
        } finally {
            history.close(false);
            EarlyGlStateTracker.invalidate();
        }
    }

    @Test
    public void coalescesSameFrameChangesUntilACaptureBoundary() throws Exception {
        CacheBudget budget = new CacheBudget(1024L, 1024L, 1024L);
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger ledger = new ResourceLedger(guard, budget,
            new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind,
                                              int nativeId) { }
            }, 4);
        LwjglDepthHistory history = new LwjglDepthHistory(guard, ledger,
            CapabilityReport.builder().build(),
            new ConservativeOcclusionHistory(), budget);
        try {
            history.geometryChanged(10L);
            history.geometryChanged(10L);
            assertEquals(2L, history.getGeometryChanges());
            assertEquals(1L, history.getSceneInvalidations());
            assertEquals(1L, history.getCoalescedGeometryChanges());

            // Simulate a successful capture submitted after the first change.
            // A subsequent change in that same frame must not be coalesced.
            Field captured = LwjglDepthHistory.class.getDeclaredField(
                "lastCaptureFrame");
            captured.setAccessible(true);
            captured.setLong(history, 10L);
            history.geometryChanged(10L);
            assertEquals(2L, history.getSceneInvalidations());
            assertEquals(1L, history.getCoalescedGeometryChanges());
        } finally {
            history.close(false);
        }
    }

    @Test
    public void geometryChurnPreservesViewCadenceButFullInvalidationResetsIt()
        throws Exception {
        CacheBudget budget = new CacheBudget(1024L, 1024L, 1024L);
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger ledger = new ResourceLedger(guard, budget,
            new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind,
                                              int nativeId) { }
            }, 4);
        LwjglDepthHistory history = new LwjglDepthHistory(guard, ledger,
            CapabilityReport.builder().build(),
            new ConservativeOcclusionHistory(), budget);
        try {
            Field field = LwjglDepthHistory.class.getDeclaredField(
                "captureGate");
            field.setAccessible(true);
            StableViewCaptureGate gate = (StableViewCaptureGate) field.get(
                history);
            assertEquals(StableViewCaptureGate.Decision.FIRST_OBSERVATION,
                gate.observe(1L, 99L));

            history.geometryChanged(1L);
            assertEquals(StableViewCaptureGate.Decision.CAPTURE_ALLOWED,
                gate.observe(2L, 99L));

            history.invalidateScene();
            assertEquals(StableViewCaptureGate.Decision.FIRST_OBSERVATION,
                gate.observe(3L, 99L));
        } finally {
            history.close(false);
        }
    }

    @Test
    public void validatedPublicationLedgerSurvivesGeometryAndDrainsOnce()
        throws Exception {
        CacheBudget budget = new CacheBudget(1024L, 1024L, 1024L);
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger ledger = new ResourceLedger(guard, budget,
            new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind,
                                              int nativeId) { }
            }, 4);
        LwjglDepthHistory history = new LwjglDepthHistory(guard, ledger,
            CapabilityReport.builder().build(),
            new ConservativeOcclusionHistory(), budget);
        try {
            history.noteOracleValidatedPublication();
            history.geometryChanged(4L);
            assertEquals(1L, history.getOracleValidatedPublications());
            assertTrue(history.consumeOracleValidatedPublication());
            assertFalse(history.consumeOracleValidatedPublication());

            history.noteOracleValidatedPublication();
            history.reset(false);
            assertEquals("lifetime diagnostics remain cumulative", 2L,
                history.getOracleValidatedPublications());
            assertFalse("reset must not certify a later graph",
                history.consumeOracleValidatedPublication());
        } finally {
            history.close(false);
        }
    }
}
