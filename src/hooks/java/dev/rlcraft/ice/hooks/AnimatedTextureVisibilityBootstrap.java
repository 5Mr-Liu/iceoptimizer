package dev.rlcraft.ice.hooks;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Core-only no-op trampoline for independent animated-sprite visibility. */
public final class AnimatedTextureVisibilityBootstrap {
    private static final MethodType TWO_OBJECTS = MethodType.methodType(
        void.class, Object.class, Object.class);
    private static final MethodType ONE_OBJECT = MethodType.methodType(
        void.class, Object.class);
    private static volatile Delegate delegate;

    private AnimatedTextureVisibilityBootstrap() {
    }

    @SuppressWarnings("unchecked")
    public static boolean install(Class<?> bridgeType) {
        if (bridgeType == null) return false;
        try {
            try {
                Object[] callbacks = (Object[]) bridgeType.getMethod("visibilityCallbacks").invoke(null);
                if (callbacks.length != 3 || !(callbacks[0] instanceof BiConsumer)
                    || !(callbacks[1] instanceof BiConsumer) || !(callbacks[2] instanceof Consumer))
                    throw new IllegalArgumentException("animated visibility callback ABI");
                delegate = new Delegate((BiConsumer<Object, Object>) callbacks[0],
                    (BiConsumer<Object, Object>) callbacks[1], (Consumer<Object>) callbacks[2]);
                return true;
            } catch (NoSuchMethodException oldBridge) {
                // Retain the old installation ABI for external bridge implementations.
            }
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
        try { current.terrain.accept(renderChunk, layer); }
        catch (Throwable ignored) { HookFatalErrors.rethrowIfFatal(ignored); }
    }

    public static void bufferDraw(Object tessellator) {
        Delegate current = delegate;
        if (current == null) return;
        try { current.buffer.accept(tessellator); }
        catch (Throwable ignored) { HookFatalErrors.rethrowIfFatal(ignored); }
    }

    public static void terrainDraw(Object container, Object layer) {
        Delegate current = delegate;
        if (current == null) return;
        try { current.terrainDraw.accept(container, layer); }
        catch (Throwable ignored) { HookFatalErrors.rethrowIfFatal(ignored); }
    }

    static void resetForTest() { delegate = null; }

    private static final class Delegate {
        private final BiConsumer<Object, Object> terrain;
        private final BiConsumer<Object, Object> terrainDraw;
        private final Consumer<Object> buffer;
        private Delegate(BiConsumer<Object, Object> terrain,
                         BiConsumer<Object, Object> terrainDraw, Consumer<Object> buffer) {
            this.terrain = terrain;
            this.terrainDraw = terrainDraw;
            this.buffer = buffer;
        }
        private Delegate(MethodHandle terrain, MethodHandle terrainDraw,
                         MethodHandle buffer) {
            this((chunk, layer) -> invoke(terrain, chunk, layer),
                (container, layer) -> invoke(terrainDraw, container, layer),
                value -> {
                    try { buffer.invokeExact(value); }
                    catch (Throwable failure) { HookFatalErrors.rethrowIfFatal(failure); }
                });
        }
        private static void invoke(MethodHandle handle, Object left, Object right) {
            try { handle.invokeExact(left, right); }
            catch (Throwable failure) { HookFatalErrors.rethrowIfFatal(failure); }
        }
    }
}
