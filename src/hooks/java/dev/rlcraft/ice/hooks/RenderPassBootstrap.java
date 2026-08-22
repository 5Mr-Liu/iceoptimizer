package dev.rlcraft.ice.hooks;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Core-only fail-open trampoline for vanilla semantic pass observations. */
public final class RenderPassBootstrap {
    private static final MethodType BEGIN = MethodType.methodType(long.class);
    private static final MethodType END = MethodType.methodType(void.class,
        long.class);
    private static final MethodType ABORT = MethodType.methodType(void.class,
        long.class, Throwable.class);
    private static volatile Delegate delegate;

    private RenderPassBootstrap() {
    }

    public static boolean install(Class<?> bridgeType) {
        if (bridgeType == null) return false;
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            delegate = new Delegate(
                lookup.findStatic(bridgeType, "beginSky", BEGIN),
                lookup.findStatic(bridgeType, "beginWeather", BEGIN),
                lookup.findStatic(bridgeType, "beginHand", BEGIN),
                lookup.findStatic(bridgeType, "end", END),
                lookup.findStatic(bridgeType, "abort", ABORT));
            return true;
        } catch (Throwable incompatible) {
            HookFatalErrors.rethrowIfFatal(incompatible);
            delegate = null;
            return false;
        }
    }

    public static long beginSky() { return begin(0); }
    public static long beginWeather() { return begin(1); }
    public static long beginHand() { return begin(2); }

    private static long begin(int pass) {
        Delegate current = delegate;
        if (current == null) return 0L;
        try {
            if (pass == 0) return (long) current.sky.invokeExact();
            if (pass == 1) return (long) current.weather.invokeExact();
            return (long) current.hand.invokeExact();
        } catch (Throwable ignored) {
            HookFatalErrors.rethrowIfFatal(ignored);
            return 0L;
        }
    }

    public static void end(long token) {
        Delegate current = delegate;
        if (current == null || token == 0L) return;
        try { current.end.invokeExact(token); }
        catch (Throwable ignored) { HookFatalErrors.rethrowIfFatal(ignored); }
    }

    public static void abort(long token, Throwable error) {
        Delegate current = delegate;
        if (current == null || token == 0L) return;
        try { current.abort.invokeExact(token, error); }
        catch (Throwable ignored) { HookFatalErrors.rethrowIfFatal(ignored); }
    }

    static void resetForTest() { delegate = null; }

    private static final class Delegate {
        private final MethodHandle sky;
        private final MethodHandle weather;
        private final MethodHandle hand;
        private final MethodHandle end;
        private final MethodHandle abort;

        private Delegate(MethodHandle sky, MethodHandle weather,
                         MethodHandle hand, MethodHandle end,
                         MethodHandle abort) {
            this.sky = sky;
            this.weather = weather;
            this.hand = hand;
            this.end = end;
            this.abort = abort;
        }
    }
}
