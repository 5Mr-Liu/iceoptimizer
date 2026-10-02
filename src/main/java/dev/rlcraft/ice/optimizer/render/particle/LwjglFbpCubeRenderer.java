package dev.rlcraft.ice.optimizer.render.particle;

import dev.rlcraft.ice.optimizer.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import org.lwjgl.opengl.GL11;

/** The reviewed FBP layout, with the same client-array/postDraw effects as Forge. */
public final class LwjglFbpCubeRenderer {
    public static final int STRIDE = 31;
    private static final MethodHandle MULTI_TEXTURE = multiTextureMethod();

    private LwjglFbpCubeRenderer() { }

    private static MethodHandle multiTextureMethod() {
        try {
            return MethodHandles.publicLookup().findVirtual(BufferBuilder.class,
                "isMultiTexture", MethodType.methodType(Boolean.TYPE));
        } catch (NoSuchMethodException vanilla) {
            return null;
        } catch (Throwable unavailable) {
            FatalErrors.rethrowIfFatal(unavailable);
            // An inaccessible/unknown OptiFine method must keep its uploader.
            return MethodHandles.dropArguments(MethodHandles.constant(Boolean.TYPE,
                Boolean.TRUE), 0, BufferBuilder.class);
        }
    }

    public static boolean isSingleTexture(BufferBuilder buffer) {
        if (MULTI_TEXTURE == null) return true;
        try { return !(boolean) MULTI_TEXTURE.invokeExact(buffer); }
        catch (Throwable unavailable) {
            FatalErrors.rethrowIfFatal(unavailable);
            return false;
        }
    }

    public static boolean isExactFormat(VertexFormat format) {
        // FBP exposes a mutable format. Check its five descriptors directly;
        // this allocates nothing and notices same-size in-place changes.
        return format != null && format.getClass() == VertexFormat.class
            && format.getSize() == STRIDE && format.getElementCount() == 5
            && element(format, 0, 0, VertexFormatElement.EnumUsage.POSITION,
                VertexFormatElement.EnumType.FLOAT, 3, 0)
            && element(format, 1, 12, VertexFormatElement.EnumUsage.UV,
                VertexFormatElement.EnumType.FLOAT, 2, 0)
            && element(format, 2, 20, VertexFormatElement.EnumUsage.COLOR,
                VertexFormatElement.EnumType.UBYTE, 4, 0)
            && element(format, 3, 24, VertexFormatElement.EnumUsage.UV,
                VertexFormatElement.EnumType.SHORT, 2, 1)
            && element(format, 4, 28, VertexFormatElement.EnumUsage.NORMAL,
                VertexFormatElement.EnumType.BYTE, 3, 0);
    }

    private static boolean element(VertexFormat format, int index, int offset,
                                   VertexFormatElement.EnumUsage usage,
                                   VertexFormatElement.EnumType type,
                                   int count, int texture) {
        VertexFormatElement value = format.getElement(index);
        return value.getClass() == VertexFormatElement.class
            && format.getOffset(index) == offset && value.getUsage() == usage
            && value.getType() == type && value.getElementCount() == count
            && value.getIndex() == texture;
    }

    public static void draw(BufferBuilder buffer) {
        buffer.finishDrawing();
        int vertices = buffer.getVertexCount();
        try {
            if (vertices > 0) {
                try {
                    setupArrays(buffer.getByteBuffer());
                    GlStateManager.glDrawArrays(buffer.getDrawMode(), 0, vertices);
                } finally { finishArrays(); }
            }
        } finally {
            // The packet was consumed by finishDrawing. A failed GL submit
            // propagates; it is never replayed through the transparent fallback.
            buffer.reset();
        }
    }

    private static void setupArrays(ByteBuffer bytes) {
        bytes.position(0);
        GlStateManager.glVertexPointer(3, GL11.GL_FLOAT, STRIDE, bytes);
        GlStateManager.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        bytes.position(12);
        GlStateManager.glTexCoordPointer(2, GL11.GL_FLOAT, STRIDE, bytes);
        GlStateManager.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        bytes.position(20);
        GlStateManager.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, STRIDE, bytes);
        GlStateManager.glEnableClientState(GL11.GL_COLOR_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        bytes.position(24);
        GlStateManager.glTexCoordPointer(2, GL11.GL_SHORT, STRIDE, bytes);
        GlStateManager.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        bytes.position(28);
        GlStateManager.glNormalPointer(GL11.GL_BYTE, STRIDE, bytes);
        GlStateManager.glEnableClientState(GL11.GL_NORMAL_ARRAY);
    }

    private static void finishArrays() {
        GlStateManager.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        GlStateManager.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GlStateManager.glDisableClientState(GL11.GL_COLOR_ARRAY);
        GlStateManager.resetColor();
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.glDisableClientState(GL11.GL_NORMAL_ARRAY);
    }
}
