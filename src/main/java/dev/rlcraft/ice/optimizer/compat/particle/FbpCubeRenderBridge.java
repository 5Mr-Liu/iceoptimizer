package dev.rlcraft.ice.optimizer.compat.particle;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.hud.HudRenderBridge;
import dev.rlcraft.ice.optimizer.compat.texture.AnimatedTextureVisibilityBridge;
import dev.rlcraft.ice.optimizer.render.particle.LwjglFbpCubeRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;

/** Actual Digging cube helper, independent of renderer qualification/dormancy. */
public final class FbpCubeRenderBridge {
    private FbpCubeRenderBridge() { }

    public static boolean isEnabled() {
        return OptimizerRegistry.isOperational(OptimizationModule.FBP_CUBE_SUBMISSION);
    }

    public static void drawBoundary(Tessellator tessellator) {
        // A previously deferred vanilla billboard run precedes this cube.
        ParticleRenderBridge.fbpBoundary();
        tessellator.draw();
    }

    public static void drawCube(Tessellator tessellator) {
        ModernRendererRuntime runtime = ClientOptimizerRuntime.INSTANCE.modernRenderer();
        BufferBuilder buffer = tessellator.getBuffer();
        if (runtime != null && runtime.isShaderPackSafeForNativeVertexFormats()
            && LwjglFbpCubeRenderer.isExactFormat(buffer.getVertexFormat())
            && LwjglFbpCubeRenderer.isSingleTexture(buffer)) {
            // Preserve the two installed Tessellator observers before its
            // finishDrawing/reset boundary. Either observer can invalidate
            // the tracked bindings, so authenticate after both callbacks.
            HudRenderBridge.barrier();
            AnimatedTextureVisibilityBridge.bufferDraw(tessellator);
            if (EarlyGlStateTracker.isFixedFunctionClientArraySubmissionSafe()) {
                LwjglFbpCubeRenderer.draw(buffer);
                return;
            }
        }
        tessellator.draw();
    }
}
