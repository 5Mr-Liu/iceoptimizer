package dev.rlcraft.ice.optimizer.client;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.bridge.ClientRuntimeAccess;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import dev.rlcraft.ice.optimizer.compat.model.ModelMeshCaptureBridge;
import dev.rlcraft.ice.optimizer.compat.renderlib.RenderLibRenderBridge;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.memory.CacheBudgetStatus;
import dev.rlcraft.ice.optimizer.render.backend.AdaptiveBackendController;
import dev.rlcraft.ice.optimizer.render.backend.BackendLifecycleState;
import dev.rlcraft.ice.optimizer.render.backend.MeasurementArm;
import dev.rlcraft.ice.optimizer.render.backend.SceneFingerprint;
import dev.rlcraft.ice.optimizer.render.entity.LwjglModelMeshCache;
import dev.rlcraft.ice.optimizer.render.frame.RenderPass;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyMatrixStateTracker;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Map;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.Pbuffer;
import org.lwjgl.opengl.PixelFormat;

/** Actual capture -> dormant-frame wake -> GPU publication -> resumed model draw. */
public final class ModelBackendRecoveryProbe {
    private static final int SIZE = 64;

    public static void main(String[] arguments) throws Exception {
        Pbuffer context = new Pbuffer(SIZE, SIZE, new PixelFormat().withDepthBits(24), null, null);
        Field rendererField = field(ClientOptimizerRuntime.class, "modernRenderer");
        Object previousRenderer = rendererField.get(ClientOptimizerRuntime.INSTANCE);
        Field bridgeField = field(OptimizerBridge.class, "clientRuntime");
        Object previousBridge = bridgeField.get(null);
        ModernRendererRuntime runtime = null;
        int displayList = 0;
        try {
            context.makeCurrent();
            OpenGlHelper.defaultTexUnit = GL13.GL_TEXTURE0;
            OpenGlHelper.lightmapTexUnit = GL13.GL_TEXTURE1;
            prepareDrawState();
            for (OptimizationModule module : OptimizationModule.values()) OptimizerRegistry.breaker(module).configure(false, 3);
            for (OptimizationModule module : new OptimizationModule[] { OptimizationModule.MODERN_FRAME_COORDINATOR,
                OptimizationModule.LEGACY_GL_ISLAND, OptimizationModule.MODERN_ENTITY_BACKEND,
                OptimizationModule.MODERN_TESR_BACKEND }) OptimizerRegistry.breaker(module).configure(true, 3);
            final ClientEpochs epochs = new ClientEpochs();
            CacheBudget budget = new CacheBudget(256L << 20, 192L << 20, 256L << 20);
            runtime = new ModernRendererRuntime(epochs, budget);
            rendererField.set(ClientOptimizerRuntime.INSTANCE, runtime);
            OptimizerBridge.attachClientRuntime(new ClientRuntimeAccess() {
                @Override public long currentFrameId() { return epochs.currentFrameId(); }
                @Override public long currentClientTickId() { return epochs.currentClientTickId(); }
                @Override public long currentWorldGeneration() { return epochs.currentWorldGeneration(); }
                @Override public long currentResourceGeneration() { return epochs.currentResourceGeneration(); }
                @Override public long currentGlContextGeneration() { return epochs.currentGlContextGeneration(); }
            });
            Method initialize = ModernRendererRuntime.class.getDeclaredMethod("ensureInitialized");
            initialize.setAccessible(true);
            initialize.invoke(runtime);
            require(runtime.initialized, "actual renderer initialization: " + runtime.detail);
            // A standalone JVM does not install Forge's transformed GL wrappers.
            // Publish their state after capability probes and rebuild controller
            // readiness from the real probe results; native resources stay owned.
            prepareDrawState();
            OptimizerRegistry.breaker(OptimizationModule.MODERN_ENTITY_BACKEND).configure(true, 3);
            OptimizerRegistry.breaker(OptimizationModule.MODERN_TESR_BACKEND).configure(true, 3);
            Method configure = ModernRendererRuntime.class.getDeclaredMethod("configureBackends");
            configure.setAccessible(true);
            configure.invoke(runtime);
            ModelMeshCaptureBridge.reset();
            AdaptiveBackendController entity = runtime.components.backends.get(OptimizationModule.MODERN_ENTITY_BACKEND);
            AdaptiveBackendController tesr = runtime.components.backends.get(OptimizationModule.MODERN_TESR_BACKEND);
            require(entity != null && tesr != null, "model capabilities available: entity="
                + OptimizerRegistry.breaker(OptimizationModule.MODERN_ENTITY_BACKEND).snapshot().getDetail()
                + ", TESR=" + OptimizerRegistry.breaker(OptimizationModule.MODERN_TESR_BACKEND).snapshot().getDetail()
                + ", capability failures=" + runtime.components.capabilities.getFailures());
            for (Map.Entry<OptimizationModule, AdaptiveBackendController> entry : runtime.components.backends.entrySet()) {
                if (entry.getKey() == OptimizationModule.MODERN_ENTITY_BACKEND
                    || entry.getKey() == OptimizationModule.MODERN_TESR_BACKEND) awaitData(entry.getValue());
                else entry.getValue().fallback("probe dormant backend", false);
            }
            long frame = epochs.nextFrame();
            runtime.beginFrame(frame, epochs.snapshot());
            require(!runtime.isFrameInfrastructureActive(), "all backends sleep without pending geometry");
            runtime.endFrame();
            displayList = GL11.glGenLists(1);
            require(displayList > 0, "display list allocation");
            OwnedList owner = new OwnedList(displayList);
            BufferBuilder builder = new BufferBuilder(128);
            builder.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_NORMAL);
            for (double[] xy : corners()) builder.pos(xy[0], xy[1], 0).tex(0, 0).normal(0, 0, 1).endVertex();
            ModelMeshCaptureBridge.begin(owner);
            ModelMeshCaptureBridge.captureQuad(builder);
            GL11.glNewList(displayList, GL11.GL_COMPILE);
            GL11.glBegin(GL11.GL_QUADS);
            for (double[] xy : corners()) GL11.glVertex3d(xy[0], xy[1], 0);
            GL11.glEnd();
            GL11.glEndList();
            ModelMeshCaptureBridge.finish(owner);
            require(ModelMeshCaptureBridge.hasPendingModelMeshes(), "compiler captures while model render hooks sleep");
            long attempts = ModelMeshCaptureBridge.diagnostics().getPublicationAttempts();
            prepareDrawState();
            for (int index = 0; index < 100; index++) ModelMeshCaptureBridge.callList(displayList);
            require(ModelMeshCaptureBridge.diagnostics().getPublicationAttempts() == attempts,
                "ordinary draw does not retry cold publication");
            require(entity.isAwaitingCandidateData() && tesr.isAwaitingCandidateData(), "draws do not restart measurement");
            frame = epochs.nextFrame();
            runtime.beginFrame(frame, epochs.snapshot());
            require(runtime.isFrameInfrastructureActive(), "pending queue wakes dormant frame infrastructure");
            require(!ModelMeshCaptureBridge.hasPendingModelMeshes(), "frame-boundary publication drains capture");
            require(ModelMeshCaptureBridge.diagnostics().getPublicationAttempts() == attempts + 1,
                "one cold publication attempt");
            require(entity.lifecycleState() == BackendLifecycleState.PAIRED_MEASURE
                && tesr.lifecycleState() == BackendLifecycleState.PAIRED_MEASURE,
                "successful GPU publication restarts both paired model paths");
            require(entity.expectedMeasurementArm() == MeasurementArm.LEGACY && entity.snapshot().getMeasurementSamples() == 0,
                "resumed paired measurement starts from clean Legacy arm");
            LwjglModelMeshCache.Entry mesh = runtime.components.modelMeshes.find(displayList, runtime.currentStamp());
            require(mesh != null && runtime.components.modelMeshes.validate(mesh), "actual GPU bytes validate");
            prepareDrawState();
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glCallList(displayList);
            byte[] legacy = pixels();
            runtime.shaderRuntime.shaderPackStateKnown = true;
            runtime.shaderRuntime.shaderPackActive = false;
            for (OptimizationModule module : new OptimizationModule[] {
                OptimizationModule.MODERN_ENTITY_BACKEND, OptimizationModule.MODERN_TESR_BACKEND }) {
                AdaptiveBackendController backend = runtime.components.backends.get(module);
                SceneFingerprint scene = new SceneFingerprint(0, 0, 0, 0, 0, 0, 0, 20, 5, 8, SIZE, SIZE, 0, 1L, 1L);
                RenderPass pass = module == OptimizationModule.MODERN_TESR_BACKEND
                    ? RenderPass.TESR_PASS_0 : RenderPass.ENTITY_PASS_0;
                measureOriginalList(runtime, module, pass, scene, frame, displayList, legacy);
                require(backend.expectedMeasurementArm() == MeasurementArm.MODERN,
                    "real original-list CPU/GPU sample advances to Modern arm");
                ModernRendererRuntime.RenderBackendSample sample = new ModernRendererRuntime.RenderBackendSample(
                    module, BackendLifecycleState.PAIRED_MEASURE, MeasurementArm.MODERN, scene, null, null, 0L, frame);
                Object traversal = openTraversal(module, pass, sample);
                long[][] reasons = (long[][]) field(ModernRendererRuntime.class, "modelDrawReasons").get(runtime);
                int index = module == OptimizationModule.MODERN_TESR_BACKEND ? 1 : 0;
                int modernReason = modernDrawReason();
                long before = reasons[index][modernReason];
                try {
                    prepareDrawState();
                    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                    require(runtime.tryDrawModelMesh(displayList), "actual runtime model draw passes traversal/Modern gate");
                    byte[] modern = pixels();
                    require(Arrays.equals(legacy, modern), "Legacy and recovered runtime VBO pixels match");
                    require((modern[(SIZE / 2 * SIZE + SIZE / 2) * 4] & 255) != 0, "pixel comparison contains visible geometry");
                    require(reasons[index][modernReason] == before + 1L, "MODERN_DRAW diagnostic records real emission");
                    require(field(traversal.getClass(), "eligibleDraws").getInt(traversal) == 1
                        && field(traversal.getClass(), "modernDraws").getInt(traversal) == 1,
                        "traversal records one eligible and one Modern draw");
                    runtime.endRenderBackendSample(sample, true, true);
                } finally {
                    traversals().remove();
                }
            }
            // Exercise the actual call-list hook, including a stale traversal
            // sample after the final validation advances to paired measurement.
            for (OptimizationModule module : new OptimizationModule[] {
                OptimizationModule.MODERN_ENTITY_BACKEND, OptimizationModule.MODERN_TESR_BACKEND }) {
                AdaptiveBackendController backend = runtime.components.backends.get(module);
                field(AdaptiveBackendController.class, "state").set(backend, BackendLifecycleState.OUTPUT_VALIDATE);
                field(AdaptiveBackendController.class, "validationsSeen").setInt(backend,
                    field(AdaptiveBackendController.class, "validationFrames").getInt(backend) - 1);
                RenderPass pass = module == OptimizationModule.MODERN_TESR_BACKEND ? RenderPass.TESR_PASS_0 : RenderPass.ENTITY_PASS_0;
                ModernRendererRuntime.RenderBackendSample sample = new ModernRendererRuntime.RenderBackendSample(
                    module, BackendLifecycleState.OUTPUT_VALIDATE, MeasurementArm.LEGACY, null, null, null, 0, frame);
                openTraversal(module, pass, sample);
                long[][] reasons = (long[][]) field(ModernRendererRuntime.class, "modelDrawReasons").get(runtime);
                int modernReason = modernDrawReason();
                int index = module == OptimizationModule.MODERN_TESR_BACKEND ? 1 : 0;
                long before = reasons[index][modernReason];
                try {
                    require(RenderLibRenderBridge.isModelValidationTraversal(), "validation traversal visible to call-list hook");
                    prepareDrawState(); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                    ModelMeshCaptureBridge.callList(displayList);
                    require(backend.lifecycleState() == BackendLifecycleState.PAIRED_MEASURE, "real call-list validation advances controller");
                    require(Arrays.equals(legacy, pixels()), "validation draws original list");
                    prepareDrawState(); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                    ModelMeshCaptureBridge.callList(displayList);
                    require(Arrays.equals(legacy, pixels()), "stale validation sample keeps original list");
                    require(reasons[index][modernReason] == before, "validation hook never emits a modern duplicate");
                } finally { traversals().remove(); }
            }
            System.out.println("validation_callsite_progresses=true stale_validation_does_not_draw=true");
            runtime.endFrame();
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "no native GL errors");
            runtime.shutdown();
            CacheBudgetStatus status = budget.snapshot();
            require(status.getHeapUsed() == 0L && status.getDirectUsed() == 0L && status.getGpuUsed() == 0L,
                "recovered resources release all budgets");
            System.out.println("PASS model recovery: cold capture, dormant frame wake, one publication, entity/TESR clean paired restart, no draw retry, real runtime traversal/Modern arm gates, MODERN_DRAW counted once, Legacy pixels equal, no budget leaks.");
            System.out.println("renderer=" + GL11.glGetString(GL11.GL_RENDERER));
        } finally {
            if (runtime != null) runtime.shutdown();
            ModelMeshCaptureBridge.reset();
            traversals().remove();
            rendererField.set(ClientOptimizerRuntime.INSTANCE, previousRenderer);
            bridgeField.set(null, previousBridge);
            if (displayList > 0) GL11.glDeleteLists(displayList, 1);
            context.destroy();
        }
    }

    private static void awaitData(AdaptiveBackendController backend) {
        while (backend.lifecycleState() == BackendLifecycleState.WARMUP) backend.warmupFrame(true);
        while (backend.lifecycleState() == BackendLifecycleState.OUTPUT_VALIDATE) backend.validationResult(true, "probe baseline equivalent");
        require(backend.lifecycleState() == BackendLifecycleState.PAIRED_MEASURE, "model capability self-test passed");
        backend.prepareProfiledWorkload(2, 1L);
        SceneFingerprint scene = new SceneFingerprint(0, 0, 0, 0, 0, 0, 0, 20, 5, 8, SIZE, SIZE, 0, 1L, 1L);
        backend.recordMeasurement(scene, MeasurementArm.LEGACY, 100L, true);
        for (long frame = 2L; frame < 10L; frame++) backend.recordEmptyModernArm(MeasurementArm.MODERN, frame);
        require(backend.isAwaitingCandidateData() && backend.isDormant(1000L), "empty modern arm sleeps awaiting capture");
    }

    private static void prepareDrawState() {
        GL11.glViewport(0, 0, SIZE, SIZE);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glColor4f(1, 1, 1, 1);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        EarlyGlStateTracker.useProgram(0);
        EarlyGlStateTracker.bindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, 0);
        EarlyGlStateTracker.depthFunction(GL11.GL_LESS);
        EarlyGlStateTracker.bindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, 0);
        EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        EarlyGlStateTracker.clientActiveTexture(GL13.GL_TEXTURE0);
        EarlyGlStateTracker.seedDrawState(0, 0, 0, false, GL11.GL_ONE, GL11.GL_ZERO,
            GL11.GL_ONE, GL11.GL_ZERO, false, true, false, 15, 1, 1, 1, 1);
        float[] identity = { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 };
        FloatBuffer matrix = BufferUtils.createFloatBuffer(16);
        matrix.put(identity).flip();
        EarlyMatrixStateTracker.seed(GL11.GL_MODELVIEW, matrix, matrix);
    }

    private static double[][] corners() { return new double[][] { { -.5D, -.5D }, { .5D, -.5D }, { .5D, .5D }, { -.5D, .5D } }; }

    @SuppressWarnings("unchecked")
    private static void measureOriginalList(ModernRendererRuntime runtime, OptimizationModule module,
        RenderPass pass, SceneFingerprint scene, long frame, int displayList, byte[] expected) throws Exception {
        RenderMeasurementRuntime measurements = (RenderMeasurementRuntime)
            field(ModernRendererRuntime.class, "measurements").get(runtime);
        dev.rlcraft.ice.optimizer.render.telemetry.RenderProfileKey key =
            new dev.rlcraft.ice.optimizer.render.telemetry.RenderProfileKey(runtime.currentStamp(), pass,
                dev.rlcraft.ice.optimizer.render.backend.RenderBackendId.LEGACY);
        Class<?> pendingType = Class.forName(RenderMeasurementRuntime.class.getName() + "$BackendGpuSample");
        java.lang.reflect.Constructor<?> constructor = pendingType.getDeclaredConstructor(
            OptimizationModule.class, SceneFingerprint.class, MeasurementArm.class);
        constructor.setAccessible(true);
        ((Map) measurements.backendGpuSamples).put(key, constructor.newInstance(module, scene, MeasurementArm.LEGACY));
        prepareDrawState(); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        dev.rlcraft.ice.optimizer.render.telemetry.CorrelatedRenderProfiler.GpuScope gpu =
            runtime.components.profiler.beginGpu(runtime.currentStamp(), pass,
                dev.rlcraft.ice.optimizer.render.backend.RenderBackendId.LEGACY, measurements.backendGpuCompletion);
        require(gpu != null, "real timer query allocated for Legacy model arm");
        ModernRendererRuntime.RenderBackendSample sample = new ModernRendererRuntime.RenderBackendSample(
            module, BackendLifecycleState.PAIRED_MEASURE, MeasurementArm.LEGACY, scene, key, gpu,
            System.nanoTime(), frame);
        Object traversal = openTraversal(module, pass, sample);
        try {
            ModelMeshCaptureBridge.callList(displayList);
            int eligible = field(traversal.getClass(), "eligibleDraws").getInt(traversal);
            require(eligible == 1 && field(traversal.getClass(), "modernDraws").getInt(traversal) == 0,
                "original shortcut contributes one real Legacy draw and no Modern draw");
            runtime.endRenderBackendSample(sample, eligible > 0, false);
            GL11.glFinish(); runtime.components.profiler.pollGpu(256);
            require(runtime.components.backends.get(module).snapshot().getMeasurementSamples() == 1,
                "completed native timer sample accepted by ABBA controller");
            require(Arrays.equals(expected, pixels()), "measured Legacy list preserves pixels");
        } finally { traversals().remove(); }
    }

    private static byte[] pixels() {
        ByteBuffer buffer = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        byte[] pixels = new byte[buffer.capacity()];
        buffer.get(pixels);
        return pixels;
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static ThreadLocal<?> traversals() throws Exception {
        return (ThreadLocal<?>) field(RenderLibRenderBridge.class, "TRAVERSALS").get(null);
    }

    private static Object openTraversal(OptimizationModule module, RenderPass pass,
                                       ModernRendererRuntime.RenderBackendSample sample) throws Exception {
        traversals().remove();
        Object stack = traversals().get();
        Object traversal = ((Object[]) field(stack.getClass(), "values").get(stack))[0];
        field(stack.getClass(), "size").setInt(stack, 1);
        field(traversal.getClass(), "module").set(traversal, module);
        field(traversal.getClass(), "objectPass").set(traversal, pass);
        field(traversal.getClass(), "objectActive").setBoolean(traversal, true);
        field(traversal.getClass(), "objectCandidateAllowed").setBoolean(traversal, true);
        field(traversal.getClass(), "sample").set(traversal, sample);
        return traversal;
    }

    private static int modernDrawReason() throws Exception {
        Class<?> type = Class.forName(ModernRendererRuntime.class.getName() + "$ModelDrawReason");
        for (Object value : type.getEnumConstants()) {
            if ("MODERN_DRAW".equals(value.toString())) return ((Enum<?>) value).ordinal();
        }
        throw new AssertionError("MODERN_DRAW diagnostic unavailable");
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class OwnedList {
        private final int displayList;
        private OwnedList(int displayList) { this.displayList = displayList; }
    }
}
