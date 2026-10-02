package dev.rlcraft.ice.optimizer.client;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.memory.CacheBudgetStatus;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.lang.reflect.Method;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.Pbuffer;
import org.lwjgl.opengl.PixelFormat;

/** Exercises production component construction, invalidation and cleanup in a hidden GL context. */
public final class RendererLifecycleProbe {
    public static void main(String[] arguments) throws Exception {
        Pbuffer context = new Pbuffer(64, 64, new PixelFormat().withDepthBits(24), null, null);
        try {
            context.makeCurrent();
            // Minecraft sets these during its OpenGlHelper startup. This
            // standalone fixture runs without Minecraft's main constructor.
            OpenGlHelper.defaultTexUnit = GL13.GL_TEXTURE0;
            OpenGlHelper.lightmapTexUnit = GL13.GL_TEXTURE1;
            System.out.println("GL renderer: " + GL11.glGetString(GL11.GL_RENDERER));
            OptimizerRegistry.breaker(OptimizationModule.MODERN_FRAME_COORDINATOR).configure(true, 3);
            OptimizerRegistry.breaker(OptimizationModule.LEGACY_GL_ISLAND).configure(true, 3);
            ClientEpochs epochs = new ClientEpochs();
            CacheBudget budget = new CacheBudget(256L << 20, 192L << 20, 256L << 20);
            ModernRendererRuntime runtime = new ModernRendererRuntime(epochs, budget);
            try {
                invoke(runtime, "ensureInitialized");
                require(runtime.initialized, "initialization: " + runtime.detail);
                RendererComponents first = runtime.components;
                epochs.invalidateWorld();
                runtime.invalidateWorld();
                invoke(runtime, "applyPendingInvalidations");
                require(runtime.initialized && runtime.components == first, "world change retains graph");
                epochs.invalidateResources();
                runtime.invalidateResources();
                invoke(runtime, "applyPendingInvalidations");
                require(runtime.initialized && runtime.components == first, "resource reload retains graph");
                long previousContext = epochs.currentGlContextGeneration();
                epochs.invalidateGlContext();
                runtime.invalidateContext(previousContext);
                invoke(runtime, "applyPendingInvalidations");
                require(!runtime.initialized && runtime.components != first, "context loss detaches graph");
                require(runtime.components.contextGeneration == 0L, "context ownership cleared");
                require(first.resources.snapshot().getLive() == 0, "lost context resource names abandoned");
                requireEmpty(budget, "context abandonment");
                invoke(runtime, "ensureInitialized");
                require(runtime.initialized, "reinitialization: " + runtime.detail);
                RendererComponents second = runtime.components;
                require(second != first, "new generation has a new owner");
                require(second.contextGeneration == epochs.currentGlContextGeneration(), "new context generation");
                runtime.shutdown();
                require(!runtime.initialized && runtime.components != second, "shutdown detaches graph");
                require(second.resources.snapshot().getLive() == 0, "shutdown destroys resource names");
                requireEmpty(budget, "shutdown");
                runtime.shutdown();
                requireEmpty(budget, "repeated shutdown");
                System.out.println("PASS: initialization, world change, resource reload, context abandonment, reinitialization, shutdown, repeated shutdown; no budget leaks.");
            } finally {
                runtime.shutdown();
            }
            CacheBudget constrained = new CacheBudget(1L, 1L, 1L);
            ModernRendererRuntime failing = new ModernRendererRuntime(new ClientEpochs(), constrained);
            try {
                invoke(failing, "ensureInitialized");
                require(!failing.initialized, "constrained initialization fails without publishing");
                require(failing.components.profiler == null, "failed graph detached");
                requireEmpty(constrained, "partial construction cleanup");
                System.out.println("PASS: partial construction failure cleanup; no budget leaks.");
            } finally {
                failing.shutdown();
            }
        } finally {
            context.destroy();
        }
    }

    private static void invoke(ModernRendererRuntime runtime, String name) throws Exception {
        Method method = ModernRendererRuntime.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(runtime);
    }

    private static void requireEmpty(CacheBudget budget, String operation) {
        CacheBudgetStatus status = budget.snapshot();
        require(status.getHeapUsed() == 0L && status.getDirectUsed() == 0L
            && status.getGpuUsed() == 0L, operation + " leaves budgets: "
                + status.getHeapUsed() + "/" + status.getDirectUsed() + "/" + status.getGpuUsed());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
