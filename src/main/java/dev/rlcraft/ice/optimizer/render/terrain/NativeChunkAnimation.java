package dev.rlcraft.ice.optimizer.render.terrain;

import java.lang.reflect.Field;
import java.util.Map;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.util.vector.Vector3f;

/** Direct use of ChunkAnimator's own easing; no per-chunk compatibility decision. */
final class NativeChunkAnimation {
    private static final Map<?, ?> PENDING;
    private static final NativeAnimationOffset OFFSET;
    static {
        Map<?, ?> pending = null;
        NativeAnimationOffset offset = null;
        try {
            Class<?> owner = Class.forName("lumien.chunkanimator.ChunkAnimator");
            Object instance = field(owner, "INSTANCE").get(null);
            Object handler = field(owner, "animationHandler").get(instance);
            pending = (Map<?, ?>) field(handler.getClass(), "timeStamps").get(handler);
            offset = NativeAnimationOffset.bind(handler);
        } catch (ClassNotFoundException absent) {
            // ChunkAnimator is optional. Nothing needs animating without it.
        } catch (Throwable error) {
            if (error instanceof Error) throw (Error) error;
            throw new ExceptionInInitializerError(error);
        }
        PENDING = pending;
        OFFSET = offset;
    }

    private NativeChunkAnimation() { }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    static boolean pending(Object chunk) { return PENDING != null && PENDING.containsKey(chunk); }

    static void offset(Object chunk, BlockPos position, float[] target) {
        Vector3f value = OFFSET.get(chunk, position);
        target[0] = value == null ? 0 : value.x;
        target[1] = value == null ? 0 : value.y;
        target[2] = value == null ? 0 : value.z;
    }
}
