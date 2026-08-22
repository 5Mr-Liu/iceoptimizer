package dev.rlcraft.ice.optimizer.client;

import dev.rlcraft.ice.IceMod;
import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.ModuleCircuitBreaker;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.memory.CacheBudgetStatus;
import dev.rlcraft.ice.optimizer.compat.chunk.ChunkAnimatorRenderBridge;
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
import dev.rlcraft.ice.optimizer.render.backend.CapabilityReport;
import dev.rlcraft.ice.optimizer.render.backend.LwjglCapabilitySelfTest;
import dev.rlcraft.ice.optimizer.render.backend.MeasurementArm;
import dev.rlcraft.ice.optimizer.render.backend.ModernCapability;
import dev.rlcraft.ice.optimizer.render.backend.RenderBackendId;
import dev.rlcraft.ice.optimizer.render.backend.SceneFingerprint;
import dev.rlcraft.ice.optimizer.render.arena.ArenaStatus;
import dev.rlcraft.ice.optimizer.render.entity.CertifiedDrawSites;
import dev.rlcraft.ice.optimizer.render.entity.LwjglModelMeshCache;
import dev.rlcraft.ice.optimizer.render.entity.ModelMeshPayload;
import dev.rlcraft.ice.optimizer.render.frame.FrameCoordinator;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.frame.PassGraph;
import dev.rlcraft.ice.optimizer.render.frame.RenderPass;
import dev.rlcraft.ice.optimizer.render.hud.FontLayoutCache;
import dev.rlcraft.ice.optimizer.render.hud.HudOutputValidator;
import dev.rlcraft.ice.optimizer.render.hud.HudVertexStream;
import dev.rlcraft.ice.optimizer.render.hud.LwjglHudRenderer;
import dev.rlcraft.ice.optimizer.render.legacy.GlStateMirror;
import dev.rlcraft.ice.optimizer.render.legacy.LegacyGlIsland;
import dev.rlcraft.ice.optimizer.render.legacy.LwjglLegacyStateRestorer;
import dev.rlcraft.ice.optimizer.render.optifine.LwjglShaderProgramInstaller;
import dev.rlcraft.ice.optimizer.render.optifine.LwjglShaderImageCertification;
import dev.rlcraft.ice.optimizer.render.optifine.LwjglOptifineShaderActivation;
import dev.rlcraft.ice.optimizer.render.optifine.OptifineShaderBackendSelector;
import dev.rlcraft.ice.optimizer.render.optifine.OptifineProgramIntrospector;
import dev.rlcraft.ice.optimizer.render.optifine.ShaderCertificationRegistry;
import dev.rlcraft.ice.optimizer.render.optifine.ShaderCertificationPipeline;
import dev.rlcraft.ice.optimizer.render.optifine.LwjglShaderCompilationDriver;
import dev.rlcraft.ice.optimizer.render.optifine.OptifineProgramState;
import dev.rlcraft.ice.optimizer.render.optifine.PreparedShaderPermutation;
import dev.rlcraft.ice.optimizer.render.optifine.ShaderPermutationKey;
import dev.rlcraft.ice.optimizer.render.optifine.ShaderCompileInstallGate;
import dev.rlcraft.ice.optimizer.render.optifine.ShaderTerrainLayoutCertification;
import dev.rlcraft.ice.optimizer.render.particle.ParticleInstanceStream;
import dev.rlcraft.ice.optimizer.render.particle.LwjglFbpPacketRenderer;
import dev.rlcraft.ice.optimizer.render.particle.LwjglParticleRenderer;
import dev.rlcraft.ice.optimizer.render.resource.LwjglResourceDestroyer;
import dev.rlcraft.ice.optimizer.render.resource.LwjglRetirementFence;
import dev.rlcraft.ice.optimizer.render.resource.RenderHandle;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedgerStatus;
import dev.rlcraft.ice.optimizer.render.telemetry.CorrelatedRenderProfiler;
import dev.rlcraft.ice.optimizer.render.telemetry.LwjglGpuTimestampDriver;
import dev.rlcraft.ice.optimizer.render.telemetry.CpuWorkKind;
import dev.rlcraft.ice.optimizer.render.telemetry.PassProfile;
import dev.rlcraft.ice.optimizer.render.telemetry.RenderCounter;
import dev.rlcraft.ice.optimizer.render.telemetry.RenderProfilerSnapshot;
import dev.rlcraft.ice.optimizer.render.telemetry.RenderProfileKey;
import dev.rlcraft.ice.optimizer.render.terrain.LwjglTerrainArena;
import dev.rlcraft.ice.optimizer.render.terrain.TerrainIndirectReason;
import dev.rlcraft.ice.optimizer.render.terrain.TerrainUploadContext;
import dev.rlcraft.ice.optimizer.render.texture.SpriteVisibilityTracker;
import dev.rlcraft.ice.optimizer.render.texture.TextureUploadStream;
import dev.rlcraft.ice.optimizer.render.texture.LwjglAnimatedTextureUploadStream;
import dev.rlcraft.ice.optimizer.render.texture.TextureOutputValidator;
import dev.rlcraft.ice.optimizer.render.visibility.ConservativeOcclusionHistory;
import dev.rlcraft.ice.optimizer.render.visibility.LwjglDepthHistory;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import dev.rlcraft.ice.optimizer.runtime.EpochToken;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockRenderLayer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GLContext;

/** Render-thread owner of the complete hybrid renderer component graph. */
public final class ModernRendererRuntime {
    private static final int INVALIDATE_WORLD = 1;
    private static final int INVALIDATE_RESOURCES = 1 << 1;
    private static final int INVALIDATE_CONTEXT = 1 << 2;
    private static final int MAX_PENDING_SHADER_CANDIDATES = 64;
    private static final long MAX_PENDING_SHADER_BYTES = 32L * 1024L * 1024L;
    private static final int MAX_PENDING_SHADER_VALIDATIONS = 64;
    private static final int MAX_SHADER_BINDINGS = 256;
    private static final long DIAGNOSTICS_FRAME_INTERVAL = 120L;
    private static final int TERRAIN_SHADOW_UPLOADS_PER_FRAME = 8;
    private static final long TERRAIN_SHADOW_BYTES_PER_FRAME =
        16L * 1024L * 1024L;
    private static final int TERRAIN_MEASUREMENT_MIN_OWNED = 64;
    private static final int TERRAIN_MEASUREMENT_MIN_COVERAGE_PERCENT = 60;
    private static final int MODEL_ADMISSIONS_PER_FRAME = 48;
    private static final long MODEL_ADMISSION_BYTES_PER_FRAME =
        4L * 1024L * 1024L;

    private final ClientEpochs epochs;
    private final CacheBudget budget;
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
    private final ArrayDeque<CapturedShaderSources> pendingShaderCandidates =
        new ArrayDeque<CapturedShaderSources>();
    private long pendingShaderCandidateBytes;
    private final ArrayDeque<ShaderValidationRequest> pendingShaderValidations =
        new ArrayDeque<ShaderValidationRequest>();
    private final IdentityHashMap<Object, ShaderProgramBinding> shaderBindings =
        new IdentityHashMap<Object, ShaderProgramBinding>();
    private final HashMap<Integer, ShaderProgramBinding> shaderBindingsById =
        new HashMap<Integer, ShaderProgramBinding>();
    private boolean shaderBindingsPoisoned;
    private ShaderProgramBinding shaderBindingPublicationWitness;
    private ShaderProgramBinding[] shaderBindingCleanupWitness;
    private ShaderBindingPublicationFault shaderBindingPublicationFault;
    private final OptifineProgramIntrospector shaderIntrospector =
        new OptifineProgramIntrospector();
    private final EnumMap<OptimizationModule, AdaptiveBackendController> backends =
        new EnumMap<OptimizationModule, AdaptiveBackendController>(OptimizationModule.class);
    private final InitializationRetryPolicy initializationRetry =
        new InitializationRetryPolicy();
    private volatile long lostContextGeneration;
    private ComponentGraph activeGraph;
    private long componentContextGeneration;
    private RenderThreadGuard threadGuard;
    private GlStateMirror stateMirror;
    private FrameCoordinator coordinator;
    private LegacyGlIsland legacyIsland;
    private CorrelatedRenderProfiler profiler;
    private ResourceLedger resources;
    private LwjglTerrainArena terrainArena;
    private LwjglModelMeshCache modelMeshes;
    private ParticleInstanceStream particles;
    private LwjglParticleRenderer particleRenderer;
    private LwjglFbpPacketRenderer fbpPacketRenderer;
    private TextureUploadStream textureUploads;
    private SpriteVisibilityTracker spriteVisibility;
    private LwjglAnimatedTextureUploadStream animatedTextures;
    private HudVertexStream hud;
    private LwjglHudRenderer hudRenderer;
    private FontLayoutCache fonts;
    private CertifiedDrawSites certifiedDrawSites;
    private ShaderCertificationRegistry shaders;
    private ShaderCertificationPipeline shaderPipeline;
    private LwjglShaderCompilationDriver shaderCompiler;
    private LwjglShaderProgramInstaller shaderInstaller;
    private LwjglShaderImageCertification shaderImageCertification;
    private LwjglOptifineShaderActivation shaderActivation;
    private OptifineShaderBackendSelector shaderBackendSelector;
    private OptifineProgramState optifineProgramState;
    private Object optifineProgramIdentity;
    private ConservativeOcclusionHistory hzbHistory;
    private LwjglDepthHistory depthHistory;
    private GlStateQueryWorkspace glStateQueryWorkspace;
    private CapabilityReport capabilities;
    private FrameStamp currentStamp;
    private CorrelatedRenderProfiler.GpuScope frameGpuScope;
    private RenderBackendSample activeShaderSample;
    private ShaderProgramBinding activeShaderSampleBinding;
    private ShaderProgramBinding activeNativeShaderBinding;
    private boolean initialized;
    private volatile boolean shutdownRequested;
    private boolean genericModelVboValidated;
    private long modelDrawOutsideTraversal;
    private long modelStateReauthenticationAttempts;
    private long modelStateReauthentications;
    private long modelStateReauthenticationFailures;
    private long modelStateReauthenticationSuppressions;
    private long modelUploadBindingAttempts;
    private long modelUploadBindingRecoveries;
    private long modelUploadBindingFailures;
    private long failedModelGlInvalidation = Long.MIN_VALUE;
    private long failedModelMatrixInvalidation = Long.MIN_VALUE;
    private long failedModelReauthenticationFrame = Long.MIN_VALUE;
    private String lastModelStateFailureStage = "";
    private String lastModelStateFailureType = "";
    private String lastModelStateFailureMessage = "";
    private final long[][] modelDrawReasons = new long[2]
        [ModelDrawReason.values().length];
    private volatile boolean shaderPackStateKnown;
    private volatile boolean shaderPackActive;
    private long optifineRegionObservedGeneration;
    private long openVisibilityFrame;
    private int animatedAtlasTextureId;
    private long arenaGeneration = 1L;
    private String detail = "等待首个有效 OpenGL 帧";
    private long terrainArenaUploads;
    private final AtomicLong terrainLegacyUploads = new AtomicLong();
    private long terrainLegacyDraws;
    private long terrainArenaDraws;
    private long terrainArenaUncertainDraws;
    private long terrainArenaUnbatchedDraws;
    private long terrainArenaMultiDraws;
    private long terrainMdiSubmissions;
    private long terrainIndirectCommands;
    private long terrainShadowBudgetFrame = Long.MIN_VALUE;
    private int terrainShadowUploadsThisFrame;
    private long terrainShadowBytesThisFrame;
    private long terrainShadowUploadAttempts;
    private long terrainShadowUploads;
    private long terrainShadowUploadedBytes;
    private long terrainShadowBudgetRejects;
    private long terrainShadowQualificationSkips;
    private long terrainMeasurementCoverageRejects;
    private long terrainMeasurementProfileWarmups;
    private int terrainLastVisibleMeshes;
    private int terrainLastOwnedMeshes;
    private int terrainLastRegionRuns;
    private long hzbCaptureAttempts;
    private long hzbStateReauthenticationAttempts;
    private long hzbStateReauthentications;
    private long hzbStateReauthenticationFailures;
    private long hzbStateReauthenticationSuppressions;
    private long failedHzbStateInvalidation = Long.MIN_VALUE;
    private long failedHzbStateFrame = Long.MIN_VALUE;
    private String lastHzbStateFailureType = "";
    private String lastHzbStateFailureMessage = "";
    private long particleBackendFailures;
    private String lastParticleFailureType = "";
    private String lastParticleFailureMessage = "";
    private String lastParticleRootFailureType = "";
    private String lastParticleRootFailureMessage = "";
    private final long[] hzbCaptureReasons =
        new long[HzbCaptureReason.values().length];
    private final long[] terrainFallbackReasons =
        new long[TerrainFallbackReason.values().length];
    private Object pendingTerrainContainer;
    private BlockRenderLayer pendingTerrainLayer;
    private TerrainFallbackReason pendingTerrainReason;
    private SceneFingerprint pendingTerrainMeasurementScene;
    private long pendingTerrainMeasurementStarted;
    private boolean pendingTerrainMeasurementStable;
    private long lastDiagnosticsFrame = Long.MIN_VALUE;
    private final LinkedHashMap<RenderProfileKey, HzbGpuSample> hzbGpuSamples =
        new LinkedHashMap<RenderProfileKey, HzbGpuSample>();
    private final LinkedHashMap<RenderProfileKey, BackendGpuSample> backendGpuSamples =
        new LinkedHashMap<RenderProfileKey, BackendGpuSample>();
    private final EnumMap<OptimizationModule, Long> lastValidationFrames =
        new EnumMap<OptimizationModule, Long>(OptimizationModule.class);
    private final CorrelatedRenderProfiler.GpuCompletion hzbGpuCompletion =
        new CorrelatedRenderProfiler.GpuCompletion() {
            @Override public void completed(RenderProfileKey key, long elapsedNanos) {
                completeHzbGpuSample(key, elapsedNanos);
            }
        };
    private final CorrelatedRenderProfiler.GpuCompletion backendGpuCompletion =
        new CorrelatedRenderProfiler.GpuCompletion() {
            @Override public void completed(RenderProfileKey key, long elapsedNanos) {
                completeBackendGpuSample(key, elapsedNanos);
            }
        };

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
            // Context loss must dispose/abandon the old graph before any new
            // GL capability probe.  Resource/world changes are likewise
            // applied at the frame ownership boundary.
            applyPendingInvalidations();
            ensureInitialized();
            if (!initialized) return;
            drainPendingShaderCandidates(2);
            drainPendingShaderValidations(1);
            promoteCertifiedShaderBackend();
            profiler.pollGpu(8);
            synchronizeTerrainSubBackends();
            coordinator.beginFrame(frameId, token);
            currentStamp = coordinator.beginPrimaryView();
            if (ModelMeshCaptureBridge.hasPendingModelMeshes()
                && prepareModelMeshAdmissions()) {
                ModelMeshCaptureBridge.drainPendingModelMeshes(
                    MODEL_ADMISSIONS_PER_FRAME,
                    MODEL_ADMISSION_BYTES_PER_FRAME);
            }
            if (spriteVisibility != null) {
                spriteVisibility.beginFrame(frameId,
                    epochs.currentResourceGeneration(),
                    epochs.currentAtlasGeneration());
                openVisibilityFrame = frameId;
            }
            drainTextureCounters();
            if (depthHistory != null) {
                try {
                    AdaptiveBackendController visibility = backends.get(
                        OptimizationModule.MODERN_VISIBILITY_HZB);
                    // Reauthenticate only when a completed readback may need
                    // the prior PBO binding.  LwjglDepthHistory will retain
                    // the ring payload if authentication still fails.
                    if (depthHistory.hasPendingReadback()
                        && EarlyGlStateTracker.snapshot() == null) {
                        ensureHzbTrackedState(visibility);
                    }
                    depthHistory.poll();
                    // OUTPUT_VALIDATE captures carry a sampled source-depth
                    // oracle.  Successful publication already proves the GPU
                    // reduction and retained CPU hierarchy; it must not wait
                    // for an unrelated next-frame camera/geometry identity
                    // match before advancing correctness certification.
                    if (depthHistory.consumeOracleValidatedPublication()) {
                        recordValidationOnce(
                            OptimizationModule.MODERN_VISIBILITY_HZB, true,
                            null);
                    }
                } catch (Throwable error) {
                    FatalErrors.rethrowIfFatal(error);
                    AdaptiveBackendController visibility = backends.get(
                        OptimizationModule.MODERN_VISIBILITY_HZB);
                    if (visibility != null) visibility.runtimeFailure(error);
                    depthHistory.reset(true);
                    hzbGpuSamples.clear();
                    detail = "HZB 延迟回读异常；仅可见性后端已回退："
                        + error.getClass().getSimpleName();
                }
            }
            frameGpuScope = profiler.beginGpu(currentStamp, RenderPass.FINAL,
                RenderBackendId.LEGACY);
        } catch (Throwable error) {
            failCoordinator(error);
        }
    }

    public void endFrame() {
        if (!initialized || threadGuard == null || !threadGuard.isRenderThread()) return;
        try {
            finishActiveShaderInterval(true, true);
            CorrelatedRenderProfiler.GpuScope closingFrameScope = frameGpuScope;
            frameGpuScope = null;
            if (closingFrameScope != null) closingFrameScope.close();
            if (coordinator.snapshot().getViewDepth() == 1) coordinator.endPrimaryView();
            if (coordinator.snapshot().isFrameActive()) coordinator.endFrame();
            if (terrainArena != null) {
                terrainArena.endFrame(true);
                Throwable terrainFailure = terrainArena.consumePublicationFailure();
                if (terrainFailure != null) {
                    FatalErrors.rethrowIfFatal(terrainFailure);
                    AdaptiveBackendController terrain = backends.get(
                        OptimizationModule.MODERN_TERRAIN_BACKEND);
                    if (terrain != null) terrain.runtimeFailure(terrainFailure);
                    detail = "地形资源回收异常；地形子后端已独立回退："
                        + terrainFailure.getClass().getSimpleName();
                }
            }
            resources.collect(epochs.currentGlContextGeneration(), 16);
            advanceWarmup();
            activateReadyBackends();
            publishRendererDiagnostics(false);
            if (spriteVisibility != null && openVisibilityFrame > 0L) {
                spriteVisibility.endFrame(openVisibilityFrame, true);
                openVisibilityFrame = 0L;
            }
        } catch (Throwable error) {
            failCoordinator(error);
        } finally {
            currentStamp = null;
        }
    }

    public void beginPass(RenderPass pass, RenderBackendId backend) {
        if (!initialized) return;
        coordinator.beginPass(pass, backend);
    }

    public void endPass(RenderPass pass) {
        if (!initialized) return;
        coordinator.endPass(pass);
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
        if (!initialized || currentStamp == null || coordinator == null
            || threadGuard == null || !threadGuard.isRenderThread()) return 0L;
        try {
            return coordinator.beginObservedPass(pass,
                backend == null ? RenderBackendId.LEGACY : backend);
        } catch (Throwable error) {
            failCoordinator(error);
            return 0L;
        }
    }

    public void endObservedPass(long token) {
        if (token == 0L || !initialized || coordinator == null
            || threadGuard == null || !threadGuard.isRenderThread()) return;
        try {
            if (!coordinator.endObservedPass(token)) {
                detail = "检测到模组 pass 递归/配对偏差；状态已失效并保持 Legacy";
            }
        } catch (Throwable error) {
            failCoordinator(error);
        }
    }

    public void observableBarrier(String reason) {
        if (!initialized || currentStamp == null) return;
        coordinator.observableBarrier(reason);
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
        if (!initialized || legacyIsland == null) {
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
        if (!initialized || currentStamp == null || coordinator == null
            || legacyIsland == null || threadGuard == null
            || !threadGuard.isRenderThread()) return 0L;
        long token = beginLegacyBoundary("iChunUtil WorldPortal enter", false);
        if (token == 0L) return 0L;
        try {
            currentStamp = coordinator.beginPortalView();
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
            if (initialized && coordinator != null && threadGuard != null
                && threadGuard.isRenderThread()
                && coordinator.snapshot().getViewDepth() > 1) {
                coordinator.endPortalView();
                currentStamp = coordinator.currentStamp();
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

    public void setShaderPackState(boolean known, boolean active) {
        shaderPackStateKnown = known;
        shaderPackActive = known && active;
        if (!shaderPackActive) {
            if (initialized && threadGuard != null && threadGuard.isRenderThread()) {
                finishActiveShaderInterval(false, true);
            }
            optifineProgramState = null;
            optifineProgramIdentity = null;
        }
        if ((!known || active) && depthHistory != null && threadGuard != null
            && threadGuard.isRenderThread()) {
            depthHistory.invalidateScene();
            hzbGpuSamples.clear();
        }
    }

    /**
     * Native fixed-layout streams are admitted only after the OptiFine probe
     * has positively established that no ShaderPack is active.  Unknown is a
     * compatibility state, not an optimistic "off" state.
     */
    public boolean isShaderPackSafeForNativeVertexFormats() {
        return shaderPackStateKnown && !shaderPackActive;
    }

    /** ShaderPack exception used only by the dual-resident terrain arena. */
    private boolean isShaderPackSafeForTerrainArena() {
        if (!shaderPackStateKnown) return false;
        if (!shaderPackActive) return true;
        ShaderProgramBinding binding = activeNativeShaderBinding;
        EarlyGlStateTracker.Snapshot gl = EarlyGlStateTracker.snapshot();
        boolean bindingPresent = binding != null
            && activeShaderSampleBinding == binding
            && shaderBindings.get(binding.programIdentity) == binding
            && shaderBindingsById.get(Integer.valueOf(binding.legacyProgram))
                == binding;
        boolean identityMatches = bindingPresent
            && optifineProgramIdentity == binding.programIdentity;
        boolean programMatches = identityMatches && optifineProgramState != null
            && optifineProgramState.getProgramId() == binding.candidateProgram
            && gl != null && gl.getProgram() == binding.candidateProgram;
        boolean activationStateMatches = bindingPresent
            && shaderTerrainActivationStateMatches(binding.certifiedState,
                optifineProgramState, gl == null ? -1 : gl.getDrawFramebuffer());
        boolean generationsCurrent = bindingPresent
            && bindingGenerationsCurrent(binding);
        boolean certified = generationsCurrent && shaders != null
            && shaders.isCertified(binding.key);
        return shaderTerrainGate(shaderPackStateKnown, shaderPackActive,
            bindingPresent, identityMatches, programMatches,
            activationStateMatches, generationsCurrent, certified,
            bindingPresent && binding.terrainLayoutCertified);
    }

    static boolean shaderTerrainGate(boolean known, boolean active,
                                     boolean bindingPresent,
                                     boolean identityMatches,
                                     boolean programMatches,
                                     boolean activationStateMatches,
                                     boolean generationsCurrent,
                                     boolean outputCertified,
                                     boolean layoutCertified) {
        return known && (!active || bindingPresent && identityMatches
            && programMatches && activationStateMatches && generationsCurrent
            && outputCertified && layoutCertified);
    }

    static boolean shaderTerrainActivationStateMatches(
        OptifineProgramState certified, OptifineProgramState current,
        int trackedDrawFramebuffer) {
        return certified != null && current != null
            && current.getFramebuffer() > 0
            && current.getFramebuffer() == trackedDrawFramebuffer
            && certified.isLogicalActivationEquivalent(current);
    }

    public void recordValidation(OptimizationModule module, boolean equivalent,
                                 String detail) {
        AdaptiveBackendController backend = backends.get(module);
        if (backend != null && backend.lifecycleState()
            == BackendLifecycleState.OUTPUT_VALIDATE) {
            backend.validationResult(equivalent, detail);
        }
    }

    public MeasurementArm expectedMeasurementArm(OptimizationModule module) {
        AdaptiveBackendController backend = backends.get(module);
        return backend == null ? MeasurementArm.LEGACY : backend.expectedMeasurementArm();
    }

    public void recordMeasurement(OptimizationModule module, SceneFingerprint scene,
                                  MeasurementArm arm, long nanos, boolean stable) {
        AdaptiveBackendController backend = backends.get(module);
        if (backend != null && backend.lifecycleState()
            == BackendLifecycleState.PAIRED_MEASURE) {
            backend.recordMeasurement(scene, arm, nanos, stable);
        }
    }

    public boolean activateBackend(OptimizationModule module) {
        AdaptiveBackendController backend = backends.get(module);
        return backend != null && currentStamp == null && backend.activateAtSafeBoundary();
    }

    public void shutdown() {
        shutdownRequested = true;
        if (activeGraph != null && threadGuard != null
            && !threadGuard.isRenderThread()) {
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
        if (spriteVisibility != null && openVisibilityFrame > 0L) {
            try { spriteVisibility.abortFrame(openVisibilityFrame); }
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
            try { finishActiveShaderInterval(false, true); }
            catch (Throwable error) {
                shutdownFailure = appendRuntimeFailure(shutdownFailure, error);
            }
            shaderSafeToDispose = activeNativeShaderBinding == null;
            if (!shaderSafeToDispose) {
                shutdownFailure = appendRuntimeFailure(shutdownFailure,
                    new IllegalStateException(
                        "native ShaderPack program is still active"));
            }
        } else try { abandonActiveShaderInterval(); }
        catch (Throwable error) {
            shutdownFailure = appendRuntimeFailure(shutdownFailure, error);
        }
        if (shaderSafeToDispose) {
            try { disposeActiveGraph(validContext, componentContextGeneration); }
            catch (Throwable error) {
                shutdownFailure = appendRuntimeFailure(shutdownFailure, error);
            }
        } else {
            // Deleting or forgetting a program which may still be current is
            // a use-after-retire risk.  Keep the bounded graph intact so a
            // later render-thread shutdown can retry restoration.
            currentStamp = null;
            frameGpuScope = null;
            detail = "Shader candidate 无法恢复；现代资源保持所有权并等待重试关闭";
            FatalErrors.rethrowIfFatal(shutdownFailure);
            IceMod.LOGGER.warn("现代渲染器关闭延后：仍有 active Shader candidate",
                shutdownFailure);
            return;
        }
        initialized = false;
        currentStamp = null;
        frameGpuScope = null;
        try { backendGpuSamples.clear(); }
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
        optifineProgramState = null;
        try { clearPendingShaderCandidates(); }
        catch (Throwable error) {
            shutdownFailure = appendRuntimeFailure(shutdownFailure, error);
        }
        try { clearShaderBindings(); }
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
        for (Map.Entry<OptimizationModule, AdaptiveBackendController> entry : backends.entrySet()) {
            snapshots.put(entry.getKey(), entry.getValue().snapshot());
        }
        return new ModernRendererStatus(initialized, detail, capabilities,
            coordinator == null ? null : coordinator.snapshot(),
            resources == null || threadGuard == null || !threadGuard.isRenderThread()
                ? null : resources.snapshot(), snapshots);
    }

    public RenderProfilerSnapshot profilerSnapshot() {
        return profiler == null ? null : profiler.snapshot();
    }

    public FrameStamp currentStamp() { return currentStamp; }
    public ParticleInstanceStream particles() { return particles; }
    public LwjglParticleRenderer particleRenderer() {
        return initialized && threadGuard != null && threadGuard.isRenderThread()
            ? particleRenderer : null;
    }
    public LwjglFbpPacketRenderer fbpPacketRenderer() {
        return initialized && threadGuard != null && threadGuard.isRenderThread()
            ? fbpPacketRenderer : null;
    }
    public TextureUploadStream textureUploads() { return textureUploads; }
    public SpriteVisibilityTracker spriteVisibility() { return spriteVisibility; }
    public LwjglAnimatedTextureUploadStream animatedTextures() {
        return initialized && threadGuard != null && threadGuard.isRenderThread()
            ? animatedTextures : null;
    }
    public HudVertexStream hud() { return hud; }
    public LwjglHudRenderer hudRenderer() {
        return initialized && threadGuard != null && threadGuard.isRenderThread()
            ? hudRenderer : null;
    }
    public FontLayoutCache fonts() { return fonts; }
    public CacheBudget cacheBudget() { return budget; }
    public CertifiedDrawSites certifiedDrawSites() { return certifiedDrawSites; }
    public ShaderCertificationRegistry shaders() { return shaders; }
    public ShaderCertificationPipeline shaderPipeline() { return shaderPipeline; }

    /**
     * Queues exact text already resolved by OptiFine.  This method performs no
     * GL call; compilation is drained at a later render-frame ownership
     * boundary under the captured generations.
     */
    public synchronized boolean queueOptifineShaderSources(
        String packId, String program, String permutation,
        long resourceGeneration, long shaderGeneration,
        String vertexPath, String vertexSource,
        String geometryPath, String geometrySource,
        String fragmentPath, String fragmentSource,
        String propertiesSource) {
        return queueOptifineShaderSources(null, packId, program, permutation,
            resourceGeneration, shaderGeneration, vertexPath, vertexSource,
            geometryPath, geometrySource, fragmentPath, fragmentSource,
            propertiesSource);
    }

    /** Production overload retaining a bounded identity reference to Program. */
    public synchronized boolean queueOptifineShaderSources(
        Object programIdentity, String packId, String program,
        String permutation, long resourceGeneration, long shaderGeneration,
        String vertexPath, String vertexSource,
        String geometryPath, String geometrySource,
        String fragmentPath, String fragmentSource,
        String propertiesSource) {
        if (shutdownRequested) return false;
        long currentResources = epochs.currentResourceGeneration();
        long currentShaders = epochs.currentShaderPackGeneration();
        purgeStaleShaderCandidates(currentResources, currentShaders);
        if (resourceGeneration != currentResources
            || shaderGeneration != currentShaders
            || pendingShaderCandidates.size() >= MAX_PENDING_SHADER_CANDIDATES) {
            return false;
        }
        long bytes;
        try {
            bytes = capturedShaderBytes(vertexSource, geometrySource,
                fragmentSource, propertiesSource);
        } catch (RuntimeException rejected) {
            FatalErrors.rethrowIfFatal(rejected);
            return false;
        }
        if (bytes > MAX_PENDING_SHADER_BYTES - pendingShaderCandidateBytes) {
            return false;
        }
        long heapBytes;
        try {
            heapBytes = capturedShaderHeapBytes(packId, program, permutation,
                vertexPath, vertexSource, geometryPath, geometrySource,
                fragmentPath, fragmentSource, propertiesSource);
        } catch (RuntimeException rejected) {
            FatalErrors.rethrowIfFatal(rejected);
            return false;
        }
        CacheBudget.Reservation reservation = budget.tryReserve(
            BudgetKind.HEAP, heapBytes);
        if (reservation == null) return false;
        CapturedShaderSources captured;
        try {
            captured = new CapturedShaderSources(programIdentity, packId, program,
                permutation, resourceGeneration, shaderGeneration, vertexPath,
                vertexSource, geometryPath, geometrySource, fragmentPath,
                fragmentSource, propertiesSource, bytes, reservation);
            pendingShaderCandidates.addLast(captured);
            reservation = null;
        } catch (Throwable publicationFailure) {
            Throwable failure = publicationFailure;
            if (reservation != null) try { reservation.close(); }
            catch (Throwable cleanupFailure) {
                failure = appendRuntimeFailure(failure, cleanupFailure);
            }
            FatalErrors.rethrowIfFatal(failure);
            return false;
        }
        pendingShaderCandidateBytes += bytes;
        return true;
    }

    /** Links and retains a current-generation candidate without activating it. */
    public boolean compileShaderPermutation(PreparedShaderPermutation prepared) {
        return compileShaderPermutation(prepared, 0);
    }

    /** Temporary compile/link gate must pass before the retained installation. */
    public boolean compileShaderPermutation(PreparedShaderPermutation prepared,
                                             int legacyProgramId) {
        if (shutdownRequested || !initialized || prepared == null || shaderPipeline == null
            || shaderCompiler == null || shaderInstaller == null || shaders == null
            || capabilities == null || legacyProgramId < 0
            || !capabilities.passed(ModernCapability.SHADER_PROGRAM)
            || !OptimizerRegistry.isOperational(
                OptimizationModule.OPTIFINE_SHADER_COMPILE)
            || threadGuard == null || !threadGuard.isRenderThread()
            || currentStamp != null) return false;
        if (prepared.getKey().getResourceGeneration()
                != epochs.currentResourceGeneration()
            || prepared.getKey().getShaderGeneration()
                != epochs.currentShaderPackGeneration()) return false;
        ShaderCompileInstallGate.Outcome outcome = ShaderCompileInstallGate.execute(
            prepared, shaderPipeline, shaderCompiler, shaderInstaller,
            legacyProgramId, epochs.currentResourceGeneration(),
            epochs.currentGlContextGeneration(),
            epochs.currentShaderPackGeneration());
        if (!outcome.isInstalled()) {
            if (!outcome.wasCompileAttempted() || outcome.isCompiled()) {
                shaders.recordCompile(prepared.getKey(), false,
                    outcome.getDetail());
            }
            if (outcome.isInfrastructureFailure()) {
                shaderDomainFailure(OptimizationModule.OPTIFINE_SHADER_COMPILE,
                    new IllegalStateException(outcome.getDetail()));
            } else {
                shaderDomainRejected(OptimizationModule.OPTIFINE_SHADER_COMPILE,
                    outcome.getDetail());
            }
            return false;
        }
        shaderDomainSuccess(OptimizationModule.OPTIFINE_SHADER_COMPILE);
        return true;
    }

    /** Unknown/uninstalled/uncertified ShaderPack programs are always OF/Legacy. */
    public RenderBackendId selectOptifineShaderBackend(
        ShaderPermutationKey permutation, boolean optifineRegionAvailable,
        boolean nativeVertexLayoutCompatible, boolean nativeBackendProfitable) {
        boolean regionAvailable = optifineRegionAvailable
            || isOptifineRegionBackendAvailable();
        if (insideLegacyIsland()) {
            return regionAvailable ? RenderBackendId.OF_COMPAT_REGION
                : RenderBackendId.LEGACY;
        }
        if (!shaderPackStateKnown || !initialized || shaderBackendSelector == null
            || shaderInstaller == null || threadGuard == null
            || !threadGuard.isRenderThread() || !shaderDomainsOperational()) {
            return regionAvailable ? RenderBackendId.OF_COMPAT_REGION
                : RenderBackendId.LEGACY;
        }
        boolean installed = permutation != null && shaderInstaller.isInstalled(
            permutation, epochs.currentResourceGeneration(),
            epochs.currentGlContextGeneration(),
            epochs.currentShaderPackGeneration());
        return shaderBackendSelector.select(shaderPackActive,
            regionAvailable, permutation, installed,
            nativeVertexLayoutCompatible, nativeBackendProfitable);
    }

    /**
     * True only after the exact OptiFine VboRegion emitter completed in the
     * current world/resource/context/shader generation.  ICE never toggles
     * OptiFine's Render Regions setting to manufacture this observation.
     */
    public boolean isOptifineRegionBackendAvailable() {
        return initialized && threadGuard != null && threadGuard.isRenderThread()
            && optifineRegionObservedGeneration == combinedGeneration()
            && OptimizerRegistry.isOperational(
                OptimizationModule.OPTIFINE_REGION_BACKEND);
    }

    /** Begins passive profiling of OptiFine's own already-batched draw. */
    public OptifineRegionDrawSample beginOptifineRegionDraw(
        Object layer, int indexPosition, int countPosition, int commandCapacity,
        int drawMode, int bufferId, int positionTop, int sizeUsed) {
        if (!initialized || currentStamp == null || profiler == null
            || insideLegacyIsland()
            || threadGuard == null || !threadGuard.isRenderThread()) return null;
        RenderPass pass = layer instanceof BlockRenderLayer
            ? terrainPass((BlockRenderLayer) layer) : RenderPass.MAIN_SOLID;
        boolean valid = indexPosition > 0 && indexPosition == countPosition
            && commandCapacity > 0 && indexPosition <= commandCapacity
            && drawMode > 0 && bufferId > 0 && positionTop >= 0
            && sizeUsed >= 0 && sizeUsed <= positionTop;
        CorrelatedRenderProfiler.CpuScope cpu = profiler.beginCpu(currentStamp,
            pass, RenderBackendId.OF_COMPAT_REGION, CpuWorkKind.SUBMISSION);
        CorrelatedRenderProfiler.GpuScope gpu = profiler.beginGpu(currentStamp,
            pass, RenderBackendId.OF_COMPAT_REGION);
        return new OptifineRegionDrawSample(pass, indexPosition,
            commandCapacity, valid, cpu, gpu);
    }

    /** Completes passive validation without replacing or replaying the OF draw. */
    public void endOptifineRegionDraw(OptifineRegionDrawSample sample,
                                      int indexPosition, int countPosition,
                                      int commandCapacity, Throwable error) {
        if (sample == null) return;
        Throwable completionError = error;
        try { if (sample.gpuScope != null) sample.gpuScope.close(); }
        catch (Throwable scopeFailure) {
            completionError = appendRuntimeFailure(completionError, scopeFailure);
        }
        try { sample.cpuScope.close(); }
        catch (Throwable scopeFailure) {
            completionError = appendRuntimeFailure(completionError, scopeFailure);
        }
        FatalErrors.rethrowIfFatal(completionError);
        error = completionError;
        AdaptiveBackendController backend = backends.get(
            OptimizationModule.OPTIFINE_REGION_BACKEND);
        if (error != null) {
            if (backend != null) backend.runtimeFailure(error);
            if (profiler != null && currentStamp != null) {
                profiler.addCounter(currentStamp, sample.pass,
                    RenderBackendId.OF_COMPAT_REGION,
                    RenderCounter.LEGACY_FALLBACK, 1L);
            }
            detail = "OptiFine VboRegion 原提交异常；ICE 观察器已独立回退："
                + error.getClass().getSimpleName();
            return;
        }
        boolean postValid = indexPosition == 0 && countPosition == 0
            && commandCapacity == sample.commandCapacity;
        boolean equivalent = sample.preDrawValid && postValid;
        if (backend != null && backend.lifecycleState()
            == BackendLifecycleState.OUTPUT_VALIDATE) {
            recordValidationOnce(OptimizationModule.OPTIFINE_REGION_BACKEND,
                equivalent, equivalent ? null
                    : "OptiFine VboRegion queue/reset invariant mismatch");
        }
        if (!equivalent) {
            if (backend != null && backend.lifecycleState()
                != BackendLifecycleState.QUARANTINED) {
                backend.correctnessFailure(
                    "OptiFine VboRegion queue/reset invariant mismatch");
            }
            return;
        }
        optifineRegionObservedGeneration = combinedGeneration();
        if (profiler != null && currentStamp != null) {
            profiler.addCounter(currentStamp, sample.pass,
                RenderBackendId.OF_COMPAT_REGION, RenderCounter.MULTI_DRAW, 1L);
            profiler.addCounter(currentStamp, sample.pass,
                RenderBackendId.OF_COMPAT_REGION, RenderCounter.DRAW,
                sample.commandCount);
        }
        // VboRegion is OptiFine's compatibility fallback, not an ICE candidate
        // that may be artificially slowed for ABBA.  Once structural/output
        // validation completes, stop its candidate state machine explicitly;
        // the current-generation observation above remains the availability
        // authority for backend selection.
        if (backend != null && backend.lifecycleState()
            == BackendLifecycleState.PAIRED_MEASURE) {
            backend.fallback("OptiFine-owned compatibility backend observed; "
                + "no ICE replacement selected", false);
        }
    }

    public int certifiedNativeShaderProgram(ShaderPermutationKey permutation) {
        if (!initialized || permutation == null || shaderInstaller == null
            || shaders == null || threadGuard == null
            || !threadGuard.isRenderThread() || !shaderDomainsOperational()) return 0;
        return shaderInstaller.certifiedProgram(permutation, shaders,
            epochs.currentResourceGeneration(), epochs.currentGlContextGeneration(),
            epochs.currentShaderPackGeneration());
    }
    public OptifineProgramState currentOptifineProgramState() {
        return optifineProgramState;
    }
    public ConservativeOcclusionHistory hzbHistory() { return hzbHistory; }

    public long currentFrameId() { return epochs.currentFrameId(); }
    public long resourceGeneration() { return epochs.currentResourceGeneration(); }
    public long atlasGeneration() { return epochs.currentAtlasGeneration(); }
    public long glContextGeneration() { return epochs.currentGlContextGeneration(); }
    public long shaderPackGeneration() { return epochs.currentShaderPackGeneration(); }

    /** Publishes the current TextureMap GL name without querying the driver. */
    public void observeAnimatedAtlas(int textureId) {
        if (!initialized || textureId <= 0 || threadGuard == null
            || !threadGuard.isRenderThread()) return;
        animatedAtlasTextureId = textureId;
    }

    /** Non-blocking state-mirror check used immediately before a sampled draw. */
    public boolean isAnimatedAtlasBound() {
        return initialized && animatedAtlasTextureId > 0 && threadGuard != null
            && threadGuard.isRenderThread()
            && EarlyGlStateTracker.boundTextureForUnit(0)
                == animatedAtlasTextureId;
    }

    public BackendLifecycleState backendLifecycleState(OptimizationModule module) {
        AdaptiveBackendController backend = backends.get(module);
        return backend == null ? BackendLifecycleState.LEGACY
            : backend.lifecycleState();
    }

    private RenderBackendId observedBackend(OptimizationModule module) {
        AdaptiveBackendController backend = backends.get(module);
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
        AdaptiveBackendController backend = backends.get(module);
        if (backend == null || sampledState == null || arm == null) return;
        BackendLifecycleState current = backend.lifecycleState();
        try {
            if (sampledState == BackendLifecycleState.PAIRED_MEASURE
                && current == BackendLifecycleState.PAIRED_MEASURE) {
                backend.recordMeasurement(scene, arm, nanos, stable);
            } else if (sampledState == BackendLifecycleState.REGRESSION_MONITOR
                && current == BackendLifecycleState.REGRESSION_MONITOR
                && arm == MeasurementArm.MODERN) {
                backend.recordRegressionSample(scene, nanos, stable);
            }
        } catch (Throwable error) {
            backend.runtimeFailure(error);
        }
    }

    public void textureBackendFailure(Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        AdaptiveBackendController backend = backends.get(
            OptimizationModule.MODERN_TEXTURE_STREAM);
        if (backend != null) backend.runtimeFailure(error);
        detail = "动画纹理 PBO 异常；纹理子后端已回退："
            + (error == null ? "unknown" : error.getClass().getSimpleName());
    }

    public void textureVisibilityFailure(Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        Throwable failure = error == null
            ? new IllegalStateException("animated texture visibility failure") : error;
        AdaptiveBackendController backend = backends.get(
            OptimizationModule.MODERN_TEXTURE_VISIBILITY);
        if (backend != null) backend.runtimeFailure(failure);
        if (spriteVisibility != null) {
            spriteVisibility.invalidate(epochs.currentResourceGeneration(),
                epochs.currentAtlasGeneration());
        }
        detail = "动画纹理可见性异常；完整原上传路径已恢复："
            + failure.getClass().getSimpleName();
    }

    public void recordTextureVisibilityDeferred(long bytes) {
        pendingVisibilityDeferred.incrementAndGet();
        if (bytes > 0L) pendingVisibilityDeferredBytes.addAndGet(bytes);
    }

    public void recordTextureVisibilityUnknown() {
        pendingVisibilityUnknownFrames.incrementAndGet();
    }

    public long consumeTextureVisibilityCatchUpNanos() {
        return pendingVisibilityCatchUpNanos.getAndSet(0L);
    }

    private boolean catchUpAnimatedTexture(
        SpriteVisibilityTracker.DeferredUpload upload) {
        if (upload == null || !initialized || threadGuard == null
            || !threadGuard.isRenderThread()
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
            TextureUtil.uploadTextureMipmap(upload.copyPixels(),
                upload.getWidth(), upload.getHeight(), upload.getOriginX(),
                upload.getOriginY(), upload.isBlur(), upload.isClamp());
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
        AdaptiveBackendController backend = backends.get(
            OptimizationModule.MODERN_TEXTURE_PERSISTENT_RING);
        if (backend != null) backend.runtimeFailure(error);
        detail = "Persistent 纹理 ring 异常；普通 PBO 路径保持可用："
            + (error == null ? "unknown" : error.getClass().getSimpleName());
    }

    public void terrainPersistentFailure(Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        AdaptiveBackendController backend = backends.get(
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
        AdaptiveBackendController backend = backends.get(
            OptimizationModule.MODERN_PARTICLE_BACKEND);
        if (backend != null) backend.runtimeFailure(error);
        detail = "粒子动态流异常；粒子子后端已回退："
            + (error == null ? "unknown" : error.getClass().getSimpleName()
                + (error.getMessage() == null || error.getMessage().isEmpty()
                    ? "" : ": " + error.getMessage()));
    }

    public void fbpPacketFailure(Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        AdaptiveBackendController backend = backends.get(
            OptimizationModule.FBP_PARTICLE_ADAPTER);
        if (backend != null) backend.runtimeFailure(error);
        detail = "FBP 原始粒子 Packet 异常；标准粒子实例流保持可用："
            + (error == null ? "unknown" : error.getClass().getSimpleName());
    }

    public void recordFbpPacket(int vertices, boolean modern) {
        if (!initialized || currentStamp == null || profiler == null
            || vertices <= 0) return;
        RenderBackendId backend = modern ? RenderBackendId.ICE_NATIVE
            : RenderBackendId.LEGACY;
        profiler.addCounter(currentStamp, RenderPass.PARTICLES, backend,
            modern ? RenderCounter.DRAW : RenderCounter.LEGACY_FALLBACK, 1L);
        profiler.addCounter(currentStamp, RenderPass.PARTICLES, backend,
            RenderCounter.VERTEX, vertices);
        if (modern) profiler.addCounter(currentStamp, RenderPass.PARTICLES,
            backend, RenderCounter.MESH_UPLOAD_BYTES,
            (long) vertices * LwjglFbpPacketRenderer.exactStrideBytes());
    }

    public void hudBackendFailure(Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        AdaptiveBackendController backend = backends.get(
            OptimizationModule.MODERN_HUD_STREAM);
        if (backend != null) backend.runtimeFailure(error);
        detail = "HUD/字体动态流异常；HUD 子后端已回退："
            + (error == null ? "unknown" : error.getClass().getSimpleName());
    }

    public void shaderBridgeFailure(Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        AdaptiveBackendController backend = backends.get(
            OptimizationModule.OPTIFINE_SHADER_BRIDGE);
        if (backend != null) backend.runtimeFailure(error);
        detail = "OptiFine Shader 生命周期桥异常；未认证程序保持 OF/Legacy："
            + (error == null ? "unknown" : error.getClass().getSimpleName());
    }

    public void beforeOptifineProgramSwitch() {
        beforeOptifineProgramSwitch(null, true);
    }

    public void beforeOptifineProgramSwitch(Object requestedProgram,
                                            boolean logicalProgramChanged) {
        if (!initialized || threadGuard == null || !threadGuard.isRenderThread()) return;
        boolean intervalOpen = activeShaderSample != null
            || activeNativeShaderBinding != null;
        if (logicalProgramChanged || intervalOpen) {
            flushBatches();
            if (currentStamp != null) coordinator.observableBarrier(
                "OptiFine useProgram");
        }
        finishActiveShaderInterval(true, true);
        if (logicalProgramChanged || intervalOpen) {
            EarlyGlStateTracker.invalidate();
            EarlyMatrixStateTracker.invalidate();
        }
    }

    public void observeOptifineProgram(OptifineProgramState state) {
        observeOptifineProgram(null, state);
    }

    public void observeOptifineProgram(Object programIdentity,
                                       OptifineProgramState state) {
        if (!initialized || state == null || threadGuard == null
            || !threadGuard.isRenderThread()) return;
        optifineProgramState = state;
        optifineProgramIdentity = programIdentity;
        EarlyGlStateTracker.useProgram(state.getProgramId());
        boolean synchronizedProgram = shaderActivation != null
            && shaderActivation.isProgramSynchronized(programIdentity,
                state.getProgramId());
        reconcileObservedShaderProgram(state.getProgramId(),
            synchronizedProgram);
        if (currentStamp != null && profiler != null) {
            profiler.addCounter(currentStamp, RenderPass.FINAL,
                RenderBackendId.OF_COMPAT_REGION, RenderCounter.PROGRAM_SWITCH, 1L);
        }
        if (shutdownRequested) return;
        if (insideLegacyIsland()) return;
        if (programIdentity == null || !shaderPackStateKnown || !shaderPackActive) {
            return;
        }
        if (shaderBindingsPoisoned) return;
        ShaderProgramBinding binding = shaderBindings.get(programIdentity);
        if (!bindingCurrent(binding, programIdentity, state.getProgramId())) return;
        queueShaderValidation(binding, state);
        activateCertifiedShaderForInterval(binding, state);
    }

    /** Package-visible CPU gate for the outcome-uncertain restoration test. */
    boolean reconcileObservedShaderProgram(int observedProgram,
                                           boolean synchronizedProgram) {
        ShaderProgramBinding uncertain = activeNativeShaderBinding;
        if (uncertain == null || !synchronizedProgram
            || observedProgram < 0
            || observedProgram == uncertain.candidateProgram) return false;
        activeNativeShaderBinding = null;
        detail = "OptiFine 原 useProgram 已确认恢复；candidate 所有权已对账";
        return true;
    }

    public void abortOptifineProgramSwitch() {
        finishActiveShaderInterval(false, true);
        EarlyGlStateTracker.invalidate();
        EarlyMatrixStateTracker.invalidate();
        optifineProgramState = null;
        optifineProgramIdentity = null;
    }

    /** Flushes the bounded HUD stream and records the exact fallback reason. */
    public LwjglHudRenderer.FlushResult flushHudStream() {
        if (!initialized || hudRenderer == null || threadGuard == null
            || !threadGuard.isRenderThread()) return LwjglHudRenderer.FlushResult.EMPTY;
        int commands = hudRenderer.getCommandCount();
        int vertices = hudRenderer.getVertexCount();
        LwjglHudRenderer.FlushResult result = hudRenderer.flush(
            EarlyGlStateTracker.snapshot());
        if (currentStamp != null && profiler != null && commands > 0) {
            if (result == LwjglHudRenderer.FlushResult.MODERN
                || result == LwjglHudRenderer.FlushResult.FAILED_AFTER_DRAW) {
                profiler.addCounter(currentStamp, RenderPass.HUD_GUI,
                    RenderBackendId.ICE_NATIVE, RenderCounter.MULTI_DRAW, 1L);
                profiler.addCounter(currentStamp, RenderPass.HUD_GUI,
                    RenderBackendId.ICE_NATIVE, RenderCounter.VERTEX, vertices);
            }
            if (result == LwjglHudRenderer.FlushResult.LEGACY_BUSY
                || result == LwjglHudRenderer.FlushResult.LEGACY_STATE
                || result == LwjglHudRenderer.FlushResult.FAILED_BEFORE_DRAW) {
                profiler.addCounter(currentStamp, RenderPass.HUD_GUI,
                    RenderBackendId.LEGACY, RenderCounter.LEGACY_FALLBACK,
                    commands);
            }
            if (result == LwjglHudRenderer.FlushResult.LEGACY_BUSY) {
                profiler.addCounter(currentStamp, RenderPass.HUD_GUI,
                    RenderBackendId.ICE_NATIVE, RenderCounter.FENCE_BUSY, 1L);
            }
        }
        if (result == LwjglHudRenderer.FlushResult.FAILED_BEFORE_DRAW
            || result == LwjglHudRenderer.FlushResult.FAILED_AFTER_DRAW) {
            Throwable error = hudRenderer.getLastError();
            hudBackendFailure(error == null
                ? new IllegalStateException("HUD submission failed") : error);
        }
        return result;
    }

    public void recordHudFontCache(int hits, int misses) {
        if (currentStamp == null || profiler == null) return;
        if (hits > 0) {
            profiler.addCounter(currentStamp, RenderPass.HUD_GUI,
                RenderBackendId.ICE_NATIVE, RenderCounter.CACHE_HIT, hits);
        }
        if (misses > 0) {
            profiler.addCounter(currentStamp, RenderPass.HUD_GUI,
                RenderBackendId.ICE_NATIVE, RenderCounter.CACHE_MISS, misses);
        }
    }

    /**
     * Rebuilds the software mirror after an observing Forge HUD event.
     * This is never used per draw and is requested only by the measured modern
     * HUD arm, so its synchronization cost participates in the ABBA decision.
     */
    public boolean resynchronizeHudState() {
        if (!initialized || threadGuard == null || !threadGuard.isRenderThread()) {
            return false;
        }
        EarlyGlStateTracker.beginProbe();
        try {
            int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            GlStateQueryWorkspace workspace = glStateQueryWorkspace;
            if (workspace == null || workspace.isClosed()) {
                throw new IllegalStateException(
                    "HUD state query workspace unavailable");
            }
            FloatBuffer depthRange = workspace.depthRange();
            GL11.glGetFloat(GL11.GL_DEPTH_RANGE, depthRange);
            int pixelPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            int arrayBuffer = GL11.glGetInteger(
                org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER_BINDING);
            int activeTextureEnum = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            int clientActiveTextureEnum = GL11.glGetInteger(
                GL13.GL_CLIENT_ACTIVE_TEXTURE);
            int activeTexture = activeTextureEnum - GL13.GL_TEXTURE0;
            int clientActiveTexture = clientActiveTextureEnum - GL13.GL_TEXTURE0;
            if (activeTexture < 0 || activeTexture >= 32
                || clientActiveTexture < 0 || clientActiveTexture >= 32) {
                throw new IllegalStateException("invalid HUD texture unit");
            }
            int texture0 = 0;
            int texture1 = 0;
            boolean texture0Enabled = false;
            boolean texture1Enabled = false;
            Throwable textureQueryFailure = null;
            try {
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                texture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                texture0Enabled = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                texture1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                texture1Enabled = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
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
            boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
            int blendSourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
            int blendDestinationRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            int blendSourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
            int blendDestinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
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
            IntBuffer viewport = workspace.viewport();
            GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);

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
            EarlyGlStateTracker.seedHudState(texture0Enabled, texture1Enabled,
                viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
            if (currentStamp != null && profiler != null) {
                profiler.addCounter(currentStamp, RenderPass.HUD_GUI,
                    RenderBackendId.ICE_NATIVE, RenderCounter.STATE_RESYNC, 1L);
            }
            EarlyGlStateTracker.Snapshot tracked = EarlyGlStateTracker.snapshot();
            return tracked != null && tracked.hasHudState();
        } catch (Throwable error) {
            EarlyGlStateTracker.invalidate();
            hudBackendFailure(error);
            return false;
        }
    }

    public void recordParticleInstances(int modernInstances, int legacyFallbacks) {
        if (!initialized || currentStamp == null || profiler == null) return;
        if (modernInstances > 0) {
            profiler.addCounter(currentStamp, RenderPass.PARTICLES,
                RenderBackendId.ICE_NATIVE, RenderCounter.INSTANCE,
                modernInstances);
            profiler.addCounter(currentStamp, RenderPass.PARTICLES,
                RenderBackendId.ICE_NATIVE, RenderCounter.VERTEX,
                (long) modernInstances * 4L);
        }
        if (legacyFallbacks > 0) {
            profiler.addCounter(currentStamp, RenderPass.PARTICLES,
                RenderBackendId.LEGACY, RenderCounter.LEGACY_FALLBACK,
                legacyFallbacks);
        }
    }

    /** Receives an exact ModelRenderer payload only on the captured GL thread. */
    public boolean acceptModelMesh(ModelMeshPayload payload) {
        if (!initialized || currentStamp == null || modelMeshes == null
            || payload == null || !isModelMeshBackendOperational()
            || insideLegacyIsland()
            || threadGuard == null || !threadGuard.isRenderThread()) {
            return false;
        }
        if (payload.getResourceGeneration() != currentStamp.getResourceGeneration()
            || payload.getContextGeneration() != currentStamp.getGlContextGeneration()) {
            return false;
        }
        try {
            return modelMeshes.accept(payload, currentStamp);
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

    /** True only after startup has made a final decision to reject both users. */
    public boolean shouldDropDeferredModelMeshes() {
        return initialized && !isModelMeshBackendOperational();
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
        return initialized && currentStamp != null && threadGuard != null
            && threadGuard.isRenderThread() ? currentStamp.getFrameId() : -1L;
    }

    public void invalidateModelMesh(int displayList) {
        if (!initialized || modelMeshes == null || displayList <= 0
            || threadGuard == null || !threadGuard.isRenderThread()) return;
        try { modelMeshes.invalidate(displayList); }
        catch (Throwable error) { modelBackendFailure(error); }
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
        if (!initialized || currentStamp == null || modelMeshes == null
            || displayList <= 0) {
            incrementModelDrawReason(module, ModelDrawReason.RUNTIME_NOT_READY);
            return false;
        }
        if (insideLegacyIsland()) {
            incrementModelDrawReason(module, ModelDrawReason.LEGACY_ISLAND);
            return false;
        }
        if (!shaderPackStateKnown) {
            incrementModelDrawReason(module,
                ModelDrawReason.SHADER_STATE_UNKNOWN);
            return false;
        }
        if (shaderPackActive) {
            incrementModelDrawReason(module, ModelDrawReason.SHADER_PACK_ACTIVE);
            return false;
        }
        AdaptiveBackendController backend = backends.get(module);
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
        LwjglModelMeshCache.Entry entry = modelMeshes.find(displayList, currentStamp);
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
                equivalent = entry.isValidated() || modelMeshes.validate(entry);
            } catch (Throwable error) {
                modelBackendFailure(error);
                return false;
            }
            if (equivalent) genericModelVboValidated = true;
            recordValidationOnce(module, equivalent,
                equivalent ? null : "ModelRenderer VBO byte/output certification failed");
            incrementModelDrawReason(module, equivalent
                ? ModelDrawReason.OUTPUT_VALIDATION_PASSED
                : ModelDrawReason.OUTPUT_VALIDATION_FAILED);
            return false;
        }
        if (!entry.isValidated() && genericModelVboValidated) {
            modelMeshes.certifyCapturedEntry(entry);
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
        if (!EarlyGlStateTracker.hasModelDrawState()) {
            incrementModelDrawReason(module, ModelDrawReason.DRAW_STATE_UNKNOWN);
            return false;
        }
        if (!EarlyMatrixStateTracker.isKnown()) {
            incrementModelDrawReason(module, ModelDrawReason.MATRIX_STATE_UNKNOWN);
            return false;
        }
        try {
            boolean handled = modelMeshes.drawCurrent(entry, currentStamp);
            if (!handled) {
                incrementModelDrawReason(module, ModelDrawReason.DRAW_DECLINED);
                return false;
            }
            RenderLibRenderBridge.markModernDraw();
            incrementModelDrawReason(module, ModelDrawReason.MODERN_DRAW);
            profiler.addCounter(currentStamp, pass, RenderBackendId.ICE_NATIVE,
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
        boolean matrixAuthenticated = EarlyMatrixStateTracker.isKnown();
        if (glAuthenticated && matrixAuthenticated) return true;
        long glInvalidation = EarlyGlStateTracker.invalidations();
        long matrixInvalidation = EarlyMatrixStateTracker.invalidations();
        long frame = currentStamp == null ? -1L : currentStamp.getFrameId();
        if (failedModelReauthenticationFrame == frame
            && failedModelGlInvalidation == glInvalidation
            && failedModelMatrixInvalidation == matrixInvalidation) {
            modelStateReauthenticationSuppressions = saturatedIncrement(
                modelStateReauthenticationSuppressions);
            return false;
        }
        modelStateReauthenticationAttempts = saturatedIncrement(
            modelStateReauthenticationAttempts);
        String stage = "workspace";
        try {
            GlStateQueryWorkspace workspace = glStateQueryWorkspace;
            if (workspace == null || workspace.isClosed()) {
                throw new IllegalStateException(
                    "model state query workspace unavailable");
            }
            if (!glAuthenticated) {
                stage = "gl-state";
                reauthenticateModelGlState(workspace);
                glAuthenticated = true;
            }
            if (!matrixAuthenticated) {
                stage = "matrix-state";
                reauthenticateModelMatrixState(workspace);
                matrixAuthenticated = true;
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
            if (!matrixAuthenticated) EarlyMatrixStateTracker.invalidate();
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
        return EarlyGlStateTracker.hasModelDrawState()
            && EarlyMatrixStateTracker.isKnown();
    }

    private void reauthenticateModelMatrixState(
        GlStateQueryWorkspace workspace) {
        int matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        FloatBuffer modelView = workspace.modelView();
        FloatBuffer projection = workspace.projection();
        FloatBuffer textureMatrix = workspace.textureMatrix();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelView);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection);
        GL11.glGetFloat(GL11.GL_TEXTURE_MATRIX, textureMatrix);
        EarlyMatrixStateTracker.seed(matrixMode, modelView, projection,
            textureMatrix);
        if (!EarlyMatrixStateTracker.isKnown()) {
            throw new IllegalStateException(
                "model matrix state publication incomplete");
        }
    }

    /** Gate shared by reviewed non-ModelRenderer mesh emitters. */
    public boolean canEmitExternalModelMesh() {
        if (!initialized || currentStamp == null || !genericModelVboValidated
            || insideLegacyIsland()
            || !shaderPackStateKnown || shaderPackActive
            || !capabilities.passed(ModernCapability.MODEL_MESH_VBO)) return false;
        OptimizationModule module = RenderLibRenderBridge.currentModule();
        RenderPass pass = RenderLibRenderBridge.currentPass();
        if ((module != OptimizationModule.MODERN_ENTITY_BACKEND
            && module != OptimizationModule.MODERN_TESR_BACKEND) || pass == null
            || !RenderLibRenderBridge.candidateAllowed()) return false;
        AdaptiveBackendController backend = backends.get(module);
        if (backend == null) return false;
        BackendLifecycleState state = backend.lifecycleState();
        if (state != BackendLifecycleState.PAIRED_MEASURE
            && state != BackendLifecycleState.MODERN
            && state != BackendLifecycleState.REGRESSION_MONITOR) return false;
        EarlyGlStateTracker.Snapshot gl = EarlyGlStateTracker.snapshot();
        return gl != null && gl.hasArrayBufferBinding() && gl.hasDrawState()
            && gl.getProgram() == 0 && EarlyMatrixStateTracker.isKnown();
    }

    public void recordExternalModelDraw() {
        if (!initialized || currentStamp == null) return;
        RenderPass pass = RenderLibRenderBridge.currentPass();
        if (pass == null) return;
        RenderLibRenderBridge.markModernDraw();
        profiler.addCounter(currentStamp, pass, RenderBackendId.ICE_NATIVE,
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
        if (!initialized || currentStamp == null || resources == null
            || threadGuard == null || !threadGuard.isRenderThread()
            || resourceGeneration != currentStamp.getResourceGeneration()
            || contextGeneration != currentStamp.getGlContextGeneration()) return null;
        return resources.registerReserved(RenderResourceKind.BUFFER, nativeId,
            chargedBytes, resourceGeneration, contextGeneration, reservation);
    }

    /** Retires one detached external-model VBO after all earlier draws. */
    public boolean retireExternalModelBuffer(RenderHandle handle) {
        if (!initialized || resources == null || threadGuard == null
            || !threadGuard.isRenderThread() || handle == null) return false;
        return resources.retire(handle,
            LwjglRetirementFence.afterCurrentCommands(resources));
    }

    public void flushModelPackets(OptimizationModule module) {
        // Immediate model VBO draws are never deferred or reordered.  Keep
        // the traversal barrier API so a future real batcher has an explicit
        // integration point, but do no queue work in the current backend.
    }

    public RenderBackendSample beginRenderBackendSample(OptimizationModule module,
                                                        RenderPass pass) {
        return beginRenderBackendSample(module, pass, 0);
    }

    private RenderBackendSample beginRenderBackendSample(
        OptimizationModule module, RenderPass pass, int workloadDiscriminator) {
        if (!initialized || currentStamp == null || module == null || pass == null
            || insideLegacyIsland()
            || threadGuard == null || !threadGuard.isRenderThread()) return null;
        if ((module == OptimizationModule.MODERN_PARTICLE_BACKEND
            || module == OptimizationModule.FBP_PARTICLE_ADAPTER)
            && !isShaderPackSafeForNativeVertexFormats()) return null;
        AdaptiveBackendController backend = backends.get(module);
        if (backend == null) return null;
        boolean profiledModelWorkload =
            module == OptimizationModule.MODERN_ENTITY_BACKEND
                || module == OptimizationModule.MODERN_TESR_BACKEND;
        if (!profiledModelWorkload && !backend.shouldInspectCandidate()) {
            return null;
        }
        SceneFingerprint scene = sceneFingerprint(workloadDiscriminator);
        if (scene != null && profiledModelWorkload) {
            int bucket = module == OptimizationModule.MODERN_ENTITY_BACKEND
                ? scene.entityLoadBucket() : scene.tileEntityLoadBucket();
            if (!backend.prepareProfiledWorkload(bucket,
                currentStamp.getFrameId())) return null;
        }
        if (!backend.shouldInspectCandidate()) return null;
        BackendLifecycleState state = backend.lifecycleState();
        MeasurementArm arm = state == BackendLifecycleState.PAIRED_MEASURE
            ? backend.expectedMeasurementArm()
            : (state == BackendLifecycleState.MODERN
                || state == BackendLifecycleState.REGRESSION_MONITOR
                    ? MeasurementArm.MODERN : MeasurementArm.LEGACY);
        RenderProfileKey key = null;
        CorrelatedRenderProfiler.GpuScope gpu = null;
        if ((state == BackendLifecycleState.PAIRED_MEASURE
            || state == BackendLifecycleState.REGRESSION_MONITOR) && scene != null) {
            RenderBackendId id = arm == MeasurementArm.MODERN
                ? RenderBackendId.ICE_NATIVE : RenderBackendId.LEGACY;
            key = new RenderProfileKey(currentStamp, pass, id);
            if (!backendGpuSamples.containsKey(key)) {
                backendGpuSamples.put(key, new BackendGpuSample(module, scene, arm));
                while (backendGpuSamples.size() > 64) {
                    Iterator<RenderProfileKey> iterator = backendGpuSamples.keySet().iterator();
                    iterator.next();
                    iterator.remove();
                }
                gpu = profiler.beginGpu(currentStamp, pass, id, backendGpuCompletion);
                if (gpu == null) backendGpuSamples.remove(key);
            } else {
                key = null;
            }
        }
        return new RenderBackendSample(module, state, arm, scene, key, gpu,
            System.nanoTime());
    }

    public void endRenderBackendSample(RenderBackendSample sample,
                                       boolean stable, boolean modernWork) {
        if (sample == null) return;
        if (sample.gpuScope != null) sample.gpuScope.close();
        if (sample.key == null) return;
        BackendGpuSample pending = backendGpuSamples.get(sample.key);
        if (pending == null) return;
        pending.cpuNanos = Math.max(1L, System.nanoTime() - sample.startedNanos);
        pending.cpuReady = true;
        pending.stable = stable && (sample.arm == MeasurementArm.LEGACY || modernWork);
        finishBackendGpuSample(sample.key, pending);
    }

    private void recordValidationOnce(OptimizationModule module, boolean equivalent,
                                      String failure) {
        if (!equivalent) {
            recordValidation(module, false, failure);
            return;
        }
        long frame = currentStamp == null ? -1L : currentStamp.getFrameId();
        Long previous = lastValidationFrames.get(module);
        if (previous != null && previous.longValue() == frame) return;
        lastValidationFrames.put(module, Long.valueOf(frame));
        recordValidation(module, true, null);
    }

    private void modelBackendFailure(Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        AdaptiveBackendController entity = backends.get(OptimizationModule.MODERN_ENTITY_BACKEND);
        AdaptiveBackendController tesr = backends.get(OptimizationModule.MODERN_TESR_BACKEND);
        if (entity != null) entity.runtimeFailure(error);
        if (tesr != null) tesr.runtimeFailure(error);
    }

    /** Records a traversal-scope failure against only its owning backend. */
    public void modelTraversalFailure(OptimizationModule module, Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        if (module != OptimizationModule.MODERN_ENTITY_BACKEND
            && module != OptimizationModule.MODERN_TESR_BACKEND) return;
        AdaptiveBackendController backend = backends.get(module);
        if (backend != null) backend.runtimeFailure(error);
    }

    public boolean tryUploadTerrain(TerrainUploadContext.Value context,
                                    BufferBuilder builder, VertexBuffer vertexBuffer) {
        boolean shaderTerrainSafe = isShaderPackSafeForTerrainArena();
        if (!initialized || currentStamp == null || terrainArena == null
            || !shaderPackStateKnown || shaderPackActive && !shaderTerrainSafe) {
            return false;
        }
        AdaptiveBackendController backend = backends.get(
            OptimizationModule.MODERN_TERRAIN_BACKEND);
        BackendLifecycleState terrainState = backend == null
            ? BackendLifecycleState.LEGACY : backend.lifecycleState();
        if (backend == null || !acceptsCandidateUploads(terrainState)) {
            return false;
        }
        boolean retainLegacyCopy = shaderPackActive
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
                shaderPackActive)) {
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
        AdaptiveBackendController mapping = backends.get(
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
        CorrelatedRenderProfiler.CpuScope scope = profiler.beginCpu(currentStamp,
            terrainPass(context.getLayer()), RenderBackendId.ICE_NATIVE, CpuWorkKind.UPLOAD);
        SceneFingerprint mappingScene = sceneFingerprint(
            Math.max(0, uploadBytes / 4096));
        long mappingStarted = System.nanoTime();
        boolean mappingStable = false;
        boolean mappedWork = false;
        boolean uploaded = false;
        Throwable operationFailure = null;
        try {
            uploaded = terrainArena.upload(context, builder, vertexBuffer,
                currentStamp, allowPersistentStorage, preferPersistentWrite,
                retainLegacyCopy);
            Throwable publicationError = terrainArena.consumePublicationFailure();
            if (publicationError != null) backend.runtimeFailure(publicationError);
            Throwable mappingError = terrainArena.consumeMappingFailure();
            if (mappingError != null) {
                terrainPersistentFailure(mappingError);
            }
            mappedWork = terrainArena.wasLastUploadPersistent();
            mappingStable = uploaded && mappingError == null
                && terrainArena.isPersistent()
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
                if (depthHistory != null) {
                    depthHistory.geometryChanged(currentStamp.getFrameId());
                }
                profiler.addCounter(currentStamp, terrainPass(context.getLayer()),
                    RenderBackendId.ICE_NATIVE, RenderCounter.MESH_UPLOAD_BYTES,
                    Math.max(0, uploadBytes));
            }
            return uploaded && !retainLegacyCopy;
        } catch (Throwable error) {
            Throwable publicationError = terrainArena.consumePublicationFailure();
            if (publicationError != null && publicationError != error) {
                error = appendRuntimeFailure(error, publicationError);
            }
            operationFailure = error;
            Throwable mappingError = terrainArena.consumeMappingFailure();
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
                recordAuxiliaryBackendSample(
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
        if (!initialized || terrainArena == null || threadGuard == null
            || !threadGuard.isRenderThread()) return;
        terrainArena.release(vertexBuffer);
        Throwable publicationError = terrainArena.consumePublicationFailure();
        if (publicationError != null) {
            FatalErrors.rethrowIfFatal(publicationError);
            AdaptiveBackendController backend = backends.get(
                OptimizationModule.MODERN_TERRAIN_BACKEND);
            if (backend != null) backend.runtimeFailure(publicationError);
        }
    }

    public void terrainGeometryChanged() {
        if (initialized && depthHistory != null && threadGuard != null
            && threadGuard.isRenderThread()) {
            depthHistory.geometryChanged(currentStamp == null ? -1L
                : currentStamp.getFrameId());
        }
    }

    public void terrainLegacyUpload() {
        saturatedIncrement(terrainLegacyUploads);
        terrainGeometryChanged();
    }

    public void beforeTerrainLegacyUpload(VertexBuffer vertexBuffer) {
        terrainLegacyUpload();
        if (!initialized || terrainArena == null || threadGuard == null
            || !threadGuard.isRenderThread()) return;
        terrainArena.beforeLegacyUpload(vertexBuffer);
        Throwable publicationError = terrainArena.consumePublicationFailure();
        if (publicationError != null) {
            FatalErrors.rethrowIfFatal(publicationError);
            AdaptiveBackendController backend = backends.get(
                OptimizationModule.MODERN_TERRAIN_BACKEND);
            if (backend != null) backend.runtimeFailure(publicationError);
        }
    }

    public boolean tryRenderTerrain(Object container, BlockRenderLayer layer) {
        beginTerrainDecision(container, layer);
        if (!initialized) return declineTerrain(TerrainFallbackReason.RUNTIME_NOT_READY);
        if (currentStamp == null) return declineTerrain(TerrainFallbackReason.NO_ACTIVE_FRAME);
        if (terrainArena == null) return declineTerrain(TerrainFallbackReason.ARENA_UNAVAILABLE);
        if (threadGuard == null || !threadGuard.isRenderThread()) {
            return declineTerrain(TerrainFallbackReason.RENDER_THREAD_UNAVAILABLE);
        }
        if (!(container instanceof TerrainRenderListAccessor)) {
            return declineTerrain(TerrainFallbackReason.CONTAINER_ABI_MISSING);
        }
        if (!shaderPackStateKnown) {
            return declineTerrain(TerrainFallbackReason.SHADER_STATE_UNKNOWN);
        }
        if (shaderPackActive && !isShaderPackSafeForTerrainArena()) {
            ShaderProgramBinding binding = activeNativeShaderBinding;
            return declineTerrain(binding != null && !binding.terrainLayoutCertified
                ? TerrainFallbackReason.SHADER_LAYOUT_UNCERTIFIED
                : TerrainFallbackReason.SHADER_PACK_ACTIVE);
        }
        TerrainRenderListAccessor accessor = (TerrainRenderListAccessor) container;
        AdaptiveBackendController visibility = backends.get(
            OptimizationModule.MODERN_VISIBILITY_HZB);
        BackendLifecycleState visibilityState = visibility == null
            ? BackendLifecycleState.LEGACY : visibility.lifecycleState();
        boolean shaderSafe = shaderPackStateKnown && !shaderPackActive
            && !insideLegacyIsland();
        // HZB owns depth-history validation, not terrain-arena ownership.  In
        // particular, a fully legacy terrain list must still be able to move
        // OUTPUT_VALIDATE forward after its CUTOUT pass captured valid depth.
        if (shaderSafe && visibilityState == BackendLifecycleState.OUTPUT_VALIDATE
            && layer == BlockRenderLayer.SOLID && depthHistory != null
            && depthHistory.hasUsableHistory(currentStamp, container)) {
            boolean valid = depthHistory.validateHistory(currentStamp, container);
            recordValidationOnce(OptimizationModule.MODERN_VISIBILITY_HZB, valid,
                valid ? null : "GPU HZB conservative hierarchy validation failed");
            visibilityState = visibility.lifecycleState();
        }
        long coverage = terrainArena.inspectCoverage(container, layer);
        int originalVisible = LwjglTerrainArena.coverageVisible(coverage);
        int arenaOwned = LwjglTerrainArena.coverageOwned(coverage);
        int regionRuns = LwjglTerrainArena.coverageRegionRuns(coverage);
        SceneFingerprint terrainScene = sceneFingerprint(originalVisible,
            arenaOwned, regionRuns);
        AdaptiveBackendController backend = backends.get(
            OptimizationModule.MODERN_TERRAIN_BACKEND);
        BackendLifecycleState state = backend == null
            ? BackendLifecycleState.LEGACY : backend.lifecycleState();
        boolean terrainProfileReady = layer != BlockRenderLayer.SOLID;
        if (layer == BlockRenderLayer.SOLID && backend != null
            && terrainScene != null) {
            terrainProfileReady = backend.prepareProfiledWorkload(
                terrainScene.terrainLoadBucket(), currentStamp.getFrameId());
            state = backend.lifecycleState();
        }
        boolean coverageReady = terrainMeasurementCoverageStable(
            originalVisible, arenaOwned);
        if (layer == BlockRenderLayer.SOLID) {
            terrainLastVisibleMeshes = originalVisible;
            terrainLastOwnedMeshes = arenaOwned;
            terrainLastRegionRuns = regionRuns;
        }
        if (arenaOwned <= 0) {
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
        long visibilityStarted = System.nanoTime();
        boolean usableHistory = useHzb && depthHistory != null
            && depthHistory.hasUsableHistory(currentStamp, container);
        AdaptiveBackendController terrainMapping = backends.get(
            OptimizationModule.MODERN_TERRAIN_PERSISTENT_MAPPING);
        BackendLifecycleState terrainMappingState = terrainMapping == null
            ? BackendLifecycleState.LEGACY : terrainMapping.lifecycleState();
        if (state == BackendLifecycleState.OUTPUT_VALIDATE) {
            Boolean equivalent = terrainArena.validateOne(container, layer, currentStamp);
            if (equivalent != null) {
                recordValidation(OptimizationModule.MODERN_TERRAIN_BACKEND,
                    equivalent.booleanValue(), equivalent.booleanValue() ? null
                        : "terrain arena readback mismatch");
                state = backend.lifecycleState();
            }
        }
        if (terrainMappingState == BackendLifecycleState.OUTPUT_VALIDATE) {
            Boolean equivalent = terrainArena.validateOnePersistent(container,
                layer, currentStamp);
            if (equivalent != null) {
                recordValidation(
                    OptimizationModule.MODERN_TERRAIN_PERSISTENT_MAPPING,
                    equivalent.booleanValue(), equivalent.booleanValue() ? null
                        : "terrain persistent-mapped write readback mismatch");
                terrainMappingState = terrainMapping.lifecycleState();
            }
        }
        MeasurementArm arm = state == BackendLifecycleState.PAIRED_MEASURE
            ? backend.expectedMeasurementArm() : MeasurementArm.LEGACY;
        boolean exactLegacyReplay = terrainArena.canReplayLegacy(container,
            layer) && state != BackendLifecycleState.MODERN
            && state != BackendLifecycleState.REGRESSION_MONITOR
            && (state != BackendLifecycleState.PAIRED_MEASURE
                || !terrainProfileReady || !coverageReady
                || arm == MeasurementArm.LEGACY);
        if (exactLegacyReplay) {
            if (state == BackendLifecycleState.PAIRED_MEASURE
                && layer == BlockRenderLayer.SOLID) {
                boolean stable = terrainProfileReady && coverageReady
                    && terrainScene != null && arm == MeasurementArm.LEGACY;
                if (stable) {
                    pendingTerrainMeasurementScene = terrainScene;
                    pendingTerrainMeasurementStarted = System.nanoTime();
                    pendingTerrainMeasurementStable = true;
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
        AdaptiveBackendController mdi = backends.get(
            OptimizationModule.MODERN_TERRAIN_MDI);
        BackendLifecycleState mdiState = mdi == null
            ? BackendLifecycleState.LEGACY : mdi.lifecycleState();
        if (batched && mdiState == BackendLifecycleState.OUTPUT_VALIDATE) {
            boolean valid = terrainArena.supportsIndirectCommands();
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
        RenderBackendId backendId = batched ? RenderBackendId.ICE_NATIVE
            : RenderBackendId.LEGACY;
        RenderPass pass = terrainPass(layer);
        SceneFingerprint scene = sceneFingerprint(originalVisible);
        RenderProfileKey hzbKey = null;
        CorrelatedRenderProfiler.GpuScope hzbTerrainGpu = null;
        boolean hzbMeasurement = layer == BlockRenderLayer.SOLID
            && visibility != null && shaderSafe
            && (visibilityState == BackendLifecycleState.PAIRED_MEASURE
                || visibilityState == BackendLifecycleState.REGRESSION_MONITOR)
            && hzbGpuSamples.isEmpty()
            && (visibilityArm == MeasurementArm.LEGACY
                || (useHzb && usableHistory));
        if (hzbMeasurement && scene != null) {
            hzbKey = beginHzbGpuSample(scene, visibilityArm);
            hzbTerrainGpu = profiler.beginGpu(currentStamp, RenderPass.MAIN_SOLID,
                visibilityArm == MeasurementArm.MODERN ? RenderBackendId.ICE_NATIVE
                    : RenderBackendId.LEGACY, hzbGpuCompletion);
            if (hzbTerrainGpu == null) invalidateHzbGpuSample(hzbKey);
        }
        long started = System.nanoTime();
        boolean mdiStable = true;
        boolean mdiWork = false;
        int mdiEligible = 0;
        boolean arenaAttempted = false;
        boolean hzbFilterOpen = false;
        boolean terrainDrawRecorded = false;
        Throwable operationFailure = null;
        CorrelatedRenderProfiler.CpuScope scope = profiler.beginCpu(currentStamp,
            pass, backendId, CpuWorkKind.SUBMISSION);
        try {
            int removed = 0;
            boolean hzbApplied = false;
            if (useHzb && depthHistory != null) {
                try {
                    removed = depthHistory.filter(container, layer, currentStamp);
                    hzbFilterOpen = true;
                    hzbApplied = usableHistory && layer != BlockRenderLayer.TRANSLUCENT;
                } catch (Throwable hzbError) {
                    FatalErrors.rethrowIfFatal(hzbError);
                    boolean rollbackSafe = true;
                    try { depthHistory.rollbackFilter(container); }
                    catch (Throwable rollbackFailure) {
                        rollbackSafe = false;
                        hzbError = appendRuntimeFailure(hzbError,
                            rollbackFailure);
                    }
                    if (visibility != null) visibility.runtimeFailure(hzbError);
                    invalidateHzbGpuSample(hzbKey);
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
            boolean handled = terrainArena.render(container, layer, batched,
                useMdi, currentStamp.getFrameId());
            boolean submissionStarted = terrainArena.wasLastSubmissionStarted();
            if (hzbFilterOpen) {
                if (handled || submissionStarted) {
                    depthHistory.commitFilter(container);
                } else {
                    depthHistory.rollbackFilter(container);
                }
                hzbFilterOpen = false;
            }
            int indirectCommands = terrainArena.getLastIndirectCommands();
            mdiEligible = terrainArena.getLastIndirectEligibleCommands();
            mdiWork = indirectCommands > 0;
            Throwable mdiError = terrainArena.consumeIndirectFailure();
            if (mdiError != null) {
                FatalErrors.rethrowIfFatal(mdiError);
                mdiStable = false;
                if (mdi != null) mdi.runtimeFailure(mdiError);
                detail = "地形 MDI 异常；已独立回退 multi-draw："
                    + mdiError.getClass().getSimpleName();
            }
            if (handled) {
                if (removed > 0) {
                    profiler.addCounter(currentStamp, pass,
                        RenderBackendId.ICE_NATIVE,
                        RenderCounter.HZB_OCCLUDED, removed);
                }
                if (hzbApplied) {
                    profiler.addCounter(currentStamp, pass,
                        RenderBackendId.ICE_NATIVE,
                        RenderCounter.HZB_TESTED, originalVisible);
                }
                profiler.addCounter(currentStamp, pass, backendId,
                    batched ? RenderCounter.MULTI_DRAW : RenderCounter.DRAW, 1L);
                if (indirectCommands > 0) {
                    profiler.addCounter(currentStamp, pass,
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
                && terrainArena.wasLastSubmissionStarted();
            if (hzbFilterOpen) {
                try {
                    if (submissionStarted) depthHistory.commitFilter(container);
                    else depthHistory.rollbackFilter(container);
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
            try { if (hzbTerrainGpu != null) hzbTerrainGpu.close(); }
            catch (Throwable error) {
                instrumentationFailure = appendRuntimeFailure(
                    instrumentationFailure, error);
            }
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
                if (mdi != null && layer == BlockRenderLayer.SOLID) {
                    recordAuxiliaryBackendSample(
                        OptimizationModule.MODERN_TERRAIN_MDI,
                        mdiState, mdiArm, scene,
                        Math.max(1L, System.nanoTime() - started),
                        scene != null && mdiStable && mdiEligible > 0
                            && (mdiArm == MeasurementArm.LEGACY || mdiWork));
                }
                if (hzbKey != null) addHzbCpuNanos(hzbKey,
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
        SceneFingerprint measurementScene = pendingTerrainMeasurementScene;
        long measurementStarted = pendingTerrainMeasurementStarted;
        boolean measurementStable = pendingTerrainMeasurementStable;
        pendingTerrainMeasurementScene = null;
        pendingTerrainMeasurementStarted = 0L;
        pendingTerrainMeasurementStable = false;
        if (measurementScene != null && measurementStarted > 0L) {
            AdaptiveBackendController backend = backends.get(
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
        if (!initialized || currentStamp == null || depthHistory == null) {
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
        AdaptiveBackendController visibility = backends.get(
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
        if (!shaderPackStateKnown || shaderPackActive) {
            incrementHzbReason(!shaderPackStateKnown
                ? HzbCaptureReason.SHADER_STATE_UNKNOWN
                : HzbCaptureReason.SHADER_PACK_ACTIVE);
            depthHistory.invalidateScene();
            invalidateCurrentHzbGpuSample(MeasurementArm.MODERN);
            if (shaderPackActive && state != BackendLifecycleState.LEGACY) {
                visibility.fallback("ShaderPack active; conservative HZB disabled", false);
            }
            return;
        }
        MeasurementArm arm = state == BackendLifecycleState.PAIRED_MEASURE
            ? visibility.expectedMeasurementArm() : MeasurementArm.MODERN;
        TerrainRenderListAccessor accessor =
            (TerrainRenderListAccessor) container;
        if (state == BackendLifecycleState.PAIRED_MEASURE
            && arm == MeasurementArm.LEGACY
            && depthHistory.hasUsableHistory(currentStamp, container)) {
            incrementHzbReason(
                HzbCaptureReason.PAIRED_LEGACY_HISTORY_REUSED);
            return;
        }

        LwjglDepthHistory.CaptureOutcome preflight;
        hzbCaptureAttempts = saturatedIncrement(hzbCaptureAttempts);
        try {
            preflight = depthHistory.preflightCapture(currentStamp,
                accessor.ice$viewEntityX(), accessor.ice$viewEntityY(),
                accessor.ice$viewEntityZ());
        } catch (Throwable error) {
            incrementHzbReason(HzbCaptureReason.CAPTURE_FAILURE);
            invalidateCurrentHzbGpuSample(arm);
            visibility.runtimeFailure(error);
            depthHistory.invalidateScene();
            return;
        }
        if (preflight != LwjglDepthHistory.CaptureOutcome.CAPTURE_READY) {
            recordHzbCaptureOutcome(preflight);
            invalidateCurrentHzbGpuSample(arm);
            if (preflight == LwjglDepthHistory.CaptureOutcome.UNSAFE_STATE
                || preflight
                    == LwjglDepthHistory.CaptureOutcome.UNSUPPORTED_SOURCE) {
                visibility.fallback("HZB preflight rejected: " + preflight,
                    false);
                depthHistory.invalidateScene();
            }
            return;
        }
        if (!ensureHzbTrackedState(visibility)) {
            depthHistory.cancelPreflight();
            incrementHzbReason(
                HzbCaptureReason.STATE_REAUTHENTICATION_FAILED);
            invalidateCurrentHzbGpuSample(arm);
            return;
        }

        if (state == BackendLifecycleState.PAIRED_MEASURE
            && arm == MeasurementArm.LEGACY) {
            // A moving camera may invalidate the validation history before the
            // first L arm. Seed a fresh history, but never count that polluted
            // frame as the Legacy baseline.
            invalidateCurrentHzbGpuSample(MeasurementArm.LEGACY);
            LwjglDepthHistory.CaptureOutcome seed;
            try {
                seed = depthHistory.capturePreflighted(currentStamp,
                    accessor.ice$viewEntityX(), accessor.ice$viewEntityY(),
                    accessor.ice$viewEntityZ(), false);
            } catch (Throwable error) {
                incrementHzbReason(HzbCaptureReason.CAPTURE_FAILURE);
                visibility.runtimeFailure(error);
                depthHistory.invalidateScene();
                return;
            }
            recordHzbCaptureOutcome(seed);
            if (seed == LwjglDepthHistory.CaptureOutcome.UNSAFE_STATE
                || seed == LwjglDepthHistory.CaptureOutcome.UNSUPPORTED_SOURCE) {
                visibility.fallback("HZB seed rejected: " + seed, false);
                depthHistory.invalidateScene();
            }
            return;
        }
        RenderProfileKey key = findCurrentHzbKey(arm);
        CorrelatedRenderProfiler.GpuScope gpu = key == null ? null
            : profiler.beginGpu(currentStamp, RenderPass.MAIN_SOLID,
                RenderBackendId.ICE_NATIVE, hzbGpuCompletion);
        if (key != null && gpu == null) invalidateHzbGpuSample(key);
        long started = System.nanoTime();
        LwjglDepthHistory.CaptureOutcome outcome;
        Throwable captureFailure = null;
        try {
            outcome = depthHistory.capturePreflighted(currentStamp,
                accessor.ice$viewEntityX(), accessor.ice$viewEntityY(),
                accessor.ice$viewEntityZ(),
                state == BackendLifecycleState.OUTPUT_VALIDATE);
        } catch (Throwable error) {
            outcome = null;
            captureFailure = error;
        }
        try { if (gpu != null) gpu.close(); }
        catch (Throwable closeFailure) {
            captureFailure = appendRuntimeFailure(captureFailure, closeFailure);
        }
        if (captureFailure != null) {
            incrementHzbReason(HzbCaptureReason.CAPTURE_FAILURE);
            invalidateHzbGpuSample(key);
            visibility.runtimeFailure(captureFailure);
            depthHistory.invalidateScene();
            return;
        }
        recordHzbCaptureOutcome(outcome);
        if (key != null) addHzbCpuNanos(key,
            Math.max(1L, System.nanoTime() - started));
        if (outcome != LwjglDepthHistory.CaptureOutcome.CAPTURED) {
            invalidateHzbGpuSample(key);
            if (outcome == LwjglDepthHistory.CaptureOutcome.UNSAFE_STATE
                || outcome == LwjglDepthHistory.CaptureOutcome.UNSUPPORTED_SOURCE) {
                visibility.fallback("HZB capture rejected: " + outcome, false);
                depthHistory.invalidateScene();
            }
        }
    }

    private boolean ensureHzbTrackedState(
        AdaptiveBackendController visibility) {
        if (EarlyGlStateTracker.snapshot() != null
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
            GlStateQueryWorkspace workspace = glStateQueryWorkspace;
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
            if (EarlyGlStateTracker.snapshot() == null
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
        return initialized && terrainArena != null && threadGuard != null
            && threadGuard.isRenderThread() && terrainArena.ownsAny(container, layer);
    }

    /**
     * Fault-only mixed fallback for an arena-owned list. It deliberately uses
     * neither HZB nor MDI and preserves the caller's exact visible order.
     */
    public boolean tryRenderTerrainFallback(Object container, BlockRenderLayer layer) {
        if (!initialized || currentStamp == null || terrainArena == null
            || threadGuard == null || !threadGuard.isRenderThread()
            || !(container instanceof TerrainRenderListAccessor)
            || layer == null || !terrainArena.ownsAny(container, layer)) {
            return declineTerrain(TerrainFallbackReason.FAULT_FALLBACK_DECLINED);
        }
        try {
            boolean handled = terrainArena.render(container, layer, false, false,
                currentStamp.getFrameId());
            if (handled) recordTerrainArenaDraw(false, 0);
            else declineTerrain(TerrainFallbackReason.FAULT_FALLBACK_DECLINED);
            return handled;
        } catch (Throwable fallbackError) {
            FatalErrors.rethrowIfFatal(fallbackError);
            AdaptiveBackendController backend = backends.get(
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

        prepareRuntimeBreakers(generation);
        if (!OptimizerRegistry.isOperational(
            OptimizationModule.MODERN_FRAME_COORDINATOR)) {
            detail = "现代帧协调器已在当前代际独立熔断";
            return;
        }

        ComponentGraph candidate = new ComponentGraph();
        candidate.contextCapabilities = context;
        candidate.contextGeneration = epochs.currentGlContextGeneration();
        try {
            candidate.threadGuard = RenderThreadGuard.captureCurrent();
            candidate.stateMirror = new GlStateMirror(32);
            candidate.profiler = new CorrelatedRenderProfiler(240);
            candidate.resources = new ResourceLedger(candidate.threadGuard, budget,
                new LwjglResourceDestroyer(), 65536);
            candidate.glStateQueryWorkspace = new GlStateQueryWorkspace(budget);
            long gpuLimit = budget.snapshot().getGpuLimit();
            candidate.modelMeshes = new LwjglModelMeshCache(candidate.threadGuard,
                candidate.resources, budget);
            candidate.particles = new ParticleInstanceStream(131072, budget);
            candidate.particleRenderer = new LwjglParticleRenderer(
                candidate.threadGuard, candidate.resources, budget, 131072);
            candidate.fbpPacketRenderer = new LwjglFbpPacketRenderer(
                candidate.threadGuard, candidate.resources, 4 * 1024 * 1024);
            candidate.textureUploads = new TextureUploadStream(4096,
                Math.min(64L * 1024L * 1024L,
                    budget.snapshot().getDirectLimit()), budget);
            long visibilityBytes = Math.max(4096L, Math.min(
                64L * 1024L * 1024L,
                budget.snapshot().getHeapLimit() / 16L));
            candidate.spriteVisibility = new SpriteVisibilityTracker(65536,
                visibilityBytes, new SpriteVisibilityTracker.CatchUpSink() {
                    @Override public boolean upload(
                        SpriteVisibilityTracker.DeferredUpload upload) {
                        return catchUpAnimatedTexture(upload);
                    }
                }, budget);
            candidate.hud = new HudVertexStream(65536, budget);
            candidate.hudRenderer = new LwjglHudRenderer(candidate.threadGuard,
                candidate.resources, budget, 65536);
            candidate.fonts = new FontLayoutCache(4096, 262144, budget);
            candidate.certifiedDrawSites = new CertifiedDrawSites(4096, budget);
            candidate.shaders = new ShaderCertificationRegistry(2048, budget);
            candidate.shaderPipeline = new ShaderCertificationPipeline(
                candidate.shaders);
            candidate.shaderCompiler = new LwjglShaderCompilationDriver(
                candidate.threadGuard, budget);
            candidate.shaderInstaller = new LwjglShaderProgramInstaller(
                candidate.threadGuard, candidate.resources, 256);
            candidate.shaderImageCertification =
                new LwjglShaderImageCertification(candidate.threadGuard, budget);
            candidate.shaderActivation = new LwjglOptifineShaderActivation(
                candidate.threadGuard, budget);
            candidate.shaderBackendSelector = new OptifineShaderBackendSelector(
                candidate.shaders);
            candidate.hzbHistory = new ConservativeOcclusionHistory();
            candidate.coordinator = new FrameCoordinator(candidate.threadGuard,
                PassGraph.standard(), candidate.stateMirror,
                reason -> flushBatches());
            candidate.legacyIsland = new LegacyGlIsland(candidate.threadGuard,
                candidate.stateMirror, () -> flushBatches(),
                new LwjglLegacyStateRestorer());
            candidate.capabilities = new LwjglCapabilitySelfTest(budget).execute();
            candidate.animatedTextures = new LwjglAnimatedTextureUploadStream(
                candidate.threadGuard, candidate.resources, budget,
                candidate.capabilities);
            initializeTrackedGlState(candidate.glStateQueryWorkspace);
            if (candidate.capabilities.passed(ModernCapability.TIMER_QUERY)) {
                candidate.profiler.attachGpuQueries(
                    new LwjglGpuTimestampDriver(), 64, budget);
            }
            candidate.terrainArena = new LwjglTerrainArena(candidate.threadGuard,
                candidate.resources, budget, candidate.capabilities,
                Math.max(16L * 1024L * 1024L, gpuLimit / 2L), arenaGeneration);
            candidate.depthHistory = new LwjglDepthHistory(candidate.threadGuard,
                candidate.resources, candidate.capabilities,
                candidate.hzbHistory, budget);
            candidate.backends.putAll(createBackends(candidate.capabilities,
                generation));

            publish(candidate);
            initializationRetry.recordSuccess(generation);
            initialized = true;
            detail = "能力自测完成；各子后端独立预热/验证/测量";
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
                dispose(candidate, disposalContextValid,
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

    private void drainPendingShaderCandidates(int maximum) {
        if (shutdownRequested || !initialized || shaderPipeline == null
            || shaderInstaller == null
            || currentStamp != null || threadGuard == null
            || !threadGuard.isRenderThread() || shaderBindingsPoisoned) return;
        int limit = Math.max(0, maximum);
        for (int index = 0; index < limit; index++) {
            CapturedShaderSources captured;
            synchronized (this) {
                captured = pendingShaderCandidates.pollFirst();
                if (captured == null) return;
                pendingShaderCandidateBytes = Math.max(0L,
                    pendingShaderCandidateBytes - captured.bytes);
            }
            try {
            if (captured.resourceGeneration != epochs.currentResourceGeneration()
                || captured.shaderGeneration
                    != epochs.currentShaderPackGeneration()) continue;
            if (captured.programIdentity == null) {
                shaderDomainRejected(OptimizationModule.OPTIFINE_SHADER_COMPILE,
                    "captured Shader candidate has no OptiFine Program identity");
                continue;
            }
            int legacyProgramId;
            try {
                legacyProgramId = shaderIntrospector.programId(
                    captured.programIdentity);
            } catch (Throwable introspectionFailure) {
                shaderDomainFailure(OptimizationModule.OPTIFINE_SHADER_COMPILE,
                    introspectionFailure);
                continue;
            }
            if (legacyProgramId <= 0) {
                shaderDomainRejected(OptimizationModule.OPTIFINE_SHADER_COMPILE,
                    "captured OptiFine Program is not linked");
                continue;
            }
            if (!shaderBindings.containsKey(captured.programIdentity)
                && shaderBindings.size() >= MAX_SHADER_BINDINGS) {
                shaderDomainRejected(OptimizationModule.OPTIFINE_SHADER_COMPILE,
                    "Shader Program binding table is full");
                continue;
            }
            try {
                PreparedShaderPermutation prepared = shaderPipeline.prepareResolved(
                    captured.packId, captured.program, captured.permutation,
                    captured.resourceGeneration, captured.shaderGeneration,
                    captured.vertexPath, captured.vertexSource,
                    captured.geometryPath, captured.geometrySource,
                    captured.fragmentPath, captured.fragmentSource,
                    captured.propertiesSource);
                if (!compileShaderPermutation(prepared, legacyProgramId)) {
                    detail = "OptiFine Shader 候选编译/安装被拒绝；原程序保持有效";
                    continue;
                }
                int candidateProgram = shaderInstaller.installedProgram(
                    prepared.getKey(), epochs.currentResourceGeneration(),
                    epochs.currentGlContextGeneration(),
                    epochs.currentShaderPackGeneration());
                if (candidateProgram <= 0) {
                    shaders.recordCompile(prepared.getKey(), false,
                        "retained shader program disappeared after installation");
                    shaderDomainFailure(OptimizationModule.OPTIFINE_SHADER_COMPILE,
                        new IllegalStateException(
                            "retained shader program publication failed"));
                    continue;
                }
                ShaderProgramBinding binding = registerShaderBinding(
                    captured.programIdentity, legacyProgramId, candidateProgram,
                    prepared);
                if (binding != null && optifineProgramIdentity
                    == captured.programIdentity && optifineProgramState != null
                    && optifineProgramState.getProgramId() == legacyProgramId) {
                    queueShaderValidation(binding, optifineProgramState);
                }
            } catch (Throwable rejected) {
                FatalErrors.rethrowIfFatal(rejected);
                ModuleCircuitBreaker breaker = OptimizerRegistry.breaker(
                    OptimizationModule.OPTIFINE_SHADER_BRIDGE);
                if (breaker != null) breaker.recordRejected(
                    "resolved Shader candidate rejected: "
                        + rejected.getClass().getSimpleName());
                detail = "OptiFine Shader 候选输入被安全拒绝："
                    + rejected.getClass().getSimpleName();
            }
            } finally {
                captured.close();
            }
        }
    }

    private void drainPendingShaderValidations(int maximum) {
        if (shutdownRequested || !initialized || currentStamp != null
            || shaderPipeline == null
            || shaderImageCertification == null || shaderInstaller == null
            || shaders == null || threadGuard == null
            || !threadGuard.isRenderThread() || shaderBindingsPoisoned) return;
        int limit = Math.max(0, maximum);
        for (int index = 0; index < limit; index++) {
            ShaderValidationRequest request = pendingShaderValidations.pollFirst();
            if (request == null) return;
            ShaderProgramBinding binding = request.binding;
            binding.validationQueued = false;
            if (!bindingCurrent(binding, binding.programIdentity,
                request.state.getProgramId()) || shaders.hasFailed(binding.key)) {
                binding.closePrepared();
                continue;
            }
            int candidateProgram = shaderInstaller.installedProgram(binding.key,
                epochs.currentResourceGeneration(),
                epochs.currentGlContextGeneration(),
                epochs.currentShaderPackGeneration());
            if (candidateProgram != binding.candidateProgram
                || candidateProgram <= 0) {
                removeShaderBinding(binding);
                continue;
            }

            boolean statePassed;
            try {
                statePassed = shaderPipeline.validateState(binding.key,
                    request.state, request.state.withProgramId(candidateProgram))
                    .isEquivalent();
            } catch (Throwable error) {
                binding.closePrepared();
                FatalErrors.rethrowIfFatal(error);
                shaders.recordStateValidation(binding.key, false);
                shaderDomainFailure(OptimizationModule.OPTIFINE_SHADER_STATE,
                    error);
                continue;
            }
            if (!statePassed) {
                shaderDomainRejected(OptimizationModule.OPTIFINE_SHADER_STATE,
                    "OptiFine Shader logical state mismatch");
                binding.closePrepared();
                continue;
            }
            shaderDomainSuccess(OptimizationModule.OPTIFINE_SHADER_STATE);

            try {
                LwjglShaderImageCertification.Result images =
                    shaderImageCertification.certify(binding.prepared,
                        binding.legacyProgram, candidateProgram, request.state);
                if (!images.wasExecuted() || !images.hasSignal()) {
                    shaders.recordImageValidation(binding.key, false);
                    if (images.isInfrastructureFailure()) {
                        shaderDomainFailure(OptimizationModule.OPTIFINE_SHADER_IMAGE,
                            new IllegalStateException(images.getDetail()));
                    } else {
                        shaderDomainRejected(
                            OptimizationModule.OPTIFINE_SHADER_IMAGE,
                            images.getDetail());
                    }
                    continue;
                }
                boolean equivalent = shaderPipeline.validateImages(binding.key,
                    images.getLegacyImages(), images.getCandidateImages(), 0);
                if (!equivalent) {
                    shaderDomainRejected(OptimizationModule.OPTIFINE_SHADER_IMAGE,
                        "OptiFine Shader scratch attachment A/B mismatch");
                    continue;
                }
                // Image equivalence is meaningful only for the exact logical
                // OptiFine pass/FBO layout that was exercised.  Publish that
                // immutable state only after a final generation/program
                // authentication; later activation and every terrain draw
                // must match it again.
                int retainedProgram = shaderInstaller.certifiedProgram(
                    binding.key, shaders,
                    epochs.currentResourceGeneration(),
                    epochs.currentGlContextGeneration(),
                    epochs.currentShaderPackGeneration());
                if (!bindingGenerationsCurrent(binding)
                    || retainedProgram != binding.candidateProgram) {
                    removeShaderBinding(binding);
                    continue;
                }
                binding.certifiedState = request.state;
                shaderDomainSuccess(OptimizationModule.OPTIFINE_SHADER_IMAGE);
                detail = "OptiFine Shader permutation 已通过编译、状态与图像认证";
            } catch (Throwable error) {
                shaders.recordImageValidation(binding.key, false);
                shaderDomainFailure(OptimizationModule.OPTIFINE_SHADER_IMAGE,
                    error);
            } finally {
                binding.closePrepared();
                EarlyGlStateTracker.invalidate();
                EarlyMatrixStateTracker.invalidate();
            }
        }
    }

    private synchronized void clearPendingShaderCandidates() {
        Throwable failure = null;
        for (CapturedShaderSources captured : pendingShaderCandidates) {
            try { captured.close(); }
            catch (Throwable closeFailure) {
                failure = appendRuntimeFailure(failure, closeFailure);
            }
        }
        try { pendingShaderCandidates.clear(); }
        catch (Throwable clearFailure) {
            failure = appendRuntimeFailure(failure, clearFailure);
        }
        pendingShaderCandidateBytes = 0L;
        if (failure != null) rethrowRuntimeFailure(failure);
    }

    private void purgeStaleShaderCandidates(long resources, long shaders) {
        Iterator<CapturedShaderSources> iterator =
            pendingShaderCandidates.iterator();
        while (iterator.hasNext()) {
            CapturedShaderSources captured = iterator.next();
            if (captured.resourceGeneration == resources
                && captured.shaderGeneration == shaders) continue;
            iterator.remove();
            captured.close();
            pendingShaderCandidateBytes = Math.max(0L,
                pendingShaderCandidateBytes - captured.bytes);
        }
    }

    synchronized int pendingShaderCandidateCountForTest() {
        return pendingShaderCandidates.size();
    }

    synchronized long pendingShaderCandidateBytesForTest() {
        return pendingShaderCandidateBytes;
    }

    int pendingShaderValidationCountForTest() {
        return pendingShaderValidations.size();
    }

    int shaderBindingCountForTest() {
        return shaderBindingsPoisoned ? 0 : shaderBindings.size();
    }

    boolean shaderBindingsPoisonedForTest() { return shaderBindingsPoisoned; }

    void shaderBindingPublicationFaultForTest(
        ShaderBindingPublicationFault fault) {
        shaderBindingPublicationFault = fault;
    }

    private ShaderProgramBinding registerShaderBinding(Object programIdentity,
                                                       int legacyProgram,
                                                       int candidateProgram,
        PreparedShaderPermutation prepared) {
        if (programIdentity == null || legacyProgram <= 0 || candidateProgram <= 0
            || prepared == null || shaderBindingsPoisoned) return null;
        if (activeNativeShaderBinding != null) {
            throw new IllegalStateException(
                "cannot replace shader bindings while a candidate may be active");
        }
        ShaderProgramBinding current = shaderBindings.get(programIdentity);
        if (current != null && current.legacyProgram == legacyProgram
            && current.candidateProgram == candidateProgram
            && current.key.equals(prepared.getKey())
            && bindingGenerationsCurrent(current)) return current;
        long heapBytes;
        try { heapBytes = preparedShaderHeapBytes(prepared); }
        catch (RuntimeException invalid) {
            FatalErrors.rethrowIfFatal(invalid);
            if (shaders != null) {
                shaders.recordCompile(prepared.getKey(), false,
                    "prepared Shader retained-Heap accounting failed");
            }
            return null;
        }
        CacheBudget.Reservation reservation = budget.tryReserve(
            BudgetKind.HEAP, heapBytes);
        if (reservation == null) {
            if (shaders != null) {
                shaders.recordCompile(prepared.getKey(), false,
                    "prepared Shader Heap budget exhausted");
            }
            shaderDomainRejected(OptimizationModule.OPTIFINE_SHADER_COMPILE,
                "prepared Shader Heap budget exhausted");
            return null;
        }
        ShaderProgramBinding binding;
        try {
            ShaderTerrainLayoutCertification.Result terrainLayout =
                initialized && threadGuard != null && threadGuard.isRenderThread()
                    ? ShaderTerrainLayoutCertification.certify(candidateProgram,
                        prepared.getVertex().getSource())
                    : null;
            binding = new ShaderProgramBinding(programIdentity,
                legacyProgram, candidateProgram, prepared,
                epochs.currentResourceGeneration(),
                epochs.currentGlContextGeneration(),
                epochs.currentShaderPackGeneration(), reservation,
                terrainLayout != null && terrainLayout.isCertified(),
                terrainLayout == null ? "terrain layout not queried"
                    : terrainLayout.getDetail());
            reservation = null;
        } finally {
            if (reservation != null) reservation.close();
        }
        Integer legacyKey = Integer.valueOf(legacyProgram);
        boolean hadPrevious = shaderBindings.containsKey(programIdentity);
        ShaderProgramBinding previous = shaderBindings.get(programIdentity);
        boolean hadCollision = shaderBindingsById.containsKey(legacyKey);
        ShaderProgramBinding collision = shaderBindingsById.get(legacyKey);
        try {
            shaderBindings.put(programIdentity, binding);
            shaderBindingPublicationCheckpoint("after-identity");
            shaderBindingsById.put(legacyKey, binding);
            shaderBindingPublicationCheckpoint("after-id");
            if (shaderBindings.get(programIdentity) != binding
                || shaderBindingsById.get(legacyKey) != binding) {
                throw new IllegalStateException(
                    "Shader binding publication verification failed");
            }
        } catch (Throwable publicationFailure) {
            Throwable failure = publicationFailure;
            boolean rollbackCertain = true;
            try {
                shaderBindingPublicationCheckpoint("before-identity-rollback");
                restoreMapEntry(shaderBindings, programIdentity, hadPrevious,
                    previous);
            } catch (Throwable rollbackFailure) {
                rollbackCertain = false;
                failure = appendRuntimeFailure(failure, rollbackFailure);
            }
            try {
                shaderBindingPublicationCheckpoint("before-id-rollback");
                restoreMapEntry(shaderBindingsById, legacyKey, hadCollision,
                    collision);
            } catch (Throwable rollbackFailure) {
                rollbackCertain = false;
                failure = appendRuntimeFailure(failure, rollbackFailure);
            }
            if (rollbackCertain) {
                try { binding.closePrepared(); }
                catch (Throwable closeFailure) {
                    failure = appendRuntimeFailure(failure, closeFailure);
                }
            } else {
                shaderBindingPublicationWitness = binding;
                failure = poisonShaderBindingTables(failure);
            }
            rethrowRuntimeFailure(failure);
            return null;
        }
        if (previous != null && previous.legacyProgram != legacyProgram
            && shaderBindingsById.get(Integer.valueOf(previous.legacyProgram))
                == previous) {
            shaderBindingsById.remove(Integer.valueOf(previous.legacyProgram));
        }
        if (collision != null && collision != previous
            && shaderBindings.get(collision.programIdentity) == collision) {
            shaderBindings.remove(collision.programIdentity);
        }
        if (previous != null) previous.validationQueued = false;
        if (collision != null) collision.validationQueued = false;
        if (previous != null) previous.closePrepared();
        if (collision != null && collision != previous) collision.closePrepared();
        return binding;
    }

    private void shaderBindingPublicationCheckpoint(String point) {
        ShaderBindingPublicationFault fault = shaderBindingPublicationFault;
        if (fault != null) fault.checkpoint(point);
    }

    private static <K, V> void restoreMapEntry(Map<K, V> map, K key,
                                                boolean present, V value) {
        if (present) map.put(key, value);
        else map.remove(key);
        if (present) {
            if (!map.containsKey(key) || map.get(key) != value) {
                throw new IllegalStateException(
                    "Shader binding rollback verification failed");
            }
        } else if (map.containsKey(key)) {
            throw new IllegalStateException(
                "Shader binding rollback left an unexpected entry");
        }
    }

    private Throwable poisonShaderBindingTables(Throwable failure) {
        shaderBindingsPoisoned = true;
        detail = "Shader binding publication outcome uncertain; native Shader path fused";
        try { clearPendingShaderCandidates(); }
        catch (Throwable cleanupFailure) {
            failure = appendRuntimeFailure(failure, cleanupFailure);
        }
        try { cleanupShaderBindingTables(false); }
        catch (Throwable cleanupFailure) {
            failure = appendRuntimeFailure(failure, cleanupFailure);
        }
        if (FatalErrors.findFatal(failure) == null) {
            try {
                shaderDomainFailure(OptimizationModule.OPTIFINE_SHADER_BRIDGE,
                    new IllegalStateException(
                        "Shader binding table publication was not atomic", failure));
            } catch (Throwable reportingFailure) {
                failure = appendRuntimeFailure(failure, reportingFailure);
            }
        }
        return failure;
    }

    private boolean bindingCurrent(ShaderProgramBinding binding,
                                   Object programIdentity,
                                   int observedProgram) {
        if (shaderBindingsPoisoned || binding == null || programIdentity == null
            || binding.programIdentity != programIdentity
            || shaderBindings.get(programIdentity) != binding
            || shaderBindingsById.get(Integer.valueOf(binding.legacyProgram))
                != binding) {
            return false;
        }
        if (binding.resourceGeneration != epochs.currentResourceGeneration()
            || binding.contextGeneration != epochs.currentGlContextGeneration()
            || binding.shaderGeneration != epochs.currentShaderPackGeneration()) {
            removeShaderBinding(binding);
            return false;
        }
        if (observedProgram != binding.legacyProgram) {
            removeShaderBinding(binding);
            shaderDomainRejected(OptimizationModule.OPTIFINE_SHADER_STATE,
                "OptiFine Program GL name changed within one generation");
            return false;
        }
        try {
            if (shaderIntrospector.programId(programIdentity)
                == binding.legacyProgram) return true;
            removeShaderBinding(binding);
            shaderDomainRejected(OptimizationModule.OPTIFINE_SHADER_STATE,
                "OptiFine Program GL name changed within one generation");
        } catch (Throwable error) {
            Throwable failure = error;
            try { removeShaderBinding(binding); }
            catch (Throwable removalFailure) {
                failure = appendRuntimeFailure(failure, removalFailure);
            }
            shaderDomainFailure(OptimizationModule.OPTIFINE_SHADER_STATE,
                failure);
        }
        return false;
    }

    private boolean bindingGenerationsCurrent(ShaderProgramBinding binding) {
        return !shaderBindingsPoisoned && binding != null
            && shaderBindings.get(binding.programIdentity) == binding
            && binding.resourceGeneration == epochs.currentResourceGeneration()
            && binding.contextGeneration == epochs.currentGlContextGeneration()
            && binding.shaderGeneration == epochs.currentShaderPackGeneration();
    }

    private void removeShaderBinding(ShaderProgramBinding binding) {
        if (binding == null) return;
        if (shaderBindingsPoisoned) {
            binding.validationQueued = false;
            binding.closePrepared();
            return;
        }
        try {
            if (shaderBindings.get(binding.programIdentity) == binding) {
                shaderBindings.remove(binding.programIdentity);
            }
            Integer legacyKey = Integer.valueOf(binding.legacyProgram);
            if (shaderBindingsById.get(legacyKey) == binding) {
                shaderBindingsById.remove(legacyKey);
            }
            if (shaderBindings.get(binding.programIdentity) == binding
                || shaderBindingsById.get(legacyKey) == binding) {
                throw new IllegalStateException(
                    "Shader binding removal verification failed");
            }
            binding.validationQueued = false;
            binding.closePrepared();
        } catch (Throwable removalFailure) {
            Throwable failure = poisonShaderBindingTables(removalFailure);
            rethrowRuntimeFailure(failure);
        }
    }

    private void queueShaderValidation(ShaderProgramBinding binding,
                                       OptifineProgramState state) {
        if (binding == null || state == null || binding.validationQueued
            || binding.prepared == null
            || shaders == null || !shaders.compilePassed(binding.key)
            || shaders.isCertified(binding.key) || shaders.hasFailed(binding.key)
            || !OptimizerRegistry.isOperational(
                OptimizationModule.OPTIFINE_SHADER_STATE)
            || !OptimizerRegistry.isOperational(
                OptimizationModule.OPTIFINE_SHADER_IMAGE)) return;
        if (pendingShaderValidations.size() >= MAX_PENDING_SHADER_VALIDATIONS) {
            shaderDomainRejected(OptimizationModule.OPTIFINE_SHADER_IMAGE,
                "Shader image validation queue is full");
            return;
        }
        binding.validationQueued = true;
        try {
            pendingShaderValidations.addLast(
                new ShaderValidationRequest(binding, state));
        } catch (Throwable publicationFailure) {
            binding.validationQueued = false;
            FatalErrors.rethrowIfFatal(publicationFailure);
            throw publicationFailure;
        }
    }

    private void promoteCertifiedShaderBackend() {
        AdaptiveBackendController backend = backends.get(
            OptimizationModule.OPTIFINE_SHADER_BRIDGE);
        if (shaderBindingsPoisoned || backend == null || backend.lifecycleState()
            != BackendLifecycleState.OUTPUT_VALIDATE || shaders == null) return;
        for (ShaderProgramBinding binding : shaderBindings.values()) {
            if (bindingGenerationsCurrent(binding)
                && shaders.isCertified(binding.key)) {
                backend.validationResult(true,
                    "至少一个当前代际 Shader permutation 已通过三门认证");
                return;
            }
        }
    }

    private void activateCertifiedShaderForInterval(
        ShaderProgramBinding binding, OptifineProgramState state) {
        if (shutdownRequested || binding == null || state == null
            || currentStamp == null
            || activeShaderSample != null || activeNativeShaderBinding != null
            || shaderActivation == null || shaderInstaller == null
            || shaders == null || !shaderDomainsOperational()
            || !shaders.isCertified(binding.key)) return;
        AdaptiveBackendController backend = backends.get(
            OptimizationModule.OPTIFINE_SHADER_BRIDGE);
        if (backend == null) return;
        BackendLifecycleState lifecycle = backend.lifecycleState();
        if (lifecycle != BackendLifecycleState.PAIRED_MEASURE
            && lifecycle != BackendLifecycleState.MODERN
            && lifecycle != BackendLifecycleState.REGRESSION_MONITOR) return;
        int candidateProgram = shaderInstaller.certifiedProgram(binding.key,
            shaders, epochs.currentResourceGeneration(),
            epochs.currentGlContextGeneration(),
            epochs.currentShaderPackGeneration());
        if (candidateProgram != binding.candidateProgram
            || state.getProgramId() != binding.legacyProgram
            || binding.certifiedState == null
            || !binding.certifiedState.isLogicalActivationEquivalent(state)) return;

        RenderBackendSample sample = beginRenderBackendSample(
            OptimizationModule.OPTIFINE_SHADER_BRIDGE, RenderPass.FINAL,
            0x40000000 | (binding.key.hashCode() & 0x3FFFFFFF));
        if (sample == null) return;
        activeShaderSample = sample;
        activeShaderSampleBinding = binding;
        if (sample.arm != MeasurementArm.MODERN) return;

        LwjglOptifineShaderActivation.Result result = shaderActivation.switchProgram(
            binding.programIdentity, binding.legacyProgram, candidateProgram);
        if (result.isSwitched()) {
            activeNativeShaderBinding = binding;
            optifineProgramState = state.withProgramId(candidateProgram);
            EarlyGlStateTracker.useProgram(candidateProgram);
            shaderDomainSuccess(OptimizationModule.OPTIFINE_SHADER_ACTIVATION);
            return;
        }
        handleShaderActivationFailure(result);
        if (!result.isRollbackSucceeded()) activeNativeShaderBinding = binding;
        endRenderBackendSample(sample, false, false);
        activeShaderSample = null;
        activeShaderSampleBinding = null;
    }

    private void finishActiveShaderInterval(boolean stable,
                                            boolean restoreOriginal) {
        ShaderProgramBinding nativeBinding = activeNativeShaderBinding;
        ShaderProgramBinding sampledBinding = activeShaderSampleBinding;
        boolean modernWork = nativeBinding != null
            && nativeBinding == sampledBinding;
        boolean restored = true;
        if (restoreOriginal && nativeBinding != null) {
            if (shaderActivation == null) {
                restored = false;
                shaderDomainFailure(OptimizationModule.OPTIFINE_SHADER_ACTIVATION,
                    new IllegalStateException("shader activation component unavailable"));
            } else {
                LwjglOptifineShaderActivation.Result result =
                    shaderActivation.switchProgram(nativeBinding.programIdentity,
                        nativeBinding.candidateProgram,
                        nativeBinding.legacyProgram);
                restored = result.isSwitched();
                if (restored) {
                    activeNativeShaderBinding = null;
                    if (optifineProgramIdentity == nativeBinding.programIdentity
                        && optifineProgramState != null) {
                        optifineProgramState = optifineProgramState.withProgramId(
                            nativeBinding.legacyProgram);
                    }
                    EarlyGlStateTracker.useProgram(nativeBinding.legacyProgram);
                    shaderDomainSuccess(
                        OptimizationModule.OPTIFINE_SHADER_ACTIVATION);
                } else {
                    handleShaderActivationFailure(result);
                }
            }
        }
        RenderBackendSample sample = activeShaderSample;
        activeShaderSample = null;
        activeShaderSampleBinding = null;
        if (sample != null) {
            endRenderBackendSample(sample, stable && restored, modernWork);
        }
        if (restoreOriginal && nativeBinding != null) {
            EarlyGlStateTracker.invalidate();
            EarlyMatrixStateTracker.invalidate();
        }
    }

    private void handleShaderActivationFailure(
        LwjglOptifineShaderActivation.Result result) {
        String failure = result == null ? "shader activation returned null"
            : result.getDetail();
        if (result == null || result.isInfrastructureFailure()
            || !result.isRollbackSucceeded()) {
            IllegalStateException error = new IllegalStateException(failure);
            shaderDomainFailure(OptimizationModule.OPTIFINE_SHADER_ACTIVATION,
                error);
            AdaptiveBackendController backend = backends.get(
                OptimizationModule.OPTIFINE_SHADER_BRIDGE);
            if (backend != null) backend.runtimeFailure(error);
        } else {
            shaderDomainRejected(OptimizationModule.OPTIFINE_SHADER_ACTIVATION,
                failure);
        }
        detail = "OptiFine Shader candidate 激活被安全回退：" + failure;
        EarlyGlStateTracker.invalidate();
        EarlyMatrixStateTracker.invalidate();
    }

    private void abandonActiveShaderInterval() {
        activeShaderSample = null;
        activeShaderSampleBinding = null;
        activeNativeShaderBinding = null;
    }

    private void clearShaderBindings() {
        cleanupShaderBindingTables(true);
    }

    private void cleanupShaderBindingTables(boolean resetFuse) {
        if (activeNativeShaderBinding != null) {
            throw new IllegalStateException(
                "cannot clear bindings while a native shader may be active");
        }
        shaderBindingsPoisoned = true;
        ShaderProgramBinding[] detached = snapshotShaderBindings();
        shaderBindingCleanupWitness = detached;
        Throwable failure = null;
        try { pendingShaderValidations.clear(); }
        catch (Throwable cleanupFailure) {
            failure = appendRuntimeFailure(failure, cleanupFailure);
        }
        try { shaderBindings.clear(); }
        catch (Throwable cleanupFailure) {
            failure = appendRuntimeFailure(failure, cleanupFailure);
        }
        try { shaderBindingsById.clear(); }
        catch (Throwable cleanupFailure) {
            failure = appendRuntimeFailure(failure, cleanupFailure);
        }
        if (!shaderBindings.isEmpty() || !shaderBindingsById.isEmpty()
            || !pendingShaderValidations.isEmpty()) {
            failure = appendRuntimeFailure(failure,
                new IllegalStateException(
                    "Shader binding cleanup did not detach every owner"));
        }
        if (failure == null) {
            shaderBindingPublicationWitness = null;
            for (ShaderProgramBinding binding : detached) {
                if (binding == null) continue;
                try {
                    binding.validationQueued = false;
                    binding.closePrepared();
                } catch (Throwable cleanupFailure) {
                    failure = appendRuntimeFailure(failure, cleanupFailure);
                }
            }
        }
        abandonActiveShaderInterval();
        optifineProgramIdentity = null;
        if (failure != null) {
            shaderBindingsPoisoned = true;
            rethrowRuntimeFailure(failure);
        }
        shaderBindingCleanupWitness = null;
        if (resetFuse) shaderBindingsPoisoned = false;
    }

    private ShaderProgramBinding[] snapshotShaderBindings() {
        int capacity = Math.addExact(shaderBindings.size(),
            shaderBindingsById.size());
        capacity = Math.addExact(capacity,
            pendingShaderValidations.size());
        if (shaderBindingPublicationWitness != null) capacity++;
        if (shaderBindingCleanupWitness != null) {
            capacity = Math.addExact(capacity,
                shaderBindingCleanupWitness.length);
        }
        ShaderProgramBinding[] values = new ShaderProgramBinding[capacity];
        int count = 0;
        if (shaderBindingCleanupWitness != null) {
            for (ShaderProgramBinding binding : shaderBindingCleanupWitness) {
                count = addUniqueBinding(values, count, binding);
            }
        }
        count = addUniqueBinding(values, count,
            shaderBindingPublicationWitness);
        for (ShaderProgramBinding binding : shaderBindings.values()) {
            count = addUniqueBinding(values, count, binding);
        }
        for (ShaderProgramBinding binding : shaderBindingsById.values()) {
            count = addUniqueBinding(values, count, binding);
        }
        for (ShaderValidationRequest request : pendingShaderValidations) {
            count = addUniqueBinding(values, count,
                request == null ? null : request.binding);
        }
        return values;
    }

    private static int addUniqueBinding(ShaderProgramBinding[] values,
                                        int count,
                                        ShaderProgramBinding binding) {
        if (binding == null) return count;
        for (int index = 0; index < count; index++) {
            if (values[index] == binding) return count;
        }
        values[count] = binding;
        return count + 1;
    }

    private boolean shaderDomainsOperational() {
        return OptimizerRegistry.isOperational(
            OptimizationModule.OPTIFINE_SHADER_COMPILE)
            && OptimizerRegistry.isOperational(
                OptimizationModule.OPTIFINE_SHADER_STATE)
            && OptimizerRegistry.isOperational(
                OptimizationModule.OPTIFINE_SHADER_IMAGE)
            && OptimizerRegistry.isOperational(
                OptimizationModule.OPTIFINE_SHADER_ACTIVATION);
    }

    private static void shaderDomainSuccess(OptimizationModule module) {
        ModuleCircuitBreaker breaker = OptimizerRegistry.breaker(module);
        if (breaker != null) breaker.recordSuccess();
    }

    private static void shaderDomainRejected(OptimizationModule module,
                                             String reason) {
        ModuleCircuitBreaker breaker = OptimizerRegistry.breaker(module);
        if (breaker != null) breaker.recordRejected(reason);
    }

    private static void shaderDomainFailure(OptimizationModule module,
                                            Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        ModuleCircuitBreaker breaker = OptimizerRegistry.breaker(module);
        if (breaker != null) breaker.recordFailure(error);
    }

    private static long capturedShaderBytes(String vertex, String geometry,
                                            String fragment,
                                            String properties) {
        if (vertex == null || fragment == null || vertex.indexOf('\0') >= 0
            || fragment.indexOf('\0') >= 0
            || geometry != null && geometry.indexOf('\0') >= 0
            || properties != null && properties.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("captured Shader source");
        }
        long bytes = utf8Bytes(vertex);
        bytes = checkedCapturedAdd(bytes, utf8Bytes(fragment));
        if (geometry != null) bytes = checkedCapturedAdd(bytes,
            utf8Bytes(geometry));
        if (properties != null) bytes = checkedCapturedAdd(bytes,
            utf8Bytes(properties));
        if (bytes <= 0L || bytes > 8L * 1024L * 1024L) {
            throw new IllegalArgumentException("captured Shader byte limit");
        }
        return bytes;
    }

    private static long utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static long checkedCapturedAdd(long left, long right) {
        if (right > Long.MAX_VALUE - left) {
            throw new IllegalArgumentException("captured Shader size overflow");
        }
        return left + right;
    }

    private static long capturedShaderHeapBytes(String... values) {
        long bytes = 0L;
        for (String value : values) {
            if (value == null) continue;
            bytes = Math.addExact(bytes, shaderStringHeapBytes(value));
        }
        return Math.max(1L, bytes);
    }

    private static long preparedShaderHeapBytes(
        PreparedShaderPermutation prepared) {
        if (prepared == null) throw new IllegalArgumentException(
            "prepared Shader");
        long bytes = 256L;
        dev.rlcraft.ice.optimizer.render.optifine.PreprocessedShader[] stages = {
            prepared.getVertex(), prepared.getGeometry(), prepared.getFragment()
        };
        for (dev.rlcraft.ice.optimizer.render.optifine.PreprocessedShader stage
            : stages) {
            if (stage == null) continue;
            bytes = Math.addExact(bytes,
                shaderStringHeapBytes(stage.getSource()));
            bytes = Math.addExact(bytes,
                dev.rlcraft.ice.optimizer.memory.RetainedHeap.referenceArray(
                    stage.getDependencies().size()));
            for (String dependency : stage.getDependencies()) {
                bytes = Math.addExact(bytes,
                    shaderStringHeapBytes(dependency));
            }
        }
        bytes = Math.addExact(bytes, Math.multiplyExact(64L,
            (long) prepared.getProperties().size()));
        for (Map.Entry<String, String> entry
            : prepared.getProperties().asMap().entrySet()) {
            bytes = Math.addExact(bytes,
                shaderStringHeapBytes(entry.getKey()));
            bytes = Math.addExact(bytes,
                shaderStringHeapBytes(entry.getValue()));
        }
        return bytes;
    }

    private static long shaderStringHeapBytes(String value) {
        if (value == null) return 0L;
        long characters = Math.multiplyExact((long) value.length(), 2L);
        // Charge retained char[] payload/header and a conservative String/
        // reference envelope, independent of Compact Strings.
        return Math.addExact(48L, characters);
    }

    private void configureBackends() {
        backends.clear();
        backends.putAll(createBackends(capabilities, combinedGeneration()));
    }

    private EnumMap<OptimizationModule, AdaptiveBackendController> createBackends(
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

    private void prepareRuntimeBreakers(long generation) {
        for (OptimizationModule module : OptimizationModule.values()) {
            if (!module.isRuntimeManagedRenderer()) continue;
            ModuleCircuitBreaker breaker = OptimizerRegistry.breaker(module);
            if (breaker != null) breaker.beginRuntimeGeneration(generation);
        }
    }

    /** Publishes the fully-created graph in one render-thread transaction. */
    private void publish(ComponentGraph graph) {
        if (graph == null || activeGraph != null) {
            throw new IllegalStateException("modern component graph publication");
        }
        componentContextGeneration = graph.contextGeneration;
        threadGuard = graph.threadGuard;
        stateMirror = graph.stateMirror;
        coordinator = graph.coordinator;
        legacyIsland = graph.legacyIsland;
        profiler = graph.profiler;
        resources = graph.resources;
        terrainArena = graph.terrainArena;
        modelMeshes = graph.modelMeshes;
        particles = graph.particles;
        particleRenderer = graph.particleRenderer;
        fbpPacketRenderer = graph.fbpPacketRenderer;
        textureUploads = graph.textureUploads;
        spriteVisibility = graph.spriteVisibility;
        animatedTextures = graph.animatedTextures;
        hud = graph.hud;
        hudRenderer = graph.hudRenderer;
        fonts = graph.fonts;
        certifiedDrawSites = graph.certifiedDrawSites;
        shaders = graph.shaders;
        shaderPipeline = graph.shaderPipeline;
        shaderCompiler = graph.shaderCompiler;
        shaderInstaller = graph.shaderInstaller;
        shaderImageCertification = graph.shaderImageCertification;
        shaderActivation = graph.shaderActivation;
        shaderBackendSelector = graph.shaderBackendSelector;
        hzbHistory = graph.hzbHistory;
        depthHistory = graph.depthHistory;
        capabilities = graph.capabilities;
        glStateQueryWorkspace = graph.glStateQueryWorkspace;
        backends.clear();
        backends.putAll(graph.backends);
        optifineProgramState = null;
        clearShaderBindings();
        optifineRegionObservedGeneration = 0L;
        animatedAtlasTextureId = 0;
        openVisibilityFrame = 0L;
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
        // Publish ownership last. Any preceding assignment failure is rolled
        // back by ensureInitialized before the candidate graph is disposed.
        activeGraph = graph;
    }

    private void disposeActiveGraph(boolean contextValid, long contextGeneration) {
        if (!contextValid) {
            abandonActiveShaderInterval();
        } else if (activeNativeShaderBinding != null) {
            throw new IllegalStateException(
                "cannot dispose a graph with an active native shader");
        }
        ComponentGraph graph = activeGraph;
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
        try { dispose(graph, contextValid, contextGeneration); }
        catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        if (failure != null) rethrowRuntimeFailure(failure);
    }

    private void clearPublishedGraph() {
        threadGuard = null;
        stateMirror = null;
        coordinator = null;
        legacyIsland = null;
        profiler = null;
        resources = null;
        terrainArena = null;
        modelMeshes = null;
        particles = null;
        particleRenderer = null;
        fbpPacketRenderer = null;
        textureUploads = null;
        spriteVisibility = null;
        animatedTextures = null;
        hud = null;
        hudRenderer = null;
        fonts = null;
        certifiedDrawSites = null;
        shaders = null;
        shaderPipeline = null;
        shaderCompiler = null;
        shaderInstaller = null;
        shaderImageCertification = null;
        shaderActivation = null;
        shaderBackendSelector = null;
        hzbHistory = null;
        depthHistory = null;
        capabilities = null;
        glStateQueryWorkspace = null;
        optifineProgramState = null;
        clearShaderBindings();
        optifineRegionObservedGeneration = 0L;
        currentStamp = null;
        frameGpuScope = null;
        componentContextGeneration = 0L;
        animatedAtlasTextureId = 0;
        openVisibilityFrame = 0L;
        backends.clear();
    }

    private static void dispose(final ComponentGraph graph,
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

    private static Throwable cleanup(Throwable priorFailure, String component,
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

    static Throwable cleanupForTest(Throwable priorFailure, Runnable action) {
        return cleanup(priorFailure, "fault-injection", action);
    }

    private static Throwable appendRuntimeFailure(Throwable first,
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

    private static void rethrowRuntimeFailure(Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("modern renderer lifecycle failed", failure);
    }

    private void reportInstrumentationFailure(Throwable operationFailure,
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

    private static boolean sameCurrentContext(ContextCapabilities expected) {
        if (expected == null) return false;
        try { return GLContext.getCapabilities() == expected; }
        catch (Throwable unavailable) {
            FatalErrors.rethrowIfFatal(unavailable);
            return false;
        }
    }

    /** One startup/generation probe; ordinary frames use only CoreMod-published state. */
    private void initializeTrackedGlState() {
        initializeTrackedGlState(glStateQueryWorkspace);
    }

    private void initializeTrackedGlState(GlStateQueryWorkspace workspace) {
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

    private void addBackend(
                            EnumMap<OptimizationModule, AdaptiveBackendController> target,
                            CapabilityReport report, long generation,
                            OptimizationModule module,
                            EnumSet<ModernCapability> required) {
        ModuleCircuitBreaker breaker = OptimizerRegistry.breaker(module);
        if (breaker == null || !OptimizerRegistry.isOperational(module)) return;
        AdaptiveBackendController controller = new AdaptiveBackendController(breaker, required);
        controller.begin(generation);
        controller.capabilityResult(report);
        target.put(module, controller);
    }

    private void applyPendingInvalidations() {
        int flags = pendingInvalidations.getAndSet(0);
        if (flags == 0) return;
        boolean contextLost = (flags & INVALIDATE_CONTEXT) != 0;
        boolean modelMeshesInvalidated = invalidatesModelMeshes(flags);
        Throwable failure = null;
        if (!contextLost && initialized && threadGuard != null
            && threadGuard.isRenderThread()) {
            try { flushBatches(); }
            catch (Throwable error) {
                failure = appendRuntimeFailure(failure, error);
            }
            try { finishActiveShaderInterval(false, true); }
            catch (Throwable error) {
                failure = appendRuntimeFailure(failure, error);
            }
            if (activeNativeShaderBinding != null) {
                // Preserve both the binding evidence and installer ownership.
                // The next safe boundary retries restoration before any graph
                // mutation; context loss is handled separately by abandon.
                pendingInvalidations.getAndUpdate(value -> value | flags);
                failure = appendRuntimeFailure(failure,
                    new IllegalStateException(
                        "native shader restoration blocked invalidation"));
                currentStamp = null;
                frameGpuScope = null;
                rethrowRuntimeFailure(failure);
            }
        } else {
            abandonActiveShaderInterval();
        }
        try { clearPendingShaderCandidates(); }
        catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        try { clearShaderBindings(); }
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
            long abandonedGeneration = componentContextGeneration > 0L
                ? componentContextGeneration : lostContextGeneration;
            try { disposeActiveGraph(false, abandonedGeneration); }
            catch (Throwable error) {
                failure = appendRuntimeFailure(failure, error);
            }
            initialized = false;
            currentStamp = null;
            frameGpuScope = null;
            hzbGpuSamples.clear();
            backendGpuSamples.clear();
            lastValidationFrames.clear();
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
        try { if (certifiedDrawSites != null) certifiedDrawSites.invalidate(); }
        catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        try { if (shaderInstaller != null) shaderInstaller.reset(true); }
        catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        try { if (shaders != null) shaders.invalidate(); }
        catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        optifineProgramState = null;
        optifineRegionObservedGeneration = 0L;
        try { if (hzbHistory != null) hzbHistory.invalidate(); }
        catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        hzbGpuSamples.clear();
        backendGpuSamples.clear();
        lastValidationFrames.clear();
        if (modelMeshesInvalidated) genericModelVboValidated = false;
        try { if (depthHistory != null) depthHistory.reset(true); }
        catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        try {
            if (animatedTextures != null) animatedTextures.reset(true,
                epochs.currentGlContextGeneration());
        } catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        try {
            if (hudRenderer != null) hudRenderer.reset(true,
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
        try { if (spriteVisibility != null) spriteVisibility.invalidate(); }
        catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        animatedAtlasTextureId = 0;
        try { if (fonts != null) fonts.invalidate(); }
        catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        if (arenaGeneration == Long.MAX_VALUE) {
            failure = appendRuntimeFailure(failure,
                new IllegalStateException("terrain arena generation exhausted"));
        } else {
            long nextArenaGeneration = ++arenaGeneration;
            try {
                if (terrainArena != null) {
                    terrainArena.reset(nextArenaGeneration, true);
                }
            } catch (Throwable error) {
                failure = appendRuntimeFailure(failure, error);
            }
        }
        if (modelMeshesInvalidated) {
            try { if (modelMeshes != null) modelMeshes.reset(true); }
            catch (Throwable error) {
                failure = appendRuntimeFailure(failure, error);
            }
        }
        try {
            if (particleRenderer != null) particleRenderer.reset(true,
                epochs.currentResourceGeneration(),
                epochs.currentGlContextGeneration());
        } catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        try {
            if (fbpPacketRenderer != null) fbpPacketRenderer.reset(true,
                epochs.currentResourceGeneration(),
                epochs.currentGlContextGeneration());
        } catch (Throwable error) {
            failure = appendRuntimeFailure(failure, error);
        }
        if (failure == null) try {
            prepareRuntimeBreakers(combinedGeneration());
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
            frameGpuScope = null;
            rethrowRuntimeFailure(failure);
        }
    }

    private void flushBatches() {
        if (particles != null) particles.discardAtBarrier();
        if (particleRenderer != null && particleRenderer.size() > 0) {
            particleRenderer.discard();
        }
        if (hudRenderer != null && hudRenderer.hasCommands()) flushHudStream();
        if (hud != null) hud.discardAtBarrier();
    }

    private long beginLegacyBoundary(String reason,
                                     boolean coordinatorBarrier) {
        if (!initialized || legacyIsland == null || threadGuard == null
            || !threadGuard.isRenderThread()) return 0L;
        boolean islandOperational = OptimizerRegistry.isOperational(
            OptimizationModule.LEGACY_GL_ISLAND);
        try {
            // A certified OptiFine candidate is an internal implementation
            // detail and must never leak into an observing callback.
            finishActiveShaderInterval(true, true);
            long token;
            if (islandOperational) {
                token = legacyIsland.enter();
            } else {
                // A fused island still remains an ordering barrier. Dropping
                // the wrapper must never let buffered modern work cross an
                // observing Forge/mod callback.
                flushBatches();
                if (stateMirror != null) stateMirror.invalidateAll();
                token = 0L;
            }
            if (coordinatorBarrier && currentStamp != null && coordinator != null) {
                try {
                    coordinator.observableBarrier(reason == null
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
            if (stateMirror != null) stateMirror.invalidateAll();
            EarlyGlStateTracker.invalidate();
            EarlyMatrixStateTracker.invalidate();
            return 0L;
        }
    }

    private void endLegacyBoundary(long token, Throwable originalError) {
        Throwable failure = originalError;
        try {
            if (token != 0L && legacyIsland != null) {
                legacyIsland.exit(token, originalError);
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

    private boolean insideLegacyIsland() {
        return legacyIsland != null && legacyIsland.isInside();
    }

    private void drainTextureCounters() {
        if (profiler == null || currentStamp == null) return;
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
        if (commands > 0L) profiler.addCounter(currentStamp,
            RenderPass.ANIMATED_TEXTURE_UPLOAD, RenderBackendId.ICE_NATIVE,
            RenderCounter.UPLOAD_COMMAND, commands);
        if (bytes > 0L) profiler.addCounter(currentStamp,
            RenderPass.ANIMATED_TEXTURE_UPLOAD, RenderBackendId.ICE_NATIVE,
            RenderCounter.TEXTURE_UPLOAD_BYTES, bytes);
        if (busy > 0L) profiler.addCounter(currentStamp,
            RenderPass.ANIMATED_TEXTURE_UPLOAD, RenderBackendId.ICE_NATIVE,
            RenderCounter.FENCE_BUSY, busy);
        if (fallbacks > 0L) profiler.addCounter(currentStamp,
            RenderPass.ANIMATED_TEXTURE_UPLOAD, RenderBackendId.LEGACY,
            RenderCounter.LEGACY_FALLBACK, fallbacks);
        if (visibilityDeferred > 0L) profiler.addCounter(currentStamp,
            RenderPass.ANIMATED_TEXTURE_UPLOAD, RenderBackendId.ICE_NATIVE,
            RenderCounter.TEXTURE_VISIBILITY_DEFERRED, visibilityDeferred);
        if (visibilityDeferredBytes > 0L) profiler.addCounter(currentStamp,
            RenderPass.ANIMATED_TEXTURE_UPLOAD, RenderBackendId.ICE_NATIVE,
            RenderCounter.TEXTURE_VISIBILITY_DEFERRED_BYTES,
            visibilityDeferredBytes);
        if (visibilityCaughtUp > 0L) profiler.addCounter(currentStamp,
            RenderPass.ANIMATED_TEXTURE_UPLOAD, RenderBackendId.ICE_NATIVE,
            RenderCounter.TEXTURE_VISIBILITY_CAUGHT_UP, visibilityCaughtUp);
        if (visibilityCaughtUpBytes > 0L) profiler.addCounter(currentStamp,
            RenderPass.ANIMATED_TEXTURE_UPLOAD, RenderBackendId.ICE_NATIVE,
            RenderCounter.TEXTURE_VISIBILITY_CAUGHT_UP_BYTES,
            visibilityCaughtUpBytes);
        if (visibilityUnknown > 0L) profiler.addCounter(currentStamp,
            RenderPass.ANIMATED_TEXTURE_UPLOAD, RenderBackendId.LEGACY,
            RenderCounter.TEXTURE_VISIBILITY_UNKNOWN_FRAME, visibilityUnknown);
    }

    private RenderProfileKey beginHzbGpuSample(SceneFingerprint scene,
                                               MeasurementArm arm) {
        RenderBackendId backend = arm == MeasurementArm.MODERN
            ? RenderBackendId.ICE_NATIVE : RenderBackendId.LEGACY;
        RenderProfileKey key = new RenderProfileKey(currentStamp,
            RenderPass.MAIN_SOLID, backend);
        hzbGpuSamples.put(key, new HzbGpuSample(scene, arm,
            arm == MeasurementArm.MODERN ? 2 : 1));
        while (hzbGpuSamples.size() > 64) {
            Iterator<RenderProfileKey> iterator = hzbGpuSamples.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
        return key;
    }

    private RenderProfileKey findCurrentHzbKey(MeasurementArm arm) {
        if (currentStamp == null) return null;
        RenderProfileKey key = new RenderProfileKey(currentStamp,
            RenderPass.MAIN_SOLID, arm == MeasurementArm.MODERN
                ? RenderBackendId.ICE_NATIVE : RenderBackendId.LEGACY);
        return hzbGpuSamples.containsKey(key) ? key : null;
    }

    private void invalidateCurrentHzbGpuSample(MeasurementArm arm) {
        invalidateHzbGpuSample(findCurrentHzbKey(arm));
    }

    private void invalidateHzbGpuSample(RenderProfileKey key) {
        if (key != null) hzbGpuSamples.remove(key);
    }

    private void addHzbCpuNanos(RenderProfileKey key, long nanos) {
        HzbGpuSample sample = hzbGpuSamples.get(key);
        if (sample == null) return;
        sample.cpuNanos = safeAdd(sample.cpuNanos, nanos);
        sample.cpuReady = true;
        finishHzbGpuSample(key, sample);
    }

    private void completeHzbGpuSample(RenderProfileKey key, long elapsedNanos) {
        HzbGpuSample sample = hzbGpuSamples.get(key);
        if (sample == null) return;
        sample.gpuNanos = safeAdd(sample.gpuNanos, Math.max(0L, elapsedNanos));
        sample.completedGpuScopes++;
        finishHzbGpuSample(key, sample);
    }

    private void completeBackendGpuSample(RenderProfileKey key, long elapsedNanos) {
        BackendGpuSample sample = backendGpuSamples.get(key);
        if (sample == null) return;
        sample.gpuNanos = Math.max(0L, elapsedNanos);
        sample.gpuReady = true;
        finishBackendGpuSample(key, sample);
    }

    private void finishBackendGpuSample(RenderProfileKey key, BackendGpuSample sample) {
        if (!sample.cpuReady || !sample.gpuReady) return;
        backendGpuSamples.remove(key);
        AdaptiveBackendController backend = backends.get(sample.module);
        if (backend == null) return;
        long pipeline = Math.max(1L, Math.max(sample.cpuNanos, sample.gpuNanos));
        if (backend.lifecycleState() == BackendLifecycleState.PAIRED_MEASURE) {
            backend.recordMeasurement(sample.scene, sample.arm, pipeline, sample.stable);
        } else if (backend.lifecycleState() == BackendLifecycleState.REGRESSION_MONITOR
            && sample.arm == MeasurementArm.MODERN) {
            backend.recordRegressionSample(sample.scene, pipeline, sample.stable);
        }
    }

    private void finishHzbGpuSample(RenderProfileKey key, HzbGpuSample sample) {
        if (!sample.cpuReady || sample.completedGpuScopes < sample.expectedGpuScopes) return;
        hzbGpuSamples.remove(key);
        long pipelineNanos = Math.max(1L, Math.max(sample.cpuNanos, sample.gpuNanos));
        AdaptiveBackendController visibility = backends.get(
            OptimizationModule.MODERN_VISIBILITY_HZB);
        if (visibility == null) return;
        if (visibility.lifecycleState() == BackendLifecycleState.PAIRED_MEASURE) {
            visibility.recordMeasurement(sample.scene, sample.arm, pipelineNanos, true);
        } else if (visibility.lifecycleState()
            == BackendLifecycleState.REGRESSION_MONITOR
            && sample.arm == MeasurementArm.MODERN) {
            visibility.recordRegressionSample(sample.scene, pipelineNanos, true);
        }
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
        pendingTerrainMeasurementScene = null;
        pendingTerrainMeasurementStarted = 0L;
        pendingTerrainMeasurementStable = false;
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

    /**
     * Builds immutable diagnostics on the render thread.  Report threads only
     * read the two volatile strings published by ModernRendererDiagnostics;
     * they never traverse controllers, GL resources, or arena ownership maps.
     */
    private void publishRendererDiagnostics(boolean force) {
        long frame = currentStamp == null ? -1L : currentStamp.getFrameId();
        if (!force && frame < 0L) return;
        if (!force && lastDiagnosticsFrame != Long.MIN_VALUE
            && frame >= lastDiagnosticsFrame
            && frame - lastDiagnosticsFrame < DIAGNOSTICS_FRAME_INTERVAL) return;
        lastDiagnosticsFrame = frame;
        try {
            StringBuilder report = new StringBuilder(8192);
            report.append("ICE modern renderer diagnostics\n");
            report.append("format=1\n");
            report.append("published_frame=").append(frame).append('\n');
            report.append("renderer_generation=").append(combinedGeneration())
                .append('\n');
            report.append("initialized=").append(initialized).append('\n');
            report.append("detail=").append(diagnosticText(detail, 1024))
                .append('\n');
            report.append("shader_pack_state_known=")
                .append(shaderPackStateKnown).append('\n');
            report.append("shader_pack_active=").append(shaderPackActive)
                .append('\n');
            ShaderProgramBinding diagnosticShader = activeNativeShaderBinding;
            report.append("shader_native_binding_active=")
                .append(diagnosticShader != null).append('\n');
            report.append("shader_terrain_layout_certified=")
                .append(diagnosticShader != null
                    && diagnosticShader.terrainLayoutCertified).append('\n');
            report.append("shader_terrain_layout_detail=")
                .append(diagnosticText(diagnosticShader == null ? ""
                    : diagnosticShader.terrainLayoutDetail, 1024)).append('\n');
            report.append("shader_terrain_gate=")
                .append(isShaderPackSafeForTerrainArena()).append('\n');
            CacheBudgetStatus budgetStatus = budget.snapshot();
            report.append("optimizer_budget_heap_used_bytes=")
                .append(budgetStatus.getHeapUsed()).append('\n');
            report.append("optimizer_budget_heap_limit_bytes=")
                .append(budgetStatus.getHeapLimit()).append('\n');
            report.append("optimizer_budget_direct_used_bytes=")
                .append(budgetStatus.getDirectUsed()).append('\n');
            report.append("optimizer_budget_direct_limit_bytes=")
                .append(budgetStatus.getDirectLimit()).append('\n');
            report.append("optimizer_budget_gpu_used_bytes=")
                .append(budgetStatus.getGpuUsed()).append('\n');
            report.append("optimizer_budget_gpu_limit_bytes=")
                .append(budgetStatus.getGpuLimit()).append('\n');
            report.append("optimizer_budget_rejected_reservations=")
                .append(budgetStatus.getRejectedReservations()).append('\n');
            if (resources != null) {
                ResourceLedgerStatus ledgerStatus = resources.snapshot();
                report.append("resource_ledger_live=")
                    .append(ledgerStatus.getLive()).append('\n');
                report.append("resource_ledger_retired=")
                    .append(ledgerStatus.getRetired()).append('\n');
                report.append("resource_ledger_live_bytes=")
                    .append(ledgerStatus.getLiveBytes()).append('\n');
                report.append("resource_ledger_created=")
                    .append(ledgerStatus.getCreated()).append('\n');
                report.append("resource_ledger_destroyed=")
                    .append(ledgerStatus.getDestroyed()).append('\n');
                report.append("resource_ledger_abandoned=")
                    .append(ledgerStatus.getAbandoned()).append('\n');
                report.append("resource_ledger_rejected=")
                    .append(ledgerStatus.getRejected()).append('\n');
                report.append("resource_ledger_fence_timeouts=")
                    .append(ledgerStatus.getTimedOut()).append('\n');
            }
            appendRenderWorkDiagnostics(report);
            report.append("terrain_upload_arena=").append(terrainArenaUploads)
                .append('\n');
            report.append("terrain_upload_legacy=")
                .append(terrainLegacyUploads.get())
                .append('\n');
            report.append("terrain_shadow_upload_attempts=")
                .append(terrainShadowUploadAttempts).append('\n');
            report.append("terrain_shadow_uploads=")
                .append(terrainShadowUploads).append('\n');
            report.append("terrain_shadow_uploaded_bytes=")
                .append(terrainShadowUploadedBytes).append('\n');
            report.append("terrain_shadow_budget_rejects=")
                .append(terrainShadowBudgetRejects).append('\n');
            report.append("terrain_shadow_qualification_skips=")
                .append(terrainShadowQualificationSkips).append('\n');
            report.append("terrain_measurement_coverage_rejects=")
                .append(terrainMeasurementCoverageRejects).append('\n');
            report.append("terrain_measurement_profile_warmups=")
                .append(terrainMeasurementProfileWarmups).append('\n');
            report.append("terrain_last_visible_meshes=")
                .append(terrainLastVisibleMeshes).append('\n');
            report.append("terrain_last_owned_meshes=")
                .append(terrainLastOwnedMeshes).append('\n');
            report.append("terrain_last_ownership_percent=")
                .append(terrainLastVisibleMeshes <= 0 ? 0.0D
                    : 100.0D * terrainLastOwnedMeshes
                        / terrainLastVisibleMeshes)
                .append('\n');
            report.append("terrain_last_region_runs=")
                .append(terrainLastRegionRuns).append('\n');
            report.append("terrain_draw_arena_total=").append(terrainArenaDraws)
                .append('\n');
            report.append("terrain_draw_legacy=").append(terrainLegacyDraws)
                .append('\n');
            report.append("terrain_draw_arena_unbatched=")
                .append(terrainArenaUnbatchedDraws).append('\n');
            report.append("terrain_draw_arena_multi=")
                .append(terrainArenaMultiDraws).append('\n');
            report.append("terrain_draw_arena_uncertain=")
                .append(terrainArenaUncertainDraws).append('\n');
            report.append("terrain_mdi_submissions=")
                .append(terrainMdiSubmissions).append('\n');
            report.append("terrain_indirect_commands=")
                .append(terrainIndirectCommands).append('\n');
            if (terrainArena != null) {
                ArenaStatus arena = terrainArena.allocatorStatus();
                report.append("terrain_arena_owned_meshes=")
                    .append(terrainArena.getOwnedMeshes()).append('\n');
                report.append("terrain_arena_shadow_owned_meshes=")
                    .append(terrainArena.getShadowOwnedMeshes()).append('\n');
                report.append("terrain_arena_shadow_owned_bytes=")
                    .append(terrainArena.getShadowOwnedBytes()).append('\n');
                report.append("terrain_arena_used_bytes=")
                    .append(arena.getUsedBytes()).append('\n');
                report.append("terrain_arena_committed_bytes=")
                    .append(arena.getCommittedBytes()).append('\n');
                report.append("terrain_arena_live_allocations=")
                    .append(arena.getAllocations()).append('\n');
                report.append("terrain_arena_free_segments=")
                    .append(arena.getFreeSegments()).append('\n');
                report.append("terrain_arena_rejected_allocations=")
                    .append(arena.getRejected()).append('\n');
                report.append("terrain_arena_invalid_frees=")
                    .append(arena.getInvalidFrees()).append('\n');
                report.append("terrain_arena_rejected_uploads=")
                    .append(terrainArena.getRejectedUploads()).append('\n');
                report.append("terrain_arena_invalid_payloads=")
                    .append(terrainArena.getInvalidPayloads()).append('\n');
                report.append("terrain_arena_uploaded_bytes=")
                    .append(terrainArena.getUploadedBytes()).append('\n');
                report.append("terrain_arena_persistent_uploads=")
                    .append(terrainArena.getPersistentUploads()).append('\n');
                report.append("terrain_arena_subdata_uploads=")
                    .append(terrainArena.getSubDataUploads()).append('\n');
                report.append("terrain_arena_raw_draw_calls=")
                    .append(terrainArena.getDrawCalls()).append('\n');
                report.append("terrain_arena_raw_multi_draw_calls=")
                    .append(terrainArena.getMultiDrawCalls()).append('\n');
                report.append("terrain_arena_raw_indirect_draw_calls=")
                    .append(terrainArena.getIndirectDrawCalls()).append('\n');
                report.append("terrain_arena_indirect_fallbacks=")
                    .append(terrainArena.getIndirectFallbacks()).append('\n');
                report.append("terrain_arena_multi_draw_capacity_fallbacks=")
                    .append(terrainArena.getMultiDrawCapacityFallbacks())
                    .append('\n');
                report.append("terrain_arena_indirect_unknown_bindings=")
                    .append(terrainArena.getIndirectUnknownBindings())
                    .append('\n');
                report.append("terrain_arena_indirect_binding_reauthentications=")
                    .append(terrainArena.getIndirectBindingReauthentications())
                    .append('\n');
                report.append("terrain_arena_indirect_binding_query_failures=")
                    .append(terrainArena.getIndirectBindingQueryFailures())
                    .append('\n');
                report.append("terrain_arena_indirect_binding_query_suppressions=")
                    .append(terrainArena.getIndirectBindingQuerySuppressions())
                    .append('\n');
                for (TerrainIndirectReason reason
                    : TerrainIndirectReason.values()) {
                    report.append("terrain_arena_indirect_reason.")
                        .append(reason.name()).append('=')
                        .append(terrainArena.getIndirectReason(reason))
                        .append('\n');
                }
                report.append("terrain_arena_busy_fences=")
                    .append(terrainArena.getBusyFences()).append('\n');
                report.append("terrain_chunk_animator_compatibility_draws=")
                    .append(terrainArena.getChunkAnimatorCompatibilityDraws())
                    .append('\n');
            }
            report.append("chunk_animator_probe_status=")
                .append(ChunkAnimatorRenderBridge.status()).append('\n');
            report.append("chunk_animator_probe_runtime_failures=")
                .append(ChunkAnimatorRenderBridge.runtimeFailures()).append('\n');
            report.append("chunk_animator_probe_failure_exception=")
                .append(diagnosticText(
                    ChunkAnimatorRenderBridge.failureType(), 256)).append('\n');
            report.append("chunk_animator_probe_failure_message=")
                .append(diagnosticText(
                    ChunkAnimatorRenderBridge.failureMessage(), 1024))
                .append('\n');
            if (depthHistory != null) {
                report.append("hzb_capture_attempts=")
                    .append(hzbCaptureAttempts).append('\n');
                report.append("hzb_state_reauthentication_attempts=")
                    .append(hzbStateReauthenticationAttempts).append('\n');
                report.append("hzb_state_reauthentications=")
                    .append(hzbStateReauthentications).append('\n');
                report.append("hzb_state_reauthentication_failures=")
                    .append(hzbStateReauthenticationFailures).append('\n');
                report.append("hzb_state_reauthentication_suppressions=")
                    .append(hzbStateReauthenticationSuppressions).append('\n');
                report.append("hzb_state_last_failure_exception=")
                    .append(diagnosticText(lastHzbStateFailureType, 256))
                    .append('\n');
                report.append("hzb_state_last_failure_message=")
                    .append(diagnosticText(lastHzbStateFailureMessage, 1024))
                    .append('\n');
                report.append("hzb_state_failed_invalidation=")
                    .append(failedHzbStateInvalidation).append('\n');
                report.append("hzb_state_failed_frame=")
                    .append(failedHzbStateFrame).append('\n');
                for (HzbCaptureReason reason : HzbCaptureReason.values()) {
                    report.append("hzb_capture_reason.")
                        .append(reason.name()).append('=')
                        .append(hzbCaptureReasons[reason.ordinal()])
                        .append('\n');
                }
                report.append("hzb_captures=").append(depthHistory.getCaptures())
                    .append('\n');
                report.append("hzb_published=").append(depthHistory.getPublished())
                    .append('\n');
                report.append("hzb_oracle_validated_publications=")
                    .append(depthHistory.getOracleValidatedPublications())
                    .append('\n');
                report.append("hzb_view_gate.INVALID_INPUT=")
                    .append(depthHistory.getViewGateInvalidInput())
                    .append('\n');
                report.append("hzb_view_gate.FIRST_OBSERVATION=")
                    .append(depthHistory.getViewGateFirstObservation())
                    .append('\n');
                report.append("hzb_view_gate.VIEW_CHANGED=")
                    .append(depthHistory.getViewGateViewChanged())
                    .append('\n');
                report.append("hzb_view_gate.DUPLICATE_FRAME=")
                    .append(depthHistory.getViewGateDuplicateFrame())
                    .append('\n');
                report.append("hzb_view_gate.FRAME_GAP=")
                    .append(depthHistory.getViewGateFrameGap())
                    .append('\n');
                report.append("hzb_view_gate.CAPTURE_ALLOWED=")
                    .append(depthHistory.getViewGateCaptureAllowed())
                    .append('\n');
                report.append("hzb_deferred_polls=")
                    .append(depthHistory.getDeferredPolls()).append('\n');
                report.append("hzb_tested=").append(depthHistory.getTested())
                    .append('\n');
                report.append("hzb_occluded=").append(depthHistory.getOccluded())
                    .append('\n');
                report.append("hzb_raw_occluded=")
                    .append(depthHistory.getRawOccluded()).append('\n');
                report.append("hzb_confirmation_deferrals=")
                    .append(depthHistory.getConfirmationDeferrals()).append('\n');
                report.append("hzb_chunk_animation_bypasses=")
                    .append(depthHistory.getAnimationBypasses()).append('\n');
                report.append("hzb_confirmation_capacity_resets=")
                    .append(depthHistory.getOcclusionGateCapacityResets())
                    .append('\n');
                report.append("hzb_confirmation_capacity=")
                    .append(depthHistory.getOcclusionGateCapacity())
                    .append('\n');
                report.append("hzb_confirmation_budget_reductions=")
                    .append(depthHistory
                        .getOcclusionGateBudgetCapacityReductions())
                    .append('\n');
                report.append("hzb_filter_rollbacks=")
                    .append(depthHistory.getFilterRollbacks()).append('\n');
                report.append("hzb_filter_transaction_deferrals=")
                    .append(depthHistory.getFilterTransactionDeferrals())
                    .append('\n');
                report.append("hzb_filter_transaction_failures=")
                    .append(depthHistory.getFilterTransactionFailures())
                    .append('\n');
                report.append("hzb_busy=").append(depthHistory.getBusy())
                    .append('\n');
                report.append("hzb_rejected=").append(depthHistory.getRejected())
                    .append('\n');
                report.append("hzb_scene_invalidations=")
                    .append(depthHistory.getSceneInvalidations()).append('\n');
                report.append("hzb_stale_completions=")
                    .append(depthHistory.getStaleCompletions()).append('\n');
                report.append("hzb_geometry_changes=")
                    .append(depthHistory.getGeometryChanges()).append('\n');
                report.append("hzb_geometry_changes_coalesced=")
                    .append(depthHistory.getCoalescedGeometryChanges())
                    .append('\n');
            }
            report.append("particle_backend_failures=")
                .append(particleBackendFailures).append('\n');
            report.append("particle_last_failure_exception=")
                .append(diagnosticText(lastParticleFailureType, 256))
                .append('\n');
            report.append("particle_last_failure_message=")
                .append(diagnosticText(lastParticleFailureMessage, 1024))
                .append('\n');
            report.append("particle_last_root_failure_exception=")
                .append(diagnosticText(lastParticleRootFailureType, 256))
                .append('\n');
            report.append("particle_last_root_failure_message=")
                .append(diagnosticText(lastParticleRootFailureMessage, 1024))
                .append('\n');
            report.append("model_mesh_cache_entries=")
                .append(modelMeshes == null ? 0 : modelMeshes.size()).append('\n');
            report.append("model_mesh_generic_validated=")
                .append(genericModelVboValidated).append('\n');
            ModelMeshCaptureBridge.Diagnostics modelCapture =
                ModelMeshCaptureBridge.diagnostics();
            report.append("model_mesh_capture_begins=")
                .append(modelCapture.getCaptureBegins()).append('\n');
            report.append("model_mesh_capture_enabled=")
                .append(modelCapture.getEnabledCaptures()).append('\n');
            report.append("model_mesh_capture_early=")
                .append(modelCapture.getEarlyCaptures()).append('\n');
            report.append("model_mesh_capture_disabled=")
                .append(modelCapture.getDisabledCaptures()).append('\n');
            report.append("model_mesh_capture_quads=")
                .append(modelCapture.getCapturedQuads()).append('\n');
            report.append("model_mesh_capture_rejected=")
                .append(modelCapture.getRejectedCaptures()).append('\n');
            report.append("model_mesh_capture_completed=")
                .append(modelCapture.getCompletedCaptures()).append('\n');
            report.append("model_mesh_capture_immediate_publications=")
                .append(modelCapture.getImmediatePublications()).append('\n');
            report.append("model_mesh_capture_deferred_publications=")
                .append(modelCapture.getDeferredPublications()).append('\n');
            report.append("model_mesh_capture_late_qualifications=")
                .append(modelCapture.getLateQualifications()).append('\n');
            report.append("model_mesh_capture_stale_drops=")
                .append(modelCapture.getStalePendingDrops()).append('\n');
            report.append("model_mesh_capture_disabled_drops=")
                .append(modelCapture.getDisabledPendingDrops()).append('\n');
            report.append("model_mesh_pending_purge_scans=")
                .append(modelCapture.getPendingPurgeScans()).append('\n');
            report.append("model_mesh_publication_attempts=")
                .append(modelCapture.getPublicationAttempts()).append('\n');
            report.append("model_mesh_publication_failures=")
                .append(modelCapture.getPublicationFailures()).append('\n');
            report.append("model_mesh_publication_retry_suppressions=")
                .append(modelCapture.getPublicationRetrySuppressions())
                .append('\n');
            report.append("model_mesh_publication_drain_cycles=")
                .append(modelCapture.getPublicationDrainCycles()).append('\n');
            report.append("model_mesh_publication_drain_examined=")
                .append(modelCapture.getPublicationDrainExamined()).append('\n');
            report.append("model_mesh_publication_drained=")
                .append(modelCapture.getDrainedPublications()).append('\n');
            report.append("model_mesh_capture_pending=")
                .append(modelCapture.getPendingMeshes()).append('\n');
            report.append("model_mesh_capture_pending_bytes=")
                .append(modelCapture.getPendingBytes()).append('\n');
            report.append("model_mesh_draw_outside_traversal=")
                .append(modelDrawOutsideTraversal).append('\n');
            report.append("model_mesh_state_reauthentication_attempts=")
                .append(modelStateReauthenticationAttempts).append('\n');
            report.append("model_mesh_state_reauthentications=")
                .append(modelStateReauthentications).append('\n');
            report.append("model_mesh_state_reauthentication_failures=")
                .append(modelStateReauthenticationFailures).append('\n');
            report.append("model_mesh_state_reauthentication_suppressions=")
                .append(modelStateReauthenticationSuppressions).append('\n');
            report.append("model_mesh_upload_binding_attempts=")
                .append(modelUploadBindingAttempts).append('\n');
            report.append("model_mesh_upload_binding_recoveries=")
                .append(modelUploadBindingRecoveries).append('\n');
            report.append("model_mesh_upload_binding_failures=")
                .append(modelUploadBindingFailures).append('\n');
            report.append("model_mesh_state_last_failure_stage=")
                .append(diagnosticText(lastModelStateFailureStage, 256))
                .append('\n');
            report.append("model_mesh_state_last_failure_exception=")
                .append(diagnosticText(lastModelStateFailureType, 256))
                .append('\n');
            report.append("model_mesh_state_last_failure_message=")
                .append(diagnosticText(lastModelStateFailureMessage, 1024))
                .append('\n');
            report.append("model_mesh_state_failed_gl_invalidation=")
                .append(failedModelGlInvalidation).append('\n');
            report.append("model_mesh_state_failed_matrix_invalidation=")
                .append(failedModelMatrixInvalidation).append('\n');
            report.append("model_mesh_state_failed_frame=")
                .append(failedModelReauthenticationFrame).append('\n');
            for (int backendIndex = 0; backendIndex < 2; backendIndex++) {
                String backendName = backendIndex == 0 ? "entity" : "tesr";
                for (ModelDrawReason reason : ModelDrawReason.values()) {
                    report.append("model_mesh_draw_reason.")
                        .append(backendName).append('.')
                        .append(reason.name()).append('=')
                        .append(modelDrawReasons[backendIndex][reason.ordinal()])
                        .append('\n');
                }
            }

            ModernCapability[] capabilityValues = ModernCapability.values();
            for (int i = 0; i < capabilityValues.length; i++) {
                ModernCapability capability = capabilityValues[i];
                CapabilityReport.FailureDetail failure = capabilities == null
                    ? null : capabilities.getFailureDetail(capability);
                String prefix = "capability." + capability.name() + ".";
                String status = capabilities != null && capabilities.passed(capability)
                    ? "PASS" : failure == null ? "NOT_TESTED" : "FAIL";
                report.append(prefix).append("status=").append(status).append('\n');
                report.append(prefix).append("detail=")
                    .append(diagnosticText(failure == null ? ""
                        : failure.summary(), 4096)).append('\n');
                report.append(prefix).append("stage=")
                    .append(diagnosticText(failure == null ? ""
                        : failure.getStage(), 256)).append('\n');
                report.append(prefix).append("exception=")
                    .append(diagnosticText(failure == null ? ""
                        : failure.getExceptionType(), 256)).append('\n');
                report.append(prefix).append("gl_state=")
                    .append(diagnosticText(failure == null ? ""
                        : failure.getGlState(), 4096)).append('\n');
                report.append(prefix).append("gl_errors=")
                    .append(diagnosticText(failure == null ? ""
                        : failure.getGlErrors(), 512)).append('\n');
            }

            long fallbackTotal = 0L;
            TerrainFallbackReason topReason = null;
            long topReasonCount = 0L;
            TerrainFallbackReason[] reasons = TerrainFallbackReason.values();
            for (int i = 0; i < reasons.length; i++) {
                long count = terrainFallbackReasons[i];
                fallbackTotal = safeAdd(fallbackTotal, count);
                if (count > topReasonCount) {
                    topReason = reasons[i];
                    topReasonCount = count;
                }
                report.append("terrain_reason.").append(reasons[i].name())
                    .append('=').append(count).append('\n');
            }
            report.append("terrain_reason_total=").append(fallbackTotal)
                .append('\n');

            for (Map.Entry<OptimizationModule, AdaptiveBackendController> entry
                : backends.entrySet()) {
                BackendStatus status = entry.getValue().snapshot();
                String prefix = "backend." + entry.getKey().getId() + ".";
                report.append(prefix).append("state=")
                    .append(status.getState().name()).append('\n');
                report.append(prefix).append("active=")
                    .append(status.isActive()).append('\n');
                report.append(prefix).append("generation=")
                    .append(status.getGeneration()).append('\n');
                report.append(prefix).append("detail=")
                    .append(diagnosticText(status.getDetail(), 512)).append('\n');
                report.append(prefix).append("median_improvement_percent=")
                    .append(diagnosticPercent(status.getMedianImprovement()))
                    .append('\n');
                report.append(prefix).append("p95_regression_percent=")
                    .append(diagnosticPercent(status.getP95Regression()))
                    .append('\n');
                report.append(prefix).append("measurement_samples=")
                    .append(status.getMeasurementSamples()).append('\n');
                report.append(prefix).append("ignored_unstable_samples=")
                    .append(status.getIgnoredUnstableSamples()).append('\n');
                report.append(prefix).append("workload_bucket=")
                    .append(status.getWorkloadBucket()).append('\n');
                report.append(prefix).append("workload_profiles_evaluated=")
                    .append(status.getEvaluatedWorkloadProfiles()).append('\n');
                report.append(prefix).append("workload_retests=")
                    .append(status.getWorkloadRetests()).append('\n');
                report.append(prefix).append("workload_next_retest_frame=")
                    .append(status.getNextRetestFrame()).append('\n');
            }

            StringBuilder summary = new StringBuilder(256);
            summary.append("Terrain U A/L ").append(terrainArenaUploads)
                .append('/').append(terrainLegacyUploads.get())
                .append(" | D A/L ").append(terrainArenaDraws)
                .append('/').append(terrainLegacyDraws)
                .append(" | MDI ").append(terrainMdiSubmissions)
                .append('/').append(terrainIndirectCommands)
                .append(" | FB ");
            if (topReason == null) summary.append("none");
            else summary.append(topReason.name()).append(' ')
                .append(topReasonCount);
            ModernRendererDiagnostics.publish(report.toString(),
                summary.toString());
        } catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            // Diagnostics are observational.  A formatting/reflection-neutral
            // publication failure must not invalidate a rendered frame.
        }
    }

    private void appendRenderWorkDiagnostics(StringBuilder report) {
        if (report == null || profiler == null) return;
        RenderProfilerSnapshot snapshot = profiler.snapshot();
        if (snapshot == null) return;
        report.append("render_profile_dropped_frames=")
            .append(snapshot.getDroppedFrames()).append('\n');
        report.append("render_profile_dropped_gpu_queries=")
            .append(snapshot.getDroppedGpuQueries()).append('\n');
        report.append("render_profile_scope_errors=")
            .append(snapshot.getScopeErrors()).append('\n');
        EnumMap<RenderBackendId, long[]> totals =
            new EnumMap<RenderBackendId, long[]>(RenderBackendId.class);
        for (RenderBackendId backend : RenderBackendId.values()) {
            totals.put(backend, new long[5]);
        }
        for (Map.Entry<RenderProfileKey, PassProfile> entry
            : snapshot.getProfiles().entrySet()) {
            RenderProfileKey key = entry.getKey();
            PassProfile profile = entry.getValue();
            if (key == null || profile == null) continue;
            long[] values = totals.get(key.getBackend());
            if (values == null) continue;
            values[0] = safeAdd(values[0], 1L);
            values[1] = safeAdd(values[1], profile.getCpuInclusiveNanos());
            values[2] = safeAdd(values[2], profile.getCpuExclusiveNanos());
            values[3] = safeAdd(values[3], profile.getGpuNanos());
            if (profile.getGpuNanos() > 0L) {
                values[4] = safeAdd(values[4], 1L);
            }
        }
        for (Map.Entry<RenderBackendId, long[]> entry : totals.entrySet()) {
            String prefix = "render_profile." + entry.getKey().name() + ".";
            long[] values = entry.getValue();
            report.append(prefix).append("profile_keys=")
                .append(values[0]).append('\n');
            report.append(prefix).append("cpu_inclusive_nanos=")
                .append(values[1]).append('\n');
            report.append(prefix).append("cpu_exclusive_nanos=")
                .append(values[2]).append('\n');
            report.append(prefix).append("gpu_nanos=")
                .append(values[3]).append('\n');
            report.append(prefix).append("gpu_profile_keys=")
                .append(values[4]).append('\n');
        }
    }

    private static String diagnosticPercent(double ratio) {
        if (Double.isNaN(ratio) || Double.isInfinite(ratio)) return "n/a";
        return String.format(Locale.ROOT, "%.3f", ratio * 100.0D);
    }

    private static String diagnosticText(String value, int maximum) {
        if (value == null || value.isEmpty() || maximum <= 0) return "";
        int length = Math.min(value.length(), maximum);
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char character = value.charAt(i);
            result.append(character < 32 || character == 127 ? ' ' : character);
        }
        return result.toString();
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
        for (AdaptiveBackendController backend : backends.values()) {
            if (backend.lifecycleState() == BackendLifecycleState.WARMUP) {
                backend.warmupFrame(true);
            }
        }
    }

    private void activateReadyBackends() {
        synchronizeTerrainSubBackends();
        for (AdaptiveBackendController backend : backends.values()) {
            if (backend.lifecycleState() == BackendLifecycleState.MODERN) {
                backend.activateAtSafeBoundary();
            }
        }
    }

    private void synchronizeTerrainSubBackends() {
        AdaptiveBackendController terrain = backends.get(
            OptimizationModule.MODERN_TERRAIN_BACKEND);
        if (terrain == null) return;
        BackendLifecycleState parentState = terrain.lifecycleState();
        if (parentState != BackendLifecycleState.LEGACY
            && parentState != BackendLifecycleState.QUARANTINED) return;
        suspendTerrainChild(OptimizationModule.MODERN_TERRAIN_MDI,
            parentState);
        suspendTerrainChild(
            OptimizationModule.MODERN_TERRAIN_PERSISTENT_MAPPING,
            parentState);
    }

    private void suspendTerrainChild(OptimizationModule module,
                                     BackendLifecycleState parentState) {
        AdaptiveBackendController child = backends.get(module);
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

    private SceneFingerprint sceneFingerprint(int visibleSections) {
        return sceneFingerprint(visibleSections, -1, -1);
    }

    private SceneFingerprint sceneFingerprint(int visibleSections,
                                              int terrainArenaOwned,
                                              int terrainRegionRuns) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.world == null
            || minecraft.getRenderViewEntity() == null || currentStamp == null) return null;
        Entity camera = minecraft.getRenderViewEntity();
        int weather = (minecraft.world.isRaining() ? 1 : 0)
            | (minecraft.world.isThundering() ? 2 : 0);
        return new SceneFingerprint(minecraft.world.provider.getDimension(),
            floorRegion(camera.posX), floorRegion(camera.posY), floorRegion(camera.posZ),
            angleBucket(camera.rotationYaw), angleBucket(camera.rotationPitch),
            visibleSections, minecraft.world.loadedEntityList.size(),
            minecraft.world.loadedTileEntityList.size(),
            minecraft.gameSettings.renderDistanceChunks, minecraft.displayWidth,
            minecraft.displayHeight, weather, currentStamp.getResourceGeneration(),
            currentStamp.getShaderPackGeneration(), terrainArenaOwned,
            terrainRegionRuns);
    }

    private static int floorRegion(double coordinate) {
        if (!Double.isFinite(coordinate)) return 0;
        double value = Math.floor(coordinate / 16.0D);
        if (value <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
        if (value >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) value;
    }

    private static int angleBucket(float angle) {
        if (Float.isNaN(angle) || Float.isInfinite(angle)) return 0;
        return (int) Math.floor(angle / 5.0F);
    }

    private static RenderPass terrainPass(BlockRenderLayer layer) {
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
        CorrelatedRenderProfiler.GpuScope danglingFrameScope = frameGpuScope;
        frameGpuScope = null;
        if (danglingFrameScope != null) {
            try { danglingFrameScope.close(); }
            catch (Throwable cleanupFailure) {
                failure = appendRuntimeFailure(failure, cleanupFailure);
            }
        }
        if (spriteVisibility != null && openVisibilityFrame > 0L) {
            try { spriteVisibility.abortFrame(openVisibilityFrame); }
            catch (Throwable cleanupFailure) {
                failure = appendRuntimeFailure(failure, cleanupFailure);
            }
            openVisibilityFrame = 0L;
        }
        if (coordinator != null && threadGuard != null && threadGuard.isRenderThread()) {
            try { coordinator.resetAfterFailure(failure); }
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
        EarlyMatrixStateTracker.invalidate();
        detail = "帧协调异常，当前帧已回退 Legacy："
            + failure.getClass().getSimpleName();
        FatalErrors.rethrowIfFatal(failure);
    }

    private long combinedGeneration() {
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

    private enum TerrainFallbackReason {
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

    private enum HzbCaptureReason {
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

    private enum ModelDrawReason {
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

    private static final class ShaderProgramBinding {
        private final Object programIdentity;
        private final int legacyProgram;
        private final int candidateProgram;
        private PreparedShaderPermutation prepared;
        private CacheBudget.Reservation preparedReservation;
        private final ShaderPermutationKey key;
        private final long resourceGeneration;
        private final long contextGeneration;
        private final long shaderGeneration;
        private final boolean terrainLayoutCertified;
        private final String terrainLayoutDetail;
        private OptifineProgramState certifiedState;
        private boolean validationQueued;

        private ShaderProgramBinding(Object programIdentity, int legacyProgram,
                                     int candidateProgram,
                                     PreparedShaderPermutation prepared,
                                     long resourceGeneration,
                                     long contextGeneration,
                                     long shaderGeneration,
                                     CacheBudget.Reservation reservation,
                                     boolean terrainLayoutCertified,
                                     String terrainLayoutDetail) {
            if (programIdentity == null || legacyProgram <= 0
                || candidateProgram <= 0 || prepared == null
                || resourceGeneration <= 0L || contextGeneration <= 0L
                || shaderGeneration <= 0L || reservation == null) {
                throw new IllegalArgumentException("Shader Program binding");
            }
            this.programIdentity = programIdentity;
            this.legacyProgram = legacyProgram;
            this.candidateProgram = candidateProgram;
            this.prepared = prepared;
            this.preparedReservation = reservation;
            this.key = prepared.getKey();
            this.resourceGeneration = resourceGeneration;
            this.contextGeneration = contextGeneration;
            this.shaderGeneration = shaderGeneration;
            this.terrainLayoutCertified = terrainLayoutCertified;
            this.terrainLayoutDetail = terrainLayoutDetail == null ? ""
                : terrainLayoutDetail;
        }

        private void closePrepared() {
            prepared = null;
            CacheBudget.Reservation reservation = preparedReservation;
            preparedReservation = null;
            if (reservation != null) reservation.close();
        }
    }

    private static final class ShaderValidationRequest {
        private final ShaderProgramBinding binding;
        private final OptifineProgramState state;
        private ShaderValidationRequest(ShaderProgramBinding binding,
                                        OptifineProgramState state) {
            if (binding == null || state == null) {
                throw new IllegalArgumentException("Shader validation request");
            }
            this.binding = binding;
            this.state = state;
        }
    }

    private static final class CapturedShaderSources implements AutoCloseable {
        private final Object programIdentity;
        private final String packId;
        private final String program;
        private final String permutation;
        private final long resourceGeneration;
        private final long shaderGeneration;
        private final String vertexPath;
        private final String vertexSource;
        private final String geometryPath;
        private final String geometrySource;
        private final String fragmentPath;
        private final String fragmentSource;
        private final String propertiesSource;
        private final long bytes;
        private CacheBudget.Reservation heapReservation;

        private CapturedShaderSources(Object programIdentity, String packId,
                                      String program,
                                      String permutation,
                                      long resourceGeneration,
                                      long shaderGeneration,
                                      String vertexPath, String vertexSource,
                                      String geometryPath, String geometrySource,
                                      String fragmentPath, String fragmentSource,
                                      String propertiesSource, long bytes,
                                      CacheBudget.Reservation heapReservation) {
            if (!boundedCapture(packId, 256) || !boundedCapture(program, 256)
                || !boundedCapture(permutation, 1024)
                || !boundedCapture(vertexPath, 1024)
                || !boundedCapture(fragmentPath, 1024)
                || geometrySource != null && !boundedCapture(geometryPath, 1024)
                || resourceGeneration <= 0L || shaderGeneration <= 0L
                || bytes <= 0L || heapReservation == null) {
                throw new IllegalArgumentException("captured Shader metadata");
            }
            this.programIdentity = programIdentity;
            this.packId = packId;
            this.program = program;
            this.permutation = permutation;
            this.resourceGeneration = resourceGeneration;
            this.shaderGeneration = shaderGeneration;
            this.vertexPath = vertexPath;
            this.vertexSource = vertexSource;
            this.geometryPath = geometryPath;
            this.geometrySource = geometrySource;
            this.fragmentPath = fragmentPath;
            this.fragmentSource = fragmentSource;
            this.propertiesSource = propertiesSource == null ? "" : propertiesSource;
            this.bytes = bytes;
            this.heapReservation = heapReservation;
        }

        @Override public void close() {
            CacheBudget.Reservation reservation = heapReservation;
            heapReservation = null;
            if (reservation != null) reservation.close();
        }

        private static boolean boundedCapture(String value, int maximum) {
            return value != null && !value.isEmpty() && value.length() <= maximum
                && value.indexOf('\0') < 0;
        }
    }

    interface ShaderBindingPublicationFault {
        void checkpoint(String point);
    }

    /** Candidate graph is private until every constructor/self-test succeeds. */
    private static final class ComponentGraph {
        private ContextCapabilities contextCapabilities;
        private long contextGeneration;
        private RenderThreadGuard threadGuard;
        private GlStateMirror stateMirror;
        private FrameCoordinator coordinator;
        private LegacyGlIsland legacyIsland;
        private CorrelatedRenderProfiler profiler;
        private ResourceLedger resources;
        private LwjglTerrainArena terrainArena;
        private LwjglModelMeshCache modelMeshes;
        private ParticleInstanceStream particles;
        private LwjglParticleRenderer particleRenderer;
        private LwjglFbpPacketRenderer fbpPacketRenderer;
        private TextureUploadStream textureUploads;
        private SpriteVisibilityTracker spriteVisibility;
        private LwjglAnimatedTextureUploadStream animatedTextures;
        private HudVertexStream hud;
        private LwjglHudRenderer hudRenderer;
        private FontLayoutCache fonts;
        private CertifiedDrawSites certifiedDrawSites;
        private ShaderCertificationRegistry shaders;
        private ShaderCertificationPipeline shaderPipeline;
        private LwjglShaderCompilationDriver shaderCompiler;
        private LwjglShaderProgramInstaller shaderInstaller;
        private LwjglShaderImageCertification shaderImageCertification;
        private LwjglOptifineShaderActivation shaderActivation;
        private OptifineShaderBackendSelector shaderBackendSelector;
        private ConservativeOcclusionHistory hzbHistory;
        private LwjglDepthHistory depthHistory;
        private GlStateQueryWorkspace glStateQueryWorkspace;
        private CapabilityReport capabilities;
        private final EnumMap<OptimizationModule, AdaptiveBackendController> backends =
            new EnumMap<OptimizationModule, AdaptiveBackendController>(
                OptimizationModule.class);
    }

    private static final class HzbGpuSample {
        private final SceneFingerprint scene;
        private final MeasurementArm arm;
        private final int expectedGpuScopes;
        private int completedGpuScopes;
        private long cpuNanos;
        private long gpuNanos;
        private boolean cpuReady;

        private HzbGpuSample(SceneFingerprint scene, MeasurementArm arm,
                             int expectedGpuScopes) {
            this.scene = scene;
            this.arm = arm;
            this.expectedGpuScopes = expectedGpuScopes;
        }
    }

    public static final class RenderBackendSample {
        private final OptimizationModule module;
        private final BackendLifecycleState state;
        private final MeasurementArm arm;
        private final SceneFingerprint scene;
        private final RenderProfileKey key;
        private final CorrelatedRenderProfiler.GpuScope gpuScope;
        private final long startedNanos;

        private RenderBackendSample(OptimizationModule module,
                                    BackendLifecycleState state,
                                    MeasurementArm arm, SceneFingerprint scene,
                                    RenderProfileKey key,
                                    CorrelatedRenderProfiler.GpuScope gpuScope,
                                    long startedNanos) {
            this.module = module;
            this.state = state;
            this.arm = arm;
            this.scene = scene;
            this.key = key;
            this.gpuScope = gpuScope;
            this.startedNanos = startedNanos;
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
        private final RenderPass pass;
        private final int commandCount;
        private final int commandCapacity;
        private final boolean preDrawValid;
        private final CorrelatedRenderProfiler.CpuScope cpuScope;
        private final CorrelatedRenderProfiler.GpuScope gpuScope;

        private OptifineRegionDrawSample(RenderPass pass, int commandCount,
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

    private static final class BackendGpuSample {
        private final OptimizationModule module;
        private final SceneFingerprint scene;
        private final MeasurementArm arm;
        private long cpuNanos;
        private long gpuNanos;
        private boolean cpuReady;
        private boolean gpuReady;
        private boolean stable;

        private BackendGpuSample(OptimizationModule module,
                                 SceneFingerprint scene, MeasurementArm arm) {
            this.module = module;
            this.scene = scene;
            this.arm = arm;
        }
    }
}
