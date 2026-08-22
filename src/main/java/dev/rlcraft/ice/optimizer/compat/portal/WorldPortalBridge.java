package dev.rlcraft.ice.optimizer.compat.portal;

import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime;
import java.lang.reflect.InvocationTargetException;

/** Production bridge for iChunUtil's recursive WorldPortal renderer. */
public final class WorldPortalBridge {
    private static final String MODULE = "legacy-gl-island";
    private static volatile boolean coreBridgeInstalled;

    private WorldPortalBridge() {
    }

    public static synchronized boolean installCoreBridge() {
        if (coreBridgeInstalled) return true;
        try {
            ClassLoader loader = WorldPortalBridge.class.getClassLoader();
            Class<?> bootstrap = Class.forName(
                "dev.rlcraft.ice.hooks.WorldPortalBootstrap", true, loader);
            Object installed = bootstrap.getMethod("install", Class.class)
                .invoke(null, WorldPortalBridge.class);
            if (Boolean.TRUE.equals(installed)) {
                coreBridgeInstalled = true;
                return true;
            }
            OptimizerBridge.failure(MODULE,
                new IllegalStateException("Core WorldPortal bridge mismatch"));
        } catch (ClassNotFoundException missingCore) {
            return false;
        } catch (Throwable error) {
            Throwable cause = error instanceof InvocationTargetException
                && ((InvocationTargetException) error).getCause() != null
                ? ((InvocationTargetException) error).getCause() : error;
            OptimizerBridge.failure(MODULE, cause);
        }
        return false;
    }

    public static long begin() {
        try {
            ModernRendererRuntime runtime =
                ClientOptimizerRuntime.INSTANCE.modernRenderer();
            return runtime == null ? 0L : runtime.beginPortalView();
        } catch (Throwable error) {
            OptimizerBridge.failure(MODULE, error);
            return 0L;
        }
    }

    public static void end(long token) {
        finish(token, null);
    }

    public static void abort(long token, Throwable originalError) {
        finish(token, originalError);
    }

    private static void finish(long token, Throwable originalError) {
        if (token == 0L) return;
        try {
            ModernRendererRuntime runtime =
                ClientOptimizerRuntime.INSTANCE.modernRenderer();
            if (runtime != null) runtime.endPortalView(token, originalError);
        } catch (Throwable error) {
            OptimizerBridge.failure(MODULE, error);
        }
    }
}
