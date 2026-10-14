package dev.rlcraft.ice.optimizer.compat.texture;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import dev.rlcraft.ice.optimizer.bridge.UnsafeLegacyReplayException;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime;
import dev.rlcraft.ice.optimizer.render.backend.BackendLifecycleState;
import dev.rlcraft.ice.optimizer.render.backend.RenderBackendId;
import dev.rlcraft.ice.optimizer.render.frame.RenderPass;
import dev.rlcraft.ice.optimizer.render.texture.AnimationTextureCommandQueue;
import dev.rlcraft.ice.optimizer.render.texture.LwjglAnimatedTextureUploadStream;
import dev.rlcraft.ice.optimizer.render.texture.LwjglAnimatedTextureUploadStream.FlushResult;
import dev.rlcraft.ice.optimizer.render.texture.SpriteVisibilityTracker;
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
        ModernRendererRuntime candidate =
            ClientOptimizerRuntime.INSTANCE.modernRenderer();
        if (candidate != null
            && !candidate.shouldObserveBackend(
                OptimizationModule.MODERN_TEXTURE_STREAM)
            && !candidate.shouldObserveBackend(
                OptimizationModule.MODERN_TEXTURE_VISIBILITY)
            && !candidate.shouldObserveBackend(
                OptimizationModule.MODERN_TEXTURE_PERSISTENT_RING)) return 0L;
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
        }
        Scope scope = state.scopes[state.depth++];
        scope.reset(token);
        if (state.overflow != 0) return token;
        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft == null || !minecraft.isCallingFromMinecraftThread()
                || minecraft.gameSettings == null || minecraft.gameSettings.anaglyph) {
                return token;
            }
            ModernRendererRuntime runtime = candidate;
            LwjglAnimatedTextureUploadStream uploader = runtime == null
                ? null : runtime.animatedTextures();
            if (runtime == null || uploader == null) return token;
            scope.runtime = runtime;
            scope.uploader = uploader;
            scope.tracker = runtime.spriteVisibility();
            scope.resourceGeneration = runtime.resourceGeneration();
            scope.atlasGeneration = runtime.atlasGeneration();
            scope.contextGeneration = runtime.glContextGeneration();
            if (atlas instanceof AbstractTexture) {
                scope.atlasTextureId = ((AbstractTexture) atlas).getGlTextureId();
                runtime.observeAnimatedAtlas(scope.atlasTextureId);
            }
            scope.modern = runtime.backendLifecycleState(
                OptimizationModule.MODERN_TEXTURE_STREAM) == BackendLifecycleState.MODERN;
            scope.visibilityModern = runtime.backendLifecycleState(
                OptimizationModule.MODERN_TEXTURE_VISIBILITY) == BackendLifecycleState.MODERN;
            scope.passToken = runtime.beginObservedPass(
                RenderPass.ANIMATED_TEXTURE_UPLOAD,
                scope.modern || scope.visibilityModern
                    ? RenderBackendId.ICE_NATIVE
                    : RenderBackendId.LEGACY);
            if (!scope.modern) return token;

            scope.persistent = runtime.backendLifecycleState(
                OptimizationModule.MODERN_TEXTURE_PERSISTENT_RING) == BackendLifecycleState.MODERN;
        } catch (Throwable error) {
            Throwable fatal = FatalErrors.findFatal(error);
            if (fatal != null) throw unwindBeginFatal(state, scope, fatal);
            safeTextureBackendFailure(scope, error);
            scope.modern = false;
        }
        return token;
    }

    public static void beforeSprite(Object sprite) { beforeSprite(sprite, false); }

    public static void beforeSprite(Object sprite, boolean reviewedFoamFix) {
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
        scope.capturedCount = 0;
        scope.captureLevels = false;
        scope.eligible = sprite != null
            && (sprite.getClass() == TextureAtlasSprite.class || reviewedFoamFix);
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
        if (!scope.eligible) flush(scope);
        scope.captureLevels = reviewedFoamFix && (scope.modern || scope.visibilityModern);
    }

    public static void afterSprite() {
        State state = STATE.get();
        Scope scope = state.current();
        if (scope == null || state.overflow != 0) return;
        commitCaptured(scope);
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
    }

    public static boolean tryUpload(int[][] data, int width, int height,
                                    int originX, int originY, boolean blur,
                                    boolean clamp) {
        return tryUpload(data, width, height, originX, originY, blur, clamp,
            data != null && data.length > 1, false);
    }

    private static boolean tryUpload(int[][] data, int width, int height, int originX, int originY,
                                     boolean blur, boolean clamp, boolean mipFilter, boolean snapshot) {
        State state = STATE.get();
        Scope scope = state.current();
        if (scope == null || state.overflow != 0 || !scope.inSprite
            || !scope.eligible) return false;
        long bytes = AnimationTextureCommandQueue.validatedByteCount(
            data, width, height);
        if (bytes < 0L || originX < 0 || originY < 0) return false;
        if (scope.spriteRegistered && scope.tracker != null) {
            try {
                if (scope.visibilityModern && bytes > 0L
                    && scope.tracker.deferIfInvisible(scope.currentSprite,
                        scope.spriteIndex, scope.atlasTextureId, data, width,
                        height, originX, originY, blur, clamp, mipFilter,
                        scope.runtime.currentFrameId(), scope.resourceGeneration,
                        scope.atlasGeneration)) {
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
            if (offer(scope, data, width, height, originX, originY, blur, clamp, mipFilter, snapshot)) return true;
            flush(scope);
            return scope.modern && offer(scope, data, width, height, originX, originY, blur, clamp, mipFilter, snapshot);
        } catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            if (error instanceof UnsafeLegacyReplayException) rethrow(error);
            if (isLegacyReplayFailure(scope, error)) {
                throw unsafeLegacyReplay(error);
            }
            // Commands accepted before this failed offer still belong to the stream.
            flushForCoreBoundary(scope);
            safeTextureBackendFailure(scope, error);
            scope.modern = false;
            return false;
        }
    }

    private static boolean offer(Scope scope, int[][] data, int width, int height, int x, int y,
                                 boolean blur, boolean clamp, boolean mipFilter, boolean snapshot) {
        return snapshot ? scope.uploader.offerSnapshot(data, width, height, x, y, blur, clamp, mipFilter)
            : scope.uploader.offer(data, width, height, x, y, blur, clamp);
    }

    /** Consecutive FoamFix mips become one command in the atlas batch. */
    public static boolean tryUploadLevel(int mip, int[] pixels, int width, int height, int x, int y,
                                         boolean blur, boolean clamp, boolean mipFilter) {
        State state = STATE.get();
        Scope scope = state.current();
        if (scope == null || !scope.captureLevels || state.overflow != 0) return false;
        if (mip == 0) {
            commitCaptured(scope);
            scope.captureLevels = true;
            scope.capturedWidth = width;
            scope.capturedHeight = height;
            scope.capturedX = x;
            scope.capturedY = y;
            scope.capturedBlur = blur;
            scope.capturedClamp = clamp;
            scope.capturedMipFilter = mipFilter;
        }
        scope.captured[mip] = pixels;
        scope.capturedCount = mip + 1;
        return true;
    }

    private static void commitCaptured(Scope scope) {
        scope.captureLevels = false;
        int count = scope.capturedCount;
        if (count == 0) return;
        int[][] levels = scope.levelVectors[count];
        if (levels == null) scope.levelVectors[count] = levels = new int[count][];
        System.arraycopy(scope.captured, 0, levels, 0, count);
        scope.capturedCount = 0;
        try {
            if (!tryUpload(levels, scope.capturedWidth, scope.capturedHeight, scope.capturedX,
                scope.capturedY, scope.capturedBlur, scope.capturedClamp, scope.capturedMipFilter, true)) {
                // Original uploads have been claimed, so commit them here if the batch is full.
                flushForCoreBoundary(scope);
                dev.rlcraft.ice.optimizer.render.texture.ExactTextureUpload.upload(levels,
                    scope.capturedWidth, scope.capturedHeight, scope.capturedX, scope.capturedY,
                    scope.capturedBlur, scope.capturedClamp, scope.capturedMipFilter);
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw unsafeLegacyReplay(failure);
        } finally {
            java.util.Arrays.fill(scope.captured, 0, count, null);
            java.util.Arrays.fill(levels, null);
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
            commitCaptured(scope);
            flush(scope);
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
                return;
            }
            result = scope.uploader.flush(currentResources, currentContext,
                scope.persistent);
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
        return error instanceof UnsafeLegacyReplayException
            || scope != null && scope.uploader != null && error != null
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

    private static void drainMismatched(State state, Throwable traversalError) {
        IllegalStateException mismatch = new IllegalStateException(
            "animation texture scope token mismatch");
        Throwable replayFailure = null;
        Throwable fatal = null;
        while (state.depth > 0) {
            Scope scope = state.scopes[state.depth - 1];
            try {
                try { commitCaptured(scope); flush(scope); }
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
            commitCaptured(scope);
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
        private final int[][] captured = new int[32][];
        private final int[][][] levelVectors = new int[33][][];
        private int capturedCount, capturedWidth, capturedHeight, capturedX, capturedY;
        private boolean captureLevels, capturedBlur, capturedClamp, capturedMipFilter;
        private Object currentSprite;
        private ModernRendererRuntime runtime;
        private LwjglAnimatedTextureUploadStream uploader;
        private SpriteVisibilityTracker tracker;
        private long resourceGeneration;
        private long atlasGeneration;
        private long contextGeneration;
        private int atlasTextureId;
        private int spriteIndex;
        private boolean inSprite;
        private boolean eligible;
        private boolean modern;
        private boolean persistent;
        private boolean visibilityModern;
        private boolean spriteRegistered;
        private long passToken;

        private void reset(long token) {
            this.token = token;
            atlasTextureId = 0;
            spriteIndex = -1;
            inSprite = false;
            eligible = false;
            modern = false;
            persistent = false;
            visibilityModern = false;
            spriteRegistered = false;
            passToken = 0L;
            runtime = null;
            uploader = null;
            tracker = null;
            currentSprite = null;
            resourceGeneration = 0L;
            atlasGeneration = 0L;
            contextGeneration = 0L;
        }

        private void clearReferences() {
            java.util.Arrays.fill(captured, null);
            capturedCount = 0;
            captureLevels = false;
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
