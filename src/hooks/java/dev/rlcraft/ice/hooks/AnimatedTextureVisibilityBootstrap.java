package dev.rlcraft.ice.hooks;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Core-only no-op trampoline for independent animated-sprite visibility. */
public final class AnimatedTextureVisibilityBootstrap {
    private static final MethodType TWO_OBJECTS = MethodType.methodType(
        void.class, Object.class, Object.class);
    private static final MethodType ONE_OBJECT = MethodType.methodType(
        void.class, Object.class);
    private static volatile Delegate delegate;

    private AnimatedTextureVisibilityBootstrap() {
    }

    public static boolean install(Class<?> bridgeType) {
        if (bridgeType == null) return false;
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            delegate = new Delegate(
                lookup.findStatic(bridgeType, "terrainChunk", TWO_OBJECTS),
                lookup.findStatic(bridgeType, "terrainDraw", TWO_OBJECTS),
                lookup.findStatic(bridgeType, "bufferDraw", ONE_OBJECT));
            return true;
        } catch (Throwable incompatible) {
            HookFatalErrors.rethrowIfFatal(incompatible);
            delegate = null;
            return false;
        }
    }

    public static void terrainChunk(Object renderChunk, Object layer) {
        Delegate current = delegate;
        if (current == null) return;
        try { current.terrain.invokeExact(renderChunk, layer); }
        catch (Throwable ignored) { HookFatalErrors.rethrowIfFatal(ignored); }
    }

    public static void bufferDraw(Object tessellator) {
        Delegate current = delegate;
        if (current == null) return;
        try { current.buffer.invokeExact(tessellator); }
        catch (Throwable ignored) { HookFatalErrors.rethrowIfFatal(ignored); }
    }

    public static void terrainDraw(Object container, Object layer) {
        Delegate current = delegate;
        if (current == null) return;
        try { current.terrainDraw.invokeExact(container, layer); }
        catch (Throwable ignored) { HookFatalErrors.rethrowIfFatal(ignored); }
    }

    static void resetForTest() { delegate = null; }

    private static final class Delegate {
        private final MethodHandle terrain;
        private final MethodHandle terrainDraw;
        private final MethodHandle buffer;
        private Delegate(MethodHandle terrain, MethodHandle terrainDraw,
                         MethodHandle buffer) {
            this.terrain = terrain;
            this.terrainDraw = terrainDraw;
            this.buffer = buffer;
        }
    }
}
