package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import net.minecraft.client.renderer.vertex.VertexBuffer;

/** The original OptiFine region emitter for legacy meshes inside a mixed layer. */
final class OptifineTerrainRegionBridge {
    private static final MethodHandle GET_REGION = getter();
    private static MethodHandle finish;
    private static Class<?> regionClass;

    private OptifineTerrainRegionBridge() { }

    static Object region(VertexBuffer buffer) {
        if (GET_REGION == null) return null;
        try { return (Object) GET_REGION.invokeExact(buffer); }
        catch (Throwable failure) { throw propagate(failure); }
    }

    static void finish(Object region, Object container) {
        try {
            if (regionClass != region.getClass()) {
                Method method = null;
                for (Method candidate : region.getClass().getMethods()) {
                    if ("finishDraw".equals(candidate.getName())
                        && candidate.getReturnType() == void.class
                        && candidate.getParameterTypes().length == 1
                        && candidate.getParameterTypes()[0].isInstance(container)) {
                        if (method != null) throw new IllegalStateException("ambiguous OptiFine region emitter");
                        method = candidate;
                    }
                }
                if (method == null) throw new IllegalStateException("OptiFine region emitter unavailable");
                finish = MethodHandles.lookup().unreflect(method).asType(
                    MethodType.methodType(void.class, Object.class, Object.class));
                regionClass = region.getClass();
            }
            finish.invokeExact(region, container);
        } catch (Throwable failure) { throw propagate(failure); }
    }

    private static MethodHandle getter() {
        try {
            return MethodHandles.lookup().unreflect(VertexBuffer.class.getMethod("getVboRegion"))
                .asType(MethodType.methodType(Object.class, VertexBuffer.class));
        } catch (NoSuchMethodException vanilla) { return null; }
        catch (Throwable failure) { throw propagate(failure); }
    }

    private static RuntimeException propagate(Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        if (failure instanceof RuntimeException) return (RuntimeException) failure;
        return new IllegalStateException("OptiFine region terrain bridge", failure);
    }
}
