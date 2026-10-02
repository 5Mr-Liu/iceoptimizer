package dev.rlcraft.ice.optimizer.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.ModuleCircuitBreaker;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.backend.AdaptiveBackendController;
import dev.rlcraft.ice.optimizer.render.backend.BackendLifecycleState;
import dev.rlcraft.ice.optimizer.render.backend.CapabilityReport;
import dev.rlcraft.ice.optimizer.render.backend.MeasurementArm;
import dev.rlcraft.ice.optimizer.render.backend.ModernCapability;
import dev.rlcraft.ice.optimizer.render.backend.SceneFingerprint;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.frame.RenderPass;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.lang.reflect.Field;
import java.util.EnumSet;
import org.junit.Test;

public final class ModernRendererSamplingTest {
    @Test
    public void unsampledFrameStillProvidesModernDrawPermissionWithoutProfiler() throws Exception {
        OptimizationModule module = OptimizationModule.MODERN_HUD_STREAM;
        ModuleCircuitBreaker breaker = new ModuleCircuitBreaker(module);
        breaker.configure(true, 3);
        AdaptiveBackendController backend = new AdaptiveBackendController(breaker,
            EnumSet.noneOf(ModernCapability.class), 1, 1, 1, 0.05D, 0.02D);
        backend.begin(1L);
        backend.capabilityResult(CapabilityReport.builder().build());
        backend.warmupFrame(true);
        backend.validationResult(true, null);
        SceneFingerprint scene = new SceneFingerprint(0, 0, 0, 0, 0, 0,
            100, 20, 5, 12, 1920, 1080, 0, 1L, 1L);
        for (int i = 0; i < 4; i++) {
            MeasurementArm arm = backend.expectedMeasurementArm();
            backend.recordMeasurement(scene, arm,
                arm == MeasurementArm.LEGACY ? 100L : 80L, true);
        }
        assertTrue(backend.activateAtSafeBoundary());
        ClientEpochs epochs = new ClientEpochs();
        ModernRendererRuntime runtime = new ModernRendererRuntime(epochs,
            new CacheBudget(1L, 1L, 1L));
        field("initialized").setBoolean(runtime, true);
        runtime.components.threadGuard = RenderThreadGuard.captureCurrent();
        runtime.components.backends.put(module, backend);
        // No Minecraft world, GL context or profiler is installed. These
        // frames must execute the modern path without trying to measure it.
        for (long frame = 1L; frame < 16L; frame++) {
            field("currentStamp").set(runtime, new FrameStamp(frame, 1L, epochs.snapshot()));
            ModernRendererRuntime.RenderBackendSample sample =
                runtime.beginRenderBackendSample(module, RenderPass.HUD_GUI);
            assertNotNull(sample);
            assertTrue(sample.usesModernArm());
            runtime.endRenderBackendSample(sample, true, true);
        }
        assertEquals(BackendLifecycleState.REGRESSION_MONITOR, backend.lifecycleState());
    }

    private static Field field(String name) throws Exception {
        Field field = ModernRendererRuntime.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
