package dev.rlcraft.ice.optimizer.client;

import static org.junit.Assert.*;

import dev.rlcraft.ice.optimizer.ClientOptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.render.backend.AdaptiveBackendController;
import dev.rlcraft.ice.optimizer.render.backend.BackendLifecycleState;
import dev.rlcraft.ice.optimizer.render.backend.CapabilityReport;
import dev.rlcraft.ice.optimizer.render.backend.ModernCapability;
import java.util.EnumMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class RendererActivationPolicyTest {
    private OptimizerConfig.Settings previous;

    @Before public void configure() {
        previous = OptimizerConfig.settings;
        OptimizerConfig.settings = new OptimizerConfig.Settings();
        OptimizerConfig.settings.terrainSoftwareOcclusion = false;
        OptimizerRegistry.beginRuntime();
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
    }

    @After public void restore() {
        OptimizerConfig.settings = previous;
        OptimizerRegistry.beginRuntime();
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
    }

    @Test public void nonModelBackendsRunWithoutTimersWhileModelsRequireMeasurement() {
        assertDirectBackends();
    }

    @Test public void modelArraysAlsoRequireEvidenceWhenPalettesAreDisabled() {
        OptimizerConfig.settings.terrainShaderBatching = false;
        OptimizerConfig.settings.modelPoseBatching = false;
        assertDirectBackends();
    }

    @Test public void missingRequiredOperationsStillExcludeAffectedBackendOnly() {
        CapabilityReport report = supportedExcept(ModernCapability.BUFFER_OBJECT);
        EnumMap<OptimizationModule, AdaptiveBackendController> backends =
            RendererComponentFactory.createBackends(report, 1L);
        assertEquals(BackendLifecycleState.QUARANTINED,
            backends.get(OptimizationModule.MODERN_TERRAIN_BACKEND).lifecycleState());
        assertEquals(BackendLifecycleState.MODERN,
            backends.get(OptimizationModule.MODERN_TEXTURE_STREAM).lifecycleState());
    }

    @Test public void disabledModuleRemainsExcluded() {
        OptimizerRegistry.breaker(OptimizationModule.MODERN_HUD_STREAM).disable("test");
        assertFalse(RendererComponentFactory.createBackends(
            supportedExcept(ModernCapability.TIMER_QUERY), 1L)
            .containsKey(OptimizationModule.MODERN_HUD_STREAM));
    }

    private void assertDirectBackends() {
        EnumMap<OptimizationModule, AdaptiveBackendController> backends =
            RendererComponentFactory.createBackends(
                supportedExcept(ModernCapability.TIMER_QUERY), 1L);
        assertTrue(backends.containsKey(OptimizationModule.MODERN_VISIBILITY_HZB));
        assertTrue(backends.containsKey(OptimizationModule.MODERN_TEXTURE_VISIBILITY));
        assertTrue(backends.containsKey(OptimizationModule.MODERN_PARTICLE_BACKEND));
        assertTrue(backends.containsKey(OptimizationModule.MODERN_HUD_STREAM));
        for (Map.Entry<OptimizationModule, AdaptiveBackendController> entry : backends.entrySet()) {
            String module = entry.getKey().getId();
            AdaptiveBackendController backend = entry.getValue();
            if (entry.getKey() == OptimizationModule.MODERN_ENTITY_BACKEND
                || entry.getKey() == OptimizationModule.MODERN_TESR_BACKEND) {
                assertFalse(module, backend.isDirectActivation());
                assertEquals(module, BackendLifecycleState.QUARANTINED, backend.lifecycleState());
                assertFalse(module, backend.isModernActive());
                continue;
            }
            assertEquals(module, BackendLifecycleState.MODERN, backend.lifecycleState());
            assertTrue(module, backend.activateAtSafeBoundary());
            for (long frame = 1L; frame <= 700L; frame++) {
                assertFalse(module, backend.advanceFrame(frame));
                assertTrue(module, backend.prepareProfiledWorkload((int) (frame % 8), frame));
                assertFalse(module, backend.shouldMeasureFrame(frame));
                assertTrue(module, backend.isModernActive());
            }
            assertEquals(module, 0L, backend.snapshot().getMeasurementSamples());
        }
    }

    @Test public void measuredModelsRejectRegressionAndKeepAProfitableCandidate() {
        for (long modernCost : new long[] {140, 60}) {
            EnumMap<OptimizationModule, AdaptiveBackendController> backends =
                RendererComponentFactory.createBackends(supportedExcept(null), 1L);
            for (OptimizationModule module : new OptimizationModule[] {
                OptimizationModule.MODERN_ENTITY_BACKEND, OptimizationModule.MODERN_TESR_BACKEND}) {
                AdaptiveBackendController backend = backends.get(module);
                assertFalse(backend.isDirectActivation());
                while (backend.lifecycleState() == BackendLifecycleState.WARMUP) backend.warmupFrame(true);
                while (backend.lifecycleState() == BackendLifecycleState.OUTPUT_VALIDATE) backend.validationResult(true, null);
                dev.rlcraft.ice.optimizer.render.backend.SceneFingerprint scene =
                    new dev.rlcraft.ice.optimizer.render.backend.SceneFingerprint(0,0,0,0,0,0,0,20,5,8,1920,1080,0,1L,1L);
                for (int sample = 0; sample < 4096 && backend.lifecycleState() == BackendLifecycleState.PAIRED_MEASURE; sample++) {
                    dev.rlcraft.ice.optimizer.render.backend.MeasurementArm arm = backend.expectedMeasurementArm();
                    backend.recordMeasurement(scene, arm,
                        arm == dev.rlcraft.ice.optimizer.render.backend.MeasurementArm.MODERN ? modernCost : 100, true);
                }
                assertEquals(modernCost > 100 ? BackendLifecycleState.LEGACY : BackendLifecycleState.MODERN,
                    backend.lifecycleState());
            }
        }
    }

    private static CapabilityReport supportedExcept(ModernCapability omitted) {
        CapabilityReport.Builder report = CapabilityReport.builder();
        for (ModernCapability capability : ModernCapability.values()) {
            if (capability != omitted) report.pass(capability);
        }
        return report.build();
    }
}
