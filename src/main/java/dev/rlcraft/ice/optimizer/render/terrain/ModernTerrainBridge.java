package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime;
import dev.rlcraft.ice.optimizer.compat.optifine.OptifinePassLifecycleBridge;
import dev.rlcraft.ice.optimizer.render.frame.RenderPass;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.BlockRenderLayer;

/** Fail-open entry points used by early transformed Minecraft classes. */
public final class ModernTerrainBridge {
    private ModernTerrainBridge() {
    }

    public static boolean tryUpload(TerrainUploadContext.Value context,
                                    BufferBuilder builder, VertexBuffer vertexBuffer) {
        if (context == null || builder == null || vertexBuffer == null) return false;
        ModernRendererRuntime renderer = ClientOptimizerRuntime.INSTANCE.modernRenderer();
        if (renderer == null) return false;
        try {
            return renderer.tryUploadTerrain(context, builder, vertexBuffer);
        } catch (Throwable error) {
            Throwable fatal = FatalErrors.findFatal(error);
            if (fatal == null) {
                OptimizerRegistry.breaker(OptimizationModule.MODERN_TERRAIN_BACKEND)
                    .recordFailure(error);
            }
            try { renderer.releaseTerrain(vertexBuffer); }
            catch (Throwable releaseError) {
                if (releaseError != error) error.addSuppressed(releaseError);
                Throwable releaseFatal = FatalErrors.findFatal(releaseError);
                if (releaseFatal != null && fatal == null) fatal = releaseFatal;
                else OptimizerRegistry.breaker(
                        OptimizationModule.MODERN_TERRAIN_BACKEND)
                        .recordFailure(releaseError);
            }
            FatalErrors.rethrowIfFatal(fatal);
            return false;
        }
    }

    public static void beforeLegacyUpload(VertexBuffer vertexBuffer) {
        if (vertexBuffer == null) return;
        ModernRendererRuntime renderer = ClientOptimizerRuntime.INSTANCE.modernRenderer();
        if (renderer != null) {
            renderer.beforeTerrainLegacyUpload(vertexBuffer);
        }
    }

    public static boolean tryRender(Object container, BlockRenderLayer layer) {
        if (container == null || layer == null) return false;
        ModernRendererRuntime renderer = ClientOptimizerRuntime.INSTANCE.modernRenderer();
        if (renderer == null) return false;
        try {
            return renderer.tryRenderTerrain(container, layer);
        } catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            OptimizerRegistry.breaker(OptimizationModule.MODERN_TERRAIN_BACKEND)
                .recordFailure(error);
            try { return renderer.tryRenderTerrainFallback(container, layer); }
            catch (Throwable fallbackError) {
                FatalErrors.rethrowIfFatal(fallbackError);
                OptimizerRegistry.breaker(OptimizationModule.MODERN_TERRAIN_BACKEND)
                    .recordFailure(fallbackError);
                try { return renderer.ownsTerrain(container, layer); }
                catch (Throwable ownershipError) {
                    FatalErrors.rethrowIfFatal(ownershipError);
                    if (ownershipError != fallbackError) {
                        fallbackError.addSuppressed(ownershipError);
                    }
                    OptimizerRegistry.breaker(
                        OptimizationModule.MODERN_TERRAIN_BACKEND)
                        .recordFailure(ownershipError);
                    // Both modern and unbatched arena fallbacks are now
                    // outcome-uncertain. Claim the call so the transformed
                    // site cannot duplicate a driver-accepted draw.
                    return true;
                }
            }
        }
    }

    /** Brackets both the native and untouched VboRenderList emitters. */
    public static long beginRender(BlockRenderLayer layer) {
        if (layer == null) return 0L;
        ModernRendererRuntime renderer = ClientOptimizerRuntime.INSTANCE.modernRenderer();
        if (renderer == null) return 0L;
        try {
            return renderer.beginObservedPass(pass(layer),
                OptimizationModule.MODERN_TERRAIN_BACKEND);
        } catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            OptimizerRegistry.breaker(
                OptimizationModule.MODERN_FRAME_COORDINATOR)
                .recordFailure(error);
            return 0L;
        }
    }

    public static void endRender(long token) {
        if (token == 0L) return;
        ModernRendererRuntime renderer = ClientOptimizerRuntime.INSTANCE.modernRenderer();
        if (renderer == null) return;
        try { renderer.endObservedPass(token); }
        catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            OptimizerRegistry.breaker(
                OptimizationModule.MODERN_FRAME_COORDINATOR)
                .recordFailure(error);
        }
    }

    public static void afterRender(Object container, BlockRenderLayer layer) {
        if (container == null || layer == null) return;
        ModernRendererRuntime renderer = ClientOptimizerRuntime.INSTANCE.modernRenderer();
        if (renderer == null) return;
        try { renderer.afterLegacyTerrainLayer(container, layer); }
        catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            OptimizerRegistry.breaker(OptimizationModule.MODERN_VISIBILITY_HZB)
                .recordFailure(error);
        }
    }

    private static RenderPass pass(BlockRenderLayer layer) {
        if (OptifinePassLifecycleBridge.isShadowPass()) {
            return RenderPass.SHADOW_TERRAIN;
        }
        if (layer == BlockRenderLayer.TRANSLUCENT) return RenderPass.TRANSLUCENT;
        if (layer == BlockRenderLayer.CUTOUT_MIPPED) {
            return RenderPass.MAIN_CUTOUT_MIPPED;
        }
        if (layer == BlockRenderLayer.CUTOUT) return RenderPass.MAIN_CUTOUT;
        return RenderPass.MAIN_SOLID;
    }
}
