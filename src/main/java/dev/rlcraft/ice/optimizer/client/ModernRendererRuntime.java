package dev.rlcraft.ice.optimizer.client;

import dev.rlcraft.ice.IceMod;
import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.ModuleCircuitBreaker;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderListAccessor;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyMatrixStateTracker;
import dev.rlcraft.ice.optimizer.compat.lycanites.LycanitesObjRenderBridge;
import dev.rlcraft.ice.optimizer.compat.model.ModelMeshCaptureBridge;
import dev.rlcraft.ice.optimizer.compat.optifine.OptifinePassLifecycleBridge;
import dev.rlcraft.ice.optimizer.compat.renderlib.RenderLibRenderBridge;
import dev.rlcraft.ice.optimizer.render.backend.AdaptiveBackendController;
import dev.rlcraft.ice.optimizer.render.backend.BackendStatus;
import dev.rlcraft.ice.optimizer.render.backend.BackendLifecycleState;
import dev.rlcraft.ice.optimizer.render.backend.MeasurementArm;
import dev.rlcraft.ice.optimizer.render.backend.ModernCapability;
import dev.rlcraft.ice.optimizer.render.backend.RenderBackendId;
import dev.rlcraft.ice.optimizer.render.backend.SceneFingerprint;
import dev.rlcraft.ice.optimizer.render.entity.CertifiedDrawSites;
import dev.rlcraft.ice.optimizer.render.entity.LwjglModelMeshCache;
import dev.rlcraft.ice.optimizer.render.entity.ModelMeshPayload;
import dev.rlcraft.ice.optimizer.render.frame.FrameCoordinator;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.frame.RenderPass;
import dev.rlcraft.ice.optimizer.render.hud.FontLayoutCache;
import dev.rlcraft.ice.optimizer.render.hud.HudOutputValidator;
import dev.rlcraft.ice.optimizer.render.hud.HudVertexStream;
import dev.rlcraft.ice.optimizer.render.hud.LwjglHudRenderer;
import dev.rlcraft.ice.optimizer.render.optifine.ShaderCertificationRegistry;
import dev.rlcraft.ice.optimizer.render.optifine.ShaderCertificationPipeline;
import dev.rlcraft.ice.optimizer.render.optifine.OptifineProgramState;
import dev.rlcraft.ice.optimizer.render.optifine.PreparedShaderPermutation;
import dev.rlcraft.ice.optimizer.render.optifine.ShaderPermutationKey;
import dev.rlcraft.ice.optimizer.render.particle.ParticleInstanceStream;
import dev.rlcraft.ice.optimizer.render.particle.LwjglFbpPacketRenderer;
import dev.rlcraft.ice.optimizer.render.particle.LwjglParticleRenderer;
import dev.rlcraft.ice.optimizer.render.resource.LwjglRetirementFence;
import dev.rlcraft.ice.optimizer.render.resource.RenderHandle;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.telemetry.CorrelatedRenderProfiler;
import dev.rlcraft.ice.optimizer.render.telemetry.CpuWorkKind;
import dev.rlcraft.ice.optimizer.render.telemetry.RenderCounter;
import dev.rlcraft.ice.optimizer.render.telemetry.RenderProfilerSnapshot;
import dev.rlcraft.ice.optimizer.render.telemetry.RenderProfileKey;
import dev.rlcraft.ice.optimizer.render.terrain.LwjglTerrainArena;
import dev.rlcraft.ice.optimizer.render.terrain.TerrainUploadContext;
import dev.rlcraft.ice.optimizer.render.texture.SpriteVisibilityTracker;
import dev.rlcraft.ice.optimizer.render.texture.TextureUploadStream;
import dev.rlcraft.ice.optimizer.render.texture.LwjglAnimatedTextureUploadStream;
import dev.rlcraft.ice.optimizer.render.texture.TextureOutputValidator;
import dev.rlcraft.ice.optimizer.render.visibility.ConservativeOcclusionHistory;
import dev.rlcraft.ice.optimizer.render.visibility.LwjglDepthHistory;
import dev.rlcraft.ice.optimizer.render.visibility.CurrentFrameTerrainOcclusion;
import dev.rlcraft.ice.optimizer.render.visibility.RenderMatrixBridge;
import dev.rlcraft.ice.optimizer.OptimizerConfig;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import dev.rlcraft.ice.optimizer.runtime.EpochToken;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.BlockRenderLayer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GLContext;

/** Render-thread owner of the complete hybrid renderer component graph. */
public final class ModernRendererRuntime {
    private final CurrentFrameTerrainOcclusion softwareOcclusion = new CurrentFrameTerrainOcclusion();
    private static final int INVALIDATE_WORLD = 1;
    private static final int INVALIDATE_RESOURCES = 1 << 1;
    private static final int INVALIDATE_CONTEXT = 1 << 2;

    private static final int TERRAIN_SHADOW_UPLOADS_PER_FRAME = 8;
    private static final long TERRAIN_SHADOW_BYTES_PER_FRAME =
        16L * 1024L * 1024L;
    private static final int TERRAIN_MEASUREMENT_MIN_OWNED = 64;
    private static final int TERRAIN_MEASUREMENT_MIN_COVERAGE_PERCENT = 60;
    private static final int MODEL_ADMISSIONS_PER_FRAME = 48;
    private static final long MODEL_ADMISSION_BYTES_PER_FRAME =
        4L * 1024L * 1024L;

    final OptifineShaderRuntime shaderRuntime = new OptifineShaderRuntime(this);
    final RenderMeasurementRuntime measurements = new RenderMeasurementRuntime(this);
    final ClientEpochs epochs;
    final CacheBudget budget;
    private final AtomicLong rendererGeneration = new AtomicLong(1L);
    private final AtomicInteger pendingInvalidations = new AtomicInteger();
    private final AtomicLong pendingTextureCommands = new AtomicLong();
    private final AtomicLong pendingTextureBytes = new AtomicLong();
    private final AtomicLong pendingTextureBusy = new AtomicLong();
    private final AtomicLong pendingTextureFallbacks = new AtomicLong();
    private final AtomicLong pendingVisibilityDeferred = new AtomicLong();
    private final AtomicLong pendingVisibilityDeferredBytes = new AtomicLong();
    private final AtomicLong pendingVisibilityCaughtUp = new AtomicLong();
    private final AtomicLong pendingVisibilityCaughtUpBytes = new AtomicLong();
    private final AtomicLong pendingVisibilityCatchUpNanos = new AtomicLong();
    private final AtomicLong pendingVisibilityUnknownFrames = new AtomicLong();

    private final InitializationRetryPolicy initializationRetry =
        new InitializationRetryPolicy();
    private volatile long lostContextGeneration;
    private final RendererComponents dormantComponents = new RendererComponents();
    RendererComponents components = dormantComponents;
    private RendererComponents activeGraph;

    FrameStamp currentStamp;

    boolean frameInfrastructureActive;
    boolean quiescent;
    long lastRuntimeFrame = Long.MIN_VALUE;
    private boolean pendingTerrainCandidate;
    private SpriteVisibilityTracker observedSpriteVisibility;

    boolean initialized;
    volatile boolean shutdownRequested;
    private volatile boolean runtimePaused;
    private boolean resumeTrackedState;
    boolean genericModelVboValidated;
    long modelDrawOutsideTraversal;
    long modelStateReauthenticationAttempts;
    long modelStateReauthentications;
    long modelStateReauthenticationFailures;
    long modelStateReauthenticationSuppressions;
    long modelUploadBindingAttempts;
    long modelUploadBindingRecoveries;
    long modelUploadBindingFailures;
    long failedModelGlInvalidation = Long.MIN_VALUE;
    long failedModelMatrixInvalidation = Long.MIN_VALUE;
    long failedModelReauthenticationFrame = Long.MIN_VALUE;
    String lastModelStateFailureStage = "";
    String lastModelStateFailureType = "";
    String lastModelStateFailureMessage = "";
    final long[][] modelDrawReasons = new long[2]
        [ModelDrawReason.values().length];

    private long openVisibilityFrame;
    private int animatedAtlasTextureId;
    long arenaGeneration = 1L;
    String detail = "等待首个有效 OpenGL 帧";
    long terrainArenaUploads;
    final AtomicLong terrainLegacyUploads = new AtomicLong();
    long terrainLegacyDraws;
    long terrainArenaDraws;
    long terrainArenaUncertainDraws;
    long terrainArenaUnbatchedDraws;
    long terrainArenaMultiDraws;
    long terrainMdiSubmissions;
    long terrainIndirectCommands;
    private long terrainShadowBudgetFrame = Long.MIN_VALUE;
    private int terrainShadowUploadsThisFrame;
    private long terrainShadowBytesThisFrame;
    long terrainShadowUploadAttempts;
    long terrainShadowUploads;
    long terrainShadowUploadedBytes;
    long terrainShadowBudgetRejects;
    long terrainShadowQualificationSkips;
    long terrainMeasurementCoverageRejects;
    long terrainMeasurementProfileWarmups;
    int terrainLastVisibleMeshes;
    int terrainLastOwnedMeshes;
    int terrainLastRegionRuns;
    long hzbCaptureAttempts;
    long hzbStateReauthenticationAttempts;
    long hzbStateReauthentications;
    long hzbStateReauthenticationFailures;
    long hzbStateReauthenticationSuppressions;
    long failedHzbStateInvalidation = Long.MIN_VALUE;
    long failedHzbStateFrame = Long.MIN_VALUE;
    String lastHzbStateFailureType = "";
    String lastHzbStateFailureMessage = "";
    long particleBackendFailures;
    String lastParticleFailureType = "";
    String lastParticleFailureMessage = "";
    String lastParticleRootFailureType = "";
    String lastParticleRootFailureMessage = "";
    final long[] hzbCaptureReasons =
        new long[HzbCaptureReason.values().length];
    final long[] terrainFallbackReasons =
        new long[TerrainFallbackReason.values().length];
    private Object pendingTerrainContainer;
    private BlockRenderLayer pendingTerrainLayer;
    private TerrainFallbackReason pendingTerrainReason;

    private final RendererDiagnosticsWriter diagnostics =
        new RendererDiagnosticsWriter(this);

    ModernRendererRuntime(ClientEpochs epochs, CacheBudget budget) {
        if (epochs == null || budget == null) throw new IllegalArgumentException("modern runtime");
        this.epochs = epochs;
        this.budget = budget;
    }

    public void beginFrame(long frameId, EpochToken token) {
        if (shutdownRequested) {
            shutdown();
            return;
        }
        try {
            frameInfrastructureActive = false;
            currentStamp = null;
            observedSpriteVisibility = null;
            lastRuntimeFrame = frameId;
            if (runtimePaused) {
                // Resource/world flags remain pending until the registry mask
                // is enabled again. Context loss must abandon native names now.
                if ((pendingInvalidations.get() & INVALIDATE_CONTEXT) != 0) {
                    applyPendingInvalidations();
                }
                return;
            }
            // Context loss must dispose/abandon the old graph before any new
            // GL capability probe.  Resource/world changes are likewise
            // applied at the frame ownership boundary.
            applyPendingInvalidations();
            ensureInitialized();
            if (!initialized) return;
            if (resumeTrackedState) initializeTrackedGlState();
            if (components.profiler.hasPendingGpuQueries()) components.profiler.pollGpu(8);
            shaderRuntime.drainPendingShaderCandidates(2);
            shaderRuntime.drainPendingShaderValidations(1);
            shaderRuntime.promoteCertifiedShaderBackend();
            boolean lifecycleChanged = resumePendingTerrainCandidate(frameId);
            lifecycleChanged = advanceBackendLifecycles(frameId) || lifecycleChanged;
            synchronizeTerrainSubBackends();
            refreshTextureVisibilityObservation(frameId);
            if (!requiresFrameInfrastructure(frameId)) {
                if (!quiescent || lifecycleChanged) {
                    quiescent = true;
                    detail = "所有现代后端均已休眠；帧协调与逐帧测量已停止";
                    publishRendererDiagnostics(true);
                }
                discardPendingTelemetryCounters();
                return;
            }
            quiescent = false;
            components.coordinator.beginFrame(frameId, token);
            currentStamp = components.coordinator.beginPrimaryView();
            frameInfrastructureActive = true;
            if (ModelMeshCaptureBridge.hasPendingModelMeshes()
                && ModelMeshCaptureBridge.hasReadyModelMeshes(modelMeshAdmissionProgress())
                && prepareModelMeshAdmissions()) {
                ModelMeshCaptureBridge.drainPendingModelMeshes(
                    MODEL_ADMISSIONS_PER_FRAME,
                    MODEL_ADMISSION_BYTES_PER_FRAME);
            }
            if (observedSpriteVisibility != null) {
                observedSpriteVisibility.beginFrame(frameId,
                    epochs.currentResourceGeneration(),
                    epochs.currentAtlasGeneration());
                openVisibilityFrame = frameId;
            }
            drainTextureCounters();
            if (components.depthHistory != null) {
                try {
                    AdaptiveBackendController visibility = components.backends.get(
                        OptimizationModule.MODERN_VISIBILITY_HZB);
                    // Reauthenticate only when a completed readback may need
                    // the prior PBO binding.  LwjglDepthHistory will retain
                    // the ring payload if authentication still fails.
                    if (components.depthHistory.hasPendingReadback()
                        && !EarlyGlStateTracker.isKnown()) {
                        ensureHzbTrackedState(visibility);
                    }
                    components.depthHistory.poll();
                    // OUTPUT_VALIDATE captures carry a sampled source-depth
                    // oracle.  Successful publication already proves the GPU
                    // reduction and retained CPU hierarchy; it must not wait
                    // for an unrelated next-frame camera/geometry identity
                    // match before advancing correctness certification.
                    validatePublishedHzbOnce();
                } catch (Throwable error) {
                    FatalErrors.rethrowIfFatal(error);
                    AdaptiveBackendController visibility = components.backends.get(
                        OptimizationModule.MODERN_VISIBILITY_HZB);
                    if (visibility != null) visibility.runtimeFailure(error);
                    components.depthHistory.reset(true);
                    measurements.clearHzbGpuSamples();
                    detail = "HZB 延迟回读异常；仅可见性后端已回退："
                        + error.getClass().getSimpleName();
                }
            }
            if (measurements.requiresWholeFrameGpuSample(frameId)) {
                measurements.frameGpuScope = components.profiler.beginGpu(currentStamp,
                    RenderPass.FINAL, RenderBackendId.LEGACY);
            }
        } catch (Throwable error) {
            failCoordinator(error);
        }
    }

    public void endFrame() {
        if (!initialized || components.threadGuard == null || !components.threadGuard.isRenderThread()) return;
        if (!frameInfrastructureActive) {
            if (runtimePaused) {
                // Vanilla uploads and chunk deletion still retire arena ranges.
                if (components.terrainArena != null) components.terrainArena.endFrame(true);
                if (components.resources != null) components.resources.collect(epochs.currentGlContextGeneration(), 16);
            }
            currentStamp = null;
            return;
        }
        try {
            measurements.discardOpenHzbGpuSamples(currentStamp.getFrameId());
            shaderRuntime.finishActiveShaderInterval(true, true);
            CorrelatedRenderProfiler.GpuScope closingFrameScope = measurements.frameGpuScope;
            measurements.frameGpuScope = null;
            if (closingFrameScope != null) closingFrameScope.close();
            if (components.coordinator.viewDepth() == 1) components.coordinator.endPrimaryView();
            if (components.coordinator.isFrameActive()) components.coordinator.endFrame();
            if (components.terrainArena != null) {
                components.terrainArena.endFrame(true);
                Throwable terrainFailure = components.terrainArena.consumePublicationFailure();
                if (terrainFailure != null) {
                    FatalErrors.rethrowIfFatal(terrainFailure);
                    AdaptiveBackendController terrain = components.backends.get(
                        OptimizationModule.MODERN_TERRAIN_BACKEND);
                    if (terrain != null) terrain.runtimeFailure(terrainFailure);
                    detail = "地形资源回收异常；地形子后端已独立回退："
                        + terrainFailure.getClass().getSimpleName();
                }
            }
            components.resources.collect(epochs.currentGlContextGeneration(), 16);
            advanceWarmup();
            activateReadyBackends();
            publishRendererDiagnostics(false);
            if (components.spriteVisibility != null && openVisibilityFrame > 0L) {
                components.spriteVisibility.endFrame(openVisibilityFrame, true);
                openVisibilityFrame = 0L;
            }
        } catch (Throwable error) {
            failCoordinator(error);
        } finally {
            frameInfrastructureActive = false;
            currentStamp = null;
        }
    }

    public void beginPass(RenderPass pass, RenderBackendId backend) {
        if (!initialized || currentStamp == null) return;
        components.coordinator.beginPass(pass, backend);
    }

    public void endPass(RenderPass pass) {
        if (!initialized || currentStamp == null) return;
        components.coordinator.endPass(pass);
    }

    /**
     * Opens a bounded emitter-observed pass.  Legal mod recursion or repeated
     * optional passes are downgraded by FrameCoordinator to an invalidating
     * Legacy boundary instead of escaping into the original renderer.
     */
    public long beginObservedPass(RenderPass pass, OptimizationModule module) {
        return beginObservedPass(pass, observedBackend(module));
    }

    public long beginObservedPass(RenderPass pass, RenderBackendId backend) {
        if (!initialized || currentStamp == null || components.coordinator == null
            || components.threadGuard == null || !components.threadGuard.isRenderThread()) return 0L;
        try {
            return components.coordinator.beginObservedPass(pass,
                backend == null ? RenderBackendId.LEGACY : backend);
        } catch (Throwable error) {
            failCoordinator(error);
            return 0L;
        }
    }

    public void endObservedPass(long token) {
        if (token == 0L || !initialized || components.coordinator == null
            || components.threadGuard == null || !components.threadGuard.isRenderThread()) return;
        try {
            if (!components.coordinator.endObservedPass(token)) {
                detail = "检测到模组 pass 递归/配对偏差；状态已失效并保持 Legacy";
            }
        } catch (Throwable error) {
            failCoordinator(error);
        }
    }

    public void observableBarrier(String reason) {
        if (!initialized || currentStamp == null) return;
        components.coordinator.observableBarrier(reason);
        EarlyGlStateTracker.invalidate();
        EarlyMatrixStateTracker.invalidate();
    }

    public void runLegacy(Runnable code) {
        if (code == null) return;
        try {
            callLegacy("Legacy GL callback", new Callable<Void>() {
                @Override public Void call() {
                    code.run();
                    return null;
                }
            });
        } catch (RuntimeException error) {
            throw error;
        } catch (Error error) {
            throw error;
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Runs one observing callback inside the production Legacy GL island. */
    public <T> T callLegacy(String reason, Callable<T> code) throws Exception {
        if (code == null) throw new IllegalArgumentException("legacy callback");
        if (!initialized || components.legacyIsland == null || currentStamp == null) {
            try {
                return code.call();
            } catch (Exception error) {
                FatalErrors.rethrowIfFatal(error);
                throw error;
            }
        }
        long token = beginLegacyBoundary(reason, true);
        Throwable originalError = null;
        try {
            return code.call();
        } catch (Exception error) {
            originalError = error;
            FatalErrors.rethrowIfFatal(error);
            throw error;
        } catch (Error error) {
            originalError = error;
            throw error;
        } finally {
            endLegacyBoundary(token, originalError);
        }
    }

    /** Opens an independently generated, Legacy-compatible recursive view. */
    public long beginPortalView() {
        if (!initialized || currentStamp == null || components.coordinator == null
            || components.legacyIsland == null || components.threadGuard == null
            || !components.threadGuard.isRenderThread()) return 0L;
        long token = beginLegacyBoundary("iChunUtil WorldPortal enter", false);
        if (token == 0L) return 0L;
        try {
            currentStamp = components.coordinator.beginPortalView();
            return token;
        } catch (Throwable error) {
            endLegacyBoundary(token, error);
            failCoordinator(error);
            return 0L;
        }
    }

    /** Closes a recursive portal view without replacing its original error. */
    public void endPortalView(long token, Throwable originalError) {
        if (token == 0L) return;
        Throwable failure = originalError;
        try {
            if (initialized && components.coordinator != null && components.threadGuard != null
                && components.threadGuard.isRenderThread()
                && components.coordinator.viewDepth() > 1) {
                components.coordinator.endPortalView();
                currentStamp = components.coordinator.currentStamp();
            }
        } catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
            try { failCoordinator(error); }
            catch (Throwable reportingFailure) {
                failure = appendRuntimeFailure(failure, reportingFailure);
            }
        } finally {
            endLegacyBoundary(token, failure);
        }
        FatalErrors.rethrowIfFatal(failure);
    }

    public void invalidateWorld() {
        advanceRendererGeneration();
        pendingInvalidations.getAndUpdate(value -> value | INVALIDATE_WORLD);
    }

    public void invalidateResources() {
        advanceRendererGeneration();
        pendingInvalidations.getAndUpdate(value -> value | INVALIDATE_RESOURCES);
    }

    public void invalidateContext(long oldContextGeneration) {
        advanceRendererGeneration();
        lostContextGeneration = oldContextGeneration;
        pendingInvalidations.getAndUpdate(value -> value | INVALIDATE_CONTEXT);
    }

    /**
     * Session switch, called at a client-tick boundary after the registry mask
     * changes. Published terrain and model storage remain resident across a
     * pause; owned terrain uses individual draws until vanilla replaces it.
     */
    public boolean setRuntimePaused(boolean paused) {
        if (shutdownRequested) return false;
        if (runtimePaused == paused) return true;
        if (components.threadGuard != null) components.threadGuard.check();
        if (!paused) {
            runtimePaused = false;
            resumeTrackedState = initialized;
            detail = "运行时优化已恢复；复用模型缓存";
            return true;
        }
        try {
            if ((pendingInvalidations.get() & INVALIDATE_CONTEXT) != 0) {
                applyPendingInvalidations();
            }
            if (initialized) {
                flushBatches();
                if (components.modelMeshes != null) components.modelMeshes.endStateBatch();
                shaderRuntime.finishActiveShaderInterval(false, true);
                if (shaderRuntime.hasNativeBinding()) {
                    detail = "Shader 程序尚未归还，运行时切换未完成";
                    return false;
                }
                CorrelatedRenderProfiler.GpuScope frameScope = measurements.frameGpuScope;
                measurements.frameGpuScope = null;
                if (frameScope != null) frameScope.close();
                if (components.coordinator.viewDepth() == 1) components.coordinator.endPrimaryView();
                if (components.coordinator.isFrameActive()) components.coordinator.endFrame();
                catchUpTexturesForPause();
                if (components.spriteVisibility != null) components.spriteVisibility.invalidate();
                openVisibilityFrame = 0L;
                if (components.depthHistory != null) components.depthHistory.reset(true);
                else if (components.hzbHistory != null) components.hzbHistory.invalidate();
                if (components.terrainArena != null) {
                    components.terrainArena.configureVisibility(null, null, null);
                }
            }
            softwareOcclusion.clear();
            measurements.clearHzbGpuSamples();
            measurements.backendGpuSamples.clear();
            discardPendingTelemetryCounters();
            pendingTerrainCandidate = false;
            observedSpriteVisibility = null;
            currentStamp = null;
            frameInfrastructureActive = false;
            runtimePaused = true;
            EarlyGlStateTracker.invalidate();
            EarlyMatrixStateTracker.invalidate();
            detail = "运行时优化已暂停；模型缓存保留";
            return true;
        } catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            detail = "运行时切换未完成：" + error.getClass().getSimpleName();
            IceMod.LOGGER.warn("ICE 暂停渲染优化时未能完成状态归还", error);
            return false;
        }
    }

    public boolean isRuntimePaused() { return runtimePaused; }

    /** Flush deferred atlas content before normal animation uploads take over. */
    private void catchUpTexturesForPause() {
        SpriteVisibilityTracker tracker = components.spriteVisibility;
        if (tracker == null || tracker.getPendingBytes() == 0L || animatedAtlasTextureId <= 0) return;
        int activeUnit = EarlyGlStateTracker.activeTextureUnit();
        int active = activeUnit == Integer.MIN_VALUE
            ? GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) : GL13.GL_TEXTURE0 + activeUnit;
        int unpack = EarlyGlStateTracker.pixelUnpackBufferBinding();
        if (unpack == Integer.MIN_VALUE) unpack = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int texture = EarlyGlStateTracker.boundTextureForUnit(0);
        Throwable failure = null;
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            EarlyGlStateTracker.activeTexture(GL13.GL_TEXTURE0);
            if (texture == Integer.MIN_VALUE) texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, animatedAtlasTextureId);
            EarlyGlStateTracker.bindTexture(animatedAtlasTextureId);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            EarlyGlStateTracker.bindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            tracker.markUnknown(Math.max(1L, epochs.currentFrameId()),
                epochs.currentResourceGeneration(), epochs.currentAtlasGeneration());
            failure = tracker.consumeLastFailure();
        } catch (Throwable error) { failure = appendRuntimeFailure(failure, error); }
        finally {
            try {
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpack);
                EarlyGlStateTracker.bindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpack);
            } catch (Throwable error) { failure = appendRuntimeFailure(failure, error); }
            if (texture >= 0) try {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
                EarlyGlStateTracker.bindTexture(texture);
            } catch (Throwable error) { failure = appendRuntimeFailure(failure, error); }
            try {
                GL13.glActiveTexture(active);
                EarlyGlStateTracker.activeTexture(active);
            } catch (Throwable error) { failure = appendRuntimeFailure(failure, error); }
        }
        if (failure != null) {
            EarlyGlStateTracker.invalidate();
            rethrowRuntimeFailure(failure);
        }
    }

    public void setShaderPackState(boolean known, boolean active) {
        shaderRuntime.setShaderPackState(known, active);
    }

    /**
     * Native fixed-layout streams are admitted only after the OptiFine probe
     * has positively established that no ShaderPack is active.  Unknown is a
     * compatibility state, not an optimistic "off" state.
     */
    public boolean isShaderPackSafeForNativeVertexFormats() {
        return shaderRuntime.isShaderPackSafeForNativeVertexFormats();
    }

    /** ShaderPack exception used only by the dual-resident terrain arena. */
    boolean isShaderPackSafeForTerrainArena() {
        return shaderRuntime.isShaderPackSafeForTerrainArena();
    }

    public void recordValidation(OptimizationModule module, boolean equivalent,
                                 String detail) {
        measurements.recordValidation(module, equivalent, detail);
    }

    public MeasurementArm expectedMeasurementArm(OptimizationModule module) {
        return measurements.expectedMeasurementArm(module);
    }

    public void recordMeasurement(OptimizationModule module, SceneFingerprint scene,
                                  MeasurementArm arm, long nanos, boolean stable) {
        measurements.recordMeasurement(module, scene, arm, nanos, stable);
    }

    public boolean activateBackend(OptimizationModule module) {
        AdaptiveBackendController backend = components.backends.get(module);
        return backend != null && currentStamp == null && backend.activateAtSafeBoundary();
    }

    public void shutdown() {
        shutdownRequested = true;
        if (activeGraph != null && components.threadGuard != null
            && !components.threadGuard.isRenderThread()) {
            try {
                Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                    @Override public void run() { shutdown(); }
                });
                detail = "等待渲染线程关闭现代渲染资源";
                return;
            } catch (Throwable schedulingFailure) {
                FatalErrors.rethrowIfFatal(schedulingFailure);
                IceMod.LOGGER.warn("无法把现代渲染资源关闭调度到渲染线程",
                    schedulingFailure);
                return;
            }
        }
        Throwable shutdownFailure = null;
        try { publishRendererDiagnostics(true); }
        catch (Throwable error) {
            shutdownFailure = appendRuntimeFailure(shutdownFailure, error);
        }
        if (components.spriteVisibility != null && openVisibilityFrame > 0L) {
            try { components.spriteVisibility.abortFrame(openVisibilityFrame); }
            catch (Throwable error) {
                shutdownFailure = appendRuntimeFailure(shutdownFailure, error);
            }
            openVisibilityFrame = 0L;
        }
        boolean validContext = false;
        if (activeGraph != null) try {
            validContext = sameCurrentContext(activeGraph.contextCapabilities);
        } catch (Throwable error) {
            shutdownFailure = appendRuntimeFailure(shutdownFailure, error);
        }
        boolean shaderSafeToDispose = true;
        if (validContext) {
            try { flushBatches(); }
            catch (Throwable error) {
                shutdownFailure = appendRuntimeFailure(shutdownFailure, error);
            }
            try { shaderRuntime.finishActiveShaderInterval(false, true); }
            catch (Throwable error) {
                shutdownFailure = appendRuntimeFailure(shutdownFailure, error);
            }
            shaderSafeToDispose = !shaderRuntime.hasNativeBinding();
            if (!shaderSafeToDispose) {
                shutdownFailure = appendRuntimeFailure(shutdownFailure,
                    new IllegalStateException(
                        "native ShaderPack program is still active"));
            }
        } else try { shaderRuntime.abandonActiveShaderInterval(); }
        catch (Throwable error) {
            shutdownFailure = appendRuntimeFailure(shutdownFailure, error);
        }
        if (shaderSafeToDispose) {
            try { disposeActiveGraph(validContext, components.contextGeneration); }
            catch (Throwable error) {
                shutdownFailure = appendRuntimeFailure(shutdownFailure, error);
            }
        } else {
            // Deleting or forgetting a program which may still be current is
            // a use-after-retire risk.  Keep the bounded graph intact so a
            // later render-thread shutdown can retry restoration.
            currentStamp = null;
            measurements.frameGpuScope = null;
            detail = "Shader candidate 无法恢复；现代资源保持所有权并等待重试关闭";
            FatalErrors.rethrowIfFatal(shutdownFailure);
            IceMod.LOGGER.warn("现代渲染器关闭延后：仍有 active Shader candidate",
                shutdownFailure);
            return;
        }
        initialized = false;
        currentStamp = null;
        measurements.frameGpuScope = null;
        frameInfrastructureActive = false;
        quiescent = false;
        try { measurements.backendGpuSamples.clear(); }
        catch (Throwable error) {
            shutdownFailure = appendRuntimeFailure(shutdownFailure, error);
        }
        try { TextureOutputValidator.invalidate(); }
        catch (Throwable error) {
            shutdownFailure = appendRuntimeFailure(shutdownFailure, error);
        }
        try { HudOutputValidator.invalidate(); }
        catch (Throwable error) {
            shutdownFailure = appendRuntimeFailure(shutdownFailure, error);
        }
        shaderRuntime.optifineProgramState = null;
        try { shaderRuntime.clearPendingShaderCandidates(); }
        catch (Throwable error) {
            shutdownFailure = appendRuntimeFailure(shutdownFailure, error);
        }
        try { shaderRuntime.clearShaderBindings(); }
        catch (Throwable error) {
            shutdownFailure = appendRuntimeFailure(shutdownFailure, error);
        }
        detail = "已停止";
        if (shutdownFailure != null) {
            FatalErrors.rethrowIfFatal(shutdownFailure);
            IceMod.LOGGER.warn("现代渲染器关闭期间发生异常；其余资源已继续回收",
                shutdownFailure);
        }
    }

    public ModernRendererStatus status() {
        EnumMap<OptimizationModule, BackendStatus> snapshots =
            new EnumMap<OptimizationModule, BackendStatus>(OptimizationModule.class);
        for (Map.Entry<OptimizationModule, AdaptiveBackendController> entry : components.backends.entrySet()) {
            snapshots.put(entry.getKey(), entry.getValue().snapshot());
        }
        return new ModernRendererStatus(initialized, detail, components.capabilities,
            components.coordinator == null ? null : components.coordinator.snapshot(),
            components.resources == null || components.threadGuard == null || !components.threadGuard.isRenderThread()
                ? null : components.resources.snapshot(), snapshots);
    }

    public RenderProfilerSnapshot profilerSnapshot() {
        return components.profiler == null ? null : components.profiler.snapshot();
    }

    public FrameStamp currentStamp() { return currentStamp; }
    public ParticleInstanceStream particles() { return components.particles; }
    public LwjglParticleRenderer particleRenderer() {
        return initialized && components.threadGuard != null && components.threadGuard.isRenderThread()
            ? components.particleRenderer : null;
    }
    public LwjglFbpPacketRenderer fbpPacketRenderer() {
        return initialized && components.threadGuard != null && components.threadGuard.isRenderThread()
            ? components.fbpPacketRenderer : null;
    }
    public TextureUploadStream textureUploads() { return components.textureUploads; }
    public SpriteVisibilityTracker spriteVisibility() { return components.spriteVisibility; }

    /** A frame-owned reference; draw hooks perform no state or pending queries. */
    public SpriteVisibilityTracker observedSpriteVisibility() { return observedSpriteVisibility; }

    void refreshTextureVisibilityObservation(long frameId) {
        SpriteVisibilityTracker tracker = components.spriteVisibility;
        AdaptiveBackendController visibility = components.backends.get(
            OptimizationModule.MODERN_TEXTURE_VISIBILITY);
        observedSpriteVisibility = initialized && tracker != null
            && (visibility != null && !visibility.isDormant(frameId)
                || tracker.getPendingBytes() != 0L) ? tracker : null;
    }
    public LwjglAnimatedTextureUploadStream animatedTextures() {
        return initialized && components.threadGuard != null && components.threadGuard.isRenderThread()
            ? components.animatedTextures : null;
    }
    public HudVertexStream hud() { return components.hud; }
    public LwjglHudRenderer hudRenderer() {
        return initialized && components.threadGuard != null && components.threadGuard.isRenderThread()
            ? components.hudRenderer : null;
    }
    public FontLayoutCache fonts() { return components.fonts; }
    public CacheBudget cacheBudget() { return budget; }
    public CertifiedDrawSites certifiedDrawSites() { return components.certifiedDrawSites; }
    public ShaderCertificationRegistry shaders() { return components.shaders; }
    public ShaderCertificationPipeline shaderPipeline() { return components.shaderPipeline; }

    /**
     * Queues exact text already resolved by OptiFine.  This method performs no
     * GL call; compilation is drained at a later render-frame ownership
     * boundary under the captured generations.
     */
    public boolean queueOptifineShaderSources(
        String packId, String program, String permutation,
        long resourceGeneration, long shaderGeneration,
        String vertexPath, String vertexSource,
        String geometryPath, String geometrySource,
        String fragmentPath, String fragmentSource,
        String propertiesSource) {
        return shaderRuntime.queueOptifineShaderSources(packId, program, permutation, resourceGeneration, shaderGeneration, vertexPath, vertexSource, geometryPath, geometrySource, fragmentPath, fragmentSource, propertiesSource);
    }

    /** Production overload retaining a bounded identity reference to Program. */
    public boolean queueOptifineShaderSources(
        Object programIdentity, String packId, String program,
        String permutation, long resourceGeneration, long shaderGeneration,
        String vertexPath, String vertexSource,
        String geometryPath, String geometrySource,
        String fragmentPath, String fragmentSource,
        String propertiesSource) {
        return shaderRuntime.queueOptifineShaderSources(programIdentity, packId, program, permutation, resourceGeneration, shaderGeneration, vertexPath, vertexSource, geometryPath, geometrySource, fragmentPath, fragmentSource, propertiesSource);
    }

    /** Links and retains a current-generation candidate without activating it. */
    public boolean compileShaderPermutation(PreparedShaderPermutation prepared) {
        return shaderRuntime.compileShaderPermutation(prepared);
    }

    /** Temporary compile/link gate must pass before the retained installation. */
    public boolean compileShaderPermutation(PreparedShaderPermutation prepared,
                                             int legacyProgramId) {
        return shaderRuntime.compileShaderPermutation(prepared, legacyProgramId);
    }

    /** Unknown/uninstalled/uncertified ShaderPack programs are always OF/Legacy. */
    public RenderBackendId selectOptifineShaderBackend(
        ShaderPermutationKey permutation, boolean optifineRegionAvailable,
        boolean nativeVertexLayoutCompatible, boolean nativeBackendProfitable) {
        return shaderRuntime.selectOptifineShaderBackend(permutation, optifineRegionAvailable, nativeVertexLayoutCompatible, nativeBackendProfitable);
    }

    /**
     * True only after the exact OptiFine VboRegion emitter completed in the
     * current world/resource/context/shader generation.  ICE never toggles
     * OptiFine's Render Regions setting to manufacture this observation.
     */
    public boolean isOptifineRegionBackendAvailable() {
        return shaderRuntime.isOptifineRegionBackendAvailable();
    }

    /** Begins passive profiling of OptiFine's own already-batched draw. */
    public OptifineRegionDrawSample beginOptifineRegionDraw(
        Object layer, int indexPosition, int countPosition, int commandCapacity,
        int drawMode, int bufferId, int positionTop, int sizeUsed) {
        return shaderRuntime.beginOptifineRegionDraw(layer, indexPosition, countPosition, commandCapacity, drawMode, bufferId, positionTop, sizeUsed);
    }

    /** Completes passive validation without replacing or replaying the OF draw. */
    public void endOptifineRegionDraw(OptifineRegionDrawSample sample,
                                      int indexPosition, int countPosition,
                                      int commandCapacity, Throwable error) {
        shaderRuntime.endOptifineRegionDraw(sample, indexPosition, countPosition, commandCapacity, error);
    }

    public int certifiedNativeShaderProgram(ShaderPermutationKey permutation) {
        return shaderRuntime.certifiedNativeShaderProgram(permutation);
    }
    public OptifineProgramState currentOptifineProgramState() {
        return shaderRuntime.currentOptifineProgramState();
    }
    public ConservativeOcclusionHistory hzbHistory() { return components.hzbHistory; }

    public long currentFrameId() { return epochs.currentFrameId(); }
    public long resourceGeneration() { return epochs.currentResourceGeneration(); }
    public long atlasGeneration() { return epochs.currentAtlasGeneration(); }
    public long glContextGeneration() { return epochs.currentGlContextGeneration(); }
    public long shaderPackGeneration() { return epochs.currentShaderPackGeneration(); }

    /** Publishes the current TextureMap GL name without querying the driver. */
    public void observeAnimatedAtlas(int textureId) {
        if (!initialized || textureId <= 0 || components.threadGuard == null
            || !components.threadGuard.isRenderThread()) return;
        animatedAtlasTextureId = textureId;
    }

    /** Non-blocking state-mirror check used immediately before a sampled draw. */
    public boolean isAnimatedAtlasBound() {
        return initialized && animatedAtlasTextureId > 0 && components.threadGuard != null
            && components.threadGuard.isRenderThread()
            && EarlyGlStateTracker.boundTextureForUnit(0)
                == animatedAtlasTextureId;
    }

    public BackendLifecycleState backendLifecycleState(OptimizationModule module) {
        AdaptiveBackendController backend = components.backends.get(module);
        return backend == null ? BackendLifecycleState.LEGACY
            : backend.lifecycleState();
    }

    /**
     * Allocation-free gate for lifecycle hooks which are useful only while
     * this runtime owns an observed render frame.  In particular, OptiFine
     * may continue invoking its transformed pass/program hooks after every
     * adaptive backend has entered a dormant Legacy terminal state.
     */
    public boolean isFrameInfrastructureActive() {
        return initialized && frameInfrastructureActive && currentStamp != null
            && components.threadGuard != null && components.threadGuard.isRenderThread();
    }

    /** Allocation-free hot-path gate for transformed compatibility bridges. */
    public boolean shouldObserveBackend(OptimizationModule module) {
        if (!initialized || currentStamp == null || module == null
            || components.threadGuard == null || !components.threadGuard.isRenderThread()) return false;
        return mayUseBackendCandidate(module);
    }

    /** State-only form for build/capture hooks which may run outside a pass. */
    public boolean mayUseBackendCandidate(OptimizationModule module) {
        if (runtimePaused || !initialized || module == null) return false;
        AdaptiveBackendController backend = components.backends.get(module);
        long frameId = lastRuntimeFrame > 0L ? lastRuntimeFrame
            : epochs.currentFrameId();
        return backend != null
            && backend.mayInspectOrRetestCandidate(frameId);
    }

    /** Gate shared by the terrain draw/upload hooks and HZB observer. */
    public boolean shouldObserveTerrain() {
        if (!initialized || currentStamp == null || components.threadGuard == null
            || !components.threadGuard.isRenderThread()) return false;
        return backendMayInspect(OptimizationModule.MODERN_TERRAIN_BACKEND)
            || backendMayInspect(OptimizationModule.MODERN_TERRAIN_MDI)
            || backendMayInspect(
                OptimizationModule.MODERN_TERRAIN_PERSISTENT_MAPPING)
            || backendMayInspect(OptimizationModule.MODERN_VISIBILITY_HZB);
    }

    private RenderBackendId observedBackend(OptimizationModule module) {
        AdaptiveBackendController backend = components.backends.get(module);
        if (backend == null) return RenderBackendId.LEGACY;
        BackendLifecycleState state = backend.lifecycleState();
        if (state == BackendLifecycleState.MODERN
            || state == BackendLifecycleState.REGRESSION_MONITOR
            || state == BackendLifecycleState.PAIRED_MEASURE
                && backend.expectedMeasurementArm() == MeasurementArm.MODERN) {
            return RenderBackendId.ICE_NATIVE;
        }
        return RenderBackendId.LEGACY;
    }

    /** CPU-only paired sample for GL work that runs during the client tick. */
    public void recordAuxiliaryBackendSample(OptimizationModule module,
                                             BackendLifecycleState sampledState,
                                             MeasurementArm arm,
                                             SceneFingerprint scene,
                                             long nanos, boolean stable) {
        measurements.recordAuxiliaryBackendSample(module, sampledState, arm, scene, nanos, stable);
    }

    public void textureBackendFailure(Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        AdaptiveBackendController backend = components.backends.get(
            OptimizationModule.MODERN_TEXTURE_STREAM);
        if (backend != null) backend.runtimeFailure(error);
        detail = "动画纹理 PBO 异常；纹理子后端已回退："
            + (error == null ? "unknown" : error.getClass().getSimpleName());
    }

    public void textureVisibilityFailure(Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        Throwable failure = error == null
            ? new IllegalStateException("animated texture visibility failure") : error;
        AdaptiveBackendController backend = components.backends.get(
            OptimizationModule.MODERN_TEXTURE_VISIBILITY);
        if (backend != null) backend.runtimeFailure(failure);
        if (components.spriteVisibility != null) {
            components.spriteVisibility.invalidate(epochs.currentResourceGeneration(),
                epochs.currentAtlasGeneration());
        }
        detail = "动画纹理可见性异常；完整原上传路径已恢复："
            + failure.getClass().getSimpleName();
    }

    public void recordTextureVisibilityDeferred(long bytes) {
        // TextureMap can publish between frame boundaries. Its first pending
        // upload must make the next draw observer live immediately, including
        // a draw before the next frame's ordinary eligibility refresh.
        observedSpriteVisibility = components.spriteVisibility;
        pendingVisibilityDeferred.incrementAndGet();
        if (bytes > 0L) pendingVisibilityDeferredBytes.addAndGet(bytes);
    }

    public void recordTextureVisibilityUnknown() {
        pendingVisibilityUnknownFrames.incrementAndGet();
    }

    public long consumeTextureVisibilityCatchUpNanos() {
        return pendingVisibilityCatchUpNanos.getAndSet(0L);
    }

    boolean catchUpAnimatedTexture(
        SpriteVisibilityTracker.DeferredUpload upload) {
        if (upload == null || !initialized || components.threadGuard == null
            || !components.threadGuard.isRenderThread()
            || upload.getResourceGeneration()
                != epochs.currentResourceGeneration()
            || upload.getAtlasGeneration() != epochs.currentAtlasGeneration()
            || upload.getTextureId() != animatedAtlasTextureId
            || !isAnimatedAtlasBound()) {
            return false;
        }
        int activeTexture = EarlyGlStateTracker.activeTextureUnit();
        int unpackBuffer = EarlyGlStateTracker.pixelUnpackBufferBinding();
        if (activeTexture < 0 || activeTexture >= 32 || unpackBuffer < 0) {
            return false;
        }
        long started = System.nanoTime();
        // TextureUtil targets the active unit and treats its pixel pointer as
        // an unpack-PBO offset when a PBO is bound.  Preserve both pieces of
        // caller state around this synchronous, draw-boundary catch-up.
        Throwable failure = null;
        boolean activeTextureAttempted = false;
        boolean unpackBindingAttempted = false;
        try {
            if (activeTexture != 0) {
                activeTextureAttempted = true;
                GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
            }
            if (unpackBuffer != 0) {
                unpackBindingAttempted = true;
                OpenGlHelper.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            }
            dev.rlcraft.ice.optimizer.render.texture.ExactTextureUpload.upload(upload.copyPixels(),
                upload.getWidth(), upload.getHeight(), upload.getOriginX(),
                upload.getOriginY(), upload.isBlur(), upload.isClamp(), upload.isMipFilter());
        } catch (Throwable uploadFailure) {
            failure = uploadFailure;
        }
        if (unpackBindingAttempted) try {
            OpenGlHelper.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,
                unpackBuffer);
        } catch (Throwable restoreFailure) {
            failure = appendRuntimeFailure(failure, restoreFailure);
        }
        if (activeTextureAttempted) try {
                GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + activeTexture);
        } catch (Throwable restoreFailure) {
            failure = appendRuntimeFailure(failure, restoreFailure);
        }
        if (failure != null) {
            EarlyGlStateTracker.invalidate();
            rethrowRuntimeFailure(failure);
        }
        pendingVisibilityCaughtUp.incrementAndGet();
        pendingVisibilityCaughtUpBytes.addAndGet(upload.getByteCount());
        // Attribute the work to the normal texture pass counters.  Timing is
        // intentionally measured by the enclosing frame/ABBA sample, avoiding
        // synchronous GL queries at this draw boundary.
        long elapsed = System.nanoTime() - started;
        if (elapsed < 0L) {
            throw new IllegalStateException("animation catch-up clock moved backwards");
        }
        pendingVisibilityCatchUpNanos.addAndGet(Math.max(1L, elapsed));
        return true;
    }

    public void texturePersistentFailure(Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        AdaptiveBackendController backend = components.backends.get(
            OptimizationModule.MODERN_TEXTURE_PERSISTENT_RING);
        if (backend != null) backend.runtimeFailure(error);
        detail = "Persistent 纹理 ring 异常；普通 PBO 路径保持可用："
            + (error == null ? "unknown" : error.getClass().getSimpleName());
    }

    public void terrainPersistentFailure(Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        AdaptiveBackendController backend = components.backends.get(
            OptimizationModule.MODERN_TERRAIN_PERSISTENT_MAPPING);
        if (backend != null) backend.runtimeFailure(error);
        detail = "地形 Persistent Mapping 异常；subdata/multi-draw 保持可用："
            + (error == null ? "unknown" : error.getClass().getSimpleName());
    }

    public void recordTextureTransfers(int commands, long bytes,
                                       boolean fenceBusy, boolean modern) {
        if (commands > 0) pendingTextureCommands.addAndGet(commands);
        if (bytes > 0L) pendingTextureBytes.addAndGet(bytes);
        if (fenceBusy) pendingTextureBusy.incrementAndGet();
        if (!modern) pendingTextureFallbacks.incrementAndGet();
    }

    public void particleBackendFailure(Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        particleBackendFailures = saturatedIncrement(particleBackendFailures);
        Throwable root = diagnosticRootCause(error);
        lastParticleFailureType = error == null ? ""
            : error.getClass().getName();
        lastParticleFailureMessage = error == null || error.getMessage() == null
            ? "" : error.getMessage();
        lastParticleRootFailureType = root == null ? ""
            : root.getClass().getName();
        lastParticleRootFailureMessage = root == null || root.getMessage() == null
            ? "" : root.getMessage();
        AdaptiveBackendController backend = components.backends.get(
            OptimizationModule.MODERN_PARTICLE_BACKEND);
        if (backend != null) backend.runtimeFailure(error);
        detail = "粒子动态流异常；粒子子后端已回退："
            + (error == null ? "unknown" : error.getClass().getSimpleName()
                + (error.getMessage() == null || error.getMessage().isEmpty()
                    ? "" : ": " + error.getMessage()));
    }

    public void fbpPacketFailure(Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        AdaptiveBackendController backend = components.backends.get(
            OptimizationModule.FBP_PARTICLE_ADAPTER);
        if (backend != null) backend.runtimeFailure(error);
        detail = "FBP 原始粒子 Packet 异常；标准粒子实例流保持可用："
            + (error == null ? "unknown" : error.getClass().getSimpleName());
    }

    public void recordFbpPacket(int vertices, boolean modern) {
        if (!initialized || currentStamp == null || components.profiler == null
            || vertices <= 0) return;
        RenderBackendId backend = modern ? RenderBackendId.ICE_NATIVE
            : RenderBackendId.LEGACY;
        components.profiler.addCounter(currentStamp, RenderPass.PARTICLES, backend,
            modern ? RenderCounter.DRAW : RenderCounter.LEGACY_FALLBACK, 1L);
        components.profiler.addCounter(currentStamp, RenderPass.PARTICLES, backend,
            RenderCounter.VERTEX, vertices);
        if (modern) components.profiler.addCounter(currentStamp, RenderPass.PARTICLES,
            backend, RenderCounter.MESH_UPLOAD_BYTES,
            (long) vertices * LwjglFbpPacketRenderer.exactStrideBytes());
    }

    public void hudBackendFailure(Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        AdaptiveBackendController backend = components.backends.get(
            OptimizationModule.MODERN_HUD_STREAM);
        if (backend != null) backend.runtimeFailure(error);
        detail = "HUD/字体动态流异常；HUD 子后端已回退："
            + (error == null ? "unknown" : error.getClass().getSimpleName());
    }

    public void shaderBridgeFailure(Throwable error) {
        shaderRuntime.shaderBridgeFailure(error);
    }

    public void beforeOptifineProgramSwitch() {
        shaderRuntime.beforeOptifineProgramSwitch();
    }

    public void beforeOptifineProgramSwitch(Object requestedProgram,
                                            boolean logicalProgramChanged) {
        shaderRuntime.beforeOptifineProgramSwitch(requestedProgram, logicalProgramChanged);
    }

    public void observeOptifineProgram(OptifineProgramState state) {
        shaderRuntime.observeOptifineProgram(state);
    }

    public void observeOptifineProgram(Object programIdentity,
                                       OptifineProgramState state) {
        shaderRuntime.observeOptifineProgram(programIdentity, state);
    }

    /** Package-visible CPU gate for the outcome-uncertain restoration test. */
    boolean reconcileObservedShaderProgram(int observedProgram,
                                           boolean synchronizedProgram) {
        return shaderRuntime.reconcileObservedShaderProgram(observedProgram, synchronizedProgram);
    }

    public void abortOptifineProgramSwitch() {
        shaderRuntime.abortOptifineProgramSwitch();
    }

    /** Flushes the bounded HUD stream and records the exact fallback reason. */
    public LwjglHudRenderer.FlushResult flushHudStream() {
        if (!initialized || components.hudRenderer == null || components.threadGuard == null
            || !components.threadGuard.isRenderThread()) return LwjglHudRenderer.FlushResult.EMPTY;
        int commands = components.hudRenderer.getCommandCount();
        int vertices = components.hudRenderer.getVertexCount();
        LwjglHudRenderer.FlushResult result = components.hudRenderer.flushTracked();
        if (currentStamp != null && components.profiler != null && commands > 0) {
            if (result == LwjglHudRenderer.FlushResult.MODERN
                || result == LwjglHudRenderer.FlushResult.FAILED_AFTER_DRAW) {
                components.profiler.addCounter(currentStamp, RenderPass.HUD_GUI,
                    RenderBackendId.ICE_NATIVE, RenderCounter.MULTI_DRAW, 1L);
                components.profiler.addCounter(currentStamp, RenderPass.HUD_GUI,
                    RenderBackendId.ICE_NATIVE, RenderCounter.VERTEX, vertices);
            }
            if (result == LwjglHudRenderer.FlushResult.LEGACY_BUSY
                || result == LwjglHudRenderer.FlushResult.LEGACY_STATE
                || result == LwjglHudRenderer.FlushResult.FAILED_BEFORE_DRAW) {
                components.profiler.addCounter(currentStamp, RenderPass.HUD_GUI,
                    RenderBackendId.LEGACY, RenderCounter.LEGACY_FALLBACK,
                    commands);
            }
            if (result == LwjglHudRenderer.FlushResult.LEGACY_BUSY) {
                components.profiler.addCounter(currentStamp, RenderPass.HUD_GUI,
                    RenderBackendId.ICE_NATIVE, RenderCounter.FENCE_BUSY, 1L);
            }
        }
        if (result == LwjglHudRenderer.FlushResult.FAILED_BEFORE_DRAW
            || result == LwjglHudRenderer.FlushResult.FAILED_AFTER_DRAW) {
            Throwable error = components.hudRenderer.getLastError();
            hudBackendFailure(error == null
                ? new IllegalStateException("HUD submission failed") : error);
        }
        return result;
    }

    public void recordHudFontCache(int hits, int misses) {
        if (currentStamp == null || components.profiler == null) return;
        if (hits > 0) {
            components.profiler.addCounter(currentStamp, RenderPass.HUD_GUI,
                RenderBackendId.ICE_NATIVE, RenderCounter.CACHE_HIT, hits);
        }
        if (misses > 0) {
            components.profiler.addCounter(currentStamp, RenderPass.HUD_GUI,
                RenderBackendId.ICE_NATIVE, RenderCounter.CACHE_MISS, misses);
        }
    }

    /** Queries only bindings used by the next actual HUD stream submission. */
    public boolean resynchronizeHudState() {
        if (!initialized || components.threadGuard == null || !components.threadGuard.isRenderThread()) {
            return false;
        }
        try {
            GlStateQueryWorkspace workspace = components.glStateQueryWorkspace;
            if (workspace == null || workspace.isClosed()) {
                throw new IllegalStateException("HUD state query workspace unavailable");
            }
            int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            int arrayBuffer = GL11.glGetInteger(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER_BINDING);
            int activeTextureEnum = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            int clientActiveTextureEnum = GL11.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE);
            int texture0;
            boolean texture0Enabled;
            if (activeTextureEnum == GL13.GL_TEXTURE0) {
                texture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                texture0Enabled = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            } else {
                try {
                    GL13.glActiveTexture(GL13.GL_TEXTURE0);
                    texture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                    texture0Enabled = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
                } finally {
                    GL13.glActiveTexture(activeTextureEnum);
                }
            }
            IntBuffer viewport = workspace.viewport();
            GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
            EarlyGlStateTracker.useProgram(program);
            EarlyGlStateTracker.bindVertexArray(vertexArray);
            EarlyGlStateTracker.bindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, arrayBuffer);
            EarlyGlStateTracker.clientActiveTexture(clientActiveTextureEnum);
            EarlyGlStateTracker.activeTexture(GL13.GL_TEXTURE0);
            EarlyGlStateTracker.bindTexture(texture0);
            EarlyGlStateTracker.texture2dEnabled(texture0Enabled);
            EarlyGlStateTracker.activeTexture(activeTextureEnum);
            EarlyGlStateTracker.viewport(viewport.get(0), viewport.get(1),
                viewport.get(2), viewport.get(3));
            if (currentStamp != null && components.profiler != null) {
                components.profiler.addCounter(currentStamp, RenderPass.HUD_GUI,
                    RenderBackendId.ICE_NATIVE, RenderCounter.STATE_RESYNC, 1L);
            }
            return true;
        } catch (Throwable error) {
            EarlyGlStateTracker.invalidate();
            hudBackendFailure(error);
            return false;
        }
    }

    public void recordParticleInstances(int modernInstances, int legacyFallbacks) {
        if (!initialized || currentStamp == null || components.profiler == null) return;
        if (modernInstances > 0) {
            components.profiler.addCounter(currentStamp, RenderPass.PARTICLES,
                RenderBackendId.ICE_NATIVE, RenderCounter.INSTANCE,
                modernInstances);
            components.profiler.addCounter(currentStamp, RenderPass.PARTICLES,
                RenderBackendId.ICE_NATIVE, RenderCounter.VERTEX,
                (long) modernInstances * 4L);
        }
        if (legacyFallbacks > 0) {
            components.profiler.addCounter(currentStamp, RenderPass.PARTICLES,
                RenderBackendId.LEGACY, RenderCounter.LEGACY_FALLBACK,
                legacyFallbacks);
        }
    }

    /** Receives an exact ModelRenderer payload only on the captured GL thread. */
    public boolean acceptModelMesh(ModelMeshPayload payload) {
        if (!initialized || currentStamp == null || components.modelMeshes == null
            || payload == null || !isModelMeshBackendOperational()
            || insideLegacyIsland()
            || components.threadGuard == null || !components.threadGuard.isRenderThread()) {
            return false;
        }
        if (payload.getResourceGeneration() != currentStamp.getResourceGeneration()
            || payload.getContextGeneration() != currentStamp.getGlContextGeneration()) {
            return false;
        }
        try {
            boolean accepted = components.modelMeshes.accept(payload, currentStamp);
            if (accepted) {
                AdaptiveBackendController entity = components.backends.get(
                    OptimizationModule.MODERN_ENTITY_BACKEND);
                AdaptiveBackendController tesr = components.backends.get(
                    OptimizationModule.MODERN_TESR_BACKEND);
                if (entity != null && entity.candidateDataPublished()) {
                    measurements.discardBackendSamples(OptimizationModule.MODERN_ENTITY_BACKEND);
                }
                if (tesr != null && tesr.candidateDataPublished()) {
                    measurements.discardBackendSamples(OptimizationModule.MODERN_TESR_BACKEND);
                }
            }
            return accepted;
        } catch (Throwable error) {
            modelBackendFailure(error);
            return false;
        }
    }

    /**
     * A captured mesh is shared by the entity and TESR paths, so retaining it
     * is useful while either independently-fused backend remains operational.
     */
    public boolean isModelMeshBackendOperational() {
        return OptimizerRegistry.isOperational(
            OptimizationModule.MODERN_ENTITY_BACKEND)
            || OptimizerRegistry.isOperational(
                OptimizationModule.MODERN_TESR_BACKEND);
    }

    public CurrentFrameTerrainOcclusion terrainSoftwareOcclusion() {
        return !runtimePaused && OptimizerConfig.settings.enabled && OptimizerConfig.settings.modernRenderer
            && OptimizerConfig.settings.terrainSoftwareOcclusion ? softwareOcclusion : null;
    }

    /** Publish immutable atlas evidence once per resource change, including while F6 is paused. */
    public void publishTerrainOcclusionTextures(net.minecraft.client.renderer.texture.TextureMap atlas) {
        softwareOcclusion.publishTextures(atlas);
    }

    /** The captured Minecraft matrices belong to this primary view only. */
    public RenderMatrixBridge.Snapshot softwareTerrainMatrices() {
        if (softwareOcclusion.getMeshCount() < 2
            || !initialized || currentStamp == null || components.coordinator == null
            || components.coordinator.viewDepth() != 1 || insideLegacyIsland()
            || !shaderRuntime.shaderPackStateKnown || shaderRuntime.shaderPackActive
            || OptifinePassLifecycleBridge.isShadowPass()) return null;
        // Inspect only the layer's depth/coverage semantics. A mod callback may
        // invalidate the broad mirror without changing these ordinary states.
        if (GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) != 0
            || !GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
            || !GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
            || GL11.glGetInteger(GL11.GL_DEPTH_FUNC) != GL11.GL_LEQUAL
            || !GL11.glIsEnabled(GL11.GL_CULL_FACE)
            || GL11.glGetInteger(GL11.GL_FRONT_FACE) != GL11.GL_CCW
            || GL11.glGetInteger(GL11.GL_CULL_FACE_MODE) != GL11.GL_BACK
            || GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL)) return null;
        FloatBuffer range = components.glStateQueryWorkspace.depthRange();
        GL11.glGetFloat(GL11.GL_DEPTH_RANGE, range);
        if (range.get(0) != 0.0F || range.get(1) != 1.0F) return null;
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int texture;
        try {
            if (active != GL13.GL_TEXTURE0) GL13.glActiveTexture(GL13.GL_TEXTURE0);
            texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        } finally {
            if (active != GL13.GL_TEXTURE0) GL13.glActiveTexture(active);
        }
        if (texture != Minecraft.getMinecraft().getTextureMapBlocks().getGlTextureId()) return null;
        return RenderMatrixBridge.snapshot();
    }

    private void configureTerrainBatching() {
        components.terrainArena.configureNativeBatching(
            OptimizerConfig.settings.terrainShaderBatching
                && shaderRuntime.shaderPackStateKnown && !shaderRuntime.shaderPackActive,
            OptimizerConfig.settings.terrainDirectionalCulling);
    }

    /** Compiler work can wake a model backend without enabling per-draw inspection. */
    public boolean mayCaptureModelMeshes() {
        if (runtimePaused) {
            return OptimizerRegistry.isConfiguredOperational(OptimizationModule.MODERN_ENTITY_BACKEND)
                || OptimizerRegistry.isConfiguredOperational(OptimizationModule.MODERN_TESR_BACKEND);
        }
        if (!initialized) return !shutdownRequested;
        AdaptiveBackendController entity = components.backends.get(
            OptimizationModule.MODERN_ENTITY_BACKEND);
        AdaptiveBackendController tesr = components.backends.get(
            OptimizationModule.MODERN_TESR_BACKEND);
        return mayCaptureModelMeshes(entity) || mayCaptureModelMeshes(tesr);
    }

    private static boolean mayCaptureModelMeshes(AdaptiveBackendController backend) {
        return backend != null && (backend.lifecycleState() == BackendLifecycleState.WARMUP
            || backend.isAwaitingCandidateData()
            || backend.mayInspectOrRetestCandidate());
    }

    /** True only after startup has made a final decision to reject both users. */
    public boolean shouldDropDeferredModelMeshes() {
        return !runtimePaused && initialized && !isModelMeshBackendOperational();
    }

    private boolean prepareModelMeshAdmissions() {
        if (shouldDropDeferredModelMeshes()) return true;
        if (EarlyGlStateTracker.arrayBufferBinding() != Integer.MIN_VALUE) {
            return true;
        }
        modelUploadBindingAttempts = saturatedIncrement(
            modelUploadBindingAttempts);
        try {
            int binding = GL11.glGetInteger(
                org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER_BINDING);
            if (binding < 0) {
                throw new IllegalStateException(
                    "invalid model upload array-buffer binding");
            }
            org.lwjgl.opengl.GL15.glBindBuffer(
                org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, binding);
            EarlyGlStateTracker.bindBuffer(
                org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, binding);
            if (EarlyGlStateTracker.arrayBufferBinding() != binding) {
                throw new IllegalStateException(
                    "model upload binding publication incomplete");
            }
            modelUploadBindingRecoveries = saturatedIncrement(
                modelUploadBindingRecoveries);
            return true;
        } catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            EarlyGlStateTracker.invalidate();
            modelUploadBindingFailures = saturatedIncrement(
                modelUploadBindingFailures);
            lastModelStateFailureStage = "upload-array-buffer";
            lastModelStateFailureType = error.getClass().getName();
            lastModelStateFailureMessage = error.getMessage() == null
                ? "" : error.getMessage();
            return false;
        }
    }

    /** Render-frame epoch used to bound deferred VBO admission retries. */
    public long modelMeshAdmissionEpoch() {
        return initialized && currentStamp != null && components.threadGuard != null
            && components.threadGuard.isRenderThread() ? currentStamp.getFrameId() : -1L;
    }

    public void invalidateModelMesh(int displayList) {
        if (!initialized || components.modelMeshes == null || displayList <= 0
            || components.threadGuard == null || !components.threadGuard.isRenderThread()) return;
        try { components.modelMeshes.invalidate(displayList); }
        catch (Throwable error) { modelBackendFailure(error); }
    }

    /** One gate for a certified, immediately rendered ModelRenderer tree. */
    public LwjglModelMeshCache modelStateBatchCandidate() {
        OptimizationModule module = RenderLibRenderBridge.currentModule();
        if ((module != OptimizationModule.MODERN_ENTITY_BACKEND
            && module != OptimizationModule.MODERN_TESR_BACKEND)
            || RenderLibRenderBridge.currentPass() == null
            || !RenderLibRenderBridge.candidateAllowed()
            || !initialized || currentStamp == null || components.modelMeshes == null
            || insideLegacyIsland() || !shaderRuntime.shaderPackStateKnown || shaderRuntime.shaderPackActive
            || components.threadGuard == null || !components.threadGuard.isRenderThread()) return null;
        AdaptiveBackendController backend = components.backends.get(module);
        if (backend == null) return null;
        BackendLifecycleState state = backend.lifecycleState();
        if (!acceptsCandidateUploads(state)
            || state == BackendLifecycleState.OUTPUT_VALIDATE
            || !ensureModelTrackedState(backend, module)) return null;
        return components.modelMeshes;
    }

    public FrameStamp modelStateBatchStamp() {
        return currentStamp;
    }

    /** Records a completed model scope once, preserving its per-part draw count. */
    public void recordModelStateBatchDraws(int count) {
        OptimizationModule module = RenderLibRenderBridge.currentModule();
        RenderPass pass = RenderLibRenderBridge.currentPass();
        RenderLibRenderBridge.markEligibleModernDraws(count);
        int backendIndex = module == OptimizationModule.MODERN_TESR_BACKEND ? 1 : 0;
        int reasonIndex = ModelDrawReason.MODERN_DRAW.ordinal();
        long previous = modelDrawReasons[backendIndex][reasonIndex];
        modelDrawReasons[backendIndex][reasonIndex] =
            previous > Long.MAX_VALUE - count ? Long.MAX_VALUE : previous + count;
        components.profiler.addCounter(currentStamp, pass, RenderBackendId.ICE_NATIVE,
            RenderCounter.DRAW, count);
    }

    /** Called by the patched ModelRenderer call-list instruction. */
    public void validateModelMeshAtCallSite(int displayList) {
        OptimizationModule module = RenderLibRenderBridge.currentModule();
        if ((module == OptimizationModule.MODERN_ENTITY_BACKEND
            || module == OptimizationModule.MODERN_TESR_BACKEND)
            && backendLifecycleState(module) == BackendLifecycleState.OUTPUT_VALIDATE) {
            // This branch certifies the captured bytes and returns without a
            // submission. Never allow a stale traversal sample to turn the
            // validation hook into a second draw after the controller advances.
            tryDrawModelMesh(displayList);
        }
    }

    /** Called by the patched ModelRenderer call-list instruction. */
    public boolean tryDrawModelMesh(int displayList) {
        OptimizationModule module = RenderLibRenderBridge.currentModule();
        RenderPass pass = RenderLibRenderBridge.currentPass();
        if ((module != OptimizationModule.MODERN_ENTITY_BACKEND
            && module != OptimizationModule.MODERN_TESR_BACKEND)
            || pass == null) {
            modelDrawOutsideTraversal = saturatedIncrement(
                modelDrawOutsideTraversal);
            return false;
        }
        if (!initialized || currentStamp == null || components.modelMeshes == null
            || displayList <= 0) {
            incrementModelDrawReason(module, ModelDrawReason.RUNTIME_NOT_READY);
            return false;
        }
        if (insideLegacyIsland()) {
            incrementModelDrawReason(module, ModelDrawReason.LEGACY_ISLAND);
            return false;
        }
        if (!shaderRuntime.shaderPackStateKnown) {
            incrementModelDrawReason(module,
                ModelDrawReason.SHADER_STATE_UNKNOWN);
            return false;
        }
        if (shaderRuntime.shaderPackActive) {
            incrementModelDrawReason(module, ModelDrawReason.SHADER_PACK_ACTIVE);
            return false;
        }
        AdaptiveBackendController backend = components.backends.get(module);
        if (backend == null) {
            incrementModelDrawReason(module,
                ModelDrawReason.BACKEND_UNAVAILABLE);
            return false;
        }
        BackendLifecycleState state = backend.lifecycleState();
        if (!acceptsCandidateUploads(state)) {
            incrementModelDrawReason(module,
                ModelDrawReason.BACKEND_STATE_REJECTED);
            return false;
        }
        LwjglModelMeshCache.Entry entry = components.modelMeshes.find(displayList, currentStamp);
        if (entry == null) {
            incrementModelDrawReason(module, ModelDrawReason.CACHE_MISS);
            return false;
        }
        RenderLibRenderBridge.markEligibleDraw();

        if (state == BackendLifecycleState.OUTPUT_VALIDATE) {
            if (!ensureModelTrackedState(backend, module)) {
                incrementModelDrawReason(module,
                    ModelDrawReason.STATE_REAUTHENTICATION_FAILED);
                return false;
            }
            boolean equivalent;
            try {
                equivalent = entry.isValidated() || components.modelMeshes.validate(entry);
            } catch (Throwable error) {
                modelBackendFailure(error);
                return false;
            }
            if (equivalent) genericModelVboValidated = true;
            measurements.recordValidationOnce(module, equivalent,
                equivalent ? null : "ModelRenderer VBO byte/output certification failed");
            incrementModelDrawReason(module, equivalent
                ? ModelDrawReason.OUTPUT_VALIDATION_PASSED
                : ModelDrawReason.OUTPUT_VALIDATION_FAILED);
            return false;
        }
        if (!entry.isValidated() && genericModelVboValidated) {
            components.modelMeshes.certifyCapturedEntry(entry);
        }
        if (!entry.isValidated()) {
            incrementModelDrawReason(module, ModelDrawReason.ENTRY_UNVALIDATED);
            return false;
        }
        if (!RenderLibRenderBridge.candidateAllowed()) {
            incrementModelDrawReason(module,
                ModelDrawReason.MEASUREMENT_ARM_LEGACY);
            return false;
        }
        if (!ensureModelTrackedState(backend, module)) {
            incrementModelDrawReason(module,
                ModelDrawReason.STATE_REAUTHENTICATION_FAILED);
            return false;
        }
        try {
            boolean handled = components.modelMeshes.drawCurrent(entry, currentStamp);
            if (!handled) {
                incrementModelDrawReason(module, ModelDrawReason.DRAW_DECLINED);
                return false;
            }
            RenderLibRenderBridge.markModernDraw();
            incrementModelDrawReason(module, ModelDrawReason.MODERN_DRAW);
            components.profiler.addCounter(currentStamp, pass, RenderBackendId.ICE_NATIVE,
                RenderCounter.DRAW, 1L);
            return true;
        } catch (Throwable error) {
            // Either glDrawArrays was issued or client/array restoration became
            // indeterminate. Executing the display list now could duplicate
            // transparency or consume corrupt client-array state.
            backend.runtimeFailure(error);
            RenderLibRenderBridge.markModernDraw();
            incrementModelDrawReason(module,
                ModelDrawReason.DRAW_OUTCOME_UNCERTAIN);
            detail = "实体/TESR VBO 发射异常；对应子后端已回退："
                + error.getClass().getSimpleName();
            return true;
        }
    }

    /**
     * Rebuilds only the fixed-function state needed by the entity/TESR VBO
     * path.  It runs once after an observing compatibility invalidation, not
     * once per model part, and every queried value is rebound before it is
     * published to the software mirrors.
     */
    private boolean ensureModelTrackedState(AdaptiveBackendController backend,
                                            OptimizationModule module) {
        boolean glAuthenticated = EarlyGlStateTracker.hasModelDrawState();
        // Immediate VBOs and ordinary array scopes use the driver's matrices.
        // Only the optional pose palette requires a CPU matrix mirror.
        if (glAuthenticated) return true;
        long glInvalidation = EarlyGlStateTracker.invalidations();
        long frame = currentStamp == null ? -1L : currentStamp.getFrameId();
        if (failedModelReauthenticationFrame == frame
            && failedModelGlInvalidation == glInvalidation) {
            modelStateReauthenticationSuppressions = saturatedIncrement(
                modelStateReauthenticationSuppressions);
            return false;
        }
        modelStateReauthenticationAttempts = saturatedIncrement(
            modelStateReauthenticationAttempts);
        String stage = "workspace";
        try {
            GlStateQueryWorkspace workspace = components.glStateQueryWorkspace;
            if (workspace == null || workspace.isClosed()) {
                throw new IllegalStateException(
                    "model state query workspace unavailable");
            }
            if (!glAuthenticated) {
                stage = "gl-state";
                reauthenticateModelGlState(workspace);
                glAuthenticated = true;
            }
            stage = "publication";
            if (!modelPublicationComplete()) {
                throw new IllegalStateException(
                    "model GL state publication incomplete");
            }
            failedModelGlInvalidation = Long.MIN_VALUE;
            failedModelMatrixInvalidation = Long.MIN_VALUE;
            failedModelReauthenticationFrame = Long.MIN_VALUE;
            if (backend != null) backend.recoverableRuntimeSuccess();
            modelStateReauthentications = saturatedIncrement(
                modelStateReauthentications);
            return true;
        } catch (Throwable error) {
            if (!glAuthenticated) EarlyGlStateTracker.invalidate();
            failedModelGlInvalidation = EarlyGlStateTracker.invalidations();
            failedModelMatrixInvalidation =
                EarlyMatrixStateTracker.invalidations();
            failedModelReauthenticationFrame = frame;
            modelStateReauthenticationFailures = saturatedIncrement(
                modelStateReauthenticationFailures);
            FatalErrors.rethrowIfFatal(error);
            lastModelStateFailureStage = stage;
            lastModelStateFailureType = error.getClass().getName();
            lastModelStateFailureMessage = error.getMessage() == null
                ? "" : error.getMessage();
            if (backend != null) backend.recoverableRuntimeFailure(error);
            boolean quarantined = backend != null
                && backend.lifecycleState() == BackendLifecycleState.QUARANTINED;
            detail = (module == OptimizationModule.MODERN_TESR_BACKEND
                ? "TESR" : "实体")
                + "状态重新认证失败；"
                + (quarantined ? "连续失败已隔离后端：" : "当前 draw 精确回退：")
                + error.getClass().getSimpleName()
                + (error.getMessage() == null ? "" : ": " + error.getMessage());
            return false;
        }
    }

    private void reauthenticateModelGlState(GlStateQueryWorkspace workspace) {
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int readFramebuffer = GL11.glGetInteger(
            GL30.GL_READ_FRAMEBUFFER_BINDING);
        int drawFramebuffer = GL11.glGetInteger(
            GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        int pixelPackBuffer = GL11.glGetInteger(
            GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int arrayBuffer = GL11.glGetInteger(
            org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER_BINDING);
        int activeTextureEnum = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int clientActiveTextureEnum = GL11.glGetInteger(
            GL13.GL_CLIENT_ACTIVE_TEXTURE);
        int activeTexture = activeTextureEnum - GL13.GL_TEXTURE0;
        int clientActiveTexture = clientActiveTextureEnum - GL13.GL_TEXTURE0;
        if (program < 0 || readFramebuffer < 0 || drawFramebuffer < 0
            || pixelPackBuffer < 0 || arrayBuffer < 0
            || activeTexture < 0 || activeTexture >= 32
            || clientActiveTexture < 0 || clientActiveTexture >= 32) {
            throw new IllegalStateException("invalid model GL state");
        }

        int texture0 = 0;
        int texture1 = 0;
        Throwable textureFailure = null;
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            texture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            texture1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        } catch (Throwable queryFailure) {
            textureFailure = queryFailure;
        }
        try { GL13.glActiveTexture(activeTextureEnum); }
        catch (Throwable restoreFailure) {
            textureFailure = appendRuntimeFailure(textureFailure,
                restoreFailure);
        }
        if (textureFailure != null) rethrowRuntimeFailure(textureFailure);

        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        int blendSourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int blendDestinationRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int blendSourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int blendDestinationAlpha = GL11.glGetInteger(
            GL14.GL_BLEND_DST_ALPHA);
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        ByteBuffer colorMaskValues = workspace.colorMask();
        GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK, colorMaskValues);
        int colorMask = (colorMaskValues.get(0) != 0 ? 1 : 0)
            | (colorMaskValues.get(1) != 0 ? 2 : 0)
            | (colorMaskValues.get(2) != 0 ? 4 : 0)
            | (colorMaskValues.get(3) != 0 ? 8 : 0);
        FloatBuffer currentColor = workspace.currentColor();
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, currentColor);
        FloatBuffer depthRange = workspace.depthRange();
        GL11.glGetFloat(GL11.GL_DEPTH_RANGE, depthRange);

        OpenGlHelper.glUseProgram(program);
        if (readFramebuffer == drawFramebuffer) {
            OpenGlHelper.glBindFramebuffer(GL30.GL_FRAMEBUFFER,
                readFramebuffer);
        } else {
            OpenGlHelper.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,
                readFramebuffer);
            OpenGlHelper.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,
                drawFramebuffer);
        }
        GlStateManager.depthFunc(depthFunction);
        OpenGlHelper.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pixelPackBuffer);
        OpenGlHelper.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER,
            arrayBuffer);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(texture0);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE1);
        GlStateManager.bindTexture(texture1);
        GlStateManager.setActiveTexture(activeTextureEnum);
        OpenGlHelper.setClientActiveTexture(clientActiveTextureEnum);
        if (blend) GlStateManager.enableBlend();
        else GlStateManager.disableBlend();
        GlStateManager.tryBlendFuncSeparate(blendSourceRgb,
            blendDestinationRgb, blendSourceAlpha, blendDestinationAlpha);
        if (depthTest) GlStateManager.enableDepth();
        else GlStateManager.disableDepth();
        GlStateManager.depthMask(depthMask);
        if (cull) GlStateManager.enableCull();
        else GlStateManager.disableCull();
        GlStateManager.colorMask((colorMask & 1) != 0,
            (colorMask & 2) != 0, (colorMask & 4) != 0,
            (colorMask & 8) != 0);
        GlStateManager.color(currentColor.get(0), currentColor.get(1),
            currentColor.get(2), currentColor.get(3));

        EarlyGlStateTracker.useProgram(program);
        EarlyGlStateTracker.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER,
            readFramebuffer);
        EarlyGlStateTracker.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,
            drawFramebuffer);
        EarlyGlStateTracker.depthFunction(depthFunction);
        EarlyGlStateTracker.seedDepthRange(depthRange.get(0),
            depthRange.get(1));
        EarlyGlStateTracker.bindBuffer(GL21.GL_PIXEL_PACK_BUFFER,
            pixelPackBuffer);
        EarlyGlStateTracker.bindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER,
            arrayBuffer);
        EarlyGlStateTracker.clientActiveTexture(clientActiveTextureEnum);
        EarlyGlStateTracker.seedDrawState(activeTexture, texture0, texture1,
            blend, blendSourceRgb, blendDestinationRgb, blendSourceAlpha,
            blendDestinationAlpha, depthTest, depthMask, cull, colorMask,
            currentColor.get(0), currentColor.get(1), currentColor.get(2),
            currentColor.get(3));
        if (!EarlyGlStateTracker.hasModelDrawState()) {
            throw new IllegalStateException(
                "model GL state publication incomplete");
        }
    }

    /**
     * ModelRenderer submission intentionally authenticates a strict subset of
     * the global compatibility mirror. Requiring snapshot() here also requires
     * unrelated PBO/FBO state and turns a valid fixed-function model state into
     * three consecutive false failures and a permanent TESR quarantine.
     */
    static boolean modelPublicationComplete() {
        return EarlyGlStateTracker.hasModelDrawState();
    }

    /** Gate shared by reviewed non-ModelRenderer mesh emitters. */
    public boolean canEmitExternalModelMesh() {
        if (!initialized || currentStamp == null
            || insideLegacyIsland()
            || !shaderRuntime.shaderPackStateKnown || shaderRuntime.shaderPackActive) return false;
        OptimizationModule module = RenderLibRenderBridge.currentModule();
        RenderPass pass = RenderLibRenderBridge.currentPass();
        if ((module != OptimizationModule.MODERN_ENTITY_BACKEND
            && module != OptimizationModule.MODERN_TESR_BACKEND) || pass == null
            || !RenderLibRenderBridge.candidateAllowed()) return false;
        AdaptiveBackendController backend = components.backends.get(module);
        if (backend == null) return false;
        if (!backend.isDirectActivation() && (!genericModelVboValidated
            || !components.capabilities.passed(ModernCapability.MODEL_MESH_VBO))) return false;
        BackendLifecycleState state = backend.lifecycleState();
        if (state != BackendLifecycleState.PAIRED_MEASURE
            && state != BackendLifecycleState.MODERN
            && state != BackendLifecycleState.REGRESSION_MONITOR) return false;
        return EarlyGlStateTracker.isKnown() && EarlyGlStateTracker.hasModelDrawState()
            && EarlyGlStateTracker.modelProgramBinding() == 0;
    }

    public void recordExternalModelDraw() {
        if (!initialized || currentStamp == null) return;
        RenderPass pass = RenderLibRenderBridge.currentPass();
        if (pass == null) return;
        RenderLibRenderBridge.markModernDraw();
        components.profiler.addCounter(currentStamp, pass, RenderBackendId.ICE_NATIVE,
            RenderCounter.DRAW, 1L);
    }

    /**
     * Transfers an externally-created, already-detached VBO into the renderer
     * ledger without charging its pre-allocation reservation twice.
     */
    public RenderHandle adoptExternalModelBuffer(int nativeId, long chargedBytes,
                                                  long resourceGeneration,
                                                  long contextGeneration,
                                                  CacheBudget.Reservation reservation) {
        if (!initialized || currentStamp == null || components.resources == null
            || components.threadGuard == null || !components.threadGuard.isRenderThread()
            || resourceGeneration != currentStamp.getResourceGeneration()
            || contextGeneration != currentStamp.getGlContextGeneration()) return null;
        return components.resources.registerReserved(RenderResourceKind.BUFFER, nativeId,
            chargedBytes, resourceGeneration, contextGeneration, reservation);
    }

    /** Retires one detached external-model VBO after all earlier draws. */
    public boolean retireExternalModelBuffer(RenderHandle handle) {
        if (!initialized || components.resources == null || components.threadGuard == null
            || !components.threadGuard.isRenderThread() || handle == null) return false;
        return components.resources.retire(handle,
            LwjglRetirementFence.afterCurrentCommands(components.resources));
    }

    public void flushModelPackets(OptimizationModule module) {
        if (components.modelMeshes != null) components.modelMeshes.flushPoseBatch();
    }

    public RenderBackendSample beginRenderBackendSample(OptimizationModule module,
                                                        RenderPass pass) {
        return measurements.beginRenderBackendSample(module, pass);
    }

    public void endRenderBackendSample(RenderBackendSample sample,
                                       boolean stable, boolean modernWork) {
        measurements.endRenderBackendSample(sample, stable, modernWork);
    }

    private void modelBackendFailure(Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        AdaptiveBackendController entity = components.backends.get(OptimizationModule.MODERN_ENTITY_BACKEND);
        AdaptiveBackendController tesr = components.backends.get(OptimizationModule.MODERN_TESR_BACKEND);
        if (entity != null) entity.runtimeFailure(error);
        if (tesr != null) tesr.runtimeFailure(error);
    }

    /** Records a traversal-scope failure against only its owning backend. */
    public void modelTraversalFailure(OptimizationModule module, Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        if (module != OptimizationModule.MODERN_ENTITY_BACKEND
            && module != OptimizationModule.MODERN_TESR_BACKEND) return;
        AdaptiveBackendController backend = components.backends.get(module);
        if (backend != null) backend.runtimeFailure(error);
    }

    public boolean tryUploadTerrain(TerrainUploadContext.Value context,
                                    BufferBuilder builder, VertexBuffer vertexBuffer) {
        boolean shaderTerrainSafe = isShaderPackSafeForTerrainArena();
        if (!initialized || currentStamp == null || components.terrainArena == null
            || !shaderRuntime.shaderPackStateKnown || shaderRuntime.shaderPackActive && !shaderTerrainSafe) {
            return false;
        }
        AdaptiveBackendController backend = components.backends.get(
            OptimizationModule.MODERN_TERRAIN_BACKEND);
        BackendLifecycleState terrainState = backend == null
            ? BackendLifecycleState.LEGACY : backend.lifecycleState();
        if (backend == null || !acceptsCandidateUploads(terrainState)) {
            return false;
        }
        configureTerrainBatching();
        boolean retainLegacyCopy = shaderRuntime.shaderPackActive
            || retainsTerrainLegacyCopy(terrainState);
        int uploadBytes = builder == null || builder.getByteBuffer() == null
            ? 0 : builder.getByteBuffer().limit();
        if (retainLegacyCopy) {
            terrainShadowUploadAttempts = saturatedIncrement(
                terrainShadowUploadAttempts);
            // Qualification is measured on SOLID.  Letting CUTOUT and
            // TRANSLUCENT shadow copies consume the fixed arena first made the
            // arena reach capacity while SOLID ownership remained too sparse
            // to start a single ABBA sample. ShaderPack certification is the
            // exception: it requires an exact Legacy twin for every admitted
            // layer until its native layout is certified.
            if (!terrainShadowQualificationAllowed(context.getLayer(),
                shaderRuntime.shaderPackActive)) {
                terrainShadowQualificationSkips = saturatedIncrement(
                    terrainShadowQualificationSkips);
                return false;
            }
            long frame = currentStamp.getFrameId();
            if (terrainShadowBudgetFrame != frame) {
                terrainShadowBudgetFrame = frame;
                terrainShadowUploadsThisFrame = 0;
                terrainShadowBytesThisFrame = 0L;
            }
            if (!terrainShadowBudgetAllows(terrainShadowUploadsThisFrame,
                terrainShadowBytesThisFrame, uploadBytes)) {
                terrainShadowBudgetRejects = saturatedIncrement(
                    terrainShadowBudgetRejects);
                return false;
            }
        }
        AdaptiveBackendController mapping = components.backends.get(
            OptimizationModule.MODERN_TERRAIN_PERSISTENT_MAPPING);
        BackendLifecycleState mappingState = mapping == null
            ? BackendLifecycleState.LEGACY : mapping.lifecycleState();
        MeasurementArm mappingArm = mappingState == BackendLifecycleState.PAIRED_MEASURE
            ? mapping.expectedMeasurementArm()
            : (mappingState == BackendLifecycleState.LEGACY
                || mappingState == BackendLifecycleState.QUARANTINED
                    ? MeasurementArm.LEGACY : MeasurementArm.MODERN);
        boolean allowPersistentStorage = mapping != null
            && acceptsCandidateUploads(mappingState);
        boolean preferPersistentWrite = allowPersistentStorage
            && mappingArm == MeasurementArm.MODERN;
        CorrelatedRenderProfiler.CpuScope scope = components.profiler.beginCpu(currentStamp,
            terrainPass(context.getLayer()), RenderBackendId.ICE_NATIVE, CpuWorkKind.UPLOAD);
        boolean measureMapping = mapping != null
            && mapping.shouldMeasureFrame(currentStamp.getFrameId());
        SceneFingerprint mappingScene = measureMapping ? measurements.sceneFingerprint(
            Math.max(0, uploadBytes / 4096)) : null;
        long mappingStarted = measureMapping ? System.nanoTime() : 0L;
        boolean mappingStable = false;
        boolean mappedWork = false;
        boolean uploaded = false;
        Throwable operationFailure = null;
        try {
            uploaded = components.terrainArena.upload(context, builder, vertexBuffer,
                currentStamp, allowPersistentStorage, preferPersistentWrite,
                retainLegacyCopy);
            Throwable publicationError = components.terrainArena.consumePublicationFailure();
            if (publicationError != null) backend.runtimeFailure(publicationError);
            Throwable mappingError = components.terrainArena.consumeMappingFailure();
            if (mappingError != null) {
                terrainPersistentFailure(mappingError);
            }
            mappedWork = components.terrainArena.wasLastUploadPersistent();
            mappingStable = uploaded && mappingError == null
                && components.terrainArena.isPersistent()
                && (mappingArm == MeasurementArm.LEGACY || mappedWork);
            if (uploaded) {
                terrainArenaUploads = saturatedIncrement(terrainArenaUploads);
                if (retainLegacyCopy) {
                    terrainShadowUploadsThisFrame++;
                    terrainShadowBytesThisFrame = safeAdd(
                        terrainShadowBytesThisFrame, Math.max(0, uploadBytes));
                    terrainShadowUploads = saturatedIncrement(
                        terrainShadowUploads);
                    terrainShadowUploadedBytes = safeAdd(
                        terrainShadowUploadedBytes, Math.max(0, uploadBytes));
                }
                if (components.depthHistory != null) {
                    components.depthHistory.geometryChanged(currentStamp.getFrameId());
                }
                components.profiler.addCounter(currentStamp, terrainPass(context.getLayer()),
                    RenderBackendId.ICE_NATIVE, RenderCounter.MESH_UPLOAD_BYTES,
                    Math.max(0, uploadBytes));
            }
            return uploaded && !retainLegacyCopy;
        } catch (Throwable error) {
            Throwable publicationError = components.terrainArena.consumePublicationFailure();
            if (publicationError != null && publicationError != error) {
                error = appendRuntimeFailure(error, publicationError);
            }
            operationFailure = error;
            Throwable mappingError = components.terrainArena.consumeMappingFailure();
            if (mappingError != null) terrainPersistentFailure(mappingError);
            backend.runtimeFailure(error);
            // upload() commits the arena mapping and resets BufferBuilder as
            // one transaction.  Once it returned true, a later profiler,
            // breaker, or HZB-invalidation failure must not make the CoreMod
            // execute the now-empty legacy upload and replace valid geometry
            // with an empty VBO.  Keep ownership and quarantine this backend.
            if (uploaded && !retainLegacyCopy) return true;
            rethrowRuntimeFailure(error);
            return false;
        } finally {
            Throwable instrumentationFailure = null;
            try { scope.close(); }
            catch (Throwable error) {
                instrumentationFailure = appendRuntimeFailure(
                    instrumentationFailure, error);
            }
            try {
                if (measureMapping) recordAuxiliaryBackendSample(
                    OptimizationModule.MODERN_TERRAIN_PERSISTENT_MAPPING,
                    mappingState, mappingArm, mappingScene,
                    Math.max(1L, System.nanoTime() - mappingStarted),
                    mappingStable && (mappingArm == MeasurementArm.LEGACY
                        || mappedWork));
            } catch (Throwable error) {
                instrumentationFailure = appendRuntimeFailure(
                    instrumentationFailure, error);
            }
            if (instrumentationFailure != null) {
                reportInstrumentationFailure(operationFailure,
                    instrumentationFailure);
            }
        }
    }

    public void releaseTerrain(VertexBuffer vertexBuffer) {
        softwareOcclusion.release(vertexBuffer);
        if (!initialized || components.terrainArena == null || components.threadGuard == null
            || !components.threadGuard.isRenderThread()) return;
        components.terrainArena.release(vertexBuffer);
        Throwable publicationError = components.terrainArena.consumePublicationFailure();
        if (publicationError != null) {
            FatalErrors.rethrowIfFatal(publicationError);
            AdaptiveBackendController backend = components.backends.get(
                OptimizationModule.MODERN_TERRAIN_BACKEND);
            if (backend != null) backend.runtimeFailure(publicationError);
        }
    }

    public void terrainGeometryChanged() {
        if (initialized && components.depthHistory != null && components.threadGuard != null
            && components.threadGuard.isRenderThread()) {
            components.depthHistory.geometryChanged(currentStamp == null ? -1L
                : currentStamp.getFrameId());
        }
    }

    public void terrainLegacyUpload() {
        saturatedIncrement(terrainLegacyUploads);
        terrainGeometryChanged();
    }

    public void beforeTerrainLegacyUpload(VertexBuffer vertexBuffer) {
        terrainLegacyUpload();
        if (!initialized || components.terrainArena == null || components.threadGuard == null
            || !components.threadGuard.isRenderThread()) return;
        observeTerrainCandidateUpload();
        components.terrainArena.beforeLegacyUpload(vertexBuffer);
        Throwable publicationError = components.terrainArena.consumePublicationFailure();
        if (publicationError != null) {
            FatalErrors.rethrowIfFatal(publicationError);
            AdaptiveBackendController backend = components.backends.get(
                OptimizationModule.MODERN_TERRAIN_BACKEND);
            if (backend != null) backend.runtimeFailure(publicationError);
        }
    }

    public void beforeTerrainBufferStorageReplacement(VertexBuffer vertexBuffer) {
        if (initialized && components.terrainArena != null && components.threadGuard != null
            && components.threadGuard.isRenderThread())
            components.terrainArena.beforeBufferStorageReplacement(vertexBuffer);
    }

    public long modelMeshAdmissionProgress() {
        if (!initialized || components.modelMeshes == null) return Long.MIN_VALUE;
        try { return components.modelMeshes.admissionProgress(); }
        catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            modelBackendFailure(error);
            return Long.MIN_VALUE;
        }
    }

    public boolean modelMeshAdmissionBlockedByCapacity() {
        return components.modelMeshes != null
            && components.modelMeshes.lastAdmissionBlockedByCapacity();
    }

    /** The already-installed upload boundary runs even while draw hooks sleep. */
    void observeTerrainCandidateUpload() {
        AdaptiveBackendController terrain = components.backends.get(
            OptimizationModule.MODERN_TERRAIN_BACKEND);
        if (terrain != null && terrain.isAwaitingCandidateData()) pendingTerrainCandidate = true;
    }

    boolean resumePendingTerrainCandidate(long frameId) {
        if (!pendingTerrainCandidate) return false;
        AdaptiveBackendController terrain = components.backends.get(
            OptimizationModule.MODERN_TERRAIN_BACKEND);
        if (terrain == null || !terrain.isAwaitingCandidateData()) {
            pendingTerrainCandidate = false;
            return false;
        }
        if (!terrain.terrainCandidateAvailable(frameId)) return false;
        pendingTerrainCandidate = false;
        measurements.discardBackendSamples(OptimizationModule.MODERN_TERRAIN_BACKEND);
        return true;
    }

    public boolean tryRenderTerrain(Object container, BlockRenderLayer layer) {
        beginTerrainDecision(container, layer);
        if (!initialized) return declineTerrain(TerrainFallbackReason.RUNTIME_NOT_READY);
        if (currentStamp == null) return declineTerrain(TerrainFallbackReason.NO_ACTIVE_FRAME);
        if (components.terrainArena == null) return declineTerrain(TerrainFallbackReason.ARENA_UNAVAILABLE);
        if (components.threadGuard == null || !components.threadGuard.isRenderThread()) {
            return declineTerrain(TerrainFallbackReason.RENDER_THREAD_UNAVAILABLE);
        }
        if (!(container instanceof TerrainRenderListAccessor)) {
            return declineTerrain(TerrainFallbackReason.CONTAINER_ABI_MISSING);
        }
        if (!shaderRuntime.shaderPackStateKnown) {
            return declineTerrain(TerrainFallbackReason.SHADER_STATE_UNKNOWN);
        }
        if (shaderRuntime.shaderPackActive && !isShaderPackSafeForTerrainArena()) {
            return declineTerrain(shaderRuntime.hasNativeBinding()
                && !shaderRuntime.nativeTerrainLayoutCertified()
                ? TerrainFallbackReason.SHADER_LAYOUT_UNCERTIFIED
                : TerrainFallbackReason.SHADER_PACK_ACTIVE);
        }
        TerrainRenderListAccessor accessor = (TerrainRenderListAccessor) container;
        configureTerrainBatching();
        AdaptiveBackendController visibility = components.backends.get(
            OptimizationModule.MODERN_VISIBILITY_HZB);
        BackendLifecycleState visibilityState = visibility == null
            ? BackendLifecycleState.LEGACY : visibility.lifecycleState();
        boolean shaderSafe = shaderRuntime.shaderPackStateKnown && !shaderRuntime.shaderPackActive
            && !insideLegacyIsland();
        // HZB owns depth-history validation, not terrain-arena ownership.  In
        // particular, a fully legacy terrain list must still be able to move
        // OUTPUT_VALIDATE forward after its CUTOUT pass captured valid depth.
        if (shaderSafe && visibilityState == BackendLifecycleState.OUTPUT_VALIDATE
            && layer == BlockRenderLayer.SOLID && components.depthHistory != null) {
            Boolean valid = components.depthHistory.validateHistory(currentStamp, container);
            // Rechecking a retained pyramid is not another independent source
            // oracle. Successful publications advance validation once in
            // beginFrame; this live projection check can only reject output.
            if (Boolean.FALSE.equals(valid)) {
                measurements.recordValidationOnce(OptimizationModule.MODERN_VISIBILITY_HZB,
                    false, "GPU HZB conservative hierarchy validation failed");
                visibilityState = visibility.lifecycleState();
            }
        }
        AdaptiveBackendController backend = components.backends.get(
            OptimizationModule.MODERN_TERRAIN_BACKEND);
        boolean profileTerrain = backend == null || !backend.isDirectActivation();
        int originalVisible;
        int arenaOwned;
        int regionRuns;
        if (!profileTerrain) {
            java.util.List<?> visibleChunks = accessor.ice$renderChunks();
            originalVisible = visibleChunks == null ? 0 : visibleChunks.size();
            // The draw planner resolves visible ownership. Explicit rendering
            // needs neither an extra list scan nor an ABBA coverage profile.
            arenaOwned = originalVisible == 0 || components.terrainArena.getOwnedMeshes() == 0
                ? 0 : -1;
            regionRuns = -1;
        } else if (layer == BlockRenderLayer.SOLID) {
            long coverage = components.terrainArena.inspectCoverage(container, layer);
            originalVisible = LwjglTerrainArena.coverageVisible(coverage);
            arenaOwned = LwjglTerrainArena.coverageOwned(coverage);
            regionRuns = LwjglTerrainArena.coverageRegionRuns(coverage);
        } else {
            // Only SOLID trains the coverage/load profile. Other layers need
            // an ownership predicate, not a second full list traversal.
            java.util.List<?> visibleChunks = accessor.ice$renderChunks();
            originalVisible = visibleChunks == null ? 0 : visibleChunks.size();
            arenaOwned = originalVisible > 0
                && components.terrainArena.ownsAny(container, layer) ? 1 : 0;
            regionRuns = 0;
        }
        SceneFingerprint terrainScene = profileTerrain && layer == BlockRenderLayer.SOLID
            ? measurements.sceneFingerprint(originalVisible, arenaOwned, regionRuns) : null;
        BackendLifecycleState state = backend == null
            ? BackendLifecycleState.LEGACY : backend.lifecycleState();
        boolean terrainProfileReady = !profileTerrain || layer != BlockRenderLayer.SOLID;
        if (layer == BlockRenderLayer.SOLID && backend != null
            && terrainScene != null) {
            terrainProfileReady = backend.prepareProfiledWorkload(
                terrainScene.terrainLoadBucket(), currentStamp.getFrameId());
            state = backend.lifecycleState();
        }
        boolean coverageReady = !profileTerrain || terrainMeasurementCoverageStable(
            originalVisible, arenaOwned);
        if (layer == BlockRenderLayer.SOLID) {
            terrainLastVisibleMeshes = originalVisible;
            terrainLastOwnedMeshes = arenaOwned;
            terrainLastRegionRuns = regionRuns;
        }
        if (arenaOwned == 0) {
            return declineTerrain(TerrainFallbackReason.NO_ARENA_OWNERSHIP);
        }
        MeasurementArm visibilityArm = visibilityState == BackendLifecycleState.PAIRED_MEASURE
            ? visibility.expectedMeasurementArm()
            : (visibilityState == BackendLifecycleState.MODERN
                || visibilityState == BackendLifecycleState.REGRESSION_MONITOR
                    ? MeasurementArm.MODERN : MeasurementArm.LEGACY);
        boolean useHzb = shaderSafe && (visibilityState == BackendLifecycleState.MODERN
            || visibilityState == BackendLifecycleState.REGRESSION_MONITOR
            || (visibilityState == BackendLifecycleState.PAIRED_MEASURE
                && visibilityArm == MeasurementArm.MODERN));
        boolean measureVisibility = layer == BlockRenderLayer.SOLID
            && visibility != null
            && visibility.shouldMeasureFrame(currentStamp.getFrameId());
        long visibilityStarted = measureVisibility ? System.nanoTime() : 0L;
        boolean usableHistory = useHzb && components.depthHistory != null
            && components.depthHistory.hasUsableHistory(currentStamp, container);
        MeasurementArm arm = state == BackendLifecycleState.PAIRED_MEASURE
            ? backend.expectedMeasurementArm() : MeasurementArm.LEGACY;
        boolean exactLegacyReplay = state != BackendLifecycleState.MODERN
            && state != BackendLifecycleState.REGRESSION_MONITOR
            && (state != BackendLifecycleState.PAIRED_MEASURE
                || !terrainProfileReady || !coverageReady
                || arm == MeasurementArm.LEGACY)
            && components.terrainArena.canReplayLegacy(container, layer);
        if (exactLegacyReplay) {
            if (state == BackendLifecycleState.PAIRED_MEASURE
                && layer == BlockRenderLayer.SOLID) {
                boolean stable = terrainProfileReady && coverageReady
                    && terrainScene != null && arm == MeasurementArm.LEGACY;
                if (stable) {
                    measurements.pendingTerrainMeasurementScene = terrainScene;
                    measurements.pendingTerrainMeasurementStarted = System.nanoTime();
                    measurements.pendingTerrainMeasurementStable = true;
                } else {
                    terrainMeasurementCoverageRejects = saturatedIncrement(
                        terrainMeasurementCoverageRejects);
                    if (!terrainProfileReady) {
                        terrainMeasurementProfileWarmups = saturatedIncrement(
                            terrainMeasurementProfileWarmups);
                    }
                }
            }
            return declineTerrain(TerrainFallbackReason.SHADOW_LEGACY_REPLAY);
        }
        boolean batched = state == BackendLifecycleState.MODERN
            || state == BackendLifecycleState.REGRESSION_MONITOR
            || (state == BackendLifecycleState.PAIRED_MEASURE
                && arm == MeasurementArm.MODERN);
        AdaptiveBackendController mdi = components.backends.get(
            OptimizationModule.MODERN_TERRAIN_MDI);
        BackendLifecycleState mdiState = mdi == null
            ? BackendLifecycleState.LEGACY : mdi.lifecycleState();
        if (batched && mdiState == BackendLifecycleState.OUTPUT_VALIDATE) {
            boolean valid = components.terrainArena.supportsIndirectCommands();
            recordValidation(OptimizationModule.MODERN_TERRAIN_MDI, valid,
                valid ? null : "terrain MDI executable self-test unavailable");
            mdiState = mdi.lifecycleState();
        }
        MeasurementArm mdiArm = mdiState == BackendLifecycleState.PAIRED_MEASURE
            ? mdi.expectedMeasurementArm()
            : (mdiState == BackendLifecycleState.MODERN
                || mdiState == BackendLifecycleState.REGRESSION_MONITOR
                    ? MeasurementArm.MODERN : MeasurementArm.LEGACY);
        boolean useMdi = batched && (mdiState == BackendLifecycleState.MODERN
            || mdiState == BackendLifecycleState.REGRESSION_MONITOR
            || (mdiState == BackendLifecycleState.PAIRED_MEASURE
                && mdiArm == MeasurementArm.MODERN));
        CurrentFrameTerrainOcclusion coverageSource = layer == BlockRenderLayer.SOLID ? terrainSoftwareOcclusion() : null;
        RenderMatrixBridge.Snapshot coverageMatrices = coverageSource == null ? null : softwareTerrainMatrices();
        components.terrainArena.configureVisibility(currentStamp, coverageSource, coverageMatrices);
        if (coverageMatrices != null) {
            useHzb = false;
            usableHistory = false;
        }
        RenderBackendId backendId = batched ? RenderBackendId.ICE_NATIVE
            : RenderBackendId.LEGACY;
        RenderPass pass = terrainPass(layer);
        boolean measureMdi = layer == BlockRenderLayer.SOLID && mdi != null
            && mdi.shouldMeasureFrame(currentStamp.getFrameId());
        SceneFingerprint scene = measureMdi || measureVisibility
            ? measurements.sceneFingerprint(originalVisible) : null;
        RenderProfileKey hzbKey = null;
        boolean hzbMeasurement = measureVisibility && shaderSafe
            && measurements.hzbGpuSamples.isEmpty()
            && (visibilityArm == MeasurementArm.LEGACY
                || (useHzb && usableHistory));
        if (hzbMeasurement && scene != null) {
            hzbKey = measurements.beginHzbGpuSample(scene, visibilityArm);
            CorrelatedRenderProfiler.GpuScope hzbTerrainGpu = components.profiler.beginGpu(
                currentStamp, RenderPass.MAIN_SOLID,
                visibilityArm == MeasurementArm.MODERN ? RenderBackendId.ICE_NATIVE
                    : RenderBackendId.LEGACY, measurements.hzbGpuCompletion);
            if (hzbTerrainGpu == null) measurements.invalidateHzbGpuSample(hzbKey);
            else measurements.attachHzbGpuScope(hzbKey, hzbTerrainGpu);
        }
        long started = System.nanoTime();
        boolean mdiStable = true;
        boolean mdiWork = false;
        int mdiEligible = 0;
        boolean arenaAttempted = false;
        boolean hzbFilterOpen = false;
        boolean terrainDrawRecorded = false;
        Throwable operationFailure = null;
        CorrelatedRenderProfiler.CpuScope scope = components.profiler.beginCpu(currentStamp,
            pass, backendId, CpuWorkKind.SUBMISSION);
        try {
            int removed = 0;
            boolean hzbApplied = false;
            if (useHzb && components.depthHistory != null) {
                try {
                    removed = components.depthHistory.filter(container, layer, currentStamp);
                    hzbFilterOpen = true;
                    hzbApplied = usableHistory && layer != BlockRenderLayer.TRANSLUCENT;
                } catch (Throwable hzbError) {
                    FatalErrors.rethrowIfFatal(hzbError);
                    boolean rollbackSafe = true;
                    try { components.depthHistory.rollbackFilter(container); }
                    catch (Throwable rollbackFailure) {
                        rollbackSafe = false;
                        hzbError = appendRuntimeFailure(hzbError,
                            rollbackFailure);
                    }
                    if (visibility != null) visibility.runtimeFailure(hzbError);
                    measurements.invalidateHzbGpuSample(hzbKey);
                    usableHistory = false;
                    useHzb = false;
                    detail = "HZB 过滤异常；本层已回退未过滤 Arena 提交："
                        + hzbError.getClass().getSimpleName();
                    if (!rollbackSafe) rethrowRuntimeFailure(hzbError);
                }
            }
            // No unrelated work belongs between the transactional list
            // compaction and the arena call. The transaction is committed
            // only when this draw is owned; a pre-submission decline restores
            // the byte-for-byte legacy order before the bridge can retry.
            arenaAttempted = true;
            boolean handled = components.terrainArena.render(container, layer, batched,
                useMdi, currentStamp.getFrameId());
            boolean submissionStarted = components.terrainArena.wasLastSubmissionStarted();
            if (hzbFilterOpen) {
                if (handled || submissionStarted) {
                    components.depthHistory.commitFilter(container);
                } else {
                    components.depthHistory.rollbackFilter(container);
                }
                hzbFilterOpen = false;
            }
            int indirectCommands = components.terrainArena.getLastIndirectCommands();
            mdiEligible = components.terrainArena.getLastIndirectEligibleCommands();
            mdiWork = indirectCommands > 0;
            Throwable mdiError = components.terrainArena.consumeIndirectFailure();
            if (mdiError != null) {
                FatalErrors.rethrowIfFatal(mdiError);
                mdiStable = false;
                if (mdi != null) mdi.runtimeFailure(mdiError);
                detail = "地形 MDI 异常；已独立回退 multi-draw："
                    + mdiError.getClass().getSimpleName();
            }
            if (handled) {
                if (removed > 0) {
                    components.profiler.addCounter(currentStamp, pass,
                        RenderBackendId.ICE_NATIVE,
                        RenderCounter.HZB_OCCLUDED, removed);
                }
                if (hzbApplied) {
                    components.profiler.addCounter(currentStamp, pass,
                        RenderBackendId.ICE_NATIVE,
                        RenderCounter.HZB_TESTED, originalVisible);
                }
                components.profiler.addCounter(currentStamp, pass, backendId,
                    batched ? RenderCounter.MULTI_DRAW : RenderCounter.DRAW, 1L);
                if (indirectCommands > 0) {
                    components.profiler.addCounter(currentStamp, pass,
                        RenderBackendId.ICE_NATIVE,
                        RenderCounter.INDIRECT_COMMAND, indirectCommands);
                }
                recordTerrainArenaDraw(batched, indirectCommands);
                terrainDrawRecorded = true;
            } else {
                declineTerrain(TerrainFallbackReason.ARENA_DECLINED);
            }
            if (handled) afterTerrainLayer(container, layer);
            return handled;
        } catch (Throwable error) {
            boolean submissionStarted = arenaAttempted
                && components.terrainArena.wasLastSubmissionStarted();
            if (hzbFilterOpen) {
                try {
                    if (submissionStarted) components.depthHistory.commitFilter(container);
                    else components.depthHistory.rollbackFilter(container);
                    hzbFilterOpen = false;
                } catch (Throwable transactionFailure) {
                    error = appendRuntimeFailure(error, transactionFailure);
                }
            }
            operationFailure = error;
            if (!submissionStarted) {
                rethrowRuntimeFailure(error);
            }
            FatalErrors.rethrowIfFatal(error);
            if (backend != null) backend.runtimeFailure(error);
            detail = "地形提交异常；arena 所有权已保留并熔断："
                + error.getClass().getSimpleName();
            // Returning false here would execute the old list after a partial
            // draw and would also omit arena-only meshes. Ownership therefore
            // makes this call fully handled even on a failed frame.
            if (terrainDrawRecorded) {
                incrementTerrainReason(TerrainFallbackReason.ARENA_POST_DRAW_FAILURE);
            } else {
                incrementTerrainReason(
                    TerrainFallbackReason.ARENA_SUBMISSION_UNCERTAIN);
                recordTerrainArenaUncertainDraw();
            }
            return true;
        } finally {
            Throwable instrumentationFailure = null;
            // Both HZB arms keep the same SOLID-through-CUTOUT query interval.
            // Only the Modern arm adds filtering/capture work within it. The
            // CUTOUT hook closes reused or captured depth; endFrame discards
            // an unfinished query if that hook never runs.
            try { scope.close(); }
            catch (Throwable error) {
                instrumentationFailure = appendRuntimeFailure(
                    instrumentationFailure, error);
            }
            try {
                if (backend != null
                    && state == BackendLifecycleState.PAIRED_MEASURE
                    && layer == BlockRenderLayer.SOLID) {
                    boolean stableCoverage = terrainProfileReady
                        && coverageReady && terrainScene != null;
                    if (!stableCoverage) {
                        terrainMeasurementCoverageRejects = saturatedIncrement(
                            terrainMeasurementCoverageRejects);
                        if (!terrainProfileReady) {
                            terrainMeasurementProfileWarmups = saturatedIncrement(
                                terrainMeasurementProfileWarmups);
                        }
                    }
                    recordMeasurement(OptimizationModule.MODERN_TERRAIN_BACKEND,
                        terrainScene, arm,
                        Math.max(1L, System.nanoTime() - started),
                        stableCoverage);
                }
                if (measureMdi) {
                    recordAuxiliaryBackendSample(
                        OptimizationModule.MODERN_TERRAIN_MDI,
                        mdiState, mdiArm, scene,
                        Math.max(1L, System.nanoTime() - started),
                        scene != null && mdiStable && mdiEligible > 0
                            && (mdiArm == MeasurementArm.LEGACY || mdiWork));
                }
                if (hzbKey != null) measurements.addHzbCpuNanos(hzbKey,
                    Math.max(1L, System.nanoTime() - visibilityStarted));
            } catch (Throwable error) {
                instrumentationFailure = appendRuntimeFailure(
                    instrumentationFailure, error);
            }
            if (instrumentationFailure != null) {
                // A completed/uncertain draw must never be replayed merely
                // because its profiler bookkeeping failed.
                reportInstrumentationFailure(operationFailure,
                    instrumentationFailure);
            }
        }
    }

    public void afterLegacyTerrainLayer(Object container, BlockRenderLayer layer) {
        terrainLegacyDraws = saturatedIncrement(terrainLegacyDraws);
        SceneFingerprint measurementScene = measurements.pendingTerrainMeasurementScene;
        long measurementStarted = measurements.pendingTerrainMeasurementStarted;
        boolean measurementStable = measurements.pendingTerrainMeasurementStable;
        measurements.pendingTerrainMeasurementScene = null;
        measurements.pendingTerrainMeasurementStarted = 0L;
        measurements.pendingTerrainMeasurementStable = false;
        if (measurementScene != null && measurementStarted > 0L) {
            AdaptiveBackendController backend = components.backends.get(
                OptimizationModule.MODERN_TERRAIN_BACKEND);
            if (backend != null && backend.lifecycleState()
                == BackendLifecycleState.PAIRED_MEASURE
                && backend.expectedMeasurementArm() == MeasurementArm.LEGACY) {
                recordMeasurement(OptimizationModule.MODERN_TERRAIN_BACKEND,
                    measurementScene, MeasurementArm.LEGACY,
                    Math.max(1L, System.nanoTime() - measurementStarted),
                    measurementStable);
            }
        }
        TerrainFallbackReason reason = pendingTerrainContainer == container
            && pendingTerrainLayer == layer && pendingTerrainReason != null
                ? pendingTerrainReason : TerrainFallbackReason.UNTRACKED_LEGACY;
        incrementTerrainReason(reason);
        clearTerrainDecision();
        afterTerrainLayer(container, layer);
    }

    public void afterTerrainLayer(Object container, BlockRenderLayer layer) {
        if (!initialized || currentStamp == null || components.depthHistory == null) {
            incrementHzbReason(HzbCaptureReason.RUNTIME_NOT_READY);
            return;
        }
        if (insideLegacyIsland()) {
            incrementHzbReason(HzbCaptureReason.LEGACY_ISLAND);
            return;
        }
        if (!(container instanceof TerrainRenderListAccessor)) {
            incrementHzbReason(HzbCaptureReason.CONTAINER_ABI_MISSING);
            return;
        }
        if (layer != BlockRenderLayer.CUTOUT) {
            incrementHzbReason(HzbCaptureReason.NON_CAPTURE_LAYER);
            return;
        }
        AdaptiveBackendController visibility = components.backends.get(
            OptimizationModule.MODERN_VISIBILITY_HZB);
        if (visibility == null) {
            incrementHzbReason(HzbCaptureReason.BACKEND_UNAVAILABLE);
            return;
        }
        if (!acceptsCandidateUploads(visibility.lifecycleState())) {
            incrementHzbReason(HzbCaptureReason.BACKEND_STATE_REJECTED);
            return;
        }
        BackendLifecycleState state = visibility.lifecycleState();
        if (!shaderRuntime.shaderPackStateKnown || shaderRuntime.shaderPackActive) {
            incrementHzbReason(!shaderRuntime.shaderPackStateKnown
                ? HzbCaptureReason.SHADER_STATE_UNKNOWN
                : HzbCaptureReason.SHADER_PACK_ACTIVE);
            components.depthHistory.invalidateScene();
            measurements.invalidateCurrentHzbGpuSample(MeasurementArm.MODERN);
            if (shaderRuntime.shaderPackActive && state != BackendLifecycleState.LEGACY) {
                visibility.fallback("ShaderPack active; conservative HZB disabled", false);
            }
            return;
        }
        MeasurementArm arm = state == BackendLifecycleState.PAIRED_MEASURE
            ? visibility.expectedMeasurementArm() : MeasurementArm.MODERN;
        TerrainRenderListAccessor accessor =
            (TerrainRenderListAccessor) container;
        long captureStarted = System.nanoTime();
        if (state == BackendLifecycleState.PAIRED_MEASURE
            && arm == MeasurementArm.LEGACY
            && components.depthHistory.hasUsableHistory(currentStamp, container)) {
            incrementHzbReason(
                HzbCaptureReason.PAIRED_LEGACY_HISTORY_REUSED);
            measurements.finishReusedHzbGpuSample(arm, captureStarted);
            return;
        }

        LwjglDepthHistory.CaptureOutcome preflight;
        boolean refreshHistory = state == BackendLifecycleState.OUTPUT_VALIDATE;
        hzbCaptureAttempts = saturatedIncrement(hzbCaptureAttempts);
        try {
            preflight = components.depthHistory.preflightCapture(currentStamp,
                accessor.ice$viewEntityX(), accessor.ice$viewEntityY(),
                accessor.ice$viewEntityZ(), refreshHistory);
        } catch (Throwable error) {
            incrementHzbReason(HzbCaptureReason.CAPTURE_FAILURE);
            measurements.invalidateCurrentHzbGpuSample(arm);
            visibility.runtimeFailure(error);
            components.depthHistory.invalidateScene();
            return;
        }
        if (preflight != LwjglDepthHistory.CaptureOutcome.CAPTURE_READY) {
            recordHzbCaptureOutcome(preflight);
            if (preflight == LwjglDepthHistory.CaptureOutcome.HISTORY_REUSED
                && arm == MeasurementArm.MODERN) {
                // Reusing validated depth is the normal stable-view workload.
                // Its terrain/filter query is still a complete modern sample;
                // discarding it would prevent paired measurement progressing.
                measurements.finishReusedHzbGpuSample(arm, captureStarted);
            } else {
                measurements.invalidateCurrentHzbGpuSample(arm);
            }
            if (preflight == LwjglDepthHistory.CaptureOutcome.UNSAFE_STATE
                || preflight
                    == LwjglDepthHistory.CaptureOutcome.UNSUPPORTED_SOURCE) {
                visibility.fallback("HZB preflight rejected: " + preflight,
                    false);
                components.depthHistory.invalidateScene();
            }
            return;
        }
        if (!ensureHzbTrackedState(visibility)) {
            components.depthHistory.cancelPreflight();
            incrementHzbReason(
                HzbCaptureReason.STATE_REAUTHENTICATION_FAILED);
            measurements.invalidateCurrentHzbGpuSample(arm);
            return;
        }

        if (state == BackendLifecycleState.PAIRED_MEASURE
            && arm == MeasurementArm.LEGACY) {
            // A moving camera may invalidate the validation history before the
            // first L arm. Seed a fresh history, but never count that polluted
            // frame as the Legacy baseline.
            measurements.invalidateCurrentHzbGpuSample(MeasurementArm.LEGACY);
            LwjglDepthHistory.CaptureOutcome seed;
            try {
                seed = components.depthHistory.capturePreflighted(currentStamp,
                    accessor.ice$viewEntityX(), accessor.ice$viewEntityY(),
                    accessor.ice$viewEntityZ(), false, refreshHistory);
            } catch (Throwable error) {
                incrementHzbReason(HzbCaptureReason.CAPTURE_FAILURE);
                visibility.runtimeFailure(error);
                components.depthHistory.invalidateScene();
                return;
            }
            recordHzbCaptureOutcome(seed);
            if (seed == LwjglDepthHistory.CaptureOutcome.UNSAFE_STATE
                || seed == LwjglDepthHistory.CaptureOutcome.UNSUPPORTED_SOURCE) {
                visibility.fallback("HZB seed rejected: " + seed, false);
                components.depthHistory.invalidateScene();
            }
            return;
        }
        RenderProfileKey key = measurements.findCurrentHzbKey(arm);
        long started = key == null ? 0L : System.nanoTime();
        LwjglDepthHistory.CaptureOutcome outcome;
        Throwable captureFailure = null;
        try {
            outcome = components.depthHistory.capturePreflighted(currentStamp,
                accessor.ice$viewEntityX(), accessor.ice$viewEntityY(),
                accessor.ice$viewEntityZ(),
                state == BackendLifecycleState.OUTPUT_VALIDATE, refreshHistory);
        } catch (Throwable error) {
            outcome = null;
            captureFailure = error;
        }
        try { if (key != null) measurements.closeHzbGpuScope(key); }
        catch (Throwable closeFailure) {
            captureFailure = appendRuntimeFailure(captureFailure, closeFailure);
        }
        if (captureFailure != null) {
            incrementHzbReason(HzbCaptureReason.CAPTURE_FAILURE);
            measurements.invalidateHzbGpuSample(key);
            visibility.runtimeFailure(captureFailure);
            components.depthHistory.invalidateScene();
            return;
        }
        recordHzbCaptureOutcome(outcome);
        if (key != null) measurements.addHzbCpuNanos(key,
            Math.max(1L, System.nanoTime() - started));
        if (outcome != LwjglDepthHistory.CaptureOutcome.CAPTURED) {
            measurements.invalidateHzbGpuSample(key);
            if (outcome == LwjglDepthHistory.CaptureOutcome.UNSAFE_STATE
                || outcome == LwjglDepthHistory.CaptureOutcome.UNSUPPORTED_SOURCE) {
                visibility.fallback("HZB capture rejected: " + outcome, false);
                components.depthHistory.invalidateScene();
            }
        }
    }

    private void validatePublishedHzbOnce() {
        if (components.depthHistory.consumeOracleValidatedPublication()) {
            measurements.recordValidationOnce(OptimizationModule.MODERN_VISIBILITY_HZB,
                true, null);
        }
    }

    private boolean ensureHzbTrackedState(
        AdaptiveBackendController visibility) {
        if (EarlyGlStateTracker.isKnown()
            && EarlyGlStateTracker.hasKnownDepthRange()) return true;
        long invalidation = EarlyGlStateTracker.invalidations();
        long frame = currentStamp == null ? -1L : currentStamp.getFrameId();
        if (failedHzbStateInvalidation == invalidation
            && failedHzbStateFrame == frame) {
            hzbStateReauthenticationSuppressions = saturatedIncrement(
                hzbStateReauthenticationSuppressions);
            return false;
        }
        hzbStateReauthenticationAttempts = saturatedIncrement(
            hzbStateReauthenticationAttempts);
        try {
            GlStateQueryWorkspace workspace = components.glStateQueryWorkspace;
            if (workspace == null || workspace.isClosed()) {
                throw new IllegalStateException(
                    "HZB state query workspace unavailable");
            }
            int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int readFramebuffer = GL11.glGetInteger(
                GL30.GL_READ_FRAMEBUFFER_BINDING);
            int drawFramebuffer = GL11.glGetInteger(
                GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            int pixelPackBuffer = GL11.glGetInteger(
                GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            if (program < 0 || readFramebuffer < 0 || drawFramebuffer < 0
                || pixelPackBuffer < 0) {
                throw new IllegalStateException("negative HZB GL state");
            }
            FloatBuffer depthRange = workspace.depthRange();
            GL11.glGetFloat(GL11.GL_DEPTH_RANGE, depthRange);
            EarlyGlStateTracker.useProgram(program);
            EarlyGlStateTracker.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER,
                readFramebuffer);
            EarlyGlStateTracker.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,
                drawFramebuffer);
            EarlyGlStateTracker.depthFunction(depthFunction);
            EarlyGlStateTracker.bindBuffer(GL21.GL_PIXEL_PACK_BUFFER,
                pixelPackBuffer);
            EarlyGlStateTracker.seedDepthRange(depthRange.get(0),
                depthRange.get(1));
            if (!EarlyGlStateTracker.isKnown()
                || !EarlyGlStateTracker.hasKnownDepthRange()) {
                throw new IllegalStateException(
                    "HZB GL state publication incomplete");
            }
            failedHzbStateInvalidation = Long.MIN_VALUE;
            failedHzbStateFrame = Long.MIN_VALUE;
            if (visibility != null) visibility.recoverableRuntimeSuccess();
            hzbStateReauthentications = saturatedIncrement(
                hzbStateReauthentications);
            return true;
        } catch (Throwable error) {
            EarlyGlStateTracker.invalidate();
            failedHzbStateInvalidation = EarlyGlStateTracker.invalidations();
            failedHzbStateFrame = frame;
            hzbStateReauthenticationFailures = saturatedIncrement(
                hzbStateReauthenticationFailures);
            FatalErrors.rethrowIfFatal(error);
            lastHzbStateFailureType = error.getClass().getName();
            lastHzbStateFailureMessage = error.getMessage() == null
                ? "" : error.getMessage();
            if (visibility != null) visibility.recoverableRuntimeFailure(error);
            boolean quarantined = visibility != null
                && visibility.lifecycleState()
                    == BackendLifecycleState.QUARANTINED;
            detail = "HZB 状态重新认证失败；"
                + (quarantined ? "连续失败已隔离可见性后端："
                    : "本次捕获已跳过：")
                + error.getClass().getSimpleName()
                + (error.getMessage() == null ? "" : ": " + error.getMessage());
            return false;
        }
    }

    private void recordHzbCaptureOutcome(
        LwjglDepthHistory.CaptureOutcome outcome) {
        if (outcome == null) {
            incrementHzbReason(HzbCaptureReason.CAPTURE_FAILURE);
            return;
        }
        switch (outcome) {
            case CAPTURED:
                incrementHzbReason(HzbCaptureReason.CAPTURED);
                break;
            case VIEW_UNSTABLE:
                incrementHzbReason(HzbCaptureReason.VIEW_UNSTABLE);
                break;
            case CAPTURE_PENDING:
                incrementHzbReason(HzbCaptureReason.CAPTURE_PENDING);
                break;
            case HISTORY_REUSED:
                incrementHzbReason(HzbCaptureReason.HISTORY_REUSED);
                break;
            case TEMPORARILY_UNAVAILABLE:
                incrementHzbReason(
                    HzbCaptureReason.TEMPORARILY_UNAVAILABLE);
                break;
            case UNSAFE_STATE:
                incrementHzbReason(HzbCaptureReason.UNSAFE_STATE);
                break;
            case UNSUPPORTED_SOURCE:
                incrementHzbReason(HzbCaptureReason.UNSUPPORTED_SOURCE);
                break;
            default:
                incrementHzbReason(HzbCaptureReason.CAPTURE_FAILURE);
                break;
        }
    }

    public boolean ownsTerrain(Object container, BlockRenderLayer layer) {
        return initialized && components.terrainArena != null && components.threadGuard != null
            && components.threadGuard.isRenderThread() && components.terrainArena.ownsAny(container, layer);
    }

    /**
     * Individual mixed draws for a paused or failed backend. This uses
     * neither HZB nor MDI and preserves the caller's exact visible order.
     */
    public boolean tryRenderTerrainFallback(Object container, BlockRenderLayer layer) {
        if (!initialized || (!runtimePaused && currentStamp == null) || components.terrainArena == null
            || components.threadGuard == null || !components.threadGuard.isRenderThread()
            || !(container instanceof TerrainRenderListAccessor)
            || layer == null || !components.terrainArena.ownsAny(container, layer)) {
            return runtimePaused ? false : declineTerrain(TerrainFallbackReason.FAULT_FALLBACK_DECLINED);
        }
        try {
            boolean handled = components.terrainArena.render(container, layer, false, false,
                runtimePaused ? lastRuntimeFrame : currentStamp.getFrameId());
            if (!runtimePaused) {
                if (handled) recordTerrainArenaDraw(false, 0);
                else declineTerrain(TerrainFallbackReason.FAULT_FALLBACK_DECLINED);
            }
            return handled;
        } catch (Throwable fallbackError) {
            FatalErrors.rethrowIfFatal(fallbackError);
            AdaptiveBackendController backend = components.backends.get(
                OptimizationModule.MODERN_TERRAIN_BACKEND);
            if (backend != null) backend.runtimeFailure(fallbackError);
            detail = "地形安全回退异常；本帧禁止重放："
                + fallbackError.getClass().getSimpleName();
            // Arena-only meshes have no legacy VBO copy. Once ownership was
            // observed, returning false could both omit those meshes and
            // duplicate any driver-accepted draw, so this failed frame is
            // treated as handled and the independent breaker owns recovery.
            incrementTerrainReason(TerrainFallbackReason.ARENA_SUBMISSION_UNCERTAIN);
            recordTerrainArenaUncertainDraw();
            return true;
        }
    }

    private void ensureInitialized() {
        if (shutdownRequested) {
            detail = "现代渲染器已请求停止";
            return;
        }
        if (initialized) return;
        long generation = combinedGeneration();
        long now = System.nanoTime();
        if (!initializationRetry.canAttempt(generation, now)) {
            long millis = Math.max(1L,
                initializationRetry.remainingNanos(generation, now) / 1_000_000L);
            detail = "现代渲染组件初始化退避中（" + millis + " ms）";
            return;
        }

        ContextCapabilities context;
        try { context = GLContext.getCapabilities(); }
        catch (Throwable unavailable) {
            FatalErrors.rethrowIfFatal(unavailable);
            context = null;
        }
        if (context == null) {
            // Absence is a lifecycle state, not an initialization failure.  Do
            // not grow the backoff so a newly-current context can recover on
            // the very next render tick.
            detail = "等待有效 OpenGL context";
            return;
        }

        RendererComponentFactory.prepareRuntimeBreakers(generation);
        if (!OptimizerRegistry.isOperational(
            OptimizationModule.MODERN_FRAME_COORDINATOR)) {
            detail = "现代帧协调器已在当前代际独立熔断";
            return;
        }

        RendererComponents candidate = new RendererComponents();
        candidate.contextCapabilities = context;
        candidate.contextGeneration = epochs.currentGlContextGeneration();
        try {
            RendererComponentFactory.initialize(candidate, this, generation);

            publish(candidate);
            initializationRetry.recordSuccess(generation);
            initialized = true;
            detail = "渲染组件已初始化；实体与方块实体后端经输出校验和收益测量后启用";
            OptimizerRegistry.breaker(OptimizationModule.MODERN_FRAME_COORDINATOR)
                .activate("帧/视图/pass 语义协调已启动，绘制仍按子后端认证结果选择");
            OptimizerRegistry.breaker(OptimizationModule.LEGACY_GL_ISLAND)
                .activate("Legacy GL barrier 与全状态失效机制已启动");
        } catch (Throwable error) {
            if (activeGraph == null || activeGraph == candidate) {
                activeGraph = null;
                try { clearPublishedGraph(); }
                catch (Throwable rollbackFailure) {
                    error = appendRuntimeFailure(error, rollbackFailure);
                }
            }
            boolean disposalContextValid = false;
            try { disposalContextValid = sameCurrentContext(context); }
            catch (Throwable contextFailure) {
                error = appendRuntimeFailure(error, contextFailure);
            }
            try {
                RendererComponentFactory.dispose(candidate, disposalContextValid,
                    candidate.contextGeneration);
            } catch (Throwable disposalFailure) {
                error = appendRuntimeFailure(error, disposalFailure);
            }
            FatalErrors.rethrowIfFatal(error);
            long delay = initializationRetry.recordFailure(generation,
                System.nanoTime());
            OptimizerRegistry.breaker(OptimizationModule.MODERN_FRAME_COORDINATOR)
                .recordFailure(error);
            detail = "现代渲染组件初始化失败；Legacy 保持可用，"
                + (delay / 1_000_000L) + " ms 后重试："
                + error.getClass().getSimpleName();
        }
    }

    int pendingShaderCandidateCountForTest() {
        return shaderRuntime.pendingShaderCandidateCountForTest();
    }

    long pendingShaderCandidateBytesForTest() {
        return shaderRuntime.pendingShaderCandidateBytesForTest();
    }

    int pendingShaderValidationCountForTest() {
        return shaderRuntime.pendingShaderValidationCountForTest();
    }

    int shaderBindingCountForTest() {
        return shaderRuntime.shaderBindingCountForTest();
    }

    boolean shaderBindingsPoisonedForTest() {
        return shaderRuntime.shaderBindingsPoisonedForTest();
    }

    void shaderBindingPublicationFaultForTest(
        ShaderBindingPublicationFault fault) {
        shaderRuntime.shaderBindingPublicationFaultForTest(fault);
    }

    private void configureBackends() {
        components.backends.clear();
        components.backends.putAll(RendererComponentFactory.createBackends(components.capabilities, combinedGeneration()));
    }

    /** Publishes the fully-created graph in one render-thread transaction. */
    private void publish(RendererComponents graph) {
        if (graph == null || activeGraph != null) {
            throw new IllegalStateException("modern component graph publication");
        }
        components = graph;
        shaderRuntime.optifineProgramState = null;
        shaderRuntime.clearShaderBindings();
        shaderRuntime.optifineRegionObservedGeneration = 0L;
        animatedAtlasTextureId = 0;
        openVisibilityFrame = 0L;
        observedSpriteVisibility = null;
        pendingTerrainCandidate = false;
        failedModelGlInvalidation = Long.MIN_VALUE;
        failedModelMatrixInvalidation = Long.MIN_VALUE;
        failedModelReauthenticationFrame = Long.MIN_VALUE;
        lastModelStateFailureStage = "";
        lastModelStateFailureType = "";
        lastModelStateFailureMessage = "";
        failedHzbStateInvalidation = Long.MIN_VALUE;
        failedHzbStateFrame = Long.MIN_VALUE;
        lastHzbStateFailureType = "";
        lastHzbStateFailureMessage = "";
        lastParticleFailureType = "";
        lastParticleFailureMessage = "";
        lastParticleRootFailureType = "";
        lastParticleRootFailureMessage = "";
        // Publish ownership last. Any preceding reset failure is rolled
        // back by ensureInitialized before the candidate graph is disposed.
        activeGraph = graph;
        // The factory already seeded this newly published graph's GL state.
        resumeTrackedState = false;
    }

    private void disposeActiveGraph(boolean contextValid, long contextGeneration) {
        softwareOcclusion.clear();
        if (!contextValid) {
            shaderRuntime.abandonActiveShaderInterval();
        } else if (shaderRuntime.hasNativeBinding()) {
            throw new IllegalStateException(
                "cannot dispose a graph with an active native shader");
        }
        RendererComponents graph = activeGraph;
        // The bridge owns only Java cache records; its detached native names
        // remain ledger-owned and are destroyed/abandoned with this graph.
        // Clear those records before the ledger so no stale raw ID survives a
        // resource or context generation transition.
        boolean bridgeContextValid = graph != null && contextValid
            && graph.contextGeneration == contextGeneration
            && sameCurrentContext(graph.contextCapabilities);
        LycanitesObjRenderBridge.releaseRendererGraph(bridgeContextValid);
        activeGraph = null;
        Throwable failure = null;
        try { clearPublishedGraph(); }
        catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        try { RendererComponentFactory.dispose(graph, contextValid, contextGeneration); }
        catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        if (failure != null) rethrowRuntimeFailure(failure);
    }

    private void clearPublishedGraph() {
        dormantComponents.backends.clear();
        components = dormantComponents;
        shaderRuntime.optifineProgramState = null;
        shaderRuntime.clearShaderBindings();
        shaderRuntime.optifineRegionObservedGeneration = 0L;
        currentStamp = null;
        measurements.frameGpuScope = null;
        frameInfrastructureActive = false;
        quiescent = false;
        animatedAtlasTextureId = 0;
        openVisibilityFrame = 0L;
        observedSpriteVisibility = null;
        pendingTerrainCandidate = false;
    }

    static Throwable cleanupForTest(Throwable priorFailure, Runnable action) {
        return RendererComponentFactory.cleanup(priorFailure, "fault-injection", action);
    }

    static Throwable appendRuntimeFailure(Throwable first,
                                                  Throwable next) {
        if (first == null) return next;
        Throwable nextFatal = FatalErrors.findFatal(next);
        if (nextFatal != null && FatalErrors.findFatal(first) == null) {
            if (first != nextFatal) nextFatal.addSuppressed(first);
            return nextFatal;
        }
        if (next != null && first != next) first.addSuppressed(next);
        return first;
    }

    static void rethrowRuntimeFailure(Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("modern renderer lifecycle failed", failure);
    }

    void reportInstrumentationFailure(Throwable operationFailure,
                                              Throwable instrumentationFailure) {
        if (instrumentationFailure == null) return;
        Throwable reported = instrumentationFailure;
        try { failCoordinator(instrumentationFailure); }
        catch (Throwable reportingFailure) {
            reported = appendRuntimeFailure(reported, reportingFailure);
        }
        Throwable combined = appendRuntimeFailure(operationFailure, reported);
        if (operationFailure == null) {
            FatalErrors.rethrowIfFatal(combined);
        } else if (combined != operationFailure) {
            rethrowRuntimeFailure(combined);
        }
    }

    static boolean sameCurrentContext(ContextCapabilities expected) {
        if (expected == null) return false;
        try { return GLContext.getCapabilities() == expected; }
        catch (Throwable unavailable) {
            FatalErrors.rethrowIfFatal(unavailable);
            return false;
        }
    }

    /** One startup/generation probe; ordinary frames use only CoreMod-published state. */
    void initializeTrackedGlState() {
        initializeTrackedGlState(components.glStateQueryWorkspace);
        // A pending resource/world transition can initialize before the
        // resume branch in beginFrame; it satisfies that same one-time seed.
        resumeTrackedState = false;
    }

    void initializeTrackedGlState(GlStateQueryWorkspace workspace) {
        EarlyGlStateTracker.beginProbe();
        try {
            if (workspace == null || workspace.isClosed()) {
                throw new IllegalStateException(
                    "GL state query workspace unavailable");
            }
            int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            FloatBuffer depthRange = workspace.depthRange();
            GL11.glGetFloat(GL11.GL_DEPTH_RANGE, depthRange);
            int pixelPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            int pixelUnpackBuffer = GL11.glGetInteger(
                GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
            int arrayBuffer = GL11.glGetInteger(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER_BINDING);
            int elementBuffer = GL11.glGetInteger(
                org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
            ContextCapabilities currentCapabilities = GLContext.getCapabilities();
            EarlyGlStateTracker.fogi(0x855A, currentCapabilities.GL_NV_fog_distance
                ? GL11.glGetInteger(0x855A) : 0x855C);
            int drawIndirectBuffer = currentCapabilities.OpenGL43
                || currentCapabilities.GL_ARB_multi_draw_indirect
                    ? GL11.glGetInteger(0x8F43) : 0;
            boolean vertexArraySupported = currentCapabilities.OpenGL30
                || currentCapabilities.GL_ARB_vertex_array_object;
            int vertexArray = vertexArraySupported
                ? GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING) : 0;
            int activeTextureEnum = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            int clientActiveTextureEnum = GL11.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE);
            int activeTexture = activeTextureEnum - GL13.GL_TEXTURE0;
            if (activeTexture < 0 || activeTexture >= 32) {
                throw new IllegalStateException("invalid active texture " + activeTextureEnum);
            }
            int advertisedTextureUnits = GL11.glGetInteger(
                GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
            int textureUnitCount = Math.max(2, Math.min(32,
                advertisedTextureUnits));
            int[] textures2d = workspace.textures2d();
            boolean[] texture2dEnabled = workspace.texture2dEnabled();
            Throwable textureQueryFailure = null;
            try {
                for (int unit = 0; unit < textureUnitCount; unit++) {
                    GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
                    textures2d[unit] = GL11.glGetInteger(
                        GL11.GL_TEXTURE_BINDING_2D);
                    texture2dEnabled[unit] = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
                }
            } catch (Throwable queryFailure) {
                textureQueryFailure = queryFailure;
            }
            try { GL13.glActiveTexture(activeTextureEnum); }
            catch (Throwable restoreFailure) {
                textureQueryFailure = appendRuntimeFailure(textureQueryFailure,
                    restoreFailure);
            }
            if (textureQueryFailure != null) {
                rethrowRuntimeFailure(textureQueryFailure);
            }
            int texture0 = textures2d[0];
            int texture1 = textures2d[1];
            boolean texture0Enabled = texture2dEnabled[0];
            boolean texture1Enabled = texture2dEnabled[1];
            boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
            int blendSourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
            int blendDestinationRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            int blendSourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
            int blendDestinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            int blendEquationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
            int blendEquationAlpha = GL11.glGetInteger(
                GL20.GL_BLEND_EQUATION_ALPHA);
            boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            boolean lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
            int cullFace = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
            boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            boolean stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
            ByteBuffer colorMaskValues = workspace.colorMask();
            GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK, colorMaskValues);
            int colorMask = (colorMaskValues.get(0) != 0 ? 1 : 0)
                | (colorMaskValues.get(1) != 0 ? 2 : 0)
                | (colorMaskValues.get(2) != 0 ? 4 : 0)
                | (colorMaskValues.get(3) != 0 ? 8 : 0);
            FloatBuffer currentColor = workspace.currentColor();
            GL11.glGetFloat(GL11.GL_CURRENT_COLOR, currentColor);
            float red = currentColor.get(0);
            float green = currentColor.get(1);
            float blue = currentColor.get(2);
            float alpha = currentColor.get(3);
            IntBuffer viewport = workspace.viewport();
            GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
            IntBuffer scissorBox = workspace.scissorBox();
            GL11.glGetInteger(GL11.GL_SCISSOR_BOX, scissorBox);
            int matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
            FloatBuffer modelView = workspace.modelView();
            FloatBuffer projection = workspace.projection();
            FloatBuffer textureMatrix = workspace.textureMatrix();
            GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelView);
            GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection);
            GL11.glGetFloat(GL11.GL_TEXTURE_MATRIX, textureMatrix);
            OpenGlHelper.glUseProgram(program);
            if (readFramebuffer == drawFramebuffer) {
                OpenGlHelper.glBindFramebuffer(GL30.GL_FRAMEBUFFER, readFramebuffer);
            } else {
                OpenGlHelper.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
                OpenGlHelper.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            }
            GlStateManager.depthFunc(depthFunction);
            EarlyGlStateTracker.seedDepthRange(depthRange.get(0),
                depthRange.get(1));
            OpenGlHelper.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pixelPackBuffer);
            OpenGlHelper.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, arrayBuffer);
            GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
            GlStateManager.bindTexture(texture0);
            GlStateManager.setActiveTexture(GL13.GL_TEXTURE1);
            GlStateManager.bindTexture(texture1);
            GlStateManager.setActiveTexture(activeTextureEnum);
            OpenGlHelper.setClientActiveTexture(clientActiveTextureEnum);
            if (blend) GlStateManager.enableBlend();
            else GlStateManager.disableBlend();
            GlStateManager.tryBlendFuncSeparate(blendSourceRgb,
                blendDestinationRgb, blendSourceAlpha, blendDestinationAlpha);
            if (depthTest) GlStateManager.enableDepth();
            else GlStateManager.disableDepth();
            GlStateManager.depthMask(depthMask);
            if (cull) GlStateManager.enableCull();
            else GlStateManager.disableCull();
            if (lighting) GlStateManager.enableLighting();
            else GlStateManager.disableLighting();
            GlStateManager.colorMask((colorMask & 1) != 0, (colorMask & 2) != 0,
                (colorMask & 4) != 0, (colorMask & 8) != 0);
            GlStateManager.color(red, green, blue, alpha);
            EarlyGlStateTracker.seedDrawState(activeTexture, texture0, texture1,
                blend, blendSourceRgb, blendDestinationRgb, blendSourceAlpha,
                blendDestinationAlpha, depthTest, depthMask, cull, colorMask,
                red, green, blue, alpha);
            EarlyGlStateTracker.seedHudState(texture0Enabled, texture1Enabled,
                viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
            EarlyGlStateTracker.seedCompatibilityState(vertexArray,
                vertexArraySupported, elementBuffer, pixelUnpackBuffer,
                textureUnitCount, textures2d, texture2dEnabled,
                blendEquationRgb, blendEquationAlpha, cullFace, scissor,
                stencil, scissorBox.get(0), scissorBox.get(1),
                scissorBox.get(2), scissorBox.get(3));
            EarlyGlStateTracker.seedDrawIndirectBuffer(drawIndirectBuffer);
            EarlyMatrixStateTracker.seed(matrixMode, modelView, projection,
                textureMatrix);
            if (!EarlyGlStateTracker.isKnown()) {
                OptimizerRegistry.breaker(OptimizationModule.MODERN_VISIBILITY_HZB)
                    .forceIncompatible("GL state tracking hooks did not execute");
            }
            EarlyGlStateTracker.Snapshot tracked = EarlyGlStateTracker.snapshot();
            if (tracked == null || !tracked.hasDrawState()
                || !EarlyMatrixStateTracker.isKnown()) {
                OptimizerRegistry.breaker(OptimizationModule.MODERN_ENTITY_BACKEND)
                    .forceIncompatible("complete entity GL state tracking unavailable");
                OptimizerRegistry.breaker(OptimizationModule.MODERN_TESR_BACKEND)
                    .forceIncompatible("complete TESR GL state tracking unavailable");
            }
            if (tracked == null || !tracked.hasHudState()) {
                OptimizerRegistry.breaker(OptimizationModule.MODERN_HUD_STREAM)
                    .forceIncompatible("complete HUD GL state tracking unavailable");
            }
            if (tracked == null || !tracked.hasParticleState()) {
                OptimizerRegistry.breaker(OptimizationModule.MODERN_PARTICLE_BACKEND)
                    .forceIncompatible("complete particle GL state tracking unavailable");
            }
            if (EarlyGlStateTracker.activeTextureUnit() == Integer.MIN_VALUE
                || EarlyGlStateTracker.pixelUnpackBufferBinding()
                    == Integer.MIN_VALUE) {
                OptimizerRegistry.breaker(
                    OptimizationModule.MODERN_TEXTURE_VISIBILITY)
                    .forceIncompatible(
                        "animated atlas binding/PBO state tracking unavailable");
            }
            if (tracked == null || !tracked.hasDrawState()
                || EarlyGlStateTracker.compatibilitySnapshot() == null) {
                OptimizerRegistry.breaker(OptimizationModule.FBP_PARTICLE_ADAPTER)
                    .forceIncompatible("complete FBP compatibility GL state unavailable");
            }
        } catch (Throwable error) {
            EarlyGlStateTracker.invalidate();
            EarlyMatrixStateTracker.invalidate();
            FatalErrors.rethrowIfFatal(error);
            OptimizerRegistry.breaker(OptimizationModule.MODERN_VISIBILITY_HZB)
                .recordFailure(error);
            OptimizerRegistry.breaker(OptimizationModule.MODERN_ENTITY_BACKEND)
                .recordFailure(error);
            OptimizerRegistry.breaker(OptimizationModule.MODERN_TESR_BACKEND)
                .recordFailure(error);
            OptimizerRegistry.breaker(OptimizationModule.MODERN_HUD_STREAM)
                .recordFailure(error);
            OptimizerRegistry.breaker(OptimizationModule.MODERN_PARTICLE_BACKEND)
                .recordFailure(error);
            OptimizerRegistry.breaker(
                OptimizationModule.MODERN_TEXTURE_VISIBILITY)
                .recordFailure(error);
            OptimizerRegistry.breaker(OptimizationModule.FBP_PARTICLE_ADAPTER)
                .recordFailure(error);
        }
    }

    private void applyPendingInvalidations() {
        int flags = pendingInvalidations.getAndSet(0);
        if (flags == 0) return;
        softwareOcclusion.clear();
        boolean contextLost = (flags & INVALIDATE_CONTEXT) != 0;
        boolean modelMeshesInvalidated = invalidatesModelMeshes(flags);
        Throwable failure = null;
        if (!contextLost && initialized && components.threadGuard != null
            && components.threadGuard.isRenderThread()) {
            try { flushBatches(); }
            catch (Throwable error) {
                failure = appendRuntimeFailure(failure, error);
            }
            try { shaderRuntime.finishActiveShaderInterval(false, true); }
            catch (Throwable error) {
                failure = appendRuntimeFailure(failure, error);
            }
            if (shaderRuntime.hasNativeBinding()) {
                // Preserve both the binding evidence and installer ownership.
                // The next safe boundary retries restoration before any graph
                // mutation; context loss is handled separately by abandon.
                pendingInvalidations.getAndUpdate(value -> value | flags);
                failure = appendRuntimeFailure(failure,
                    new IllegalStateException(
                        "native shader restoration blocked invalidation"));
                currentStamp = null;
                measurements.frameGpuScope = null;
                rethrowRuntimeFailure(failure);
            }
        } else {
            shaderRuntime.abandonActiveShaderInterval();
        }
        try { shaderRuntime.clearPendingShaderCandidates(); }
        catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        try { shaderRuntime.clearShaderBindings(); }
        catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        if (contextLost) {
            try { TextureOutputValidator.invalidate(); }
            catch (Throwable error) {
                failure = appendRuntimeFailure(failure, error);
            }
            try { HudOutputValidator.invalidate(); }
            catch (Throwable error) {
                failure = appendRuntimeFailure(failure, error);
            }
            EarlyGlStateTracker.invalidate();
            EarlyMatrixStateTracker.invalidate();
            long abandonedGeneration = components.contextGeneration > 0L
                ? components.contextGeneration : lostContextGeneration;
            try { disposeActiveGraph(false, abandonedGeneration); }
            catch (Throwable error) {
                failure = appendRuntimeFailure(failure, error);
            }
            initialized = false;
            currentStamp = null;
            measurements.frameGpuScope = null;
            frameInfrastructureActive = false;
            quiescent = false;
            measurements.clearHzbGpuSamples();
            measurements.backendGpuSamples.clear();
            measurements.lastValidationFrames.clear();
            genericModelVboValidated = false;
            if (arenaGeneration == Long.MAX_VALUE) {
                failure = appendRuntimeFailure(failure,
                    new IllegalStateException("terrain arena generation exhausted"));
            } else {
                arenaGeneration++;
            }
            detail = "GL context 已变化，等待重新自测";
            if (failure != null) rethrowRuntimeFailure(failure);
            return;
        }
        if (!initialized) {
            if (failure != null) rethrowRuntimeFailure(failure);
            return;
        }
        try { LycanitesObjRenderBridge.invalidateRendererResources(this); }
        catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        try { if (components.certifiedDrawSites != null) components.certifiedDrawSites.invalidate(); }
        catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        try { if (components.shaderInstaller != null) components.shaderInstaller.reset(true); }
        catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        try { if (components.shaders != null) components.shaders.invalidate(); }
        catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        shaderRuntime.optifineProgramState = null;
        shaderRuntime.optifineRegionObservedGeneration = 0L;
        try { if (components.hzbHistory != null) components.hzbHistory.invalidate(); }
        catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        measurements.clearHzbGpuSamples();
        measurements.backendGpuSamples.clear();
        measurements.lastValidationFrames.clear();
        if (modelMeshesInvalidated) genericModelVboValidated = false;
        try { if (components.depthHistory != null) components.depthHistory.reset(true); }
        catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        try {
            if (components.animatedTextures != null) components.animatedTextures.reset(true,
                epochs.currentGlContextGeneration());
        } catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        try {
            if (components.hudRenderer != null) components.hudRenderer.reset(true,
                epochs.currentResourceGeneration(),
                epochs.currentGlContextGeneration());
        } catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        try { TextureOutputValidator.invalidate(); }
        catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        try { HudOutputValidator.invalidate(); }
        catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        try { if (components.spriteVisibility != null) components.spriteVisibility.invalidate(); }
        catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        animatedAtlasTextureId = 0;
        try { if (components.fonts != null) components.fonts.invalidate(); }
        catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        if (arenaGeneration == Long.MAX_VALUE) {
            failure = appendRuntimeFailure(failure,
                new IllegalStateException("terrain arena generation exhausted"));
        } else {
            long nextArenaGeneration = ++arenaGeneration;
            try {
                if (components.terrainArena != null) {
                    components.terrainArena.reset(nextArenaGeneration, true);
                }
            } catch (Throwable error) {
                failure = appendRuntimeFailure(failure, error);
            }
        }
        if (modelMeshesInvalidated) {
            try { if (components.modelMeshes != null) components.modelMeshes.reset(true); }
            catch (Throwable error) {
                failure = appendRuntimeFailure(failure, error);
            }
        }
        try {
            if (components.particleRenderer != null) {
                components.particleRenderer.reset(true,
                    epochs.currentResourceGeneration(), epochs.currentGlContextGeneration());
                RendererComponentFactory.initializeParticleResources(components, this);
            }
        } catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        try {
            if (components.fbpPacketRenderer != null) components.fbpPacketRenderer.reset(true,
                epochs.currentResourceGeneration(),
                epochs.currentGlContextGeneration());
        } catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        if (failure == null) try {
            RendererComponentFactory.prepareRuntimeBreakers(combinedGeneration());
            initializeTrackedGlState();
            configureBackends();
        } catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        if (failure != null) {
            try {
                disposeActiveGraph(true, epochs.currentGlContextGeneration());
            } catch (Throwable cleanupFailure) {
                failure = appendRuntimeFailure(failure, cleanupFailure);
            }
            initialized = false;
            currentStamp = null;
            measurements.frameGpuScope = null;
            rethrowRuntimeFailure(failure);
        }
    }

    void flushBatches() {
        if (components.modelMeshes != null) components.modelMeshes.flushPoseBatch();
        if (components.particles != null) components.particles.discardAtBarrier();
        if (components.particleRenderer != null && components.particleRenderer.size() > 0) {
            components.particleRenderer.discard();
        }
        if (components.hudRenderer != null && components.hudRenderer.hasCommands()) flushHudStream();
        if (components.hud != null) components.hud.discardAtBarrier();
    }

    private long beginLegacyBoundary(String reason,
                                     boolean coordinatorBarrier) {
        if (!initialized || components.legacyIsland == null || components.threadGuard == null
            || !components.threadGuard.isRenderThread()) return 0L;
        boolean islandOperational = OptimizerRegistry.isOperational(
            OptimizationModule.LEGACY_GL_ISLAND);
        try {
            // A certified OptiFine candidate is an internal implementation
            // detail and must never leak into an observing callback.
            shaderRuntime.finishActiveShaderInterval(true, true);
            long token;
            if (islandOperational) {
                token = components.legacyIsland.enter();
            } else {
                // A fused island still remains an ordering barrier. Dropping
                // the wrapper must never let buffered modern work cross an
                // observing Forge/mod callback.
                flushBatches();
                if (components.stateMirror != null) components.stateMirror.invalidateAll();
                token = 0L;
            }
            if (coordinatorBarrier && currentStamp != null && components.coordinator != null) {
                try {
                    components.coordinator.observableBarrier(reason == null
                        ? "Legacy GL callback" : reason);
                } catch (Throwable error) {
                    failCoordinator(error);
                }
            }
            return token;
        } catch (Throwable error) {
            try { flushBatches(); }
            catch (Throwable flushFailure) {
                error = appendRuntimeFailure(error, flushFailure);
            }
            FatalErrors.rethrowIfFatal(error);
            ModuleCircuitBreaker breaker = OptimizerRegistry.breaker(
                OptimizationModule.LEGACY_GL_ISLAND);
            if (breaker != null) breaker.recordFailure(error);
            if (components.stateMirror != null) components.stateMirror.invalidateAll();
            EarlyGlStateTracker.invalidate();
            EarlyMatrixStateTracker.invalidate();
            return 0L;
        }
    }

    private void endLegacyBoundary(long token, Throwable originalError) {
        Throwable failure = originalError;
        try {
            if (token != 0L && components.legacyIsland != null) {
                components.legacyIsland.exit(token, originalError);
            }
        } catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
            ModuleCircuitBreaker breaker = OptimizerRegistry.breaker(
                OptimizationModule.LEGACY_GL_ISLAND);
            if (breaker != null) try { breaker.recordFailure(error); }
            catch (Throwable reportingFailure) {
                failure = appendRuntimeFailure(failure, reportingFailure);
            }
        } finally {
            EarlyGlStateTracker.invalidate();
            EarlyMatrixStateTracker.invalidate();
        }
        FatalErrors.rethrowIfFatal(failure);
    }

    boolean insideLegacyIsland() {
        return components.legacyIsland != null && components.legacyIsland.isInside();
    }

    private void drainTextureCounters() {
        if (components.profiler == null || currentStamp == null) return;
        long commands = pendingTextureCommands.getAndSet(0L);
        long bytes = pendingTextureBytes.getAndSet(0L);
        long busy = pendingTextureBusy.getAndSet(0L);
        long fallbacks = pendingTextureFallbacks.getAndSet(0L);
        long visibilityDeferred = pendingVisibilityDeferred.getAndSet(0L);
        long visibilityDeferredBytes =
            pendingVisibilityDeferredBytes.getAndSet(0L);
        long visibilityCaughtUp = pendingVisibilityCaughtUp.getAndSet(0L);
        long visibilityCaughtUpBytes =
            pendingVisibilityCaughtUpBytes.getAndSet(0L);
        long visibilityUnknown = pendingVisibilityUnknownFrames.getAndSet(0L);
        if (commands > 0L) components.profiler.addCounter(currentStamp,
            RenderPass.ANIMATED_TEXTURE_UPLOAD, RenderBackendId.ICE_NATIVE,
            RenderCounter.UPLOAD_COMMAND, commands);
        if (bytes > 0L) components.profiler.addCounter(currentStamp,
            RenderPass.ANIMATED_TEXTURE_UPLOAD, RenderBackendId.ICE_NATIVE,
            RenderCounter.TEXTURE_UPLOAD_BYTES, bytes);
        if (busy > 0L) components.profiler.addCounter(currentStamp,
            RenderPass.ANIMATED_TEXTURE_UPLOAD, RenderBackendId.ICE_NATIVE,
            RenderCounter.FENCE_BUSY, busy);
        if (fallbacks > 0L) components.profiler.addCounter(currentStamp,
            RenderPass.ANIMATED_TEXTURE_UPLOAD, RenderBackendId.LEGACY,
            RenderCounter.LEGACY_FALLBACK, fallbacks);
        if (visibilityDeferred > 0L) components.profiler.addCounter(currentStamp,
            RenderPass.ANIMATED_TEXTURE_UPLOAD, RenderBackendId.ICE_NATIVE,
            RenderCounter.TEXTURE_VISIBILITY_DEFERRED, visibilityDeferred);
        if (visibilityDeferredBytes > 0L) components.profiler.addCounter(currentStamp,
            RenderPass.ANIMATED_TEXTURE_UPLOAD, RenderBackendId.ICE_NATIVE,
            RenderCounter.TEXTURE_VISIBILITY_DEFERRED_BYTES,
            visibilityDeferredBytes);
        if (visibilityCaughtUp > 0L) components.profiler.addCounter(currentStamp,
            RenderPass.ANIMATED_TEXTURE_UPLOAD, RenderBackendId.ICE_NATIVE,
            RenderCounter.TEXTURE_VISIBILITY_CAUGHT_UP, visibilityCaughtUp);
        if (visibilityCaughtUpBytes > 0L) components.profiler.addCounter(currentStamp,
            RenderPass.ANIMATED_TEXTURE_UPLOAD, RenderBackendId.ICE_NATIVE,
            RenderCounter.TEXTURE_VISIBILITY_CAUGHT_UP_BYTES,
            visibilityCaughtUpBytes);
        if (visibilityUnknown > 0L) components.profiler.addCounter(currentStamp,
            RenderPass.ANIMATED_TEXTURE_UPLOAD, RenderBackendId.LEGACY,
            RenderCounter.TEXTURE_VISIBILITY_UNKNOWN_FRAME, visibilityUnknown);
    }

    private static long safeAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private static long saturatedIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    private static void saturatedIncrement(AtomicLong value) {
        while (true) {
            long current = value.get();
            if (current == Long.MAX_VALUE
                || value.compareAndSet(current, current + 1L)) return;
        }
    }

    private void beginTerrainDecision(Object container, BlockRenderLayer layer) {
        pendingTerrainContainer = container;
        pendingTerrainLayer = layer;
        pendingTerrainReason = null;
        measurements.pendingTerrainMeasurementScene = null;
        measurements.pendingTerrainMeasurementStarted = 0L;
        measurements.pendingTerrainMeasurementStable = false;
    }

    private boolean declineTerrain(TerrainFallbackReason reason) {
        pendingTerrainReason = reason == null
            ? TerrainFallbackReason.UNTRACKED_LEGACY : reason;
        return false;
    }

    private void recordTerrainArenaDraw(boolean batched, int indirectCommands) {
        terrainArenaDraws = saturatedIncrement(terrainArenaDraws);
        if (!batched) {
            terrainArenaUnbatchedDraws = saturatedIncrement(
                terrainArenaUnbatchedDraws);
        } else if (indirectCommands > 0) {
            terrainMdiSubmissions = saturatedIncrement(terrainMdiSubmissions);
            terrainIndirectCommands = safeAdd(terrainIndirectCommands,
                (long) indirectCommands);
        } else {
            terrainArenaMultiDraws = saturatedIncrement(terrainArenaMultiDraws);
        }
        clearTerrainDecision();
    }

    private void recordTerrainArenaUncertainDraw() {
        terrainArenaDraws = saturatedIncrement(terrainArenaDraws);
        terrainArenaUncertainDraws = saturatedIncrement(
            terrainArenaUncertainDraws);
        clearTerrainDecision();
    }

    private void incrementTerrainReason(TerrainFallbackReason reason) {
        TerrainFallbackReason actual = reason == null
            ? TerrainFallbackReason.UNTRACKED_LEGACY : reason;
        int index = actual.ordinal();
        terrainFallbackReasons[index] = saturatedIncrement(
            terrainFallbackReasons[index]);
    }

    private void incrementHzbReason(HzbCaptureReason reason) {
        HzbCaptureReason actual = reason == null
            ? HzbCaptureReason.CAPTURE_FAILURE : reason;
        int index = actual.ordinal();
        hzbCaptureReasons[index] = saturatedIncrement(
            hzbCaptureReasons[index]);
    }

    private void incrementModelDrawReason(OptimizationModule module,
                                          ModelDrawReason reason) {
        int backendIndex = module == OptimizationModule.MODERN_TESR_BACKEND
            ? 1 : 0;
        ModelDrawReason actual = reason == null
            ? ModelDrawReason.RUNTIME_NOT_READY : reason;
        int reasonIndex = actual.ordinal();
        modelDrawReasons[backendIndex][reasonIndex] = saturatedIncrement(
            modelDrawReasons[backendIndex][reasonIndex]);
    }

    private void clearTerrainDecision() {
        pendingTerrainContainer = null;
        pendingTerrainLayer = null;
        pendingTerrainReason = null;
    }

    private void publishRendererDiagnostics(boolean force) {
        diagnostics.publish(force);
    }

    private static Throwable diagnosticRootCause(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 32; depth++) {
            Throwable next = current.getCause();
            if (next == null || next == current) return current;
            current = next;
        }
        return current;
    }

    private void advanceWarmup() {
        for (AdaptiveBackendController backend : components.backends.values()) {
            if (backend.lifecycleState() == BackendLifecycleState.WARMUP) {
                backend.warmupFrame(true);
            }
        }
    }

    private boolean advanceBackendLifecycles(long frameId) {
        boolean changed = false;
        for (AdaptiveBackendController backend : components.backends.values()) {
            if (backend.advanceFrame(frameId)) changed = true;
        }
        return changed;
    }

    private boolean requiresFrameInfrastructure(long frameId) {
        if (terrainSoftwareOcclusion() != null && softwareOcclusion.getMeshCount() >= 2) return true;
        for (AdaptiveBackendController backend : components.backends.values()) {
            if (!backend.isDormant(frameId)) return true;
        }
        return components.depthHistory != null && components.depthHistory.hasPendingReadback()
            || shaderRuntime.hasPendingWork()
            || observedSpriteVisibility != null
            || ModelMeshCaptureBridge.hasPendingModelMeshes() && mayCaptureModelMeshes();
    }

    private boolean backendMayInspect(OptimizationModule module) {
        AdaptiveBackendController backend = components.backends.get(module);
        return backend != null
            && backend.mayInspectOrRetestCandidate(lastRuntimeFrame);
    }

    private void discardPendingTelemetryCounters() {
        pendingTextureCommands.set(0L);
        pendingTextureBytes.set(0L);
        pendingTextureBusy.set(0L);
        pendingTextureFallbacks.set(0L);
        pendingVisibilityDeferred.set(0L);
        pendingVisibilityDeferredBytes.set(0L);
        pendingVisibilityCaughtUp.set(0L);
        pendingVisibilityCaughtUpBytes.set(0L);
        pendingVisibilityCatchUpNanos.set(0L);
        pendingVisibilityUnknownFrames.set(0L);
    }

    private void activateReadyBackends() {
        synchronizeTerrainSubBackends();
        for (AdaptiveBackendController backend : components.backends.values()) {
            if (backend.lifecycleState() == BackendLifecycleState.MODERN) {
                backend.activateAtSafeBoundary();
            }
        }
    }

    private void synchronizeTerrainSubBackends() {
        AdaptiveBackendController terrain = components.backends.get(
            OptimizationModule.MODERN_TERRAIN_BACKEND);
        if (terrain == null) return;
        BackendLifecycleState parentState = terrain.lifecycleState();
        if (parentState != BackendLifecycleState.LEGACY
            && parentState != BackendLifecycleState.QUARANTINED) {
            resumeTerrainChild(OptimizationModule.MODERN_TERRAIN_MDI);
            resumeTerrainChild(OptimizationModule.MODERN_TERRAIN_PERSISTENT_MAPPING);
            return;
        }
        suspendTerrainChild(OptimizationModule.MODERN_TERRAIN_MDI,
            parentState);
        suspendTerrainChild(
            OptimizationModule.MODERN_TERRAIN_PERSISTENT_MAPPING,
            parentState);
    }

    private void resumeTerrainChild(OptimizationModule module) {
        AdaptiveBackendController child = components.backends.get(module);
        if (child != null && child.resumeAfterParent()) measurements.discardBackendSamples(module);
    }

    private void suspendTerrainChild(OptimizationModule module,
                                     BackendLifecycleState parentState) {
        AdaptiveBackendController child = components.backends.get(module);
        if (child != null) child.suspendByParent("terrain parent "
            + parentState.name() + "; child measurement suspended");
    }

    private static boolean acceptsCandidateUploads(BackendLifecycleState state) {
        return state == BackendLifecycleState.WARMUP
            || state == BackendLifecycleState.OUTPUT_VALIDATE
            || state == BackendLifecycleState.PAIRED_MEASURE
            || state == BackendLifecycleState.MODERN
            || state == BackendLifecycleState.REGRESSION_MONITOR;
    }

    private static boolean retainsTerrainLegacyCopy(
        BackendLifecycleState state) {
        return state == BackendLifecycleState.WARMUP
            || state == BackendLifecycleState.OUTPUT_VALIDATE
            || state == BackendLifecycleState.PAIRED_MEASURE;
    }

    static boolean terrainShadowBudgetAllows(int uploads, long bytes,
                                             int nextBytes) {
        if (uploads < 0 || bytes < 0L || nextBytes <= 0) return false;
        return uploads < TERRAIN_SHADOW_UPLOADS_PER_FRAME
            && bytes <= TERRAIN_SHADOW_BYTES_PER_FRAME - nextBytes;
    }

    static boolean terrainShadowQualificationAllowed(BlockRenderLayer layer,
                                                      boolean shaderPackActive) {
        return layer == BlockRenderLayer.SOLID
            || shaderPackActive && layer != null;
    }

    static boolean terrainMeasurementCoverageStable(int visible, int owned) {
        if (visible <= 0 || owned < TERRAIN_MEASUREMENT_MIN_OWNED
            || owned > visible) return false;
        return (long) owned * 100L >= (long) visible
            * TERRAIN_MEASUREMENT_MIN_COVERAGE_PERCENT;
    }

    static RenderPass terrainPass(BlockRenderLayer layer) {
        if (OptifinePassLifecycleBridge.isShadowPass()) {
            return RenderPass.SHADOW_TERRAIN;
        }
        if (layer == BlockRenderLayer.TRANSLUCENT) return RenderPass.TRANSLUCENT;
        if (layer == BlockRenderLayer.CUTOUT_MIPPED) return RenderPass.MAIN_CUTOUT_MIPPED;
        if (layer == BlockRenderLayer.CUTOUT) return RenderPass.MAIN_CUTOUT;
        return RenderPass.MAIN_SOLID;
    }

    private void failCoordinator(Throwable error) {
        Throwable failure = error == null
            ? new IllegalStateException("unknown frame coordinator failure") : error;
        CorrelatedRenderProfiler.GpuScope danglingFrameScope = measurements.frameGpuScope;
        measurements.frameGpuScope = null;
        if (danglingFrameScope != null) {
            try { danglingFrameScope.close(); }
            catch (Throwable cleanupFailure) {
                failure = appendRuntimeFailure(failure, cleanupFailure);
            }
        }
        if (components.spriteVisibility != null && openVisibilityFrame > 0L) {
            try { components.spriteVisibility.abortFrame(openVisibilityFrame); }
            catch (Throwable cleanupFailure) {
                failure = appendRuntimeFailure(failure, cleanupFailure);
            }
            openVisibilityFrame = 0L;
        }
        if (components.coordinator != null && components.threadGuard != null && components.threadGuard.isRenderThread()) {
            try { components.coordinator.resetAfterFailure(failure); }
            catch (Throwable cleanupFailure) {
                failure = appendRuntimeFailure(failure, cleanupFailure);
            }
        }
        try {
            OptimizerRegistry.breaker(OptimizationModule.MODERN_FRAME_COORDINATOR)
                .recordFailure(failure);
        } catch (Throwable breakerFailure) {
            failure = appendRuntimeFailure(failure, breakerFailure);
        }
        currentStamp = null;
        frameInfrastructureActive = false;
        EarlyMatrixStateTracker.invalidate();
        detail = "帧协调异常，当前帧已回退 Legacy："
            + failure.getClass().getSimpleName();
        FatalErrors.rethrowIfFatal(failure);
    }

    long combinedGeneration() {
        return rendererGeneration.get();
    }

    /**
     * ModelRenderer geometry is independent of the active world, but it is
     * qualified by the resource and GL-context generations.  Keeping this
     * policy in one predicate prevents an ordinary dimension/world switch
     * from throwing away a warm VBO cache while still rejecting stale names
     * after a resource reload or context loss.
     */
    private static boolean invalidatesModelMeshes(int flags) {
        return (flags & (INVALIDATE_RESOURCES | INVALIDATE_CONTEXT)) != 0;
    }

    private long advanceRendererGeneration() {
        while (true) {
            long current = rendererGeneration.get();
            if (current <= 0L || current == Long.MAX_VALUE) {
                throw new IllegalStateException(
                    "modern renderer generation exhausted");
            }
            long next = current + 1L;
            if (rendererGeneration.compareAndSet(current, next)) return next;
        }
    }

    long rendererGenerationForTest() { return combinedGeneration(); }

    static boolean invalidatesModelMeshesForTest(boolean world,
                                                  boolean resources,
                                                  boolean context) {
        int flags = (world ? INVALIDATE_WORLD : 0)
            | (resources ? INVALIDATE_RESOURCES : 0)
            | (context ? INVALIDATE_CONTEXT : 0);
        return invalidatesModelMeshes(flags);
    }

    enum TerrainFallbackReason {
        RUNTIME_NOT_READY,
        NO_ACTIVE_FRAME,
        ARENA_UNAVAILABLE,
        RENDER_THREAD_UNAVAILABLE,
        CONTAINER_ABI_MISSING,
        SHADER_STATE_UNKNOWN,
        SHADER_PACK_ACTIVE,
        SHADER_LAYOUT_UNCERTIFIED,
        NO_ARENA_OWNERSHIP,
        SHADOW_LEGACY_REPLAY,
        ARENA_DECLINED,
        FAULT_FALLBACK_DECLINED,
        ARENA_SUBMISSION_UNCERTAIN,
        ARENA_POST_DRAW_FAILURE,
        UNTRACKED_LEGACY
    }

    enum HzbCaptureReason {
        RUNTIME_NOT_READY,
        LEGACY_ISLAND,
        CONTAINER_ABI_MISSING,
        NON_CAPTURE_LAYER,
        BACKEND_UNAVAILABLE,
        BACKEND_STATE_REJECTED,
        SHADER_STATE_UNKNOWN,
        SHADER_PACK_ACTIVE,
        STATE_REAUTHENTICATION_FAILED,
        PAIRED_LEGACY_HISTORY_REUSED,
        CAPTURED,
        VIEW_UNSTABLE,
        CAPTURE_PENDING,
        HISTORY_REUSED,
        TEMPORARILY_UNAVAILABLE,
        UNSAFE_STATE,
        UNSUPPORTED_SOURCE,
        CAPTURE_FAILURE
    }

    enum ModelDrawReason {
        RUNTIME_NOT_READY,
        LEGACY_ISLAND,
        SHADER_STATE_UNKNOWN,
        SHADER_PACK_ACTIVE,
        BACKEND_UNAVAILABLE,
        BACKEND_STATE_REJECTED,
        CACHE_MISS,
        STATE_REAUTHENTICATION_FAILED,
        OUTPUT_VALIDATION_PASSED,
        OUTPUT_VALIDATION_FAILED,
        ENTRY_UNVALIDATED,
        MEASUREMENT_ARM_LEGACY,
        DRAW_STATE_UNKNOWN,
        MATRIX_STATE_UNKNOWN,
        SCOPE_REJECTED,
        PACKET_REJECTED,
        STREAM_REJECTED,
        DRAW_DECLINED,
        MODERN_DRAW,
        DRAW_OUTCOME_UNCERTAIN
    }

    interface ShaderBindingPublicationFault {
        void checkpoint(String point);
    }

    /** Candidate graph is private until every constructor/self-test succeeds. */

    public static final class RenderBackendSample {
        final OptimizationModule module;
        final BackendLifecycleState state;
        final MeasurementArm arm;
        final SceneFingerprint scene;
        final RenderProfileKey key;
        final CorrelatedRenderProfiler.GpuScope gpuScope;
        final long startedNanos;
        final long frameId;

        RenderBackendSample(OptimizationModule module,
                                    BackendLifecycleState state,
                                    MeasurementArm arm, SceneFingerprint scene,
                                    RenderProfileKey key,
                                    CorrelatedRenderProfiler.GpuScope gpuScope,
                                    long startedNanos, long frameId) {
            this.module = module;
            this.state = state;
            this.arm = arm;
            this.scene = scene;
            this.key = key;
            this.gpuScope = gpuScope;
            this.startedNanos = startedNanos;
            this.frameId = frameId;
        }

        public boolean usesModernArm() {
            return state == BackendLifecycleState.MODERN
                || state == BackendLifecycleState.REGRESSION_MONITOR
                || (state == BackendLifecycleState.PAIRED_MEASURE
                    && arm == MeasurementArm.MODERN);
        }

        public BackendLifecycleState lifecycleState() { return state; }
        public RenderBackendId backendId() {
            return usesModernArm() ? RenderBackendId.ICE_NATIVE
                : RenderBackendId.LEGACY;
        }
    }

    /** Opaque render-thread scope owned by the OptiFine Region observer. */
    public static final class OptifineRegionDrawSample {
        final RenderPass pass;
        final int commandCount;
        final int commandCapacity;
        final boolean preDrawValid;
        final CorrelatedRenderProfiler.CpuScope cpuScope;
        final CorrelatedRenderProfiler.GpuScope gpuScope;

        OptifineRegionDrawSample(RenderPass pass, int commandCount,
                                         int commandCapacity,
                                         boolean preDrawValid,
                                         CorrelatedRenderProfiler.CpuScope cpuScope,
                                         CorrelatedRenderProfiler.GpuScope gpuScope) {
            this.pass = pass;
            this.commandCount = commandCount;
            this.commandCapacity = commandCapacity;
            this.preDrawValid = preDrawValid;
            this.cpuScope = cpuScope;
            this.gpuScope = gpuScope;
        }
    }

}
