package dev.rlcraft.ice.optimizer.render.terrain;

import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.util.vector.Vector3f;

/** Bind the optional animation API once; hot calls use an ordinary interface. */
interface NativeAnimationOffset {
    Vector3f get(Object chunk, BlockPos position);

    static NativeAnimationOffset bind(Object handler) throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodHandle target = lookup.unreflect(handler.getClass().getMethod("getOffset", Object.class, BlockPos.class));
        MethodType signature = MethodType.methodType(Vector3f.class, Object.class, BlockPos.class);
        return (NativeAnimationOffset) LambdaMetafactory.metafactory(lookup, "get",
            MethodType.methodType(NativeAnimationOffset.class, handler.getClass()), signature, target, signature)
            .getTarget().invoke(handler);
    }
}
