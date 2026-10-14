package dev.rlcraft.ice.optimizer.compat.model;

import static org.junit.Assert.*;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.bridge.ClientRuntimeAccess;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.entity.ModelMeshPayload;
import dev.rlcraft.ice.optimizer.render.frame.FrameCoordinator;
import dev.rlcraft.ice.optimizer.render.frame.PassGraph;
import dev.rlcraft.ice.optimizer.render.legacy.GlStateMirror;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

public final class ModelMeshPauseTest {
    private ClientRuntimeAccess previousRuntime;
    private ModernRendererRuntime previousRenderer;
    private boolean previousPause;
    private ClientEpochs epochs;
    private ModernRendererRuntime renderer;
    private FrameCoordinator coordinator;

    @Before public void setup() throws Exception {
        previousRuntime = (ClientRuntimeAccess) field(OptimizerBridge.class, "clientRuntime").get(null);
        previousPause = OptimizerRegistry.isRuntimePaused();
        Field rendererField = field(ClientOptimizerRuntime.class, "modernRenderer");
        previousRenderer = (ModernRendererRuntime) rendererField.get(ClientOptimizerRuntime.INSTANCE);
        epochs = new ClientEpochs();
        Constructor<ModernRendererRuntime> constructor = ModernRendererRuntime.class
            .getDeclaredConstructor(ClientEpochs.class, CacheBudget.class);
        constructor.setAccessible(true);
        renderer = constructor.newInstance(epochs,
            new CacheBudget(1024L, 1024L, 1024L));
        field(ModernRendererRuntime.class, "initialized").setBoolean(renderer, true);
        Object components = field(ModernRendererRuntime.class, "components").get(renderer);
        RenderThreadGuard threadGuard = RenderThreadGuard.captureCurrent();
        field(components.getClass(), "threadGuard").set(components, threadGuard);
        coordinator = new FrameCoordinator(threadGuard, PassGraph.standard(),
            new GlStateMirror(2), reason -> { });
        field(components.getClass(), "coordinator").set(components, coordinator);
        openFrame();
        rendererField.set(ClientOptimizerRuntime.INSTANCE, renderer);
        OptimizerRegistry.breaker(OptimizationModule.MODERN_ENTITY_BACKEND).configure(true, 3);
        OptimizerRegistry.breaker(OptimizationModule.MODERN_TESR_BACKEND).configure(false, 3);
        OptimizerRegistry.setRuntimePaused(true);
        assertTrue(renderer.setRuntimePaused(true));
        assertFalse(coordinator.isFrameActive());
        assertEquals(-1L, renderer.modelMeshAdmissionEpoch());
        OptimizerBridge.attachClientRuntime(new ClientRuntimeAccess() {
            public long currentFrameId() { return 1L; }
            public long currentClientTickId() { return 1L; }
            public long currentWorldGeneration() { return 1L; }
            public long currentResourceGeneration() { return 1L; }
            public long currentGlContextGeneration() { return 1L; }
        });
        ModelMeshCaptureBridge.resetForTest();
    }

    @After public void cleanup() throws Exception {
        ModelMeshCaptureBridge.resetForTest();
        OptimizerBridge.attachClientRuntime(previousRuntime);
        field(ClientOptimizerRuntime.class, "modernRenderer").set(ClientOptimizerRuntime.INSTANCE, previousRenderer);
        OptimizerRegistry.breaker(OptimizationModule.MODERN_ENTITY_BACKEND).configure(false, 3);
        OptimizerRegistry.breaker(OptimizationModule.MODERN_TESR_BACKEND).configure(false, 3);
        OptimizerRegistry.setRuntimePaused(previousPause);
    }

    @Test public void pausedCompilationSurvivesLegacyDrawAndRemainsReadyAfterResume() throws Exception {
        compile(new Owner(71));
        assertFalse(OptimizerRegistry.isOperational(OptimizationModule.MODERN_ENTITY_BACKEND));
        assertEquals(1, ModelMeshCaptureBridge.diagnostics().getPendingMeshes());
        Object pending = pending().get(Integer.valueOf(71));
        long attempts = ModelMeshCaptureBridge.diagnostics().getPublicationAttempts();
        assertEquals(0, ModelMeshCaptureBridge.drainPendingModelMeshes(16, 65536L));
        assertEquals(attempts, ModelMeshCaptureBridge.diagnostics().getPublicationAttempts());

        // Execute the actual callList bytecode with its native draw replaced by
        // a counter. A pause must return before inspecting batch state or Pending.
        NativeList.calls = 0;
        pausedCallList().getMethod("callList", int.class).invoke(null, 71);
        assertEquals(1, NativeList.calls);
        assertEquals(71, NativeList.last);
        assertSame(pending, pending().get(Integer.valueOf(71)));

        assertTrue(renderer.setRuntimePaused(false));
        OptimizerRegistry.setRuntimePaused(false);
        openFrame();
        assertTrue(ModelMeshCaptureBridge.isPending(71));
        ModelMeshPayload payload = (ModelMeshPayload) field(pending.getClass(), "payload").get(pending);
        assertEquals(71, payload.getDisplayList());
        assertEquals(4, payload.getVertexCount());
        assertEquals(96, payload.getByteLength());
        ModelMeshCaptureBridge.drainPendingModelMeshes(16, 65536L);
        assertEquals(attempts + 1L, ModelMeshCaptureBridge.diagnostics().getPublicationAttempts());
        // This fixture deliberately has no GL cache; failed publication keeps
        // the same bounded payload available for a later frame.
        assertSame(pending, pending().get(Integer.valueOf(71)));
    }

    @Test public void pauseDoesNotCaptureConfiguredOffOrIncompatibleModules() {
        OptimizerRegistry.breaker(OptimizationModule.MODERN_ENTITY_BACKEND).configure(false, 3);
        compile(new Owner(72));
        assertFalse(ModelMeshCaptureBridge.hasPendingModelMeshes());
        OptimizerRegistry.breaker(OptimizationModule.MODERN_ENTITY_BACKEND).configure(true, 3);
        OptimizerRegistry.breaker(OptimizationModule.MODERN_ENTITY_BACKEND).forceIncompatible("fixture");
        compile(new Owner(73));
        assertFalse(ModelMeshCaptureBridge.hasPendingModelMeshes());
        OptimizerRegistry.breaker(OptimizationModule.MODERN_TESR_BACKEND).configure(true, 3);
        compile(new Owner(74));
        assertEquals(1, ModelMeshCaptureBridge.diagnostics().getPendingMeshes());
    }

    @Test public void cancelledAndEmptyRecompilationsStillInvalidatePendingWhilePaused() {
        Owner owner = new Owner(75);
        compile(owner);
        ModelMeshCaptureBridge.begin(owner);
        ModelMeshCaptureBridge.cancel(owner);
        assertFalse(ModelMeshCaptureBridge.hasPendingModelMeshes());
        assertEquals(0L, ModelMeshCaptureBridge.diagnostics().getPendingBytes());
        compile(owner);
        ModelMeshCaptureBridge.begin(owner);
        ModelMeshCaptureBridge.finish(owner);
        assertFalse(ModelMeshCaptureBridge.hasPendingModelMeshes());
        assertEquals(0, ModelMeshCaptureBridge.captureDepthForTest());
    }

    private static void compile(Owner owner) {
        BufferBuilder builder = new BufferBuilder(256);
        builder.begin(7, DefaultVertexFormats.POSITION_TEX_NORMAL);
        for (int i = 0; i < 4; i++) {
            builder.pos(i & 1, i >> 1, 0).tex(i & 1, i >> 1).normal(0, 0, 1).endVertex();
        }
        ModelMeshCaptureBridge.begin(owner);
        ModelMeshCaptureBridge.captureQuad(builder);
        ModelMeshCaptureBridge.finish(owner);
    }

    private void openFrame() throws Exception {
        coordinator.beginFrame(epochs.nextFrame(), epochs.snapshot());
        field(ModernRendererRuntime.class, "currentStamp").set(renderer,
            coordinator.beginPrimaryView());
        field(ModernRendererRuntime.class, "frameInfrastructureActive").setBoolean(renderer, true);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field value = owner.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }

    private static Map<?, ?> pending() throws Exception {
        return (Map<?, ?>) field(ModelMeshCaptureBridge.class, "PENDING").get(null);
    }

    private static Class<?> pausedCallList() throws Exception {
        ClassNode source = new ClassNode(Opcodes.ASM5);
        try (InputStream input = ModelMeshCaptureBridge.class.getResourceAsStream("ModelMeshCaptureBridge.class")) {
            new ClassReader(input).accept(source, 0);
        }
        String name = "dev/rlcraft/ice/optimizer/compat/model/PausedModelCallListFixture";
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        Map<String, String> remap = new HashMap<String, String>();
        remap.put("net/minecraft/client/renderer/GlStateManager", Type.getInternalName(NativeList.class));
        remap.put(Type.getInternalName(ModelStateBatchBridge.class), Type.getInternalName(ForbiddenBatch.class));
        for (MethodNode method : source.methods) if (method.name.equals("callList")) {
            method.accept(new MethodRemapper(writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                method.name, method.desc, null, null), new SimpleRemapper(remap)));
        }
        writer.visitEnd();
        final byte[] bytes = writer.toByteArray();
        return new ClassLoader(ModelMeshPauseTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(name.replace('/', '.'), bytes, 0, bytes.length); }
        }.define();
    }

    public static final class NativeList {
        static int calls, last;
        public static void callList(int value) { calls++; last = value; }
    }

    public static final class ForbiddenBatch {
        public static boolean draw(int value) { throw new AssertionError("paused model batch inspected"); }
    }

    public static final class Owner {
        public final int displayList;
        Owner(int displayList) { this.displayList = displayList; }
    }
}
