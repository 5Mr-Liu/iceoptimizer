package dev.rlcraft.ice.hooks;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Core-only trampoline for the animation-atlas scope.
 *
 * <p>The regular optimizer JAR is not resolved while Minecraft's texture
 * classes are being defined.  Until a compatible delegate is installed every
 * entry is a no-op and every upload returns {@code false}, which leaves the
 * original TextureUtil call in control.</p>
 */
public final class AnimatedTextureBootstrap {
    private static final String UNSAFE_REPLAY_FAILURE =
        "dev.rlcraft.ice.optimizer.bridge.UnsafeLegacyReplayException";
    private static final MethodType BEGIN_TYPE = MethodType.methodType(
        long.class, Object.class);
    private static final MethodType BEFORE_SPRITE_TYPE = MethodType.methodType(
        void.class, Object.class);
    private static final MethodType AFTER_SPRITE_TYPE = MethodType.methodType(
        void.class);
    private static final MethodType TEXTURE_BARRIER_TYPE = MethodType.methodType(
        void.class);
    private static final MethodType UPLOAD_TYPE = MethodType.methodType(
        boolean.class, int[][].class, int.class, int.class, int.class,
        int.class, boolean.class, boolean.class);
    private static final MethodType END_TYPE = MethodType.methodType(
        void.class, long.class);
    private static final MethodType ABORT_TYPE = MethodType.methodType(
        void.class, long.class, Throwable.class);
    private static volatile Delegate delegate;

    private AnimatedTextureBootstrap() {
    }

    public static boolean install(Class<?> bridgeType) {
        if (bridgeType == null) return false;
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Delegate replacement = new Delegate(
                lookup.findStatic(bridgeType, "begin", BEGIN_TYPE),
                lookup.findStatic(bridgeType, "beforeSprite", BEFORE_SPRITE_TYPE),
                lookup.findStatic(bridgeType, "afterSprite", AFTER_SPRITE_TYPE),
                lookup.findStatic(bridgeType, "textureBarrier",
                    TEXTURE_BARRIER_TYPE),
                lookup.findStatic(bridgeType, "tryUpload", UPLOAD_TYPE),
                lookup.findStatic(bridgeType, "end", END_TYPE),
                lookup.findStatic(bridgeType, "abort", ABORT_TYPE));
            delegate = replacement;
            return true;
        } catch (Throwable incompatibleBridge) {
            HookFatalErrors.rethrowIfFatal(incompatibleBridge);
            delegate = null;
            return false;
        }
    }

    public static long begin(Object atlas) {
        Delegate current = delegate;
        if (current == null) return 0L;
        try { return (long) current.begin.invokeExact(atlas); }
        catch (Throwable failedDelegate) {
            HookFatalErrors.rethrowIfFatal(failedDelegate);
            if (isUnsafeReplayFailure(failedDelegate)) {
                return AnimatedTextureBootstrap
                    .<RuntimeException, Long>raise(failedDelegate).longValue();
            }
            return 0L;
        }
    }

    public static void beforeSprite(Object sprite) {
        Delegate current = delegate;
        if (current == null) return;
        try { current.beforeSprite.invokeExact(sprite); }
        catch (Throwable ignored) {
            HookFatalErrors.rethrowIfFatal(ignored);
            if (isUnsafeReplayFailure(ignored)) {
                AnimatedTextureBootstrap.<RuntimeException, Void>raise(ignored);
            }
        }
    }

    public static void afterSprite() {
        Delegate current = delegate;
        if (current == null) return;
        try { current.afterSprite.invokeExact(); }
        catch (Throwable ignored) { HookFatalErrors.rethrowIfFatal(ignored); }
    }

    public static void textureBarrier() {
        Delegate current = delegate;
        if (current == null) return;
        try { current.textureBarrier.invokeExact(); }
        catch (Throwable ignored) {
            HookFatalErrors.rethrowIfFatal(ignored);
            if (isUnsafeReplayFailure(ignored)) {
                AnimatedTextureBootstrap.<RuntimeException, Void>raise(ignored);
            }
        }
    }

    public static boolean tryUpload(int[][] data, int width, int height,
                                    int originX, int originY, boolean blur,
                                    boolean clamp) {
        Delegate current = delegate;
        if (current == null) return false;
        try {
            return (boolean) current.upload.invokeExact(data, width, height,
                originX, originY, blur, clamp);
        } catch (Throwable failedDelegate) {
            HookFatalErrors.rethrowIfFatal(failedDelegate);
            if (isUnsafeReplayFailure(failedDelegate)) {
                return AnimatedTextureBootstrap
                    .<RuntimeException, Boolean>raise(failedDelegate)
                    .booleanValue();
            }
            return false;
        }
    }

    public static void end(long token) {
        Delegate current = delegate;
        if (current == null || token == 0L) return;
        try { current.end.invokeExact(token); }
        catch (Throwable ignored) {
            HookFatalErrors.rethrowIfFatal(ignored);
            if (isUnsafeReplayFailure(ignored)) {
                AnimatedTextureBootstrap.<RuntimeException, Void>raise(ignored);
            }
        }
    }

    public static void abort(long token, Throwable error) {
        Delegate current = delegate;
        if (current == null || token == 0L) return;
        try { current.abort.invokeExact(token, error); }
        catch (Throwable ignored) { HookFatalErrors.rethrowIfFatal(ignored); }
    }

    static void resetForTest() {
        delegate = null;
    }

    private static boolean isUnsafeReplayFailure(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 32; depth++) {
            if (UNSAFE_REPLAY_FAILURE.equals(current.getClass().getName())) {
                return true;
            }
            Throwable next = current.getCause();
            if (next == current) return false;
            current = next;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, T> T raise(Throwable error) throws E {
        throw (E) error;
    }

    private static final class Delegate {
        private final MethodHandle begin;
        private final MethodHandle beforeSprite;
        private final MethodHandle afterSprite;
        private final MethodHandle textureBarrier;
        private final MethodHandle upload;
        private final MethodHandle end;
        private final MethodHandle abort;

        private Delegate(MethodHandle begin, MethodHandle beforeSprite,
                         MethodHandle afterSprite, MethodHandle textureBarrier,
                         MethodHandle upload,
                         MethodHandle end, MethodHandle abort) {
            this.begin = begin;
            this.beforeSprite = beforeSprite;
            this.afterSprite = afterSprite;
            this.textureBarrier = textureBarrier;
            this.upload = upload;
            this.end = end;
            this.abort = abort;
        }
    }
}
