package dev.rlcraft.ice.hooks;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Core-JAR trampoline around OptiFine VboRegion.finishDraw. */
public final class OptifineRegionBootstrap {
    private static final MethodType BEGIN = MethodType.methodType(long.class,
        Object.class);
    private static final MethodType END = MethodType.methodType(void.class,
        long.class, Object.class);
    private static final MethodType ABORT = MethodType.methodType(void.class,
        long.class, Object.class, Throwable.class);
    private static volatile Delegate delegate;

    private OptifineRegionBootstrap() {
    }

    public static boolean install(Class<?> bridgeType) {
        if (bridgeType == null) return false;
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            delegate = new Delegate(
                lookup.findStatic(bridgeType, "begin", BEGIN),
                lookup.findStatic(bridgeType, "end", END),
                lookup.findStatic(bridgeType, "abort", ABORT));
            return true;
        } catch (Throwable incompatible) {
            HookFatalErrors.rethrowIfFatal(incompatible);
            delegate = null;
            return false;
        }
    }

    public static long begin(Object region) {
        Delegate current = delegate;
        if (current == null) return 0L;
        try { return (long) current.begin.invokeExact(region); }
        catch (Throwable ignored) {
            HookFatalErrors.rethrowIfFatal(ignored);
            return 0L;
        }
    }

    public static void end(long token, Object region) {
        Delegate current = delegate;
        if (current == null || token == 0L) return;
        try { current.end.invokeExact(token, region); }
        catch (Throwable ignored) { HookFatalErrors.rethrowIfFatal(ignored); }
    }

    public static void abort(long token, Object region, Throwable error) {
        Delegate current = delegate;
        if (current == null || token == 0L) return;
        try { current.abort.invokeExact(token, region, error); }
        catch (Throwable ignored) { HookFatalErrors.rethrowIfFatal(ignored); }
    }

    static void resetForTest() { delegate = null; }

    private static final class Delegate {
        private final MethodHandle begin;
        private final MethodHandle end;
        private final MethodHandle abort;

        private Delegate(MethodHandle begin, MethodHandle end,
                         MethodHandle abort) {
            this.begin = begin;
            this.end = end;
            this.abort = abort;
        }
    }
}
