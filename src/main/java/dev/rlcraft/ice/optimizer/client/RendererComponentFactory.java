package dev.rlcraft.ice.optimizer.client;

import dev.rlcraft.ice.IceMod;
import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.ModuleCircuitBreaker;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.render.backend.AdaptiveBackendController;
import dev.rlcraft.ice.optimizer.render.backend.CandidateDataSource;
import dev.rlcraft.ice.optimizer.render.backend.CapabilityReport;
import dev.rlcraft.ice.optimizer.render.backend.LwjglCapabilitySelfTest;
import dev.rlcraft.ice.optimizer.render.backend.ModernCapability;
import dev.rlcraft.ice.optimizer.render.entity.CertifiedDrawSites;
import dev.rlcraft.ice.optimizer.render.entity.LwjglModelMeshCache;
import dev.rlcraft.ice.optimizer.render.frame.FrameCoordinator;
import dev.rlcraft.ice.optimizer.render.frame.PassGraph;
import dev.rlcraft.ice.optimizer.render.hud.FontLayoutCache;
import dev.rlcraft.ice.optimizer.render.hud.HudVertexStream;
import dev.rlcraft.ice.optimizer.render.hud.LwjglHudRenderer;
import dev.rlcraft.ice.optimizer.render.legacy.GlStateMirror;
import dev.rlcraft.ice.optimizer.render.legacy.LegacyGlIsland;
import dev.rlcraft.ice.optimizer.render.legacy.LwjglLegacyStateRestorer;
import dev.rlcraft.ice.optimizer.render.optifine.LwjglShaderProgramInstaller;
import dev.rlcraft.ice.optimizer.render.optifine.LwjglShaderImageCertification;
import dev.rlcraft.ice.optimizer.render.optifine.LwjglOptifineShaderActivation;
import dev.rlcraft.ice.optimizer.render.optifine.OptifineShaderBackendSelector;
import dev.rlcraft.ice.optimizer.render.optifine.ShaderCertificationRegistry;
import dev.rlcraft.ice.optimizer.render.optifine.ShaderCertificationPipeline;
import dev.rlcraft.ice.optimizer.render.optifine.LwjglShaderCompilationDriver;
import dev.rlcraft.ice.optimizer.render.particle.ParticleInstanceStream;
import dev.rlcraft.ice.optimizer.render.particle.LwjglFbpPacketRenderer;
import dev.rlcraft.ice.optimizer.render.particle.LwjglParticleRenderer;
import dev.rlcraft.ice.optimizer.render.resource.LwjglResourceDestroyer;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import dev.rlcraft.ice.optimizer.render.telemetry.CorrelatedRenderProfiler;
import dev.rlcraft.ice.optimizer.render.telemetry.LwjglGpuTimestampDriver;
import dev.rlcraft.ice.optimizer.render.terrain.LwjglTerrainArena;
import dev.rlcraft.ice.optimizer.render.texture.SpriteVisibilityTracker;
import dev.rlcraft.ice.optimizer.render.texture.TextureUploadStream;
import dev.rlcraft.ice.optimizer.render.texture.LwjglAnimatedTextureUploadStream;
import dev.rlcraft.ice.optimizer.render.visibility.ConservativeOcclusionHistory;
import dev.rlcraft.ice.optimizer.render.visibility.LwjglDepthHistory;
import java.util.EnumMap;
import java.util.EnumSet;

import static dev.rlcraft.ice.optimizer.client.ModernRendererRuntime.appendRuntimeFailure;
import static dev.rlcraft.ice.optimizer.client.ModernRendererRuntime.rethrowRuntimeFailure;
import static dev.rlcraft.ice.optimizer.client.ModernRendererRuntime.sameCurrentContext;
/** Creates and disposes a complete graph at renderer lifecycle boundaries. */
final class RendererComponentFactory {
    private RendererComponentFactory() { }

    static void initialize(RendererComponents candidate, ModernRendererRuntime runtime, long generation) {
        candidate.threadGuard = RenderThreadGuard.captureCurrent();
        candidate.stateMirror = new GlStateMirror(32);
        candidate.profiler = new CorrelatedRenderProfiler(240);
        candidate.resources = new ResourceLedger(candidate.threadGuard, runtime.budget,
            new LwjglResourceDestroyer(), 65536);
        candidate.glStateQueryWorkspace = new GlStateQueryWorkspace(runtime.budget);
        long gpuLimit = runtime.budget.snapshot().getGpuLimit();
        candidate.modelMeshes = new LwjglModelMeshCache(candidate.threadGuard,
            candidate.resources, runtime.budget);
        candidate.particles = new ParticleInstanceStream(131072, runtime.budget);
        candidate.particleRenderer = new LwjglParticleRenderer(
            candidate.threadGuard, candidate.resources, runtime.budget, 131072);
        candidate.fbpPacketRenderer = new LwjglFbpPacketRenderer(
            candidate.threadGuard, candidate.resources, 4 * 1024 * 1024);
        candidate.textureUploads = new TextureUploadStream(4096,
            Math.min(64L * 1024L * 1024L,
                runtime.budget.snapshot().getDirectLimit()), runtime.budget);
        long visibilityBytes = Math.max(4096L, Math.min(
            64L * 1024L * 1024L,
            runtime.budget.snapshot().getHeapLimit() / 16L));
        candidate.spriteVisibility = new SpriteVisibilityTracker(65536,
            visibilityBytes, new SpriteVisibilityTracker.CatchUpSink() {
                @Override public boolean upload(
                    SpriteVisibilityTracker.DeferredUpload upload) {
                    return runtime.catchUpAnimatedTexture(upload);
                }
            }, runtime.budget);
        candidate.hud = new HudVertexStream(65536, runtime.budget);
        candidate.hudRenderer = new LwjglHudRenderer(candidate.threadGuard,
            candidate.resources, runtime.budget, 65536);
        candidate.fonts = new FontLayoutCache(4096, 262144, runtime.budget);
        candidate.certifiedDrawSites = new CertifiedDrawSites(4096, runtime.budget);
        candidate.shaders = new ShaderCertificationRegistry(2048, runtime.budget);
        candidate.shaderPipeline = new ShaderCertificationPipeline(
            candidate.shaders);
        candidate.shaderCompiler = new LwjglShaderCompilationDriver(
            candidate.threadGuard, runtime.budget);
        candidate.shaderInstaller = new LwjglShaderProgramInstaller(
            candidate.threadGuard, candidate.resources, 256);
        candidate.shaderImageCertification =
            new LwjglShaderImageCertification(candidate.threadGuard, runtime.budget);
        candidate.shaderActivation = new LwjglOptifineShaderActivation(
            candidate.threadGuard, runtime.budget);
        candidate.shaderBackendSelector = new OptifineShaderBackendSelector(
            candidate.shaders);
        candidate.hzbHistory = new ConservativeOcclusionHistory();
        candidate.coordinator = new FrameCoordinator(candidate.threadGuard,
            PassGraph.standard(), candidate.stateMirror,
            reason -> runtime.flushBatches());
        candidate.legacyIsland = new LegacyGlIsland(candidate.threadGuard,
            candidate.stateMirror, () -> runtime.flushBatches(),
            new LwjglLegacyStateRestorer());
        candidate.capabilities = new LwjglCapabilitySelfTest(runtime.budget).execute();
        candidate.animatedTextures = new LwjglAnimatedTextureUploadStream(
            candidate.threadGuard, candidate.resources, runtime.budget,
            candidate.capabilities);
        runtime.initializeTrackedGlState(candidate.glStateQueryWorkspace);
        if (candidate.capabilities.passed(ModernCapability.TIMER_QUERY)) {
            candidate.profiler.attachGpuQueries(
                new LwjglGpuTimestampDriver(), 64, runtime.budget);
        }
        candidate.terrainArena = new LwjglTerrainArena(candidate.threadGuard,
            candidate.resources, runtime.budget, candidate.capabilities,
            Math.max(16L * 1024L * 1024L, gpuLimit / 2L), runtime.arenaGeneration);
        candidate.depthHistory = new LwjglDepthHistory(candidate.threadGuard,
            candidate.resources, candidate.capabilities,
            candidate.hzbHistory, runtime.budget);
        candidate.backends.putAll(createBackends(candidate.capabilities,
            generation));
    }

    static EnumMap<OptimizationModule, AdaptiveBackendController> createBackends(
        CapabilityReport report, long generation) {
        EnumMap<OptimizationModule, AdaptiveBackendController> configured =
            new EnumMap<OptimizationModule, AdaptiveBackendController>(
                OptimizationModule.class);
        addBackend(configured, report, generation,
            OptimizationModule.MODERN_FRAME_COORDINATOR,
            EnumSet.noneOf(ModernCapability.class));
        addBackend(configured, report, generation,
            OptimizationModule.MODERN_TERRAIN_BACKEND,
            EnumSet.of(ModernCapability.BUFFER_OBJECT,
                ModernCapability.MULTI_DRAW, ModernCapability.SYNC_FENCE));
        addBackend(configured, report, generation,
            OptimizationModule.MODERN_TERRAIN_MDI,
            EnumSet.of(ModernCapability.BUFFER_OBJECT,
                ModernCapability.MULTI_DRAW_INDIRECT,
                ModernCapability.SYNC_FENCE));
        addBackend(configured, report, generation,
            OptimizationModule.MODERN_TERRAIN_PERSISTENT_MAPPING,
            EnumSet.of(ModernCapability.BUFFER_OBJECT,
                ModernCapability.BUFFER_STORAGE,
                ModernCapability.PERSISTENT_MAPPING,
                ModernCapability.SYNC_FENCE));
        addBackend(configured, report, generation,
            OptimizationModule.MODERN_VISIBILITY_HZB,
            EnumSet.of(ModernCapability.OFFSCREEN_FRAMEBUFFER,
                ModernCapability.TIMER_QUERY, ModernCapability.CONSERVATIVE_HZB));
        addBackend(configured, report, generation,
            OptimizationModule.MODERN_ENTITY_BACKEND,
            EnumSet.of(ModernCapability.BUFFER_OBJECT, ModernCapability.SYNC_FENCE,
                ModernCapability.TIMER_QUERY, ModernCapability.MODEL_MESH_VBO));
        addBackend(configured, report, generation,
            OptimizationModule.MODERN_TESR_BACKEND,
            EnumSet.of(ModernCapability.BUFFER_OBJECT, ModernCapability.SYNC_FENCE,
                ModernCapability.TIMER_QUERY, ModernCapability.MODEL_MESH_VBO));
        addBackend(configured, report, generation,
            OptimizationModule.MODERN_PARTICLE_BACKEND,
            EnumSet.of(ModernCapability.BUFFER_OBJECT,
                ModernCapability.SYNC_FENCE,
                ModernCapability.PARTICLE_INSTANCING));
        addBackend(configured, report, generation,
            OptimizationModule.FBP_PARTICLE_ADAPTER,
            EnumSet.of(ModernCapability.BUFFER_OBJECT,
                ModernCapability.SYNC_FENCE,
                ModernCapability.FBP_PACKET_VBO));
        addBackend(configured, report, generation,
            OptimizationModule.MODERN_TEXTURE_STREAM,
            EnumSet.of(ModernCapability.PIXEL_UNPACK_BUFFER,
                ModernCapability.SYNC_FENCE));
        addBackend(configured, report, generation,
            OptimizationModule.MODERN_TEXTURE_PERSISTENT_RING,
            EnumSet.of(ModernCapability.PIXEL_UNPACK_BUFFER,
                ModernCapability.SYNC_FENCE, ModernCapability.PERSISTENT_MAPPING));
        addBackend(configured, report, generation,
            OptimizationModule.MODERN_TEXTURE_VISIBILITY,
            EnumSet.of(ModernCapability.PIXEL_UNPACK_BUFFER));
        addBackend(configured, report, generation,
            OptimizationModule.MODERN_HUD_STREAM,
            EnumSet.of(ModernCapability.BUFFER_OBJECT,
                ModernCapability.MULTI_DRAW, ModernCapability.SYNC_FENCE,
                ModernCapability.OFFSCREEN_FRAMEBUFFER));
        addBackend(configured, report, generation,
            OptimizationModule.OPTIFINE_REGION_BACKEND,
            EnumSet.of(ModernCapability.MULTI_DRAW));
        addBackend(configured, report, generation,
            OptimizationModule.OPTIFINE_SHADER_BRIDGE,
            EnumSet.of(ModernCapability.OFFSCREEN_FRAMEBUFFER,
                ModernCapability.TIMER_QUERY, ModernCapability.SHADER_PROGRAM));
        addBackend(configured, report, generation,
            OptimizationModule.LEGACY_GL_ISLAND,
            EnumSet.noneOf(ModernCapability.class));
        addBackend(configured, report, generation,
            OptimizationModule.RENDER_VALIDATION,
            EnumSet.of(ModernCapability.OFFSCREEN_FRAMEBUFFER,
                ModernCapability.TIMER_QUERY));
        return configured;
    }

    static void prepareRuntimeBreakers(long generation) {
        for (OptimizationModule module : OptimizationModule.values()) {
            if (!module.isRuntimeManagedRenderer()) continue;
            ModuleCircuitBreaker breaker = OptimizerRegistry.breaker(module);
            if (breaker != null) breaker.beginRuntimeGeneration(generation);
        }
    }

    static void addBackend(
                            EnumMap<OptimizationModule, AdaptiveBackendController> target,
                            CapabilityReport report, long generation,
                            OptimizationModule module,
                            EnumSet<ModernCapability> required) {
        ModuleCircuitBreaker breaker = OptimizerRegistry.breaker(module);
        if (breaker == null || !OptimizerRegistry.isOperational(module)) return;
        CandidateDataSource source = module == OptimizationModule.MODERN_TERRAIN_BACKEND
            ? CandidateDataSource.TERRAIN_UPLOAD
            : module == OptimizationModule.MODERN_ENTITY_BACKEND
                || module == OptimizationModule.MODERN_TESR_BACKEND
                    ? CandidateDataSource.MODEL_PUBLICATION : CandidateDataSource.NONE;
        AdaptiveBackendController controller = new AdaptiveBackendController(breaker, required, source);
        controller.begin(generation);
        controller.capabilityResult(report);
        target.put(module, controller);
    }

    static void dispose(final RendererComponents graph,
                                boolean requestedContextValid,
                                long contextGeneration) {
        if (graph == null || graph.threadGuard == null) return;
        if (!graph.threadGuard.isRenderThread()) {
            throw new IllegalStateException(
                "refusing to dispose modern OpenGL graph off render thread");
        }
        final long ownedContext = graph.contextGeneration > 0L
            ? graph.contextGeneration : contextGeneration;
        final boolean contextValid = requestedContextValid
            && ownedContext == contextGeneration
            && sameCurrentContext(graph.contextCapabilities);
        Throwable failure = null;
        failure = cleanup(failure, "GPU query ring", new Runnable() {
            @Override public void run() {
                if (graph.profiler != null) graph.profiler.resetGpu(contextValid);
            }
        });
        failure = cleanup(failure, "terrain arena", new Runnable() {
            @Override public void run() {
                if (graph.terrainArena != null) graph.terrainArena.close(contextValid);
            }
        });
        failure = cleanup(failure, "depth history", new Runnable() {
            @Override public void run() {
                if (graph.depthHistory != null) graph.depthHistory.close(contextValid);
            }
        });
        failure = cleanup(failure, "model mesh cache", new Runnable() {
            @Override public void run() {
                if (graph.modelMeshes != null) graph.modelMeshes.close(contextValid);
            }
        });
        failure = cleanup(failure, "particle renderer", new Runnable() {
            @Override public void run() {
                if (graph.particleRenderer != null) {
                    graph.particleRenderer.close(contextValid);
                }
            }
        });
        failure = cleanup(failure, "FBP packet renderer", new Runnable() {
            @Override public void run() {
                if (graph.fbpPacketRenderer != null) {
                    graph.fbpPacketRenderer.close(contextValid);
                }
            }
        });
        failure = cleanup(failure, "animated texture stream", new Runnable() {
            @Override public void run() {
                if (graph.animatedTextures != null) {
                    graph.animatedTextures.close(contextValid, ownedContext);
                }
            }
        });
        failure = cleanup(failure, "HUD stream", new Runnable() {
            @Override public void run() {
                if (graph.hudRenderer != null) graph.hudRenderer.close(contextValid);
            }
        });
        failure = cleanup(failure, "font layout cache", new Runnable() {
            @Override public void run() {
                if (graph.fonts != null) graph.fonts.invalidate();
            }
        });
        failure = cleanup(failure, "native ShaderPack programs", new Runnable() {
            @Override public void run() {
                if (graph.shaderInstaller != null) {
                    graph.shaderInstaller.close(contextValid);
                }
            }
        });
        failure = cleanup(failure, "ShaderPack image workspace", new Runnable() {
            @Override public void run() {
                if (graph.shaderImageCertification != null) {
                    graph.shaderImageCertification.close();
                }
            }
        });
        failure = cleanup(failure, "ShaderPack activation workspace", new Runnable() {
            @Override public void run() {
                if (graph.shaderActivation != null) {
                    graph.shaderActivation.close();
                }
            }
        });
        failure = cleanup(failure, "GL state query workspace", new Runnable() {
            @Override public void run() {
                if (graph.glStateQueryWorkspace != null) {
                    graph.glStateQueryWorkspace.close();
                }
            }
        });
        failure = cleanup(failure, "particle instance stream", new Runnable() {
            @Override public void run() {
                if (graph.particles != null) graph.particles.close();
            }
        });
        failure = cleanup(failure, "texture upload stream", new Runnable() {
            @Override public void run() {
                if (graph.textureUploads != null) graph.textureUploads.close();
            }
        });
        failure = cleanup(failure, "sprite visibility tracker", new Runnable() {
            @Override public void run() {
                if (graph.spriteVisibility != null) {
                    graph.spriteVisibility.close();
                }
            }
        });
        failure = cleanup(failure, "HUD vertex stream", new Runnable() {
            @Override public void run() {
                if (graph.hud != null) graph.hud.close();
            }
        });
        failure = cleanup(failure, "certified draw-site table", new Runnable() {
            @Override public void run() {
                if (graph.certifiedDrawSites != null) {
                    graph.certifiedDrawSites.close();
                }
            }
        });
        failure = cleanup(failure, "Shader certification table", new Runnable() {
            @Override public void run() {
                if (graph.shaders != null) graph.shaders.close();
            }
        });
        failure = cleanup(failure, "resource ledger", new Runnable() {
            @Override public void run() {
                if (graph.resources == null) return;
                if (contextValid) graph.resources.destroyAll(ownedContext);
                else graph.resources.abandonContext(ownedContext);
            }
        });
        if (failure != null) rethrowRuntimeFailure(failure);
    }

    static Throwable cleanup(Throwable priorFailure, String component,
                                     Runnable action) {
        try { action.run(); }
        catch (Throwable error) {
            if (FatalErrors.findFatal(error) == null) {
                IceMod.LOGGER.warn(
                    "ICE 处置现代渲染组件 {} 时继续安全回收其余组件",
                    component, error);
            }
            return appendRuntimeFailure(priorFailure, error);
        }
        return priorFailure;
    }
}
