package dev.rlcraft.ice.optimizer.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.ModuleCircuitBreaker;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.compat.model.ModelMeshCaptureBridge;
import dev.rlcraft.ice.optimizer.compat.renderlib.RenderLibRenderBridge;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.backend.AdaptiveBackendController;
import dev.rlcraft.ice.optimizer.render.backend.BackendLifecycleState;
import dev.rlcraft.ice.optimizer.render.backend.CandidateDataSource;
import dev.rlcraft.ice.optimizer.render.backend.CapabilityReport;
import dev.rlcraft.ice.optimizer.render.backend.MeasurementArm;
import dev.rlcraft.ice.optimizer.render.backend.ModernCapability;
import dev.rlcraft.ice.optimizer.render.backend.RenderBackendId;
import dev.rlcraft.ice.optimizer.render.backend.SceneFingerprint;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.frame.RenderPass;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import dev.rlcraft.ice.optimizer.render.telemetry.RenderProfileKey;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Map;
import org.junit.Test;

public final class ModelBackendRecoveryTest {
    @Test
    public void startupAndWarmupCaptureDoNotEnableDrawInspection() throws Exception {
        ModernRendererRuntime runtime = runtime();
        assertTrue(runtime.mayCaptureModelMeshes());
        field(ModernRendererRuntime.class, "shutdownRequested").setBoolean(runtime, true);
        assertFalse(runtime.mayCaptureModelMeshes());
        field(ModernRendererRuntime.class, "shutdownRequested").setBoolean(runtime, false);
        initializeFixture(runtime);
        AdaptiveBackendController warmup = controller(OptimizationModule.MODERN_ENTITY_BACKEND, 1);
        runtime.components.backends.put(OptimizationModule.MODERN_ENTITY_BACKEND, warmup);
        assertEquals(BackendLifecycleState.WARMUP, warmup.lifecycleState());
        assertTrue(runtime.mayCaptureModelMeshes());
        assertFalse(runtime.mayUseBackendCandidate(OptimizationModule.MODERN_ENTITY_BACKEND));
        assertFalse(runtime.shouldObserveBackend(OptimizationModule.MODERN_ENTITY_BACKEND));
    }

    @Test
    public void timedOutValidationResumesRemainingValidationInsteadOfSkippingIt() {
        AdaptiveBackendController controller = controller(OptimizationModule.MODERN_ENTITY_BACKEND, 2);
        controller.warmupFrame(true);
        assertTrue(controller.prepareProfiledWorkload(2, 1L));
        controller.validationResult(true, "first validation");
        assertFalse(controller.advanceFrame(1L));
        assertTrue(controller.advanceFrame(601L));
        assertWaiting(controller);
        assertTrue(controller.candidateDataPublished());
        assertEquals(BackendLifecycleState.OUTPUT_VALIDATE, controller.lifecycleState());
        controller.validationResult(true, "remaining validation");
        assertEquals(BackendLifecycleState.PAIRED_MEASURE, controller.lifecycleState());
        assertFalse(controller.candidateDataPublished());
    }

    @Test
    public void waitingEntityAndTesrSleepUntilTheColdFrameQueueHasData() throws Exception {
        ModernRendererRuntime runtime = runtime();
        initializeFixture(runtime);
        AdaptiveBackendController entity = awaiting(OptimizationModule.MODERN_ENTITY_BACKEND);
        AdaptiveBackendController tesr = awaiting(OptimizationModule.MODERN_TESR_BACKEND);
        runtime.components.backends.put(OptimizationModule.MODERN_ENTITY_BACKEND, entity);
        runtime.components.backends.put(OptimizationModule.MODERN_TESR_BACKEND, tesr);
        try {
            ModelMeshCaptureBridge.reset();
            assertFalse(requiresInfrastructure(runtime, 1000L));
            assertTrue(runtime.mayCaptureModelMeshes());
            Field pending = field(ModelMeshCaptureBridge.class, "hasPendingMeshes");
            pending.setBoolean(null, true);
            assertTrue(requiresInfrastructure(runtime, 1000L));
            for (int draw = 0; draw < 1000; draw++) {
                assertFalse(runtime.shouldObserveBackend(OptimizationModule.MODERN_ENTITY_BACKEND));
                assertFalse(runtime.shouldObserveBackend(OptimizationModule.MODERN_TESR_BACKEND));
            }
            assertWaiting(entity);
            assertWaiting(tesr);
            assertTrue(entity.candidateDataPublished());
            assertTrue(tesr.candidateDataPublished());
            assertEquals(BackendLifecycleState.PAIRED_MEASURE, entity.lifecycleState());
            assertEquals(BackendLifecycleState.PAIRED_MEASURE, tesr.lifecycleState());
            assertEquals(MeasurementArm.LEGACY, entity.expectedMeasurementArm());
            assertEquals(0, entity.snapshot().getMeasurementSamples());
        } finally {
            ModelMeshCaptureBridge.reset();
        }
    }

    @Test
    public void measuredNonBenefitAndQuarantineDoNotWakeForQueuedGeometry() throws Exception {
        ModernRendererRuntime runtime = runtime();
        initializeFixture(runtime);
        AdaptiveBackendController nonBenefit = ready(OptimizationModule.MODERN_ENTITY_BACKEND);
        nonBenefit.prepareProfiledWorkload(2, 1L);
        for (int index = 0; index < 4; index++) {
            MeasurementArm arm = nonBenefit.expectedMeasurementArm();
            nonBenefit.recordMeasurement(scene(), arm, arm == MeasurementArm.LEGACY ? 100L : 150L, true);
        }
        AdaptiveBackendController quarantined = ready(OptimizationModule.MODERN_TESR_BACKEND);
        quarantined.correctnessFailure("fixture output mismatch");
        runtime.components.backends.put(OptimizationModule.MODERN_ENTITY_BACKEND, nonBenefit);
        runtime.components.backends.put(OptimizationModule.MODERN_TESR_BACKEND, quarantined);
        try {
            field(ModelMeshCaptureBridge.class, "hasPendingMeshes").setBoolean(null, true);
            assertFalse(nonBenefit.isAwaitingCandidateData());
            assertFalse(nonBenefit.candidateDataPublished());
            assertFalse(quarantined.candidateDataPublished());
            assertFalse(runtime.mayCaptureModelMeshes());
            assertFalse(requiresInfrastructure(runtime, 1000L));
            assertEquals(BackendLifecycleState.LEGACY, nonBenefit.lifecycleState());
            assertEquals(BackendLifecycleState.QUARANTINED, quarantined.lifecycleState());
        } finally {
            ModelMeshCaptureBridge.reset();
        }
    }

    @Test
    public void inactiveNestedCompilerCaptureKeepsOuterCapturePaired() throws Exception {
        ModernRendererRuntime runtime = runtime();
        initializeFixture(runtime);
        AdaptiveBackendController backend = controller(OptimizationModule.MODERN_ENTITY_BACKEND, 1);
        runtime.components.backends.put(OptimizationModule.MODERN_ENTITY_BACKEND, backend);
        Field current = field(ClientOptimizerRuntime.class, "modernRenderer");
        Object previous = current.get(ClientOptimizerRuntime.INSTANCE);
        Object outer = new Object();
        Object inner = new Object();
        try {
            ModelMeshCaptureBridge.reset();
            current.set(ClientOptimizerRuntime.INSTANCE, runtime);
            ModelMeshCaptureBridge.begin(outer);
            assertEquals(1, captureDepth());
            backend.fallback("fixture inactive compiler", false);
            assertFalse(runtime.mayCaptureModelMeshes());
            ModelMeshCaptureBridge.begin(inner);
            assertEquals(2, captureDepth());
            ModelMeshCaptureBridge.finish(inner);
            assertEquals(1, captureDepth());
            ModelMeshCaptureBridge.finish(outer);
            assertEquals(0, captureDepth());
        } finally {
            current.set(ClientOptimizerRuntime.INSTANCE, previous);
            ModelMeshCaptureBridge.reset();
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void lateGpuCompletionCannotTrainResumedRunAndOtherBackendStillCompletes() throws Exception {
        ModernRendererRuntime runtime = runtime();
        initializeFixture(runtime);
        AdaptiveBackendController entity = awaiting(OptimizationModule.MODERN_ENTITY_BACKEND);
        AdaptiveBackendController tesr = ready(OptimizationModule.MODERN_TESR_BACKEND);
        runtime.components.backends.put(OptimizationModule.MODERN_ENTITY_BACKEND, entity);
        runtime.components.backends.put(OptimizationModule.MODERN_TESR_BACKEND, tesr);
        RenderMeasurementRuntime measurements = (RenderMeasurementRuntime)
            field(ModernRendererRuntime.class, "measurements").get(runtime);
        Map pending = measurements.backendGpuSamples;
        FrameStamp stamp = runtime.currentStamp();
        RenderProfileKey stale = new RenderProfileKey(stamp, RenderPass.ENTITY_PASS_0, RenderBackendId.LEGACY);
        RenderProfileKey other = new RenderProfileKey(stamp, RenderPass.TESR_PASS_0, RenderBackendId.LEGACY);
        pending.put(stale, gpuSample(OptimizationModule.MODERN_ENTITY_BACKEND));
        Object otherSample = gpuSample(OptimizationModule.MODERN_TESR_BACKEND);
        pending.put(other, otherSample);
        assertTrue(entity.candidateDataPublished());
        measurements.discardBackendSamples(OptimizationModule.MODERN_ENTITY_BACKEND);
        assertEquals(1, pending.size());
        org.junit.Assert.assertSame(otherSample, pending.get(other));
        measurements.completeBackendGpuSample(stale, 200L);
        assertEquals(0, entity.snapshot().getMeasurementSamples());
        assertEquals(MeasurementArm.LEGACY, entity.expectedMeasurementArm());
        measurements.completeBackendGpuSample(other, 200L);
        assertEquals(1, tesr.snapshot().getMeasurementSamples());
        assertTrue(pending.isEmpty());
    }

    @Test
    public void legacyCandidateCountsWhileOutlineDoesNotCreateEmptyModernWork() throws Exception {
        Field states = field(RenderLibRenderBridge.class, "TRAVERSALS");
        ThreadLocal<?> traversals = (ThreadLocal<?>) states.get(null);
        traversals.remove();
        try {
            Object stack = traversals.get();
            Object traversal = ((Object[]) field(stack.getClass(), "values").get(stack))[0];
            field(stack.getClass(), "size").setInt(stack, 1);
            field(traversal.getClass(), "objectActive").setBoolean(traversal, true);
            Field eligible = field(traversal.getClass(), "eligibleDraws");
            Field allowed = field(traversal.getClass(), "objectCandidateAllowed");
            allowed.setBoolean(traversal, true);
            RenderLibRenderBridge.markEligibleDraw();
            assertEquals(1, eligible.getInt(traversal));
            allowed.setBoolean(traversal, false);
            for (int draw = 0; draw < 20; draw++) RenderLibRenderBridge.markEligibleDraw();
            assertEquals(1, eligible.getInt(traversal));
        } finally {
            traversals.remove();
        }
    }

    @Test
    public void unstableEmptyModernSamplesDoNotSuspendButEightStableEmptyFramesDo() throws Exception {
        ModernRendererRuntime runtime = runtime();
        initializeFixture(runtime);
        AdaptiveBackendController backend = ready(OptimizationModule.MODERN_ENTITY_BACKEND);
        backend.prepareProfiledWorkload(2, 1L);
        backend.recordMeasurement(scene(), MeasurementArm.LEGACY, 100L, true);
        runtime.components.backends.put(OptimizationModule.MODERN_ENTITY_BACKEND, backend);
        RenderMeasurementRuntime measurements = (RenderMeasurementRuntime)
            field(ModernRendererRuntime.class, "measurements").get(runtime);
        Class<?> sampleClass = Class.forName(ModernRendererRuntime.class.getName() + "$RenderBackendSample");
        Constructor<?> constructor = sampleClass.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        for (long frame = 2L; frame < 22L; frame++) {
            Object sample = backendSample(constructor, frame);
            measurements.endRenderBackendSample((ModernRendererRuntime.RenderBackendSample) sample, false, false);
        }
        assertEquals(BackendLifecycleState.PAIRED_MEASURE, backend.lifecycleState());
        assertFalse(backend.isAwaitingCandidateData());
        for (long frame = 22L; frame < 30L; frame++) {
            Object sample = backendSample(constructor, frame);
            measurements.endRenderBackendSample((ModernRendererRuntime.RenderBackendSample) sample, true, false);
        }
        assertWaiting(backend);
    }

    @Test
    public void realModernSubmissionWithoutGpuTimingDoesNotMasqueradeAsMissingGeometry() throws Exception {
        ModernRendererRuntime runtime = runtime();
        initializeFixture(runtime);
        AdaptiveBackendController backend = ready(OptimizationModule.MODERN_ENTITY_BACKEND);
        backend.prepareProfiledWorkload(2, 1L);
        backend.recordMeasurement(scene(), MeasurementArm.LEGACY, 100L, true);
        runtime.components.backends.put(OptimizationModule.MODERN_ENTITY_BACKEND, backend);
        RenderMeasurementRuntime measurements = (RenderMeasurementRuntime)
            field(ModernRendererRuntime.class, "measurements").get(runtime);
        Class<?> sampleClass = Class.forName(ModernRendererRuntime.class.getName() + "$RenderBackendSample");
        Constructor<?> constructor = sampleClass.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        measurements.endRenderBackendSample((ModernRendererRuntime.RenderBackendSample)
            backendSample(constructor, 2L), false, true);
        assertFalse(backend.advanceFrame(2L));
        assertTrue(backend.advanceFrame(602L));
        assertEquals(BackendLifecycleState.LEGACY, backend.lifecycleState());
        assertFalse(backend.isAwaitingCandidateData());
        assertFalse(backend.candidateDataPublished());
    }

    private static Object backendSample(Constructor<?> constructor, long frame) throws Exception {
        return constructor.newInstance(OptimizationModule.MODERN_ENTITY_BACKEND,
            BackendLifecycleState.PAIRED_MEASURE, MeasurementArm.MODERN, scene(), null, null, 0L, frame);
    }

    private static Object gpuSample(OptimizationModule module) throws Exception {
        Class<?> type = Class.forName(RenderMeasurementRuntime.class.getName() + "$BackendGpuSample");
        Constructor<?> constructor = type.getDeclaredConstructor(OptimizationModule.class,
            SceneFingerprint.class, MeasurementArm.class);
        constructor.setAccessible(true);
        Object sample = constructor.newInstance(module, scene(), MeasurementArm.LEGACY);
        field(type, "cpuReady").setBoolean(sample, true);
        field(type, "cpuNanos").setLong(sample, 100L);
        field(type, "stable").setBoolean(sample, true);
        return sample;
    }

    private static ModernRendererRuntime runtime() {
        return new ModernRendererRuntime(new ClientEpochs(), new CacheBudget(1024L, 1024L, 1024L));
    }

    private static void initializeFixture(ModernRendererRuntime runtime) throws Exception {
        runtime.initialized = true;
        runtime.components.threadGuard = RenderThreadGuard.captureCurrent();
        field(ModernRendererRuntime.class, "lastRuntimeFrame").setLong(runtime, 1000L);
        field(ModernRendererRuntime.class, "currentStamp").set(runtime,
            new FrameStamp(1000L, 1L, new ClientEpochs().snapshot()));
    }

    private static AdaptiveBackendController controller(OptimizationModule module, int validations) {
        ModuleCircuitBreaker breaker = new ModuleCircuitBreaker(module);
        breaker.configure(true, 3);
        AdaptiveBackendController controller = new AdaptiveBackendController(breaker,
            EnumSet.noneOf(ModernCapability.class), 1, validations, 1, 0.05D, 0.02D,
            CandidateDataSource.MODEL_PUBLICATION);
        controller.begin(1L);
        controller.capabilityResult(CapabilityReport.builder().build());
        return controller;
    }

    private static AdaptiveBackendController ready(OptimizationModule module) {
        AdaptiveBackendController controller = controller(module, 1);
        controller.warmupFrame(true);
        controller.validationResult(true, "fixture equivalent");
        return controller;
    }

    private static AdaptiveBackendController awaiting(OptimizationModule module) {
        AdaptiveBackendController controller = ready(module);
        controller.prepareProfiledWorkload(2, 1L);
        controller.recordMeasurement(scene(), MeasurementArm.LEGACY, 100L, true);
        for (long frame = 2L; frame < 10L; frame++) controller.recordEmptyModernArm(MeasurementArm.MODERN, frame);
        assertWaiting(controller);
        return controller;
    }

    private static void assertWaiting(AdaptiveBackendController controller) {
        assertEquals(BackendLifecycleState.LEGACY, controller.lifecycleState());
        assertTrue(controller.isAwaitingCandidateData());
        assertTrue(controller.isDormant(100000L));
        assertFalse(controller.mayInspectOrRetestCandidate(100000L));
    }

    private static SceneFingerprint scene() {
        return new SceneFingerprint(0, 0, 0, 0, 0, 0, 0, 20, 5, 8, 64, 64, 0, 1L, 1L);
    }

    private static boolean requiresInfrastructure(ModernRendererRuntime runtime, long frame) throws Exception {
        Method method = ModernRendererRuntime.class.getDeclaredMethod("requiresFrameInfrastructure", long.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(runtime, frame);
    }

    private static int captureDepth() throws Exception {
        Method method = ModelMeshCaptureBridge.class.getDeclaredMethod("captureDepthForTest");
        method.setAccessible(true);
        return (Integer) method.invoke(null);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
