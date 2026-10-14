package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime;
import dev.rlcraft.ice.optimizer.compat.optifine.OptifinePassLifecycleBridge;
import dev.rlcraft.ice.optimizer.render.frame.RenderPass;
import dev.rlcraft.ice.optimizer.render.visibility.CurrentFrameTerrainOcclusion;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.BlockRenderLayer;

/** Fail-open entry points used by early transformed Minecraft classes. */
public final class ModernTerrainBridge {
    private ModernTerrainBridge() {
    }

    /** Prepare immutable coverage while the dispatcher still owns the finished builder. */
    public static void prepareUpload(BlockRenderLayer layer, BufferBuilder builder,
                                     RenderChunk chunk, CompiledChunk compiled) {
        ModernRendererRuntime renderer = ClientOptimizerRuntime.INSTANCE.modernRenderer();
        if (renderer == null) return;
        CurrentFrameTerrainOcclusion software = renderer.terrainSoftwareOcclusion();
        if (software == null) return;
        try { software.prepare(layer, builder, chunk, compiled); }
        catch (Throwable failure) { FatalErrors.rethrowIfFatal(failure); }
    }

    public static boolean tryUpload(TerrainUploadContext.Value context,
                                    BufferBuilder builder, VertexBuffer vertexBuffer) {
        if (context == null || builder == null || vertexBuffer == null) return false;
        ModernRendererRuntime renderer = ClientOptimizerRuntime.INSTANCE.modernRenderer();
        if (renderer == null) return false;
        CurrentFrameTerrainOcclusion software = renderer.terrainSoftwareOcclusion();
        if (software != null) {
            try { software.capture(context, builder, vertexBuffer); }
            catch (Throwable failure) {
                software.release(vertexBuffer);
                FatalErrors.rethrowIfFatal(failure);
            }
        }
        if (!renderer.shouldObserveTerrain()) return false;
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
            CurrentFrameTerrainOcclusion software = renderer.terrainSoftwareOcclusion();
            if (software != null) software.legacyUploadStarting(vertexBuffer);
            renderer.beforeTerrainLegacyUpload(vertexBuffer);
        }
    }

    /** Successful return from every dispatcher upload path, including untouched legacy uploads. */
    public static void afterUpload(VertexBuffer vertexBuffer) {
        ModernRendererRuntime renderer = ClientOptimizerRuntime.INSTANCE.modernRenderer();
        if (renderer == null) return;
        CurrentFrameTerrainOcclusion software = renderer.terrainSoftwareOcclusion();
        if (software == null) return;
        try { software.uploaded(vertexBuffer); }
        catch (Throwable failure) {
            software.release(vertexBuffer);
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    /** Failed uploads must never publish the candidate captured before native submission. */
    public static void failedUpload(VertexBuffer vertexBuffer) {
        ModernRendererRuntime renderer = ClientOptimizerRuntime.INSTANCE.modernRenderer();
        if (renderer == null) return;
        CurrentFrameTerrainOcclusion software = renderer.terrainSoftwareOcclusion();
        if (software != null) software.release(vertexBuffer);
    }

    /** Any direct buffer replacement invalidates old software evidence too. */
    public static void beforeVertexBufferUpload(VertexBuffer vertexBuffer) {
        ModernRendererRuntime renderer = ClientOptimizerRuntime.INSTANCE.modernRenderer();
        if (renderer == null) return;
        renderer.beforeTerrainBufferStorageReplacement(vertexBuffer);
        CurrentFrameTerrainOcclusion software = renderer.terrainSoftwareOcclusion();
        if (software != null) software.legacyUploadStarting(vertexBuffer);
    }

    /** VertexBuffer deletion is also used when OptiFine moves it into a region. */
    public static void releaseVertexBuffer(VertexBuffer vertexBuffer) {
        ModernRendererRuntime renderer = ClientOptimizerRuntime.INSTANCE.modernRenderer();
        if (renderer == null) return;
        try { renderer.releaseTerrain(vertexBuffer); }
        catch (Throwable failure) { FatalErrors.rethrowIfFatal(failure); }
    }

    public static boolean tryRender(Object container, BlockRenderLayer layer) {
        if (container == null || layer == null) return false;
        ModernRendererRuntime renderer = ClientOptimizerRuntime.INSTANCE.modernRenderer();
        if (renderer == null) return false;
        if (renderer.isRuntimePaused()) return renderer.tryRenderTerrainFallback(container, layer);
        if (!renderer.shouldObserveTerrain()) return filterLegacyTerrain(renderer, container, layer);
        try {
            return renderer.tryRenderTerrain(container, layer) || filterLegacyTerrain(renderer, container, layer);
        } catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            OptimizerRegistry.breaker(OptimizationModule.MODERN_TERRAIN_BACKEND)
                .recordFailure(error);
            try { return renderer.tryRenderTerrainFallback(container, layer) || filterLegacyTerrain(renderer, container, layer); }
            catch (Throwable fallbackError) {
                FatalErrors.rethrowIfFatal(fallbackError);
                OptimizerRegistry.breaker(OptimizationModule.MODERN_TERRAIN_BACKEND)
                    .recordFailure(fallbackError);
                try { return renderer.ownsTerrain(container, layer) || filterLegacyTerrain(renderer, container, layer); }
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

    /** Only untouched legacy submissions use CPU coverage; native commands are filtered on the GPU. */
    private static boolean filterLegacyTerrain(ModernRendererRuntime renderer, Object container, BlockRenderLayer layer) {
        CurrentFrameTerrainOcclusion software = renderer.terrainSoftwareOcclusion();
        if (software != null && layer == BlockRenderLayer.SOLID) {
            try { software.filterAdaptive(container, layer, renderer.softwareTerrainMatrices()); }
            catch (Throwable failure) { FatalErrors.rethrowIfFatal(failure); }
        }
        return false;
    }

    /** Brackets both the native and untouched VboRenderList emitters. */
    public static long beginRender(BlockRenderLayer layer) {
        if (layer == null) return 0L;
        ModernRendererRuntime renderer = ClientOptimizerRuntime.INSTANCE.modernRenderer();
        if (renderer == null || !renderer.shouldObserveTerrain()) return 0L;
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
        if (renderer == null || !renderer.shouldObserveTerrain()) return;
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
