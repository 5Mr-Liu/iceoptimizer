package dev.rlcraft.ice.optimizer.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.ModuleCircuitBreaker;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyMatrixStateTracker;
import dev.rlcraft.ice.optimizer.compat.renderlib.RenderLibRenderBridge;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.backend.AdaptiveBackendController;
import dev.rlcraft.ice.optimizer.render.backend.CapabilityReport;
import dev.rlcraft.ice.optimizer.render.backend.MeasurementArm;
import dev.rlcraft.ice.optimizer.render.backend.ModernCapability;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.frame.RenderPass;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.util.EnumSet;
import org.junit.Test;

/** Exercises the actual admission used by the reviewed Lycanites OBJ emitter. */
public final class ExternalModelMeshAdmissionTest {
    private static final OptimizationModule[] MODULES = {
        OptimizationModule.MODERN_ENTITY_BACKEND, OptimizationModule.MODERN_TESR_BACKEND
    };

    @Test public void directActivationDoesNotWaitForRemovedOutputValidation() throws Exception {
        for (OptimizationModule module : MODULES) {
            try (Fixture fixture = new Fixture(module, true)) {
                assertFalse(fixture.runtime.genericModelVboValidated);
                assertFalse(fixture.runtime.components.capabilities.passed(ModernCapability.MODEL_MESH_VBO));
                assertTrue(fixture.runtime.canEmitExternalModelMesh());
            }
        }
    }

    @Test public void legacyAdmissionStillRequiresBothOutputValidationAndCapability() throws Exception {
        for (OptimizationModule module : MODULES) {
            try (Fixture fixture = new Fixture(module, false)) {
                assertFalse(fixture.runtime.canEmitExternalModelMesh());
                fixture.runtime.genericModelVboValidated = true;
                assertTrue(fixture.runtime.canEmitExternalModelMesh());
                fixture.runtime.components.capabilities = capabilities(false);
                assertFalse(fixture.runtime.canEmitExternalModelMesh());
            }
        }
    }

    @Test public void missingPoseMirrorDoesNotDisableImmediateBackends() throws Exception {
        for (OptimizationModule module : MODULES) {
            try (Fixture fixture = new Fixture(module, true)) {
                java.lang.reflect.Method ensure = ModernRendererRuntime.class.getDeclaredMethod(
                    "ensureModelTrackedState", AdaptiveBackendController.class, OptimizationModule.class);
                ensure.setAccessible(true);
                AdaptiveBackendController backend = fixture.runtime.components.backends.get(module);
                for (int attempt = 0; attempt < 5; attempt++) {
                    EarlyMatrixStateTracker.invalidate();
                    assertTrue(fixture.runtime.canEmitExternalModelMesh());
                    assertTrue((Boolean) ensure.invoke(fixture.runtime, backend, module));
                }
                org.junit.Assert.assertEquals(0L, fixture.runtime.modelStateReauthenticationFailures);
                org.junit.Assert.assertEquals(0L, fixture.runtime.modelStateReauthenticationAttempts);
                org.junit.Assert.assertNotEquals(
                    dev.rlcraft.ice.optimizer.render.backend.BackendLifecycleState.QUARANTINED,
                    backend.lifecycleState());
            }
        }
    }

    @Test public void unknownAndActiveShaderPacksStillRejectBothAdmissionModes() throws Exception {
        for (boolean direct : new boolean[] {true, false}) {
            for (OptimizationModule module : MODULES) {
                try (Fixture fixture = new Fixture(module, direct)) {
                    fixture.runtime.genericModelVboValidated = true;
                    assertTrue(fixture.runtime.canEmitExternalModelMesh());
                    fixture.runtime.setShaderPackState(true, true);
                    assertFalse(fixture.runtime.canEmitExternalModelMesh());
                    fixture.runtime.setShaderPackState(false, false);
                    assertFalse(fixture.runtime.canEmitExternalModelMesh());
                    fixture.runtime.setShaderPackState(true, false);
                    assertTrue(fixture.runtime.canEmitExternalModelMesh());
                }
            }
        }
    }

    private static CapabilityReport capabilities(boolean modelOutput) {
        CapabilityReport.Builder report = CapabilityReport.builder()
            .pass(ModernCapability.BUFFER_OBJECT).pass(ModernCapability.SYNC_FENCE);
        if (modelOutput) report.pass(ModernCapability.MODEL_MESH_VBO);
        else report.fail(ModernCapability.MODEL_MESH_VBO, "output probe unavailable");
        return report.build();
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field value = owner.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }

    private static ThreadLocal<?> traversals() throws Exception {
        return (ThreadLocal<?>) field(RenderLibRenderBridge.class, "TRAVERSALS").get(null);
    }

    private static final class Fixture implements AutoCloseable {
        final ModernRendererRuntime runtime;

        Fixture(OptimizationModule module, boolean direct) throws Exception {
            traversals().remove();
            ClientEpochs epochs = new ClientEpochs();
            runtime = new ModernRendererRuntime(epochs, new CacheBudget(1024L, 1024L, 1024L));
            runtime.initialized = true;
            runtime.currentStamp = new FrameStamp(1L, 1L, epochs.snapshot());
            runtime.components.threadGuard = RenderThreadGuard.captureCurrent();
            runtime.components.capabilities = capabilities(!direct);
            runtime.setShaderPackState(true, false);

            ModuleCircuitBreaker breaker = new ModuleCircuitBreaker(module);
            breaker.configure(true, 3);
            AdaptiveBackendController backend = new AdaptiveBackendController(breaker,
                EnumSet.of(ModernCapability.BUFFER_OBJECT, ModernCapability.SYNC_FENCE),
                1, 1, 1, 0.05D, 0.02D);
            backend.configureDirectActivation(direct);
            backend.begin(1L);
            backend.capabilityResult(runtime.components.capabilities);
            if (!direct) {
                backend.warmupFrame(true);
                backend.validationResult(true, null);
            }
            runtime.components.backends.put(module, backend);

            RenderPass pass = module == OptimizationModule.MODERN_TESR_BACKEND
                ? RenderPass.TESR_PASS_0 : RenderPass.ENTITY_PASS_0;
            Object stack = traversals().get();
            Object traversal = ((Object[]) field(stack.getClass(), "values").get(stack))[0];
            field(stack.getClass(), "size").setInt(stack, 1);
            field(traversal.getClass(), "module").set(traversal, module);
            field(traversal.getClass(), "objectActive").setBoolean(traversal, true);
            field(traversal.getClass(), "objectCandidateAllowed").setBoolean(traversal, true);
            field(traversal.getClass(), "objectPass").set(traversal, pass);
            field(traversal.getClass(), "sample").set(traversal,
                new ModernRendererRuntime.RenderBackendSample(module, backend.lifecycleState(),
                    MeasurementArm.MODERN, null, null, null, 0L, 1L));

            EarlyGlStateTracker.beginProbe();
            EarlyGlStateTracker.useProgram(0);
            EarlyGlStateTracker.bindFramebuffer(36160, 0);
            EarlyGlStateTracker.depthFunction(515);
            EarlyGlStateTracker.bindBuffer(35051, 0);
            EarlyGlStateTracker.bindBuffer(34962, 0);
            EarlyGlStateTracker.clientActiveTexture(33984);
            EarlyGlStateTracker.seedDrawState(0, 1, 2, false, 1, 0, 1, 0,
                true, true, true, 15, 1F, 1F, 1F, 1F);
            FloatBuffer identity = FloatBuffer.wrap(new float[] {
                1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1
            });
            EarlyMatrixStateTracker.seed(5888, identity, identity);
        }

        @Override public void close() throws Exception {
            traversals().remove();
            EarlyGlStateTracker.invalidate();
            EarlyMatrixStateTracker.invalidate();
        }
    }
}
