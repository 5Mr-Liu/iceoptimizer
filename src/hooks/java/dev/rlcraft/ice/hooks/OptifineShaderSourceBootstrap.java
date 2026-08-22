package dev.rlcraft.ice.hooks;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.lwjgl.opengl.ARBShaderObjects;

/**
 * Core-JAR trampoline at OptiFine's exact resolved-source submission point.
 * The original ARB call always executes first and retains its exception.
 */
public final class OptifineShaderSourceBootstrap {
    private static final MethodType CAPTURE = MethodType.methodType(void.class,
        int.class, CharSequence.class, Object.class, String.class, int.class);
    private static volatile MethodHandle capture;

    private OptifineShaderSourceBootstrap() {
    }

    public static boolean install(Class<?> bridgeType) {
        if (bridgeType == null) return false;
        try {
            capture = MethodHandles.publicLookup().findStatic(bridgeType,
                "capture", CAPTURE);
            return true;
        } catch (Throwable incompatible) {
            HookFatalErrors.rethrowIfFatal(incompatible);
            capture = null;
            return false;
        }
    }

    public static void submit(int shader, CharSequence source, Object program,
                              String path, int stage) {
        ARBShaderObjects.glShaderSourceARB(shader, source);
        MethodHandle current = capture;
        if (current == null) return;
        try {
            current.invokeExact(shader, source, program, path, stage);
        } catch (Throwable ignored) {
            HookFatalErrors.rethrowIfFatal(ignored);
        }
    }

    static void resetForTest() { capture = null; }
}
