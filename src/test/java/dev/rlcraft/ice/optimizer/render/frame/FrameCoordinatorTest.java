package dev.rlcraft.ice.optimizer.render.frame;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.rlcraft.ice.optimizer.render.backend.RenderBackendId;
import dev.rlcraft.ice.optimizer.render.legacy.GlStateMirror;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class FrameCoordinatorTest {
    @Test
    public void preservesPassOrderAndSupportsNestedPortalViewsAtBarriers() {
        AtomicInteger flushes = new AtomicInteger();
        GlStateMirror mirror = new GlStateMirror(4);
        FrameCoordinator coordinator = new FrameCoordinator(
            RenderThreadGuard.captureCurrent(), PassGraph.standard(), mirror,
            new FrameCoordinator.BatchBarrier() {
                @Override public void flush(String reason) { flushes.incrementAndGet(); }
            });
        ClientEpochs epochs = new ClientEpochs();
        long frame = epochs.nextFrame();
        coordinator.beginFrame(frame, epochs.snapshot());
        FrameStamp primary = coordinator.beginPrimaryView();
        coordinator.skipPass(RenderPass.SHADOW_TERRAIN);
        coordinator.skipPass(RenderPass.SHADOW_ENTITY);
        coordinator.skipPass(RenderPass.SHADOW_TESR);
        coordinator.beginPass(RenderPass.SKY, RenderBackendId.LEGACY);
        coordinator.endPass(RenderPass.SKY);
        coordinator.beginPass(RenderPass.MAIN_SOLID, RenderBackendId.ICE_NATIVE);

        FrameStamp portal = coordinator.beginPortalView();
        assertEquals(primary.getFrameId(), portal.getFrameId());
        assertTrue(portal.getViewId() > primary.getViewId());
        coordinator.beginPass(RenderPass.SKY, RenderBackendId.LEGACY);
        coordinator.endPass(RenderPass.SKY);
        coordinator.endPortalView();

        coordinator.endPass(RenderPass.MAIN_SOLID);
        coordinator.endPrimaryView();
        coordinator.endFrame();
        assertFalse(coordinator.snapshot().isFrameActive());
        assertTrue(flushes.get() >= 6);
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsObservablePassReordering() {
        GlStateMirror mirror = new GlStateMirror(2);
        FrameCoordinator coordinator = new FrameCoordinator(
            RenderThreadGuard.captureCurrent(), PassGraph.standard(), mirror,
            new FrameCoordinator.BatchBarrier() {
                @Override public void flush(String reason) { }
            });
        ClientEpochs epochs = new ClientEpochs();
        coordinator.beginFrame(epochs.nextFrame(), epochs.snapshot());
        coordinator.beginPrimaryView();
        coordinator.skipPass(RenderPass.MAIN_SOLID);
        coordinator.beginPass(RenderPass.SKY, RenderBackendId.LEGACY);
    }

    @Test
    public void observedPassesPreserveRealOrderAndDegradeLegalRecursion() {
        GlStateMirror mirror = new GlStateMirror(2);
        FrameCoordinator coordinator = new FrameCoordinator(
            RenderThreadGuard.captureCurrent(), PassGraph.standard(), mirror,
            new FrameCoordinator.BatchBarrier() {
                @Override public void flush(String reason) { }
            });
        ClientEpochs epochs = new ClientEpochs();
        coordinator.beginFrame(epochs.nextFrame(), epochs.snapshot());
        coordinator.beginPrimaryView();

        long lit = coordinator.beginObservedPass(RenderPass.LIT_PARTICLES,
            RenderBackendId.LEGACY);
        assertTrue(coordinator.endObservedPass(lit));
        long particles = coordinator.beginObservedPass(RenderPass.PARTICLES,
            RenderBackendId.ICE_NATIVE);
        long nested = coordinator.beginObservedPass(RenderPass.PARTICLES,
            RenderBackendId.ICE_NATIVE);
        assertEquals(RenderBackendId.LEGACY,
            coordinator.snapshot().getBackend());
        assertTrue(coordinator.endObservedPass(nested));
        assertTrue(coordinator.endObservedPass(particles));
        long translucent = coordinator.beginObservedPass(RenderPass.TRANSLUCENT,
            RenderBackendId.ICE_NATIVE);
        assertTrue(coordinator.endObservedPass(translucent));
        long passOne = coordinator.beginObservedPass(RenderPass.ENTITY_PASS_1,
            RenderBackendId.LEGACY);
        assertTrue(coordinator.endObservedPass(passOne));
        assertTrue(coordinator.snapshot().getObservedDeviations() >= 1L);

        coordinator.endPrimaryView();
        coordinator.endFrame();
    }

    @Test
    public void standardGraphPlacesParticlesAndWeatherBeforeTranslucency() {
        PassGraph graph = PassGraph.standard();
        assertTrue(graph.indexOf(RenderPass.LIT_PARTICLES)
            < graph.indexOf(RenderPass.PARTICLES));
        assertTrue(graph.indexOf(RenderPass.PARTICLES)
            < graph.indexOf(RenderPass.WEATHER));
        assertTrue(graph.indexOf(RenderPass.WEATHER)
            < graph.indexOf(RenderPass.TRANSLUCENT));
        assertTrue(graph.indexOf(RenderPass.TRANSLUCENT)
            < graph.indexOf(RenderPass.ENTITY_PASS_1));
        assertTrue(graph.indexOf(RenderPass.DEFERRED)
            < graph.indexOf(RenderPass.HAND));
        assertTrue(graph.indexOf(RenderPass.HAND)
            < graph.indexOf(RenderPass.COMPOSITE));
    }

    @Test
    public void consecutiveRunsOfTheSameObservedPassRemainOrdered() {
        GlStateMirror mirror = new GlStateMirror(2);
        FrameCoordinator coordinator = new FrameCoordinator(
            RenderThreadGuard.captureCurrent(), PassGraph.standard(), mirror,
            new FrameCoordinator.BatchBarrier() {
                @Override public void flush(String reason) { }
            });
        ClientEpochs epochs = new ClientEpochs();
        coordinator.beginFrame(epochs.nextFrame(), epochs.snapshot());
        coordinator.beginPrimaryView();
        long solid = coordinator.beginObservedPass(RenderPass.SHADOW_TERRAIN,
            RenderBackendId.LEGACY);
        assertTrue(coordinator.endObservedPass(solid));
        long cutout = coordinator.beginObservedPass(RenderPass.SHADOW_TERRAIN,
            RenderBackendId.LEGACY);
        assertTrue(coordinator.endObservedPass(cutout));
        assertEquals(0L, coordinator.snapshot().getObservedDeviations());
        coordinator.endPrimaryView();
        coordinator.endFrame();
    }

    @Test
    public void shaderStagesAreOptionalBeforeHud() {
        GlStateMirror mirror = new GlStateMirror(2);
        FrameCoordinator coordinator = new FrameCoordinator(
            RenderThreadGuard.captureCurrent(), PassGraph.standard(), mirror,
            new FrameCoordinator.BatchBarrier() {
                @Override public void flush(String reason) { }
            });
        ClientEpochs epochs = new ClientEpochs();
        coordinator.beginFrame(epochs.nextFrame(), epochs.snapshot());
        coordinator.beginPrimaryView();
        long hud = coordinator.beginObservedPass(RenderPass.HUD_GUI,
            RenderBackendId.ICE_NATIVE);
        assertEquals(RenderBackendId.ICE_NATIVE,
            coordinator.snapshot().getBackend());
        assertTrue(coordinator.endObservedPass(hud));
        assertEquals(0L, coordinator.snapshot().getObservedDeviations());
        coordinator.endPrimaryView();
        coordinator.endFrame();
    }

    @Test
    public void recoveryAttachesFlushFailureWithoutMaskingOriginalError() {
        FrameCoordinator coordinator = new FrameCoordinator(
            RenderThreadGuard.captureCurrent(), PassGraph.standard(),
            new GlStateMirror(2), new FrameCoordinator.BatchBarrier() {
                @Override public void flush(String reason) {
                    throw new IllegalStateException("flush injected");
                }
            });
        IllegalStateException original = new IllegalStateException("original");
        coordinator.resetAfterFailure(original);
        assertEquals(1, original.getSuppressed().length);
        assertEquals("flush injected", original.getSuppressed()[0].getMessage());
        assertFalse(coordinator.snapshot().isFrameActive());
    }

    @Test
    public void recoveryPropagatesWrappedFatalAfterResettingFrameState() {
        final OutOfMemoryError fatal = new OutOfMemoryError(
            "injected coordinator fatal");
        FrameCoordinator coordinator = new FrameCoordinator(
            RenderThreadGuard.captureCurrent(), PassGraph.standard(),
            new GlStateMirror(2), new FrameCoordinator.BatchBarrier() {
                @Override public void flush(String reason) {
                    throw new IllegalStateException("wrapped flush fatal", fatal);
                }
            });
        ClientEpochs epochs = new ClientEpochs();
        coordinator.beginFrame(epochs.nextFrame(), epochs.snapshot());
        coordinator.beginPrimaryView();
        try {
            coordinator.resetAfterFailure(new IllegalStateException("original"));
            fail("wrapped fatal recovery failure must escape");
        } catch (OutOfMemoryError expected) {
            assertSame(fatal, expected);
        }
        assertFalse(coordinator.snapshot().isFrameActive());
    }

    @Test
    public void mismatchRecoveryClearsScopeBeforeReportingBarrierFailure() {
        AtomicInteger flushes = new AtomicInteger();
        FrameCoordinator coordinator = new FrameCoordinator(
            RenderThreadGuard.captureCurrent(), PassGraph.standard(),
            new GlStateMirror(2), new FrameCoordinator.BatchBarrier() {
                @Override public void flush(String reason) {
                    if (flushes.getAndIncrement() == 0) {
                        throw new IllegalStateException("flush injected");
                    }
                }
            });
        ClientEpochs epochs = new ClientEpochs();
        coordinator.beginFrame(epochs.nextFrame(), epochs.snapshot());
        coordinator.beginPrimaryView();
        long token = coordinator.beginObservedPass(RenderPass.MAIN_SOLID,
            RenderBackendId.ICE_NATIVE);
        try {
            coordinator.endObservedPass(token + 1L);
            throw new AssertionError("expected recovery barrier failure");
        } catch (IllegalStateException expected) {
            assertEquals("flush injected", expected.getMessage());
        }
        assertEquals(RenderBackendId.LEGACY,
            coordinator.snapshot().getBackend());
        coordinator.endPrimaryView();
        coordinator.endFrame();
    }
}
