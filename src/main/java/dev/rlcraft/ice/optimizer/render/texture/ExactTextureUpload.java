package dev.rlcraft.ice.optimizer.render.texture;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import net.minecraft.client.renderer.texture.TextureUtil;

/** Bind the original helpers once, including OptiFine's configured mip filter. */
public final class ExactTextureUpload {
    private ExactTextureUpload() { }

    private static final class Bindings {
        static final MethodHandle LEVEL = find(int.class, int[].class, int.class,
            int.class, int.class, int.class, boolean.class, boolean.class, boolean.class);
        static final MethodHandle FILTER = find(boolean.class, boolean.class);
        static final MethodHandle WRAP = named("setTextureClamped", "func_110997_a", boolean.class);
    }

    public static void upload(int[][] pixels, int width, int height, int x, int y,
                              boolean blur, boolean clamp, boolean mipFilter) {
        try {
            for (int mip = 0; mip < pixels.length && (width >> mip) > 0 && (height >> mip) > 0; mip++) {
                Bindings.LEVEL.invokeExact(mip, pixels[mip], width >> mip, height >> mip,
                    x >> mip, y >> mip, blur, clamp, mipFilter);
            }
        } catch (Throwable failure) { ExactTextureUpload.<RuntimeException>raise(failure); }
    }

    static void parameters(boolean blur, boolean clamp, boolean mipFilter) {
        try {
            Bindings.FILTER.invokeExact(blur, mipFilter);
            Bindings.WRAP.invokeExact(clamp);
        } catch (Throwable failure) { ExactTextureUpload.<RuntimeException>raise(failure); }
    }

    private static MethodHandle find(Class<?>... parameters) {
        for (Method method : TextureUtil.class.getDeclaredMethods()) {
            if (method.getReturnType() == void.class
                && java.util.Arrays.equals(parameters, method.getParameterTypes())) return bind(method);
        }
        throw new IllegalStateException("TextureUtil upload helper missing");
    }

    private static MethodHandle named(String mcp, String srg, Class<?> parameter) {
        try { return bind(TextureUtil.class.getDeclaredMethod(mcp, parameter)); }
        catch (NoSuchMethodException mapped) {
            try { return bind(TextureUtil.class.getDeclaredMethod(srg, parameter)); }
            catch (NoSuchMethodException missing) { throw new IllegalStateException(missing); }
        }
    }

    private static MethodHandle bind(Method method) {
        try { method.setAccessible(true); return MethodHandles.lookup().unreflect(method); }
        catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
    }

    @SuppressWarnings("unchecked") private static <T extends Throwable> void raise(Throwable failure) throws T {
        throw (T) failure;
    }
}
