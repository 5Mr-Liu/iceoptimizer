package dev.rlcraft.ice.optimizer.compat.chunk;

import dev.rlcraft.ice.optimizer.FatalErrors;
import java.lang.reflect.Field;
import java.util.Map;

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
            return Probe.ready((Map<Object, Object>) pending);
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

        private Probe(Status status, Map<Object, Object> pending,
                      Throwable failure) {
            this.status = status;
            this.pending = pending;
            this.failure = failure;
        }

        private static Probe absent() {
            return new Probe(Status.ABSENT, null, null);
        }

        private static Probe ready(Map<Object, Object> pending) {
            return new Probe(Status.READY, pending, null);
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

        Status status() { return failure == null ? status : Status.FAILED; }
        Throwable failure() { return failure; }
    }
}
