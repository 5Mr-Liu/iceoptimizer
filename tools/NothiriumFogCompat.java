package dev.rlcraft.ice.benchmark.compat;

import dev.rlcraft.ice.benchmark.NothiriumFogShaderRewrite;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.lang.reflect.Field;
import java.util.function.Consumer;
import java.util.function.Supplier;
import meldexun.matrixutil.Matrix4f;
import meldexun.renderlib.util.GLShader;
import meldexun.renderlib.util.GLUtil;
import meldexun.renderlib.util.RenderUtil;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

/** Isolated experimental compatibility layer; the dependency is supplied separately. */
public final class NothiriumFogCompat {
    private static final Matrix4f MODEL_VIEW = new Matrix4f();
    private NothiriumFogCompat() { }

    @SuppressWarnings("unchecked")
    public static GLShader build(GLShader.Builder builder, Consumer<GLShader> initializer) {
        try {
            Field field = GLShader.Builder.class.getDeclaredField("shaderMap");
            field.setAccessible(true);
            Int2ObjectMap<Supplier<String>> sources =
                (Int2ObjectMap<Supplier<String>>) field.get(builder);
            if (sources.size() != 2) throw new IllegalArgumentException("Unexpected shader stages");
            // Resolve the actual resource-pack suppliers as a pair, before changing either stage.
            String[] pair = NothiriumFogShaderRewrite.rewrite(
                sources.get(GL20.GL_VERTEX_SHADER).get(), sources.get(GL20.GL_FRAGMENT_SHADER).get());
            builder.addShader(GL20.GL_VERTEX_SHADER, () -> pair[0]);
            builder.addShader(GL20.GL_FRAGMENT_SHADER, () -> pair[1]);
            System.out.println("ICE_FOG_COMPAT effective shader pair verified and adjusted");
        } catch (ReflectiveOperationException | IllegalArgumentException failure) {
            System.out.println("ICE_FOG_COMPAT preserving unsupported shader pair: " + failure);
        }
        return builder.build(initializer);
    }

    /** Called once per terrain layer, after the dependency has bound its program. */
    public static void setup(GLShader shader) {
        int matrix = shader.getUniform("iceFogModelView");
        if (matrix < 0) return; // Unmodified resource overrides retain their own semantics.
        MODEL_VIEW.copyFrom(RenderUtil.getModelViewMatrix());
        MODEL_VIEW.translate((float) RenderUtil.getCameraOffsetX(),
            (float) RenderUtil.getCameraOffsetY(), (float) RenderUtil.getCameraOffsetZ());
        GLUtil.setMatrix(matrix, MODEL_VIEW);
        boolean radial = GLContext.getCapabilities().GL_NV_fog_distance
            && GL11.glGetInteger(0x855A) == 0x855B;
        GL20.glUniform1i(shader.getUniform("iceFogDistance"), radial ? 1 : 0);
    }
}
