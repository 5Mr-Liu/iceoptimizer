package dev.rlcraft.ice.optimizer.compat.render;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime;
import dev.rlcraft.ice.optimizer.render.backend.RenderBackendId;
import dev.rlcraft.ice.optimizer.render.frame.RenderPass;
import java.lang.reflect.InvocationTargetException;

/** Exact vanilla sky/weather/hand pass boundaries; original calls stay intact. */
public final class RenderPassLifecycleBridge {
    private static final String MODULE = "modern-frame-coordinator";
    private static volatile boolean coreBridgeInstalled;

    private RenderPassLifecycleBridge() {
    }

    public static synchronized boolean installCoreBridge() {
        if (coreBridgeInstalled) return true;
        try {
            ClassLoader loader = RenderPassLifecycleBridge.class.getClassLoader();
            Class<?> bootstrap = Class.forName(
                "dev.rlcraft.ice.hooks.RenderPassBootstrap", true, loader);
            Object installed = bootstrap.getMethod("install", Class.class)
                .invoke(null, RenderPassLifecycleBridge.class);
            if (Boolean.TRUE.equals(installed)) {
                coreBridgeInstalled = true;
                return true;
            }
            OptimizerBridge.failure(MODULE,
                new IllegalStateException("Core render pass bridge mismatch"));
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

    public static long beginSky() { return begin(RenderPass.SKY); }
    public static long beginWeather() { return begin(RenderPass.WEATHER); }
    public static long beginHand() { return begin(RenderPass.HAND); }

    public static void end(long token) {
        if (token == 0L) return;
        try {
            ModernRendererRuntime runtime =
                ClientOptimizerRuntime.INSTANCE.modernRenderer();
            if (runtime != null) runtime.endObservedPass(token);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
        }
    }

    public static void abort(long token, Throwable originalError) { end(token); }

    private static long begin(RenderPass pass) {
        try {
            ModernRendererRuntime runtime =
                ClientOptimizerRuntime.INSTANCE.modernRenderer();
            return runtime == null ? 0L : runtime.beginObservedPass(pass,
                RenderBackendId.LEGACY);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return 0L;
        }
    }
}
