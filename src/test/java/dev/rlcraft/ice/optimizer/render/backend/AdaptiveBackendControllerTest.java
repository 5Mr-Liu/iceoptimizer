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
    public void certifiedBackendKeepsRenderingBetweenRegressionSamples() {
        AdaptiveBackendController controller = readyForMeasurement(configuredBreaker());
        for (long frame = 1L; frame <= 32L; frame++) {
            assertTrue("qualification must not be sampled", controller.shouldMeasureFrame(frame));
        }
        recordWindow(controller, scene(), 100L, 80L);
        assertFalse(controller.shouldMeasureFrame(32L));
        assertTrue(controller.activateAtSafeBoundary());
        int samples = 0;
        for (long frame = 1L; frame <= 256L; frame++) {
            assertTrue("sampling must not disable modern draws", controller.isModernActive());
            if (controller.shouldMeasureFrame(frame)) {
                samples++;
                controller.recordRegressionSample(scene(), 110L, true);
            }
        }
        assertEquals(16, samples);
        assertEquals("sampled regression still falls back", BackendLifecycleState.LEGACY,
            controller.lifecycleState());
        assertFalse(controller.shouldMeasureFrame(272L));
        controller.begin(9L);
        assertFalse(controller.shouldMeasureFrame(288L));
    }

    @Test
    public void pairedStatisticsSortEachWindowWithoutChangingPercentiles() {
        AdaptiveBackendController controller = new AdaptiveBackendController(configuredBreaker(),
            EnumSet.noneOf(ModernCapability.class), 1, 1, 2, 0.05D, 0.02D);
        controller.begin(1L);
        controller.capabilityResult(CapabilityReport.builder().build());
        controller.warmupFrame(true);
        controller.validationResult(true, null);
        long[] samples = {120L, 90L, 60L, 100L, 110L, 80L, 70L, 130L};
        for (long nanos : samples) {
            controller.recordMeasurement(scene(), controller.expectedMeasurementArm(), nanos, true);
        }
        assertEquals(BackendLifecycleState.MODERN, controller.lifecycleState());
        assertEquals(1.0D - 70.0D / 110.0D,
            controller.snapshot().getMedianImprovement(), 0.000001D);
    }

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
    public void rejectedKnownLoadRearmsCooldownInsteadOfKeepingHooksHot() {
        AdaptiveBackendController controller = profiledForMeasurement(
            configuredBreaker());
        assertFalse(controller.prepareProfiledWorkload(1, 1L));
        assertTrue(controller.prepareProfiledWorkload(1, 2L));
        recordWindow(controller, sceneWithEntities(20), 100L, 110L);

        assertTrue(controller.isDormant(6L));
        assertFalse(controller.isDormant(7L));
        assertFalse(controller.prepareProfiledWorkload(1, 7L));
        assertEquals(12L, controller.snapshot().getNextRetestFrame());
        assertTrue(controller.isDormant(8L));
        assertFalse(controller.isDormant(12L));
    }

    @Test
    public void severeRegressionPermanentlyDisablesHeavierBucketRetests() {
        AdaptiveBackendController controller = profiledForMeasurement(
            configuredBreaker());
        assertFalse(controller.prepareProfiledWorkload(1, 1L));
        assertTrue(controller.prepareProfiledWorkload(1, 2L));
        recordWindow(controller, sceneWithEntities(20), 100L, 150L);
        assertEquals(BackendLifecycleState.LEGACY,
            controller.lifecycleState());
        assertTrue(controller.snapshot().isWorkloadRetestsDisabled());
        assertFalse(controller.prepareProfiledWorkload(7, 10_000L));
        assertEquals(0, controller.snapshot().getWorkloadRetests());
        assertTrue(controller.isDormant());
    }

    @Test
    public void stalledMeasurementTimesOutToDormantLegacy() {
        AdaptiveBackendController controller = readyForMeasurement(
            configuredBreaker());
        assertFalse(controller.advanceFrame(1L));
        assertTrue(controller.advanceFrame(601L));
        assertEquals(BackendLifecycleState.LEGACY,
            controller.lifecycleState());
        assertEquals(1L, controller.snapshot().getTimedOutTransitions());
        assertTrue(controller.isDormant());
    }

    @Test
    public void repeatedEmptyModernArmStopsAfterEightDistinctFrames() {
        AdaptiveBackendController controller = readyForMeasurement(
            configuredBreaker());
        controller.recordMeasurement(scene(), MeasurementArm.LEGACY, 100L,
            true);
        for (long frame = 1L; frame < 8L; frame++) {
            assertFalse(controller.recordEmptyModernArm(
                MeasurementArm.MODERN, frame));
        }
        assertTrue(controller.recordEmptyModernArm(MeasurementArm.MODERN, 8L));
        assertEquals(BackendLifecycleState.LEGACY,
            controller.lifecycleState());
        assertTrue(controller.isDormant());
    }

    @Test
    public void emptyModelArmsAwaitPublicationWithoutRejectingTheLoadBucket() {
        AdaptiveBackendController controller = profiledForMeasurement(configuredBreaker(),
            CandidateDataSource.MODEL_PUBLICATION);
        assertFalse(controller.prepareProfiledWorkload(1, 1L));
        assertTrue(controller.prepareProfiledWorkload(1, 2L));
        controller.recordMeasurement(scene(), MeasurementArm.LEGACY, 100L, true);
        for (long frame = 3L; frame < 10L; frame++) {
            assertFalse(controller.recordEmptyModernArm(MeasurementArm.MODERN, frame));
            assertFalse(controller.recordEmptyModernArm(MeasurementArm.MODERN, frame));
        }
        assertTrue(controller.recordEmptyModernArm(MeasurementArm.MODERN, 10L));
        assertTrue(controller.isAwaitingCandidateData());
        assertTrue(controller.isDormant(10_000L));
        assertFalse(controller.mayInspectOrRetestCandidate(10_000L));
        assertFalse(controller.prepareProfiledWorkload(7, 10_000L));
        assertEquals(0, controller.snapshot().getEvaluatedWorkloadProfiles());
        assertFalse(controller.snapshot().isWorkloadRetestsDisabled());
        assertTrue(controller.candidateDataPublished());
        assertFalse(controller.candidateDataPublished());
        assertFalse(controller.prepareProfiledWorkload(1, 10_001L));
        assertTrue(controller.prepareProfiledWorkload(1, 10_002L));
        recordWindow(controller, scene(), 100L, 80L);
        assertTrue(controller.activateAtSafeBoundary());
        assertTrue(controller.isModernActive());
    }

    @Test
    public void modelQualificationTimeoutResumesTheIncompleteValidation() {
        AdaptiveBackendController controller = new AdaptiveBackendController(configuredBreaker(),
            EnumSet.noneOf(ModernCapability.class), 1, 2, 1, 0.05D, 0.02D,
            2, 5L, 2, CandidateDataSource.MODEL_PUBLICATION);
        controller.begin(6L);
        controller.capabilityResult(CapabilityReport.builder().build());
        controller.warmupFrame(true);
        assertTrue(controller.prepareProfiledWorkload(1, 1L));
        controller.validationResult(true, null);
        assertFalse(controller.advanceFrame(1L));
        assertTrue(controller.advanceFrame(601L));
        assertTrue(controller.isAwaitingCandidateData());
        assertTrue(controller.candidateDataPublished());
        assertEquals(BackendLifecycleState.OUTPUT_VALIDATE, controller.lifecycleState());
        assertFalse(controller.activateAtSafeBoundary());
        controller.validationResult(true, null);
        assertEquals(BackendLifecycleState.PAIRED_MEASURE, controller.lifecycleState());
        assertFalse(controller.advanceFrame(602L));
        assertTrue(controller.advanceFrame(1202L));
        assertTrue(controller.candidateDataPublished());
        assertEquals(BackendLifecycleState.PAIRED_MEASURE, controller.lifecycleState());
        controller.begin(7L);
        assertFalse(controller.isAwaitingCandidateData());
        assertFalse(controller.candidateDataPublished());
    }

    @Test
    public void publicationDoesNotOverrideMeasuredBenefitOrCorrectnessFailures() {
        AdaptiveBackendController rejected = profiledForMeasurement(configuredBreaker(),
            CandidateDataSource.MODEL_PUBLICATION);
        rejected.prepareProfiledWorkload(1, 1L);
        assertTrue(rejected.prepareProfiledWorkload(1, 2L));
        recordWindow(rejected, scene(), 100L, 110L);
        assertFalse(rejected.isAwaitingCandidateData());
        assertFalse(rejected.candidateDataPublished());
        assertEquals(BackendLifecycleState.LEGACY, rejected.lifecycleState());

        AdaptiveBackendController invalid = profiledForMeasurement(configuredBreaker(),
            CandidateDataSource.MODEL_PUBLICATION);
        invalid.prepareProfiledWorkload(1, 1L);
        assertTrue(invalid.prepareProfiledWorkload(1, 2L));
        invalid.advanceFrame(1L);
        assertTrue(invalid.advanceFrame(601L));
        invalid.correctnessFailure("output mismatch");
        assertFalse(invalid.isAwaitingCandidateData());
        assertFalse(invalid.candidateDataPublished());
        assertEquals(BackendLifecycleState.QUARANTINED, invalid.lifecycleState());
    }

    @Test
    public void actualModernWorkWithoutGpuSamplesDoesNotWaitForNewGeometry() {
        AdaptiveBackendController controller = profiledForMeasurement(configuredBreaker());
        controller.prepareProfiledWorkload(1, 1L);
        assertTrue(controller.prepareProfiledWorkload(1, 2L));
        controller.recordMeasurement(scene(), MeasurementArm.LEGACY, 100L, true);
        controller.recordModernWork();
        // A different scene clears the paired sample window, but it does not
        // imply that already submitted geometry was absent.
        controller.recordMeasurement(sceneWithEntities(1000), MeasurementArm.MODERN, 80L, true);
        assertFalse(controller.advanceFrame(3L));
        assertTrue(controller.advanceFrame(603L));
        assertFalse(controller.isAwaitingCandidateData());
        assertFalse(controller.candidateDataPublished());
        assertEquals(BackendLifecycleState.LEGACY, controller.lifecycleState());
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
        return profiledForMeasurement(breaker, CandidateDataSource.NONE);
    }

    private static AdaptiveBackendController profiledForMeasurement(
        ModuleCircuitBreaker breaker, CandidateDataSource source) {
        AdaptiveBackendController controller = new AdaptiveBackendController(
            breaker, EnumSet.noneOf(ModernCapability.class), 1, 1, 1,
            0.05D, 0.02D, 2, 5L, 2, source);
        controller.begin(5L);
        controller.capabilityResult(CapabilityReport.builder().build());
        controller.warmupFrame(true);
        controller.validationResult(true, null);
        return controller;
    }

    @Test
    public void ordinaryProfileTimeoutDoesNotWaitForUnrelatedModelPublication() {
        AdaptiveBackendController controller = profiledForMeasurement(configuredBreaker());
        controller.prepareProfiledWorkload(1, 1L);
        assertFalse(controller.advanceFrame(1L));
        assertTrue(controller.advanceFrame(601L));
        assertTrue(controller.isDormant());
        assertFalse(controller.isAwaitingCandidateData());
        assertFalse(controller.candidateDataPublished());
        assertFalse(controller.terrainCandidateAvailable(10_000L));
    }

    @Test
    public void terrainUploadResumesIncompleteValidationAfterCooldownAndStillNeedsBenefit() {
        AdaptiveBackendController controller = new AdaptiveBackendController(configuredBreaker(),
            EnumSet.noneOf(ModernCapability.class), 1, 2, 1, 0.05D, 0.02D,
            2, 5L, 2, CandidateDataSource.TERRAIN_UPLOAD);
        controller.begin(6L);
        controller.capabilityResult(CapabilityReport.builder().build());
        controller.warmupFrame(true);
        controller.prepareProfiledWorkload(1, 1L);
        controller.validationResult(true, null);
        assertFalse(controller.advanceFrame(1L));
        assertTrue(controller.advanceFrame(601L));
        assertTrue(controller.isAwaitingCandidateData());
        assertTrue(controller.isDormant(10_000L));
        assertFalse(controller.candidateDataPublished());
        assertFalse(controller.terrainCandidateAvailable(605L));
        assertTrue(controller.terrainCandidateAvailable(606L));
        assertEquals(BackendLifecycleState.OUTPUT_VALIDATE, controller.lifecycleState());
        assertFalse(controller.isModernActive());
        assertFalse(controller.activateAtSafeBoundary());
        controller.validationResult(true, null);
        assertEquals(BackendLifecycleState.PAIRED_MEASURE, controller.lifecycleState());
        assertFalse(controller.prepareProfiledWorkload(1, 607L));
        assertTrue(controller.prepareProfiledWorkload(1, 608L));
        recordWindow(controller, scene(), 100L, 80L);
        assertTrue(controller.activateAtSafeBoundary());
        assertEquals(1, controller.snapshot().getWorkloadRetests());
    }

    @Test
    public void terrainRetriesShareTheExistingBoundAndCannotLoopForever() {
        AdaptiveBackendController controller = profiledForMeasurement(configuredBreaker(),
            CandidateDataSource.TERRAIN_UPLOAD);
        controller.prepareProfiledWorkload(1, 1L);
        controller.prepareProfiledWorkload(1, 2L);
        emptyTerrainWindow(controller, 3L);
        assertFalse(controller.terrainCandidateAvailable(14L));
        assertTrue(controller.terrainCandidateAvailable(15L));
        assertFalse(controller.prepareProfiledWorkload(1, 16L));
        assertTrue(controller.prepareProfiledWorkload(1, 17L));
        emptyTerrainWindow(controller, 18L);
        assertTrue(controller.terrainCandidateAvailable(30L));
        assertFalse(controller.prepareProfiledWorkload(1, 31L));
        assertTrue(controller.prepareProfiledWorkload(1, 32L));
        emptyTerrainWindow(controller, 33L);
        assertEquals(2, controller.snapshot().getWorkloadRetests());
        assertTrue(controller.snapshot().isWorkloadRetestsDisabled());
        assertFalse(controller.isAwaitingCandidateData());
        assertTrue(controller.isDormant(100_000L));
        assertFalse(controller.terrainCandidateAvailable(100_000L));
    }

    @Test
    public void terrainUploadsDoNotOverrideMeasuredRegressionOrCorrectnessRejection() {
        AdaptiveBackendController controller = profiledForMeasurement(configuredBreaker(),
            CandidateDataSource.TERRAIN_UPLOAD);
        controller.prepareProfiledWorkload(1, 1L);
        controller.prepareProfiledWorkload(1, 2L);
        recordWindow(controller, scene(), 100L, 150L);
        assertTrue(controller.snapshot().isWorkloadRetestsDisabled());
        assertFalse(controller.terrainCandidateAvailable(100_000L));
        assertFalse(controller.isModernActive());

        AdaptiveBackendController invalid = profiledForMeasurement(configuredBreaker(),
            CandidateDataSource.TERRAIN_UPLOAD);
        invalid.prepareProfiledWorkload(1, 1L);
        invalid.prepareProfiledWorkload(1, 2L);
        emptyTerrainWindow(invalid, 3L);
        invalid.correctnessFailure("output mismatch");
        assertFalse(invalid.terrainCandidateAvailable(100_000L));
        assertEquals(BackendLifecycleState.QUARANTINED, invalid.lifecycleState());
    }

    @Test
    public void parentPauseRetainsTheAcceptedChildAndResumesAtSafeActivation() {
        AdaptiveBackendController child = readyForMeasurement(configuredBreaker());
        recordWindow(child, scene(), 100L, 80L);
        assertTrue(child.activateAtSafeBoundary());
        child.suspendByParent("parent is waiting for terrain");
        assertEquals(BackendLifecycleState.LEGACY, child.lifecycleState());
        assertFalse(child.isModernActive());
        assertTrue(child.resumeAfterParent());
        assertFalse(child.resumeAfterParent());
        assertEquals(BackendLifecycleState.MODERN, child.lifecycleState());
        assertFalse(child.isModernActive());
        assertTrue(child.activateAtSafeBoundary());
        for (int index = 0; index < 16; index++) child.recordRegressionSample(scene(), 110L, true);
        assertEquals(BackendLifecycleState.LEGACY, child.lifecycleState());
    }

    @Test
    public void parentResumeDoesNotReviveARejectedOrQuarantinedChild() {
        AdaptiveBackendController rejected = readyForMeasurement(configuredBreaker());
        recordWindow(rejected, scene(), 100L, 150L);
        rejected.suspendByParent("parent unavailable");
        assertFalse(rejected.resumeAfterParent());

        AdaptiveBackendController invalid = readyForMeasurement(configuredBreaker());
        invalid.suspendByParent("parent unavailable");
        invalid.correctnessFailure("output mismatch");
        assertFalse(invalid.resumeAfterParent());
        assertEquals(BackendLifecycleState.QUARANTINED, invalid.lifecycleState());
    }

    private static void emptyTerrainWindow(AdaptiveBackendController controller, long firstFrame) {
        controller.recordMeasurement(scene(), MeasurementArm.LEGACY, 100L, true);
        for (long frame = firstFrame; frame < firstFrame + 7L; frame++) {
            assertFalse(controller.recordEmptyModernArm(MeasurementArm.MODERN, frame));
        }
        assertTrue(controller.recordEmptyModernArm(MeasurementArm.MODERN, firstFrame + 7L));
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
