package dev.rlcraft.ice.hooks;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Core-only fail-open trampoline for reviewed OptiFine pass boundaries. */
public final class OptifinePassBootstrap {
    private static final MethodType BEGIN = MethodType.methodType(long.class);
    private static final MethodType END = MethodType.methodType(void.class,
        long.class);
    private static final MethodType ABORT = MethodType.methodType(void.class,
        long.class, Throwable.class);
    private static final MethodType TRANSITION = MethodType.methodType(void.class);
    private static volatile Delegate delegate;

    private OptifinePassBootstrap() {
    }

    public static boolean install(Class<?> bridgeType) {
        if (bridgeType == null) return false;
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            delegate = new Delegate(
                lookup.findStatic(bridgeType, "beginShadow", BEGIN),
                lookup.findStatic(bridgeType, "endShadow", END),
                lookup.findStatic(bridgeType, "abortShadow", ABORT),
                lookup.findStatic(bridgeType, "beginDeferred", BEGIN),
                lookup.findStatic(bridgeType, "endDeferred", END),
                lookup.findStatic(bridgeType, "abortDeferred", ABORT),
                lookup.findStatic(bridgeType, "beginComposite", BEGIN),
                lookup.findStatic(bridgeType, "endComposite", END),
                lookup.findStatic(bridgeType, "abortComposite", ABORT),
                lookup.findStatic(bridgeType, "transitionFinal", TRANSITION));
            return true;
        } catch (Throwable incompatible) {
            HookFatalErrors.rethrowIfFatal(incompatible);
            delegate = null;
            return false;
        }
    }

    public static long beginShadow() { return begin(0); }
    public static long beginDeferred() { return begin(1); }
    public static long beginComposite() { return begin(2); }

    private static long begin(int stage) {
        Delegate current = delegate;
        if (current == null) return 0L;
        try {
            if (stage == 0) return (long) current.beginShadow.invokeExact();
            if (stage == 1) return (long) current.beginDeferred.invokeExact();
            return (long) current.beginComposite.invokeExact();
        } catch (Throwable ignored) {
            HookFatalErrors.rethrowIfFatal(ignored);
            return 0L;
        }
    }

    public static void endShadow(long token) { end(0, token); }
    public static void endDeferred(long token) { end(1, token); }
    public static void endComposite(long token) { end(2, token); }

    private static void end(int stage, long token) {
        Delegate current = delegate;
        if (current == null || token == 0L) return;
        try {
            if (stage == 0) current.endShadow.invokeExact(token);
            else if (stage == 1) current.endDeferred.invokeExact(token);
            else current.endComposite.invokeExact(token);
        } catch (Throwable ignored) {
            HookFatalErrors.rethrowIfFatal(ignored);
        }
    }

    public static void abortShadow(long token, Throwable error) {
        abort(0, token, error);
    }
    public static void abortDeferred(long token, Throwable error) {
        abort(1, token, error);
    }
    public static void abortComposite(long token, Throwable error) {
        abort(2, token, error);
    }

    private static void abort(int stage, long token, Throwable error) {
        Delegate current = delegate;
        if (current == null || token == 0L) return;
        try {
            if (stage == 0) current.abortShadow.invokeExact(token, error);
            else if (stage == 1) current.abortDeferred.invokeExact(token, error);
            else current.abortComposite.invokeExact(token, error);
        } catch (Throwable ignored) {
            HookFatalErrors.rethrowIfFatal(ignored);
        }
    }

    public static void transitionFinal() {
        Delegate current = delegate;
        if (current == null) return;
        try { current.transitionFinal.invokeExact(); }
        catch (Throwable ignored) { HookFatalErrors.rethrowIfFatal(ignored); }
    }

    static void resetForTest() { delegate = null; }

    private static final class Delegate {
        private final MethodHandle beginShadow;
        private final MethodHandle endShadow;
        private final MethodHandle abortShadow;
        private final MethodHandle beginDeferred;
        private final MethodHandle endDeferred;
        private final MethodHandle abortDeferred;
        private final MethodHandle beginComposite;
        private final MethodHandle endComposite;
        private final MethodHandle abortComposite;
        private final MethodHandle transitionFinal;

        private Delegate(MethodHandle beginShadow, MethodHandle endShadow,
                         MethodHandle abortShadow, MethodHandle beginDeferred,
                         MethodHandle endDeferred, MethodHandle abortDeferred,
                         MethodHandle beginComposite, MethodHandle endComposite,
                         MethodHandle abortComposite,
                         MethodHandle transitionFinal) {
            this.beginShadow = beginShadow;
            this.endShadow = endShadow;
            this.abortShadow = abortShadow;
            this.beginDeferred = beginDeferred;
            this.endDeferred = endDeferred;
            this.abortDeferred = abortDeferred;
            this.beginComposite = beginComposite;
            this.endComposite = endComposite;
            this.abortComposite = abortComposite;
            this.transitionFinal = transitionFinal;
        }
    }
}
