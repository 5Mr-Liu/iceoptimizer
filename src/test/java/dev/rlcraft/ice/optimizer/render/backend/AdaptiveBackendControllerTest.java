package dev.rlcraft.ice.optimizer.render.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.ModuleCircuitBreaker;
import dev.rlcraft.ice.optimizer.ModuleState;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import java.util.EnumSet;
import org.junit.Test;

public final class AdaptiveBackendControllerTest {
    @Test
    public void activatesOnlyAfterSelfTestValidationAndBeneficialAbbaWindow() {
        ModuleCircuitBreaker breaker = configuredBreaker();
        AdaptiveBackendController controller = new AdaptiveBackendController(breaker,
            EnumSet.of(ModernCapability.BUFFER_OBJECT), 2, 2, 2, 0.05D, 0.02D);
        controller.begin(1L);
        controller.capabilityResult(CapabilityReport.builder()
            .pass(ModernCapability.BUFFER_OBJECT).build());
        controller.warmupFrame(true);
        controller.warmupFrame(true);
        controller.validationResult(true, null);
        controller.validationResult(true, null);
        SceneFingerprint scene = scene();
        for (int i = 0; i < 8; i++) {
            MeasurementArm arm = controller.expectedMeasurementArm();
            controller.recordMeasurement(scene, arm,
                arm == MeasurementArm.LEGACY ? 100L : 80L, true);
        }
        assertEquals(BackendLifecycleState.MODERN, controller.snapshot().getState());
        assertFalse(controller.isModernActive());
        assertTrue(controller.activateAtSafeBoundary());
        assertTrue(controller.isModernActive());
        assertEquals(BackendLifecycleState.REGRESSION_MONITOR,
            controller.snapshot().getState());
    }

    @Test
    public void outputMismatchQuarantinesOnlyItsOwnModule() {
        ModuleCircuitBreaker breaker = configuredBreaker();
        AdaptiveBackendController controller = new AdaptiveBackendController(breaker,
            EnumSet.noneOf(ModernCapability.class), 1, 1, 1, 0.05D, 0.02D);
        controller.begin(2L);
        controller.capabilityResult(CapabilityReport.builder().build());
        controller.warmupFrame(true);
        controller.validationResult(false, "hash mismatch");
        assertEquals(BackendLifecycleState.QUARANTINED, controller.snapshot().getState());
        assertEquals(ModuleState.INCOMPATIBLE, breaker.snapshot().getState());
        assertFalse(controller.isModernActive());
    }

    @Test
    public void rollingSameSceneGpuRegressionFallsBackOnlyThatBackend() {
        ModuleCircuitBreaker breaker = configuredBreaker();
        AdaptiveBackendController controller = new AdaptiveBackendController(breaker,
            EnumSet.noneOf(ModernCapability.class), 1, 1, 1, 0.05D, 0.02D);
        controller.begin(3L);
        controller.capabilityResult(CapabilityReport.builder().build());
        controller.warmupFrame(true);
        controller.validationResult(true, null);
        SceneFingerprint scene = scene();
        for (int i = 0; i < 4; i++) {
            MeasurementArm arm = controller.expectedMeasurementArm();
            controller.recordMeasurement(scene, arm,
                arm == MeasurementArm.LEGACY ? 100L : 80L, true);
        }
        assertTrue(controller.activateAtSafeBoundary());
        for (int i = 0; i < 16; i++) {
            controller.recordRegressionSample(scene, 110L, true);
        }
        assertEquals(BackendLifecycleState.LEGACY, controller.lifecycleState());
        assertFalse(controller.isModernActive());
    }

    @Test
    public void smallVisibilityJitterDoesNotStarveAbbaPairing() {
        AdaptiveBackendController controller = readyForMeasurement(
            configuredBreaker());
        SceneFingerprint[] scenes = {
            scene(), new SceneFingerprint(0, 1, 2, 3, 4, 5, 102, 21, 7,
                12, 1920, 1080, 0, 1L, 1L),
            new SceneFingerprint(0, 1, 2, 3, 4, 5, 99, 19, 4,
                12, 1920, 1080, 0, 1L, 1L),
            scene()
        };
        for (int i = 0; i < scenes.length; i++) {
            MeasurementArm arm = controller.expectedMeasurementArm();
            controller.recordMeasurement(scenes[i], arm,
                arm == MeasurementArm.LEGACY ? 100L : 80L, true);
        }
        assertEquals(BackendLifecycleState.MODERN,
            controller.lifecycleState());
    }

    @Test
    public void transientPreDrawFailureRecoversWithoutDiscardingCertification() {
        ModuleCircuitBreaker breaker = configuredBreaker();
        AdaptiveBackendController controller = readyForMeasurement(breaker);
        assertTrue(controller.recoverableRuntimeFailure(
            new IllegalStateException("temporary query failure")));
        assertEquals(BackendLifecycleState.PAIRED_MEASURE,
            controller.lifecycleState());
        assertEquals(ModuleState.DEGRADED, breaker.snapshot().getState());

        controller.recoverableRuntimeSuccess();
        assertEquals(BackendLifecycleState.PAIRED_MEASURE,
            controller.lifecycleState());
        assertEquals(ModuleState.ACTIVE, breaker.snapshot().getState());
    }

    @Test
    public void rejectedOrdinaryLoadCanRemeasureOneWarmedDenseBucket() {
        ModuleCircuitBreaker breaker = configuredBreaker();
        AdaptiveBackendController controller = profiledForMeasurement(breaker);

        assertFalse(controller.prepareProfiledWorkload(1, 1L));
        // Repeated traversals in one frame must not fake cache warmup.
        assertFalse(controller.prepareProfiledWorkload(1, 1L));
        assertTrue(controller.prepareProfiledWorkload(1, 2L));
        recordWindow(controller, sceneWithEntities(20), 100L, 110L);
        assertEquals(BackendLifecycleState.LEGACY,
            controller.lifecycleState());
        assertEquals(1, controller.snapshot().getEvaluatedWorkloadProfiles());

        // Cooldown ends at frame 7, then the new bucket must itself remain
        // stable for two distinct frames before spending a retry slot.
        assertFalse(controller.prepareProfiledWorkload(2, 6L));
        assertFalse(controller.prepareProfiledWorkload(2, 7L));
        assertTrue(controller.prepareProfiledWorkload(2, 8L));
        assertEquals(BackendLifecycleState.PAIRED_MEASURE,
            controller.lifecycleState());
        recordWindow(controller, sceneWithEntities(40), 100L, 75L);
        assertEquals(BackendLifecycleState.MODERN,
            controller.lifecycleState());
        assertEquals(2, controller.snapshot().getEvaluatedWorkloadProfiles());
        assertEquals(1, controller.snapshot().getWorkloadRetests());

        assertTrue(controller.activateAtSafeBoundary());
        assertTrue(controller.isModernActive());
        // Returning to the already-rejected ordinary bucket is an immediate
        // Legacy decision and does not destroy the dense bucket certificate.
        assertFalse(controller.prepareProfiledWorkload(1, 20L));
        assertEquals(BackendLifecycleState.REGRESSION_MONITOR,
            controller.lifecycleState());
        assertTrue(controller.isModernActive());
    }

    @Test
    public void sameOrLighterRejectedLoadNeverConsumesRetestBudget() {
        AdaptiveBackendController controller = profiledForMeasurement(
            configuredBreaker());
        assertFalse(controller.prepareProfiledWorkload(2, 1L));
        assertTrue(controller.prepareProfiledWorkload(2, 2L));
        recordWindow(controller, sceneWithEntities(40), 100L, 105L);
        assertEquals(BackendLifecycleState.LEGACY,
            controller.lifecycleState());

        assertFalse(controller.prepareProfiledWorkload(2, 100L));
        assertFalse(controller.prepareProfiledWorkload(1, 101L));
        assertFalse(controller.prepareProfiledWorkload(1, 102L));
        assertEquals(0, controller.snapshot().getWorkloadRetests());
    }

    @Test
    public void parentSuspensionConvergesAWaitingChildToLegacy() {
        AdaptiveBackendController child = readyForMeasurement(
            configuredBreaker());
        child.suspendByParent("terrain parent LEGACY");
        assertEquals(BackendLifecycleState.LEGACY,
            child.lifecycleState());
        assertEquals("terrain parent LEGACY", child.snapshot().getDetail());
    }

    @Test
    public void consecutivePreDrawFailuresQuarantineAtBreakerLimit() {
        ModuleCircuitBreaker breaker = configuredBreaker();
        AdaptiveBackendController controller = readyForMeasurement(breaker);
        assertTrue(controller.recoverableRuntimeFailure(
            new IllegalStateException("one")));
        assertTrue(controller.recoverableRuntimeFailure(
            new IllegalStateException("two")));
        assertFalse(controller.recoverableRuntimeFailure(
            new IllegalStateException("three")));
        assertEquals(BackendLifecycleState.QUARANTINED,
            controller.lifecycleState());
        assertEquals(ModuleState.INCOMPATIBLE, breaker.snapshot().getState());
    }

    @Test
    public void textureWorkloadsRemainExactPairingKeys() {
        SceneFingerprint first = SceneFingerprint.textureWorkload(10, 4,
            8192L, 2, 1L, 1L);
        SceneFingerprint same = SceneFingerprint.textureWorkload(10, 4,
            8192L, 2, 1L, 1L);
        SceneFingerprint changed = SceneFingerprint.textureWorkload(11, 4,
            8192L, 2, 1L, 1L);
        assertTrue(first.isPairingCompatible(same));
        assertFalse(first.isPairingCompatible(changed));
    }

    @Test
    public void terrainPairingIncludesOwnershipAndRegionRunShape() {
        SceneFingerprint first = new SceneFingerprint(0, 1, 2, 3, 4, 5,
            100, 20, 5, 12, 1920, 1080, 0, 1L, 1L, 80, 12);
        SceneFingerprint naturalJitter = new SceneFingerprint(0, 1, 2, 3,
            4, 5, 102, 21, 6, 12, 1920, 1080, 0, 1L, 1L, 82, 14);
        SceneFingerprint lowCoverage = new SceneFingerprint(0, 1, 2, 3,
            4, 5, 100, 20, 5, 12, 1920, 1080, 0, 1L, 1L, 3, 3);
        assertTrue(first.isPairingCompatible(naturalJitter));
        assertFalse(first.isPairingCompatible(lowCoverage));
        assertEquals(1, first.terrainLoadBucket());
    }

    private static ModuleCircuitBreaker configuredBreaker() {
        ModuleCircuitBreaker breaker = new ModuleCircuitBreaker(
            OptimizationModule.MODERN_TERRAIN_BACKEND);
        breaker.configure(true, 3);
        return breaker;
    }

    private static AdaptiveBackendController readyForMeasurement(
        ModuleCircuitBreaker breaker) {
        AdaptiveBackendController controller = new AdaptiveBackendController(
            breaker, EnumSet.noneOf(ModernCapability.class), 1, 1, 1,
            0.05D, 0.02D);
        controller.begin(4L);
        controller.capabilityResult(CapabilityReport.builder().build());
        controller.warmupFrame(true);
        controller.validationResult(true, null);
        return controller;
    }

    private static AdaptiveBackendController profiledForMeasurement(
        ModuleCircuitBreaker breaker) {
        AdaptiveBackendController controller = new AdaptiveBackendController(
            breaker, EnumSet.noneOf(ModernCapability.class), 1, 1, 1,
            0.05D, 0.02D, 2, 5L, 2);
        controller.begin(5L);
        controller.capabilityResult(CapabilityReport.builder().build());
        controller.warmupFrame(true);
        controller.validationResult(true, null);
        return controller;
    }

    private static void recordWindow(AdaptiveBackendController controller,
                                     SceneFingerprint scene,
                                     long legacyNanos, long modernNanos) {
        for (int i = 0; i < 4; i++) {
            MeasurementArm arm = controller.expectedMeasurementArm();
            controller.recordMeasurement(scene, arm,
                arm == MeasurementArm.LEGACY ? legacyNanos : modernNanos,
                true);
        }
    }

    private static SceneFingerprint sceneWithEntities(int entities) {
        return new SceneFingerprint(0, 1, 2, 3, 4, 5, 100, entities, 5,
            12, 1920, 1080, 0, 1L, 1L);
    }

    private static SceneFingerprint scene() {
        return new SceneFingerprint(0, 1, 2, 3, 4, 5, 100, 20, 5,
            12, 1920, 1080, 0, 1L, 1L);
    }
}
