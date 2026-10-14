package dev.rlcraft.ice.optimizer.compat.model;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.bridge.ClientRuntimeAccess;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.entity.LwjglModelMeshCache;
import dev.rlcraft.ice.optimizer.render.entity.ModelMeshPayload;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.*;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import org.lwjgl.opengl.*;

/** Replays the real pending-upload pipeline under the recorded 3378-item starvation. */
public final class ModelAdmissionProbe {
    public static void main(String[] args) throws Exception {
        Pbuffer context = new Pbuffer(32, 32, new PixelFormat(), null, null);
        try {
            context.makeCurrent();
            exercise();
        } finally { context.destroy(); }
    }

    private static void exercise() throws Exception {
        CacheBudget budget = new CacheBudget(16 << 20, 4 << 20, 1 << 20);
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger ledger = new ResourceLedger(guard, budget, new ResourceLedger.Destroyer() {
            public void destroy(RenderResourceKind kind, int id) {
                if (kind == RenderResourceKind.BUFFER) GL15.glDeleteBuffers(id);
                else if (kind == RenderResourceKind.VERTEX_ARRAY) GL30.glDeleteVertexArrays(id);
                else if (kind == RenderResourceKind.PROGRAM) GL20.glDeleteProgram(id);
                else if (kind == RenderResourceKind.SHADER) GL20.glDeleteShader(id);
            }
        }, 128);
        LwjglModelMeshCache cache = new LwjglModelMeshCache(guard, ledger, budget);
        ClientEpochs epochs = new ClientEpochs(); epochs.nextFrame();
        FrameStamp stamp = new FrameStamp(1, 1, epochs.snapshot());
        Constructor<ModernRendererRuntime> constructor = ModernRendererRuntime.class
            .getDeclaredConstructor(ClientEpochs.class, CacheBudget.class);
        constructor.setAccessible(true);
        ModernRendererRuntime runtime = constructor.newInstance(epochs, budget);
        field(ModernRendererRuntime.class, "initialized").setBoolean(runtime, true);
        field(ModernRendererRuntime.class, "currentStamp").set(runtime, stamp);
        Object components = field(ModernRendererRuntime.class, "components").get(runtime);
        field(components.getClass(), "threadGuard").set(components, guard);
        field(components.getClass(), "modelMeshes").set(components, cache);
        field(ClientOptimizerRuntime.class, "modernRenderer").set(ClientOptimizerRuntime.INSTANCE, runtime);
        OptimizerRegistry.breaker(OptimizationModule.MODERN_ENTITY_BACKEND).configure(true, 3);
        OptimizerRegistry.setRuntimePaused(false);
        OptimizerBridge.attachClientRuntime(new ClientRuntimeAccess() {
            public long currentFrameId() { return 1; }
            public long currentClientTickId() { return 1; }
            public long currentWorldGeneration() { return 1; }
            public long currentResourceGeneration() { return 1; }
            public long currentGlContextGeneration() { return 1; }
        });
        ModelMeshCaptureBridge.resetForTest();
        int occupiedBuffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, occupiedBuffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, 1 << 20, GL15.GL_STATIC_DRAW);
        RenderHandle occupied = ledger.register(RenderResourceKind.BUFFER, occupiedBuffer, 1 << 20, stamp);
        if (occupied == null) throw new AssertionError("occupy budget");
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        Class<?> pendingType = Class.forName(ModelMeshCaptureBridge.class.getName() + "$PendingModelMesh");
        Constructor<?> pendingConstructor = pendingType.getDeclaredConstructor(int.class, byte[].class,
            VertexFormat.class, int.class, int.class, ContextCapabilities.class, ModelMeshPayload.class);
        pendingConstructor.setAccessible(true);
        Method put = ModelMeshCaptureBridge.class.getDeclaredMethod("putPending", pendingType); put.setAccessible(true);
        for (int i = 0; i < 3378; i++) {
            ModelMeshPayload payload = new ModelMeshPayload(i + 1, new byte[96],
                DefaultVertexFormats.POSITION_TEX_NORMAL, GL11.GL_QUADS, 4, 1, 1);
            put.invoke(null, pendingConstructor.newInstance(i + 1, null, null, GL11.GL_QUADS, 4, null, payload));
        }
        long attempts = ModelMeshCaptureBridge.diagnostics().getPublicationAttempts();
        int frames = 0;
        while (ModelMeshCaptureBridge.hasReadyModelMeshes(runtime.modelMeshAdmissionProgress())) {
            long examined = ModelMeshCaptureBridge.diagnostics().getPublicationDrainExamined();
            if (ModelMeshCaptureBridge.drainPendingModelMeshes(48, 2 << 20) != 0) throw new AssertionError("full budget uploaded");
            if (ModelMeshCaptureBridge.diagnostics().getPublicationDrainExamined() - examined > 48)
                throw new AssertionError("unbounded scan");
            if (++frames > 72) throw new AssertionError("failed entries revisited without progress");
        }
        if (ModelMeshCaptureBridge.diagnostics().getPublicationAttempts() - attempts != 3378)
            throw new AssertionError("payloads did not sleep after first capacity failure");
        long failed = ModelMeshCaptureBridge.diagnostics().getPublicationAttempts();
        long scanned = ModelMeshCaptureBridge.diagnostics().getPublicationDrainExamined();
        for (int i = 0; i < 10000; i++) if (ModelMeshCaptureBridge.hasReadyModelMeshes(runtime.modelMeshAdmissionProgress()))
            throw new AssertionError("frame count woke resource waiting queue");
        if (ModelMeshCaptureBridge.diagnostics().getPublicationAttempts() != failed
            || ModelMeshCaptureBridge.diagnostics().getPublicationDrainExamined() != scanned)
            throw new AssertionError("idle retry work");
        if (GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING) != 0 || GL11.glGetError() != GL11.GL_NO_ERROR)
            throw new AssertionError("capacity failure changed native state");
        // The occupied payload was never drawn; its ledger deletion is actual
        // storage release and is the only event that wakes the old queue.
        ledger.retire(occupied, null); ledger.collect(1, 128);
        if (!ModelMeshCaptureBridge.hasReadyModelMeshes(runtime.modelMeshAdmissionProgress()))
            throw new AssertionError("real release failed to wake queue");
        int restored = 0;
        while (ModelMeshCaptureBridge.hasReadyModelMeshes(runtime.modelMeshAdmissionProgress())) {
            EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING));
            restored += ModelMeshCaptureBridge.drainPendingModelMeshes(48, 2 << 20);
        }
        if (restored != 3378 || cache.size() != 3378 || ModelMeshCaptureBridge.hasPendingModelMeshes())
            throw new AssertionError("pending recovery " + restored + "/" + cache.size());
        if (GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING) != 0 || GL11.glGetError() != GL11.GL_NO_ERROR)
            throw new AssertionError("successful upload did not restore native binding");
        cache.close(true); GL11.glFinish(); ledger.destroyAll(1); ModelMeshCaptureBridge.resetForTest();
        if (budget.snapshot().getGpuUsed() != 0 || budget.snapshot().getDirectUsed() != 0 || budget.snapshot().getHeapUsed() != 0)
            throw new AssertionError("resource leak");
        System.out.println("model_pending_native PASS pending=3378 first_failures=3378 bounded_drain_frames=" + frames
            + " idle_frames=10000 idle_attempts=0 idle_examined=0 release_recovered=3378 binding_restored=true budget_leaks=0");
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field value = owner.getDeclaredField(name); value.setAccessible(true); return value;
    }
}
