package dev.rlcraft.ice.optimizer.client;

import static org.junit.Assert.*;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.entity.LwjglModelMeshCache;
import dev.rlcraft.ice.optimizer.render.frame.FrameCoordinator;
import dev.rlcraft.ice.optimizer.render.frame.PassGraph;
import dev.rlcraft.ice.optimizer.render.legacy.GlStateMirror;
import dev.rlcraft.ice.optimizer.render.resource.RenderHandle;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class ModernRendererRuntimePauseTest {
    @Test public void pauseBeforeFirstFrameDoesNotProbeGlOrStartAFrame() {
        ClientEpochs epochs = new ClientEpochs();
        ModernRendererRuntime runtime = runtime(epochs);
        assertTrue(runtime.setRuntimePaused(true));
        runtime.beginFrame(epochs.nextFrame(), epochs.snapshot());
        assertTrue(runtime.isRuntimePaused());
        assertFalse(runtime.initialized);
        assertFalse(runtime.isFrameInfrastructureActive());
        assertNull(runtime.currentStamp);
        assertNull(runtime.terrainSoftwareOcclusion());
        assertFalse(runtime.mayUseBackendCandidate(OptimizationModule.MODERN_ENTITY_BACKEND));
        assertEquals(-1L, runtime.modelMeshAdmissionEpoch());
        assertFalse(runtime.shouldDropDeferredModelMeshes());
        runtime.endFrame();
    }

    @Test public void pauseAndResumePreservePublishedCacheOwnerAndGeneration() throws Exception {
        ClientEpochs epochs = new ClientEpochs();
        ModernRendererRuntime runtime = runtime(epochs);
        RendererComponents original = new RendererComponents();
        original.threadGuard = RenderThreadGuard.captureCurrent();
        original.resources = new ResourceLedger(original.threadGuard, runtime.budget,
            (kind, id) -> { throw new AssertionError("paused cache must not be destroyed"); }, 8);
        original.modelMeshes = new LwjglModelMeshCache(original.threadGuard,
            original.resources, runtime.budget);
        original.coordinator = new FrameCoordinator(original.threadGuard,
            PassGraph.standard(), new GlStateMirror(2), reason -> { });
        Method publish = ModernRendererRuntime.class.getDeclaredMethod("publish", RendererComponents.class);
        publish.setAccessible(true);
        publish.invoke(runtime, original);
        runtime.initialized = true;
        original.coordinator.beginFrame(epochs.nextFrame(), epochs.snapshot());
        runtime.currentStamp = original.coordinator.beginPrimaryView();
        runtime.frameInfrastructureActive = true;
        RenderHandle cached = original.resources.register(RenderResourceKind.BUFFER,
            41, 32L, runtime.currentStamp);
        long generation = runtime.rendererGenerationForTest();
        assertTrue(runtime.setRuntimePaused(true));
        assertTrue(runtime.setRuntimePaused(true));
        assertSame(original, runtime.components);
        assertFalse(original.coordinator.isFrameActive());
        assertTrue(original.resources.isLive(cached));
        assertSame(original.modelMeshes, runtime.components.modelMeshes);
        assertTrue(runtime.setRuntimePaused(false));
        assertFalse(runtime.isRuntimePaused());
        assertFalse(runtime.shutdownRequested);
        assertSame(original, runtime.components);
        assertEquals(generation, runtime.rendererGenerationForTest());
    }

    @Test public void worldAndResourceChangesStayPendingUntilResume() throws Exception {
        ClientEpochs epochs = new ClientEpochs();
        ModernRendererRuntime runtime = runtime(epochs);
        assertTrue(runtime.setRuntimePaused(true));
        runtime.invalidateWorld();
        runtime.invalidateResources();
        int pending = pending(runtime).get();
        assertNotEquals(0, pending);
        runtime.beginFrame(epochs.nextFrame(), epochs.snapshot());
        assertEquals(pending, pending(runtime).get());
        assertTrue(runtime.setRuntimePaused(false));
        assertEquals(pending, pending(runtime).get());
    }

    @Test public void contextLossIsConsumedEvenWhileRuntimeIsPaused() throws Exception {
        ClientEpochs epochs = new ClientEpochs();
        ModernRendererRuntime runtime = runtime(epochs);
        assertTrue(runtime.setRuntimePaused(true));
        runtime.invalidateContext(1L);
        runtime.beginFrame(epochs.nextFrame(), epochs.snapshot());
        assertEquals(0, pending(runtime).get());
        assertTrue(runtime.isRuntimePaused());
        assertFalse(runtime.initialized);
        assertNull(runtime.currentStamp);
    }

    @Test public void freshGraphAlreadySatisfiesPendingResumeStateInitialization() throws Exception {
        ModernRendererRuntime runtime = runtime(new ClientEpochs());
        Field resume = ModernRendererRuntime.class.getDeclaredField("resumeTrackedState");
        resume.setAccessible(true);
        resume.setBoolean(runtime, true);
        Method publish = ModernRendererRuntime.class.getDeclaredMethod("publish", RendererComponents.class);
        publish.setAccessible(true);
        publish.invoke(runtime, new RendererComponents());
        assertFalse("a fully initialized replacement graph must not be probed twice", resume.getBoolean(runtime));
    }

    private static ModernRendererRuntime runtime(ClientEpochs epochs) {
        return new ModernRendererRuntime(epochs, new CacheBudget(1L << 20, 1L << 20, 1L << 20));
    }

    private static AtomicInteger pending(ModernRendererRuntime runtime) throws Exception {
        Field field = ModernRendererRuntime.class.getDeclaredField("pendingInvalidations");
        field.setAccessible(true);
        return (AtomicInteger) field.get(runtime);
    }
}
