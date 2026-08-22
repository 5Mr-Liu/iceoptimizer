package dev.rlcraft.ice.optimizer.compat.texture;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderListAccessor;
import dev.rlcraft.ice.optimizer.render.texture.SpriteVisibilityTracker;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.BitSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.BlockRenderLayer;

/**
 * Aggregates OptiFine's per-chunk and per-dynamic-batch animated-sprite sets
 * without reading or changing the Smart Animations option.
 */
public final class AnimatedTextureVisibilityBridge {
    private static final String MODULE = "modern-texture-visibility";
    private static final ClassValue<SpriteAccess> SPRITES =
        new ClassValue<SpriteAccess>() {
            @Override protected SpriteAccess computeValue(Class<?> type) {
                return new SpriteAccess(type);
            }
        };
    private static final ClassValue<RenderChunkAccess> RENDER_CHUNKS =
        new ClassValue<RenderChunkAccess>() {
            @Override protected RenderChunkAccess computeValue(Class<?> type) {
                return new RenderChunkAccess(type);
            }
        };
    private static final ClassValue<CompiledChunkAccess> COMPILED_CHUNKS =
        new ClassValue<CompiledChunkAccess>() {
            @Override protected CompiledChunkAccess computeValue(Class<?> type) {
                return new CompiledChunkAccess(type);
            }
        };
    private static final ClassValue<BufferAccess> BUFFERS =
        new ClassValue<BufferAccess>() {
            @Override protected BufferAccess computeValue(Class<?> type) {
                return new BufferAccess(type);
            }
        };
    private static volatile boolean coreBridgeInstalled;

    private AnimatedTextureVisibilityBridge() {
    }

    public static synchronized boolean installCoreBridge() {
        if (coreBridgeInstalled) return true;
        try {
            ClassLoader loader = AnimatedTextureVisibilityBridge.class.getClassLoader();
            Class<?> bootstrap = Class.forName(
                "dev.rlcraft.ice.hooks.AnimatedTextureVisibilityBootstrap",
                true, loader);
            Object installed = bootstrap.getMethod("install", Class.class)
                .invoke(null, AnimatedTextureVisibilityBridge.class);
            if (Boolean.TRUE.equals(installed)) {
                coreBridgeInstalled = true;
                return true;
            }
            OptimizerBridge.failure(MODULE, new IllegalStateException(
                "Core animated-sprite visibility bridge signature mismatch"));
        } catch (ClassNotFoundException missingCore) {
            return false;
        } catch (Throwable error) {
            Throwable cause = error instanceof InvocationTargetException
                && ((InvocationTargetException) error).getCause() != null
                ? ((InvocationTargetException) error).getCause() : error;
            FatalErrors.rethrowIfFatal(cause);
            OptimizerBridge.failure(MODULE, cause);
        }
        return false;
    }

    /** Called at ChunkRenderContainer.addRenderChunk before the draw list owns it. */
    public static void terrainChunk(Object renderChunk, Object layer) {
        ModernRendererRuntime runtime = runtime();
        SpriteVisibilityTracker tracker = tracker(runtime);
        if (tracker == null) return;
        try {
            Object compiled = renderChunk == null ? null
                : RENDER_CHUNKS.get(renderChunk.getClass()).compiled(renderChunk);
            CompiledChunkAccess access = compiled == null ? null
                : COMPILED_CHUNKS.get(compiled.getClass());
            boolean supported = access != null && access.supported();
            tracker.observeTerrainSource(supported);
            if (!supported) {
                unknown(runtime, tracker);
                return;
            }
            BitSet sprites = access.animated(compiled, layer);
            if (sprites != null) mark(runtime, tracker, sprites);
            reportFailure(runtime, tracker);
        } catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            tracker.observeTerrainSource(false);
            unknown(runtime, tracker);
            failure(runtime, error);
        }
    }

    /**
     * Rechecks the final chunk list at the concrete draw boundary.  The first
     * add-list hook gathers visibility early; this second boundary guarantees
     * that a deferred frame has caught up after the block atlas and any
     * OptiFine texture-unit state have reached their actual draw values.
     */
    public static void terrainDraw(Object container, Object layer) {
        ModernRendererRuntime runtime = runtime();
        SpriteVisibilityTracker tracker = tracker(runtime);
        if (tracker == null) return;
        if (!(container instanceof TerrainRenderListAccessor)
            || !(layer instanceof BlockRenderLayer)) {
            tracker.observeTerrainSource(false);
            unknown(runtime, tracker);
            return;
        }
        try {
            java.util.List<RenderChunk> chunks =
                ((TerrainRenderListAccessor) container).ice$renderChunks();
            if (chunks == null) {
                tracker.observeTerrainSource(false);
                unknown(runtime, tracker);
                return;
            }
            // Indexing avoids iterator allocation and preserves the exact list
            // order used by the immediately following terrain emitter.
            for (int index = 0, size = chunks.size(); index < size; index++) {
                Object renderChunk = chunks.get(index);
                Object compiled = renderChunk == null ? null
                    : RENDER_CHUNKS.get(renderChunk.getClass())
                        .compiled(renderChunk);
                CompiledChunkAccess access = compiled == null ? null
                    : COMPILED_CHUNKS.get(compiled.getClass());
                boolean supported = access != null && access.supported();
                tracker.observeTerrainSource(supported);
                if (!supported) {
                    unknown(runtime, tracker);
                    return;
                }
                BitSet sprites = access.animated(compiled, layer);
                if (sprites != null) mark(runtime, tracker, sprites);
            }
            reportFailure(runtime, tracker);
        } catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            tracker.observeTerrainSource(false);
            unknown(runtime, tracker);
            failure(runtime, error);
        }
    }

    /** Called before Tessellator resets its BufferBuilder metadata. */
    public static void bufferDraw(Object tessellator) {
        ModernRendererRuntime runtime = runtime();
        SpriteVisibilityTracker tracker = tracker(runtime);
        if (tracker == null) return;
        try {
            BufferBuilder buffer = tessellator instanceof Tessellator
                ? ((Tessellator) tessellator).getBuffer() : null;
            BufferAccess access = buffer == null ? null
                : BUFFERS.get(buffer.getClass());
            boolean supported = access != null && access.supported();
            tracker.observeBufferSource(supported);
            if (!supported) {
                unknown(runtime, tracker);
                return;
            }
            BitSet sprites = access.animated(buffer);
            if (sprites != null) mark(runtime, tracker, sprites);
            reportFailure(runtime, tracker);
        } catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            tracker.observeBufferSource(false);
            unknown(runtime, tracker);
            failure(runtime, error);
        }
    }

    /** Dynamic sources with an explicit sprite can catch up before their draw. */
    public static void spriteVisible(Object sprite) {
        ModernRendererRuntime runtime = runtime();
        SpriteVisibilityTracker tracker = tracker(runtime);
        if (tracker == null || sprite == null) return;
        try {
            int index = SPRITES.get(sprite.getClass()).index(sprite);
            tracker.markVisible(sprite, index, runtime.currentFrameId(),
                runtime.resourceGeneration(), runtime.atlasGeneration());
            if (runtime.isAnimatedAtlasBound() && tracker.hasPending(sprite)) {
                failure(runtime, new IllegalStateException(
                    "visible animated sprite could not catch up while atlas is bound"));
            }
            reportFailure(runtime, tracker);
        } catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            failure(runtime, error);
        }
    }

    static int spriteIndex(Object sprite) {
        if (sprite == null) return -1;
        try { return SPRITES.get(sprite.getClass()).index(sprite); }
        catch (Throwable unavailable) {
            FatalErrors.rethrowIfFatal(unavailable);
            return -1;
        }
    }

    private static void mark(ModernRendererRuntime runtime,
                             SpriteVisibilityTracker tracker, BitSet sprites) {
        tracker.markVisible(sprites, runtime.currentFrameId(),
            runtime.resourceGeneration(), runtime.atlasGeneration());
        if (runtime.isAnimatedAtlasBound()
            && tracker.pendingVisible(sprites) != 0) {
            failure(runtime, new IllegalStateException(
                "animated sprite set could not catch up while atlas is bound"));
        }
    }

    private static void unknown(ModernRendererRuntime runtime,
                                SpriteVisibilityTracker tracker) {
        if (runtime == null || !runtime.isAnimatedAtlasBound()) return;
        runtime.recordTextureVisibilityUnknown();
        tracker.markUnknown(runtime.currentFrameId(), runtime.resourceGeneration(),
            runtime.atlasGeneration());
        if (tracker.getPendingBytes() != 0L) {
            failure(runtime, new IllegalStateException(
                "unknown atlas draw could not catch up pending animations"));
        }
        reportFailure(runtime, tracker);
    }

    private static ModernRendererRuntime runtime() {
        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft == null || !minecraft.isCallingFromMinecraftThread()) {
                return null;
            }
            return ClientOptimizerRuntime.INSTANCE.modernRenderer();
        } catch (Throwable unavailable) {
            FatalErrors.rethrowIfFatal(unavailable);
            return null;
        }
    }

    private static SpriteVisibilityTracker tracker(ModernRendererRuntime runtime) {
        return runtime == null ? null : runtime.spriteVisibility();
    }

    private static void reportFailure(ModernRendererRuntime runtime,
                                      SpriteVisibilityTracker tracker) {
        Throwable failure = tracker.consumeLastFailure();
        if (failure != null) failure(runtime, failure);
    }

    private static void failure(ModernRendererRuntime runtime, Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        if (runtime == null) return;
        try { runtime.textureVisibilityFailure(error); }
        catch (Throwable reportingFailure) {
            FatalErrors.rethrowIfFatal(reportingFailure);
        }
    }

    private static Method method(Class<?> type, String[] names,
                                 Class<?> returnType, Class<?>... parameters) {
        Method found = null;
        for (String name : names) {
            for (Method method : type.getMethods()) {
                if (name.equals(method.getName())
                    && method.getReturnType() == returnType
                    && exactParameters(method.getParameterTypes(), parameters)) {
                    if (found != null && !found.equals(method)) return null;
                    found = method;
                }
            }
            if (found != null) return found;
        }
        return found;
    }

    private static Method objectMethod(Class<?> type, String[] names) {
        for (String name : names) {
            Method found = null;
            for (Method method : type.getMethods()) {
                Class<?> result = method.getReturnType();
                if (!name.equals(method.getName())
                    || method.getParameterTypes().length != 0
                    || result == Void.TYPE || result.isPrimitive()) continue;
                if (found != null && !found.equals(method)) return null;
                found = method;
            }
            if (found != null) return found;
        }
        return null;
    }

    private static boolean exactParameters(Class<?>[] actual,
                                           Class<?>[] expected) {
        if (actual.length != expected.length) return false;
        for (int index = 0; index < actual.length; index++) {
            if (actual[index] != expected[index]) return false;
        }
        return true;
    }

    private static Field field(Class<?> type, String name,
                               Class<?> fieldType) {
        for (Class<?> current = type; current != null;
             current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                if (field.getType() != fieldType
                    || Modifier.isStatic(field.getModifiers())) return null;
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException absent) {
                // Continue with the superclass.
            } catch (Throwable inaccessible) {
                FatalErrors.rethrowIfFatal(inaccessible);
                return null;
            }
        }
        return null;
    }

    private static final class SpriteAccess {
        private final Method index;
        private final Field indexField;
        private SpriteAccess(Class<?> type) {
            index = method(type, new String[] {"getIndexInMap"}, Integer.TYPE);
            indexField = index == null
                ? field(type, "indexInMap", Integer.TYPE) : null;
        }
        private int index(Object sprite) throws ReflectiveOperationException {
            if (index != null) return ((Integer) index.invoke(sprite)).intValue();
            return indexField == null ? -1 : indexField.getInt(sprite);
        }
    }

    private static final class RenderChunkAccess {
        private final Method compiled;
        private RenderChunkAccess(Class<?> type) {
            compiled = objectMethod(type, new String[] {"getCompiledChunk",
                "func_178571_g"});
        }
        private Object compiled(Object chunk) throws ReflectiveOperationException {
            return compiled == null ? null : compiled.invoke(chunk);
        }
    }

    private static final class CompiledChunkAccess {
        private final Method animated;
        private CompiledChunkAccess(Class<?> type) {
            animated = method(type, new String[] {"getAnimatedSprites"},
                BitSet.class, BlockRenderLayer.class);
        }
        private boolean supported() {
            return animated != null;
        }
        private BitSet animated(Object compiled, Object layer)
            throws ReflectiveOperationException {
            if (!supported() || layer == null
                || !animated.getParameterTypes()[0].isInstance(layer)) return null;
            Object value = animated.invoke(compiled, layer);
            return value instanceof BitSet ? (BitSet) value : null;
        }
    }

    private static final class BufferAccess {
        private final Method animated;
        private final Field animatedField;
        private BufferAccess(Class<?> type) {
            animated = method(type, new String[] {"getAnimatedSprites"},
                BitSet.class);
            animatedField = animated == null
                ? field(type, "animatedSprites", BitSet.class) : null;
        }
        private boolean supported() {
            return animated != null || animatedField != null;
        }
        private BitSet animated(Object buffer) throws ReflectiveOperationException {
            Object value = animated != null ? animated.invoke(buffer)
                : animatedField == null ? null : animatedField.get(buffer);
            return value instanceof BitSet ? (BitSet) value : null;
        }
    }
}
