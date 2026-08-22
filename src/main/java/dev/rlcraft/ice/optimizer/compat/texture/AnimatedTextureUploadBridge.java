package dev.rlcraft.ice.optimizer.compat.texture;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import dev.rlcraft.ice.optimizer.bridge.UnsafeLegacyReplayException;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime;
import dev.rlcraft.ice.optimizer.render.backend.BackendLifecycleState;
import dev.rlcraft.ice.optimizer.render.backend.MeasurementArm;
import dev.rlcraft.ice.optimizer.render.backend.SceneFingerprint;
import dev.rlcraft.ice.optimizer.render.backend.RenderBackendId;
import dev.rlcraft.ice.optimizer.render.frame.RenderPass;
import dev.rlcraft.ice.optimizer.render.texture.AnimationTextureCommandQueue;
import dev.rlcraft.ice.optimizer.render.texture.LwjglAnimatedTextureUploadStream;
import dev.rlcraft.ice.optimizer.render.texture.LwjglAnimatedTextureUploadStream.FlushResult;
import dev.rlcraft.ice.optimizer.render.texture.LwjglTextureUploadSelfTest;
import dev.rlcraft.ice.optimizer.render.texture.SpriteVisibilityTracker;
import dev.rlcraft.ice.optimizer.render.texture.TextureOutputValidator;
import dev.rlcraft.ice.optimizer.runtime.MonotonicTokenCounter;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** Same-thread TextureMap scope with exact custom-sprite barriers and fallback. */
public final class AnimatedTextureUploadBridge {
    private static final String MODULE = "modern-texture-stream";
    private static final AtomicLong NEXT_TOKEN = new AtomicLong(1L);
    private static final int MAX_DEPTH = 8;
    private static final ThreadLocal<State> STATE = new ThreadLocal<State>() {
        @Override protected State initialValue() { return new State(); }
    };
    private static volatile boolean coreBridgeInstalled;

    private AnimatedTextureUploadBridge() {
    }

    public static synchronized boolean installCoreBridge() {
        if (coreBridgeInstalled) return true;
        try {
            ClassLoader loader = AnimatedTextureUploadBridge.class.getClassLoader();
            Class<?> bootstrap = Class.forName(
                "dev.rlcraft.ice.hooks.AnimatedTextureBootstrap", true, loader);
            Object installed = bootstrap.getMethod("install", Class.class)
                .invoke(null, AnimatedTextureUploadBridge.class);
            if (Boolean.TRUE.equals(installed)) {
                coreBridgeInstalled = true;
                return true;
            }
            OptimizerBridge.failure(MODULE,
                new IllegalStateException("Core 动画纹理桥签名不兼容"));
        } catch (ClassNotFoundException missingCore) {
            return false;
        } catch (Throwable error) {
            Throwable cause = error instanceof InvocationTargetException
                && ((InvocationTargetException) error).getCause() != null
                ? ((InvocationTargetException) error).getCause() : error;
            FatalErrors.rethrowIfFatal(cause);
            OptimizerBridge.failure(MODULE, cause);
        }
        return false;
    }

    public static long begin(Object atlas) {
        long token = nextToken();
        if (token == 0L) return 0L;
        State state = STATE.get();
        if (state.depth >= MAX_DEPTH) {
            Scope current = state.current();
            if (current != null) flushForCoreBoundary(current);
            state.overflow++;
            return -token;
        }
        Scope parent = state.current();
        if (parent != null) {
            flushForCoreBoundary(parent);
            // A nested TextureMap traversal can rebind the atlas and PBO
            // behind the outer scope.  The outer traversal may resume only on
            // the exact legacy path; it cannot safely reuse its old mirror.
            parent.modern = false;
            parent.visibilityModern = false;
            parent.stale = true;
        }
        Scope scope = state.scopes[state.depth++];
        scope.reset(token, atlas);
        if (state.overflow != 0) return token;
        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft == null || !minecraft.isCallingFromMinecraftThread()
                || minecraft.gameSettings == null || minecraft.gameSettings.anaglyph) {
                return token;
            }
            ModernRendererRuntime runtime = ClientOptimizerRuntime.INSTANCE.modernRenderer();
            LwjglAnimatedTextureUploadStream uploader = runtime == null
                ? null : runtime.animatedTextures();
            if (runtime == null || uploader == null) return token;
            scope.runtime = runtime;
            scope.uploader = uploader;
            scope.tracker = runtime.spriteVisibility();
            scope.resourceGeneration = runtime.resourceGeneration();
            scope.atlasGeneration = runtime.atlasGeneration();
            scope.contextGeneration = runtime.glContextGeneration();
            scope.shaderGeneration = runtime.shaderPackGeneration();
            if (atlas instanceof AbstractTexture) {
                scope.atlasTextureId = ((AbstractTexture) atlas).getGlTextureId();
                runtime.observeAnimatedAtlas(scope.atlasTextureId);
            }
            scope.streamState = runtime.backendLifecycleState(
                OptimizationModule.MODERN_TEXTURE_STREAM);
            scope.streamArm = armFor(runtime,
                OptimizationModule.MODERN_TEXTURE_STREAM, scope.streamState);
            if (scope.streamState == BackendLifecycleState.OUTPUT_VALIDATE) {
                LwjglTextureUploadSelfTest.Result result =
                    TextureOutputValidator.validate(scope.contextGeneration, false,
                        runtime.cacheBudget());
                runtime.recordValidation(OptimizationModule.MODERN_TEXTURE_STREAM,
                    result.isEquivalent(), result.getDetail());
            }
            scope.modern = usesModern(scope.streamState, scope.streamArm);

            scope.visibilityState = runtime.backendLifecycleState(
                OptimizationModule.MODERN_TEXTURE_VISIBILITY);
            scope.visibilityArm = armFor(runtime,
                OptimizationModule.MODERN_TEXTURE_VISIBILITY,
                scope.visibilityState);
            if (scope.visibilityState == BackendLifecycleState.OUTPUT_VALIDATE) {
                LwjglTextureUploadSelfTest.Result result =
                    TextureOutputValidator.validate(scope.contextGeneration, false,
                        runtime.cacheBudget());
                boolean equivalent = scope.tracker != null
                    && SpriteVisibilityTracker.selfTest()
                    && result.isEquivalent();
                runtime.recordValidation(
                    OptimizationModule.MODERN_TEXTURE_VISIBILITY, equivalent,
                    equivalent ? null : result.getDetail() == null
                        ? "animated visibility CPU self-test failed"
                        : result.getDetail());
            }
            scope.visibilityModern = usesModern(scope.visibilityState,
                scope.visibilityArm);
            scope.passToken = runtime.beginObservedPass(
                RenderPass.ANIMATED_TEXTURE_UPLOAD,
                scope.modern || scope.visibilityModern
                    ? RenderBackendId.ICE_NATIVE
                    : RenderBackendId.LEGACY);
            if (!scope.modern) return token;

            scope.persistentState = runtime.backendLifecycleState(
                OptimizationModule.MODERN_TEXTURE_PERSISTENT_RING);
            scope.persistentArm = armFor(runtime,
                OptimizationModule.MODERN_TEXTURE_PERSISTENT_RING,
                scope.persistentState);
            if (scope.persistentState == BackendLifecycleState.OUTPUT_VALIDATE) {
                LwjglTextureUploadSelfTest.Result result =
                    TextureOutputValidator.validate(scope.contextGeneration, true,
                        runtime.cacheBudget());
                runtime.recordValidation(
                    OptimizationModule.MODERN_TEXTURE_PERSISTENT_RING,
                    result.isEquivalent(), result.getDetail());
            }
            scope.persistent = usesModern(scope.persistentState,
                scope.persistentArm);
        } catch (Throwable error) {
            Throwable fatal = FatalErrors.findFatal(error);
            if (fatal != null) throw unwindBeginFatal(state, scope, fatal);
            safeTextureBackendFailure(scope, error);
            scope.modern = false;
        }
        return token;
    }

    public static void beforeSprite(Object sprite) {
        State state = STATE.get();
        Scope scope = state.current();
        if (scope == null || state.overflow != 0) return;
        if (scope.inSprite) {
            flushForCoreBoundary(scope);
            scope.eligible = false;
            safeTextureBackendFailure(scope, new IllegalStateException(
                "animation sprite scope overlap"));
        }
        scope.inSprite = true;
        scope.currentSprite = null;
        scope.spriteIndex = -1;
        scope.spriteRegistered = false;
        scope.eligible = sprite != null
            && sprite.getClass() == TextureAtlasSprite.class;
        if (scope.eligible && scope.tracker != null
            && scope.atlasTextureId > 0
            && scope.runtime != null
            && scope.runtime.isAnimatedAtlasBound()) {
            scope.currentSprite = sprite;
            scope.spriteIndex = AnimatedTextureVisibilityBridge.spriteIndex(sprite);
            try {
                scope.spriteRegistered = scope.tracker.register(sprite,
                    scope.spriteIndex, scope.resourceGeneration,
                    scope.atlasGeneration);
            } catch (Throwable visibilityFailure) {
                FatalErrors.rethrowIfFatal(visibilityFailure);
                scope.visibilityModern = false;
                safeTextureVisibilityFailure(scope, visibilityFailure);
            }
        }
        if (!scope.eligible) {
            scope.customSprites++;
            flush(scope);
        }
    }

    public static void afterSprite() {
        State state = STATE.get();
        Scope scope = state.current();
        if (scope == null || state.overflow != 0) return;
        scope.inSprite = false;
        scope.eligible = false;
        scope.currentSprite = null;
        scope.spriteIndex = -1;
        scope.spriteRegistered = false;
    }

    /** Flushes queued pixels before TextureMap changes the bound atlas. */
    public static void textureBarrier() {
        State state = STATE.get();
        Scope scope = state.current();
        if (scope == null || state.overflow != 0) return;
        if (scope.inSprite) {
            flushForCoreBoundary(scope);
            scope.modern = false;
            scope.visibilityModern = false;
            safeTextureBackendFailure(scope, new IllegalStateException(
                "animation atlas binding changed inside a sprite scope"));
            return;
        }
        flushForCoreBoundary(scope);
        scope.textureBarriers++;
    }

    public static boolean tryUpload(int[][] data, int width, int height,
                                    int originX, int originY, boolean blur,
                                    boolean clamp) {
        State state = STATE.get();
        Scope scope = state.current();
        if (scope == null || state.overflow != 0 || !scope.inSprite
            || !scope.eligible) return false;
        long bytes = AnimationTextureCommandQueue.validatedByteCount(
            data, width, height);
        if (bytes < 0L || originX < 0 || originY < 0) return false;
        scope.commands++;
        scope.mipLevels += AnimationTextureCommandQueue.usableMipLevels(
            data, width, height);
        scope.bytes = safeAdd(scope.bytes, bytes);
        if (scope.spriteRegistered && scope.tracker != null) {
            try {
                scope.visibilityEligibleCommands++;
                if (scope.visibilityModern && bytes > 0L
                    && scope.tracker.deferIfInvisible(scope.currentSprite,
                        scope.spriteIndex, scope.atlasTextureId, data, width,
                        height, originX, originY, blur, clamp,
                        scope.runtime.currentFrameId(), scope.resourceGeneration,
                        scope.atlasGeneration)) {
                    scope.visibilityDeferredCommands++;
                    scope.visibilityDeferredBytes = safeAdd(
                        scope.visibilityDeferredBytes, bytes);
                    scope.runtime.recordTextureVisibilityDeferred(bytes);
                    return true;
                }
                Throwable visibilityFailure = scope.tracker.consumeLastFailure();
                if (visibilityFailure != null) {
                    safeTextureVisibilityFailure(scope, visibilityFailure);
                    scope.visibilityModern = false;
                }
                // The bridge is about to queue or fall through to the exact
                // original upload.  Drop an older pending frame before that newer
                // upload can make it stale.
                scope.tracker.immediateUpload(scope.currentSprite,
                    scope.spriteIndex, scope.resourceGeneration,
                    scope.atlasGeneration);
            } catch (Throwable visibilityFailure) {
                FatalErrors.rethrowIfFatal(visibilityFailure);
                scope.visibilityModern = false;
                scope.spriteRegistered = false;
                safeTextureVisibilityFailure(scope, visibilityFailure);
            }
        }
        if (!scope.modern || scope.uploader == null) return false;
        try {
            if (scope.uploader.offer(data, width, height, originX, originY,
                blur, clamp)) return true;
            flush(scope);
            return scope.uploader.offer(data, width, height, originX, originY,
                blur, clamp);
        } catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            if (error instanceof UnsafeLegacyReplayException) rethrow(error);
            if (isLegacyReplayFailure(scope, error)) {
                throw unsafeLegacyReplay(error);
            }
            safeTextureBackendFailure(scope, error);
            scope.modern = false;
            return false;
        }
    }

    public static void end(long token) {
        finish(token, null);
    }

    public static void abort(long token, Throwable error) {
        finish(token, error == null
            ? new IllegalStateException("animation traversal aborted") : error);
    }

    private static void finish(long token, Throwable traversalError) {
        if (token == 0L) return;
        State state = STATE.get();
        if (token < 0L) {
            if (state.overflow > 0) state.overflow--;
            return;
        }
        Scope scope = state.current();
        if (scope == null || scope.token != token) {
            drainMismatched(state, traversalError);
            return;
        }
        Throwable fatal = null;
        Throwable replayFailure = null;
        try {
            flush(scope);
            recordSamples(scope, traversalError == null);
        } catch (Throwable cleanupFailure) {
            Throwable fatalCause = FatalErrors.findFatal(cleanupFailure);
            if (fatalCause != null) {
                fatal = fatalCause;
            } else if (isLegacyReplayFailure(scope, cleanupFailure)) {
                if (traversalError == null) replayFailure = cleanupFailure;
                else addSuppressed(traversalError, cleanupFailure);
            } else {
                try { safeTextureBackendFailure(scope, cleanupFailure); }
                catch (Throwable reportingFailure) {
                    fatal = appendFailure(fatal, reportingFailure);
                }
            }
        } finally {
            try { scope.closePass(); }
            catch (Throwable closeFailure) {
                fatal = appendFailure(fatal, closeFailure);
            } finally {
                scope.clearReferences();
                state.depth--;
            }
        }
        FatalErrors.rethrowIfFatal(fatal);
        if (replayFailure != null && traversalError == null) {
            throw unsafeLegacyReplay(replayFailure);
        }
    }

    private static void flush(Scope scope) {
        if (scope == null || scope.uploader == null || !scope.modern) return;
        FlushResult result;
        try {
            if (!scope.uploader.hasCommands()) return;
            long currentResources = scope.runtime.resourceGeneration();
            long currentContext = scope.runtime.glContextGeneration();
            if (currentResources != scope.resourceGeneration
                || currentContext != scope.contextGeneration) {
                scope.uploader.reset(currentContext == scope.contextGeneration,
                    currentContext);
                scope.stale = true;
                return;
            }
            result = scope.uploader.flush(currentResources, currentContext,
                scope.persistent);
            scope.modernWork |= result.usedModern();
            scope.persistentWork |= scope.persistent
                ? result == FlushResult.PERSISTENT_PBO
                : result == FlushResult.STREAMING_PBO;
            scope.fallbacks += result == FlushResult.LEGACY
                || result == FlushResult.FAILED_TO_LEGACY ? 1 : 0;
            if (scope.uploader.wasLastFenceBusy()) scope.busy++;
            Throwable error = scope.uploader.getLastError();
            if (error != null) {
                if (scope.persistent) {
                    safeTexturePersistentFailure(scope, error);
                    scope.persistent = false;
                } else {
                    safeTextureBackendFailure(scope, error);
                    scope.modern = false;
                }
                if (result == FlushResult.FAILED_BEFORE_UPLOAD
                    || result == FlushResult.FAILED_AFTER_UPLOAD) {
                    scope.modern = false;
                }
            }
            scope.runtime.recordTextureTransfers(scope.uploader.getLastCommandCount(),
                scope.uploader.getLastBytes(), scope.uploader.wasLastFenceBusy(),
                result.usedModern());
        } catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            if (isLegacyReplayFailure(scope, error)) {
                reportReplayInfrastructure(scope, error);
                rethrow(error);
            }
            if (scope.persistent) safeTexturePersistentFailure(scope, error);
            else safeTextureBackendFailure(scope, error);
            scope.modern = false;
        }
    }

    private static boolean isLegacyReplayFailure(Scope scope,
                                                  Throwable error) {
        return scope != null && scope.uploader != null && error != null
            && scope.uploader.getLastLegacyReplayFailure() == error;
    }

    private static void reportReplayInfrastructure(Scope scope,
                                                   Throwable replayFailure) {
        if (scope == null || scope.runtime == null || replayFailure == null) return;
        for (Throwable infrastructure : replayFailure.getSuppressed()) {
            if (scope.persistent) {
                safeTexturePersistentFailure(scope, infrastructure);
            } else {
                safeTextureBackendFailure(scope, infrastructure);
            }
        }
    }

    private static void safeTextureBackendFailure(Scope scope,
                                                  Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        if (scope == null || scope.runtime == null) return;
        try { scope.runtime.textureBackendFailure(error); }
        catch (Throwable reportingFailure) {
            FatalErrors.rethrowIfFatal(reportingFailure);
        }
    }

    private static void safeTexturePersistentFailure(Scope scope,
                                                     Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        if (scope == null || scope.runtime == null) return;
        try { scope.runtime.texturePersistentFailure(error); }
        catch (Throwable reportingFailure) {
            FatalErrors.rethrowIfFatal(reportingFailure);
        }
    }

    private static void safeTextureVisibilityFailure(Scope scope,
                                                     Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        if (scope == null || scope.runtime == null) return;
        try { scope.runtime.textureVisibilityFailure(error); }
        catch (Throwable reportingFailure) {
            FatalErrors.rethrowIfFatal(reportingFailure);
        }
    }

    private static void recordSamples(Scope scope, boolean completedNormally) {
        if (scope.runtime == null || scope.commands == 0) return;
        long elapsed = Math.max(1L, System.nanoTime() - scope.startedNanos);
        SceneFingerprint scene = SceneFingerprint.textureWorkload(scope.commands,
            scope.mipLevels, scope.bytes, scope.customSprites,
            scope.resourceGeneration, scope.shaderGeneration);
        boolean commonStable = completedNormally && !scope.stale;
        boolean streamStable = commonStable
            && (!usesModern(scope.streamState, scope.streamArm) || scope.modernWork);
        scope.runtime.recordAuxiliaryBackendSample(
            OptimizationModule.MODERN_TEXTURE_STREAM, scope.streamState,
            scope.streamArm, scene, elapsed, streamStable);
        if (scope.modern && (scope.persistentState
            == BackendLifecycleState.PAIRED_MEASURE
            || scope.persistentState == BackendLifecycleState.REGRESSION_MONITOR)) {
            scope.runtime.recordAuxiliaryBackendSample(
                OptimizationModule.MODERN_TEXTURE_PERSISTENT_RING,
                scope.persistentState, scope.persistentArm, scene, elapsed,
                commonStable && scope.persistentWork);
        }
        if ((scope.visibilityState == BackendLifecycleState.PAIRED_MEASURE
            || scope.visibilityState
                == BackendLifecycleState.REGRESSION_MONITOR)
            && scope.streamState != BackendLifecycleState.PAIRED_MEASURE) {
            long visibilityElapsed = elapsed;
            if (scope.visibilityArm == MeasurementArm.MODERN) {
                visibilityElapsed = safeAdd(visibilityElapsed,
                    scope.runtime.consumeTextureVisibilityCatchUpNanos());
            }
            boolean visibilityStable = commonStable
                && scope.visibilityEligibleCommands > 0
                && scope.tracker != null && scope.tracker.sourcesReady();
            scope.runtime.recordAuxiliaryBackendSample(
                OptimizationModule.MODERN_TEXTURE_VISIBILITY,
                scope.visibilityState, scope.visibilityArm, scene,
                visibilityElapsed, visibilityStable);
        }
    }

    private static void drainMismatched(State state, Throwable traversalError) {
        IllegalStateException mismatch = new IllegalStateException(
            "animation texture scope token mismatch");
        Throwable replayFailure = null;
        Throwable fatal = null;
        while (state.depth > 0) {
            Scope scope = state.scopes[state.depth - 1];
            try {
                try { flush(scope); }
                catch (Throwable error) {
                    if (FatalErrors.findFatal(error) != null) {
                        fatal = appendFailure(fatal, error);
                    } else if (isLegacyReplayFailure(scope, error)) {
                        replayFailure = appendFailure(replayFailure, error);
                    } else try {
                        safeTextureBackendFailure(scope, error);
                    } catch (Throwable reportingFailure) {
                        fatal = appendFailure(fatal, reportingFailure);
                    }
                }
                try { recordSamples(scope, false); }
                catch (Throwable sampleFailure) {
                    if (FatalErrors.findFatal(sampleFailure) != null) {
                        fatal = appendFailure(fatal, sampleFailure);
                    } else try {
                        safeTextureBackendFailure(scope, sampleFailure);
                    } catch (Throwable reportingFailure) {
                        fatal = appendFailure(fatal, reportingFailure);
                    }
                }
                try { safeTextureBackendFailure(scope, mismatch); }
                catch (Throwable reportingFailure) {
                    fatal = appendFailure(fatal, reportingFailure);
                }
            } finally {
                try { scope.closePass(); }
                catch (Throwable closeFailure) {
                    fatal = appendFailure(fatal, closeFailure);
                } finally {
                    scope.clearReferences();
                    state.depth--;
                }
            }
        }
        state.overflow = 0;
        FatalErrors.rethrowIfFatal(fatal);
        if (replayFailure != null) {
            if (traversalError != null) {
                addSuppressed(traversalError, replayFailure);
            } else {
                throw unsafeLegacyReplay(replayFailure);
            }
        }
    }

    /**
     * flush() absorbs every recoverable infrastructure failure except a
     * failed exact Legacy replay.  Anything non-fatal escaping here therefore
     * has an uncertain submitted prefix and must cross the Core boundary.
     */
    private static void flushForCoreBoundary(Scope scope) {
        try {
            flush(scope);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            if (failure instanceof UnsafeLegacyReplayException) {
                rethrow(failure);
            }
            throw unsafeLegacyReplay(failure);
        }
    }

    private static UnsafeLegacyReplayException unsafeLegacyReplay(
        Throwable failure) {
        return new UnsafeLegacyReplayException(
            "animated texture Legacy replay outcome is uncertain", failure);
    }

    private static boolean usesModern(BackendLifecycleState state,
                                      MeasurementArm arm) {
        return state == BackendLifecycleState.MODERN
            || state == BackendLifecycleState.REGRESSION_MONITOR
            || (state == BackendLifecycleState.PAIRED_MEASURE
                && arm == MeasurementArm.MODERN);
    }

    private static MeasurementArm armFor(ModernRendererRuntime runtime,
                                         OptimizationModule module,
                                         BackendLifecycleState state) {
        if (state == BackendLifecycleState.PAIRED_MEASURE) {
            return runtime.expectedMeasurementArm(module);
        }
        return state == BackendLifecycleState.MODERN
            || state == BackendLifecycleState.REGRESSION_MONITOR
                ? MeasurementArm.MODERN : MeasurementArm.LEGACY;
    }

    private static long nextToken() {
        return MonotonicTokenCounter.nextOrZero(NEXT_TOKEN,
            "animated texture bridge token");
    }

    private static RuntimeException unwindBeginFatal(State state, Scope scope,
                                                      Throwable fatal) {
        Throwable failure = fatal;
        try { scope.closePass(); }
        catch (Throwable closeFailure) {
            failure = appendFailure(failure, closeFailure);
        } finally {
            scope.clearReferences();
            state.depth--;
            if (state.depth == 0 && state.overflow == 0) STATE.remove();
        }
        FatalErrors.rethrowIfFatal(failure);
        return new IllegalStateException("fatal texture bridge failure", failure);
    }

    static int depthForTest() { return STATE.get().depth; }
    static int overflowForTest() { return STATE.get().overflow; }
    static void resetForTest() { STATE.remove(); }

    private static long safeAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private static void addSuppressed(Throwable first, Throwable next) {
        if (first == null || next == null || first == next) return;
        first.addSuppressed(next);
    }

    private static Throwable appendFailure(Throwable first, Throwable next) {
        if (first == null) return next;
        Throwable nextFatal = FatalErrors.findFatal(next);
        if (nextFatal != null && FatalErrors.findFatal(first) == null) {
            addSuppressed(nextFatal, first);
            return nextFatal;
        }
        addSuppressed(first, next);
        return first;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("animated texture upload failed",
            failure);
    }

    private static final class State {
        private final Scope[] scopes = new Scope[MAX_DEPTH];
        private int depth;
        private int overflow;

        private State() {
            for (int i = 0; i < scopes.length; i++) scopes[i] = new Scope();
        }

        private Scope current() {
            return depth == 0 ? null : scopes[depth - 1];
        }
    }

    private static final class Scope {
        private long token;
        private Object atlas;
        private Object currentSprite;
        private ModernRendererRuntime runtime;
        private LwjglAnimatedTextureUploadStream uploader;
        private SpriteVisibilityTracker tracker;
        private BackendLifecycleState streamState = BackendLifecycleState.LEGACY;
        private BackendLifecycleState persistentState = BackendLifecycleState.LEGACY;
        private BackendLifecycleState visibilityState = BackendLifecycleState.LEGACY;
        private MeasurementArm streamArm = MeasurementArm.LEGACY;
        private MeasurementArm persistentArm = MeasurementArm.LEGACY;
        private MeasurementArm visibilityArm = MeasurementArm.LEGACY;
        private long resourceGeneration;
        private long atlasGeneration;
        private long contextGeneration;
        private long shaderGeneration;
        private long startedNanos;
        private long bytes;
        private int commands;
        private int mipLevels;
        private int customSprites;
        private int textureBarriers;
        private int atlasTextureId;
        private int spriteIndex;
        private int visibilityEligibleCommands;
        private int visibilityDeferredCommands;
        private long visibilityDeferredBytes;
        private int fallbacks;
        private int busy;
        private boolean inSprite;
        private boolean eligible;
        private boolean modern;
        private boolean persistent;
        private boolean visibilityModern;
        private boolean spriteRegistered;
        private boolean modernWork;
        private boolean persistentWork;
        private boolean stale;
        private long passToken;

        private void reset(long token, Object atlas) {
            this.token = token;
            this.atlas = atlas;
            startedNanos = System.nanoTime();
            streamState = BackendLifecycleState.LEGACY;
            persistentState = BackendLifecycleState.LEGACY;
            visibilityState = BackendLifecycleState.LEGACY;
            streamArm = MeasurementArm.LEGACY;
            persistentArm = MeasurementArm.LEGACY;
            visibilityArm = MeasurementArm.LEGACY;
            bytes = 0L;
            commands = 0;
            mipLevels = 0;
            customSprites = 0;
            textureBarriers = 0;
            atlasTextureId = 0;
            spriteIndex = -1;
            visibilityEligibleCommands = 0;
            visibilityDeferredCommands = 0;
            visibilityDeferredBytes = 0L;
            fallbacks = 0;
            busy = 0;
            inSprite = false;
            eligible = false;
            modern = false;
            persistent = false;
            visibilityModern = false;
            spriteRegistered = false;
            modernWork = false;
            persistentWork = false;
            stale = false;
            passToken = 0L;
            runtime = null;
            uploader = null;
            tracker = null;
            currentSprite = null;
            resourceGeneration = 0L;
            atlasGeneration = 0L;
            contextGeneration = 0L;
            shaderGeneration = 0L;
        }

        private void clearReferences() {
            atlas = null;
            currentSprite = null;
            runtime = null;
            uploader = null;
            tracker = null;
            inSprite = false;
            eligible = false;
            spriteRegistered = false;
        }

        private void closePass() {
            long value = passToken;
            passToken = 0L;
            if (runtime != null && value != 0L) {
                try { runtime.endObservedPass(value); }
                catch (Throwable cleanupFailure) {
                    FatalErrors.rethrowIfFatal(cleanupFailure);
                    try { runtime.textureBackendFailure(cleanupFailure); }
                    catch (Throwable reportingFailure) {
                        FatalErrors.rethrowIfFatal(reportingFailure);
                    }
                }
            }
        }
    }
}
