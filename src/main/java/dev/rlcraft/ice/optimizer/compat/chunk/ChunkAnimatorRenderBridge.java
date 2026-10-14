package dev.rlcraft.ice.optimizer.compat.chunk;

import dev.rlcraft.ice.optimizer.FatalErrors;
import java.lang.reflect.Field;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.util.vector.Vector3f;

/**
 * Allocation-free render-thread probe for ChunkAnimator's pending transforms.
 *
 * <p>ChunkAnimator applies its offset from a transformer-injected call inside
 * {@code ChunkRenderContainer.preRenderChunk}.  Batched terrain and HZB bounds
 * must therefore treat every entry in its pending-animation map specially.
 * A present-but-unreadable installation fails open: every chunk keeps the
 * compatibility draw and HZB is prevented from hiding it.</p>
 */
public final class ChunkAnimatorRenderBridge {
    private static final String OWNER = "lumien.chunkanimator.ChunkAnimator";
    private static final Probe PROBE = discover(
        ChunkAnimatorRenderBridge.class.getClassLoader(), OWNER);

    private ChunkAnimatorRenderBridge() {
    }

    /** True means this chunk must pass through preRenderChunk and stay visible. */
    public static boolean requiresCompatibilityDraw(Object renderChunk) {
        return PROBE.requiresCompatibilityDraw(renderChunk);
    }

    /**
     * Uses ChunkAnimator 1.2.1's own easing/lifecycle calculation without a GL
     * matrix call. Older optional ABIs keep the original compatibility draw.
     */
    public static boolean tryOffset(Object renderChunk, BlockPos position,
                                    float[] output) {
        return PROBE.tryOffset(renderChunk, position, output);
    }

    public static String status() { return PROBE.status().name(); }
    public static String failureType() {
        Throwable failure = PROBE.failure;
        return failure == null ? "" : failure.getClass().getName();
    }
    public static String failureMessage() {
        Throwable failure = PROBE.failure;
        return failure == null || failure.getMessage() == null
            ? "" : failure.getMessage();
    }
    public static long runtimeFailures() { return PROBE.runtimeFailures; }

    static Probe inspectForTest(Class<?> owner) {
        return inspect(owner);
    }

    private static Probe discover(ClassLoader loader, String ownerName) {
        try {
            Class<?> owner = Class.forName(ownerName, false, loader);
            return inspect(owner);
        } catch (ClassNotFoundException absent) {
            return Probe.absent();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return Probe.failed(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static Probe inspect(Class<?> owner) {
        try {
            if (owner == null) throw new IllegalArgumentException(
                "ChunkAnimator owner");
            Field instanceField = declaredField(owner, "INSTANCE");
            Object instance = instanceField.get(null);
            if (instance == null) throw new IllegalStateException(
                "ChunkAnimator INSTANCE unavailable");
            Field handlerField = declaredField(owner, "animationHandler");
            Object handler = handlerField.get(instance);
            if (handler == null) throw new IllegalStateException(
                "ChunkAnimator animationHandler unavailable");
            Field pendingField = declaredField(handler.getClass(), "timeStamps");
            Object pending = pendingField.get(handler);
            if (!(pending instanceof Map)) throw new IllegalStateException(
                "ChunkAnimator timeStamps is not a Map");
            MethodHandle offset = null;
            try {
                offset = MethodHandles.lookup().unreflect(handler.getClass()
                    .getMethod("getOffset", Object.class, BlockPos.class))
                    .asType(MethodType.methodType(Vector3f.class,
                        Object.class, Object.class, BlockPos.class));
            } catch (NoSuchMethodException olderVersion) {
                // 1.2 has preRender only. Its pending chunks stay unbatched.
            }
            return Probe.ready((Map<Object, Object>) pending, handler, offset);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return Probe.failed(failure);
        }
    }

    private static Field declaredField(Class<?> owner, String name)
        throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        if (!field.isAccessible()) field.setAccessible(true);
        return field;
    }

    enum Status { ABSENT, READY, FAILED }

    static final class Probe {
        private final Status status;
        private final Map<Object, Object> pending;
        private volatile Throwable failure;
        private volatile long runtimeFailures;
        private Object handler;
        private MethodHandle offset;

        private Probe(Status status, Map<Object, Object> pending,
                      Throwable failure) {
            this.status = status;
            this.pending = pending;
            this.failure = failure;
        }

        private static Probe absent() {
            return new Probe(Status.ABSENT, null, null);
        }

        private static Probe ready(Map<Object, Object> pending, Object handler,
                                   MethodHandle offset) {
            Probe probe = new Probe(Status.READY, pending, null);
            probe.handler = handler;
            probe.offset = offset;
            return probe;
        }

        private static Probe failed(Throwable failure) {
            return new Probe(Status.FAILED, null, failure);
        }

        boolean requiresCompatibilityDraw(Object renderChunk) {
            if (renderChunk == null || status == Status.ABSENT) return false;
            if (status == Status.FAILED || failure != null) return true;
            try {
                return pending.containsKey(renderChunk);
            } catch (Throwable probeFailure) {
                FatalErrors.rethrowIfFatal(probeFailure);
                failure = probeFailure;
                if (runtimeFailures != Long.MAX_VALUE) runtimeFailures++;
                return true;
            }
        }

        boolean tryOffset(Object renderChunk, BlockPos position, float[] output) {
            if (output == null || output.length < 3) return false;
            output[0] = output[1] = output[2] = 0.0F;
            if (status == Status.ABSENT) return true;
            if (status == Status.FAILED || failure != null || renderChunk == null
                || position == null) return false;
            try {
                if (!pending.containsKey(renderChunk)) return true;
                if (offset == null) return false;
                Vector3f value = (Vector3f) offset.invokeExact(handler,
                    renderChunk, position);
                if (value == null) return true;
                if (!Float.isFinite(value.x) || !Float.isFinite(value.y)
                    || !Float.isFinite(value.z)) return false;
                output[0] = value.x;
                output[1] = value.y;
                output[2] = value.z;
                return true;
            } catch (Throwable probeFailure) {
                FatalErrors.rethrowIfFatal(probeFailure);
                failure = probeFailure;
                if (runtimeFailures != Long.MAX_VALUE) runtimeFailures++;
                return false;
            }
        }

        Status status() { return failure == null ? status : Status.FAILED; }
        Throwable failure() { return failure; }
    }
}
