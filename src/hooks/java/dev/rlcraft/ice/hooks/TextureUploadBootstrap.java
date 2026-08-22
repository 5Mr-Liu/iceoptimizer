package dev.rlcraft.ice.hooks;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Early CoreMod-safe entry for texture uploads.
 *
 * <p>Minecraft initializes {@code TextureUtil} before regular Forge mods reach
 * pre-init.  Consequently transformed Minecraft classes must not directly
 * link against a class that exists only in the regular optimizer JAR.  Until
 * that JAR installs its delegate this bridge simply returns {@code false}, so
 * the untouched Minecraft/FoamFix implementation runs.</p>
 */
public final class TextureUploadBootstrap {
    private static final String UNSAFE_REPLAY_FAILURE =
        "dev.rlcraft.ice.optimizer.bridge.UnsafeLegacyReplayException";
    private static final MethodType LEVEL_TYPE = MethodType.methodType(boolean.class,
        int.class, int[].class, int.class, int.class, int.class, int.class,
        boolean.class, boolean.class, boolean.class);
    private static final MethodType BATCH_TYPE = MethodType.methodType(boolean.class,
        int.class, int[][].class, int.class, int.class, int.class, int.class,
        boolean.class, boolean.class, boolean.class);
    private static volatile Delegate delegate;

    private TextureUploadBootstrap() {
    }

    /** Installs a public static bridge without retaining a compile-time main-JAR dependency. */
    public static boolean install(Class<?> bridgeType) {
        if (bridgeType == null) return false;
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodHandle level = lookup.findStatic(bridgeType, "tryUploadLevel", LEVEL_TYPE);
            MethodHandle batch = lookup.findStatic(bridgeType, "tryUpload", BATCH_TYPE);
            delegate = new Delegate(level, batch);
            return true;
        } catch (Throwable incompatibleBridge) {
            HookFatalErrors.rethrowIfFatal(incompatibleBridge);
            delegate = null;
            return false;
        }
    }

    public static boolean tryUploadLevel(int mipLevel, int[] data, int width, int height,
                                         int originX, int originY, boolean linearFiltering,
                                         boolean clamped, boolean mipFiltering) {
        Delegate current = delegate;
        if (current == null) return false;
        try {
            return (boolean) current.level.invokeExact(mipLevel, data, width, height,
                originX, originY, linearFiltering, clamped, mipFiltering);
        } catch (Throwable failedDelegate) {
            HookFatalErrors.rethrowIfFatal(failedDelegate);
            if (isUnsafeReplayFailure(failedDelegate)) {
                return TextureUploadBootstrap.<RuntimeException, Boolean>raise(
                    failedDelegate);
            }
            return false;
        }
    }

    public static boolean tryUpload(int maxMips, int[][] data, int width, int height,
                                    int originX, int originY, boolean linearFiltering,
                                    boolean clamped, boolean mipFiltering) {
        Delegate current = delegate;
        if (current == null) return false;
        try {
            return (boolean) current.batch.invokeExact(maxMips, data, width, height,
                originX, originY, linearFiltering, clamped, mipFiltering);
        } catch (Throwable failedDelegate) {
            HookFatalErrors.rethrowIfFatal(failedDelegate);
            if (isUnsafeReplayFailure(failedDelegate)) {
                return TextureUploadBootstrap.<RuntimeException, Boolean>raise(
                    failedDelegate);
            }
            return false;
        }
    }

    private static boolean isUnsafeReplayFailure(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 64; depth++) {
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

    static void resetForTest() {
        delegate = null;
    }

    private static final class Delegate {
        private final MethodHandle level;
        private final MethodHandle batch;

        private Delegate(MethodHandle level, MethodHandle batch) {
            this.level = level;
            this.batch = batch;
        }
    }
}
