import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.Pbuffer;
import org.lwjgl.opengl.PixelFormat;

/** Hidden compatibility-context check for model client-array push/pop restoration. */
public final class ModelClientStateRestoreProbe {
    public static void main(String[] args) throws Exception {
        Pbuffer context = new Pbuffer(16, 16, new PixelFormat(), null, null);
        try {
            context.makeCurrent();
            int original = GL15.glGenBuffers();
            int model = GL15.glGenBuffers();
            try {
                ByteBuffer vertices = BufferUtils.createByteBuffer(3 * 24)
                    .order(ByteOrder.nativeOrder());
                for (float[] position : new float[][] {{-1,-1},{1,-1},{0,1}}) {
                    vertices.putFloat(position[0]).putFloat(position[1]).putFloat(0);
                    vertices.putFloat(0).putFloat(0);
                    vertices.put((byte) 0).put((byte) 0).put((byte) 127).put((byte) 0);
                }
                vertices.flip();
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, model);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, original);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);
                GL11.glVertexPointer(2, GL11.GL_FLOAT, 24, 0L);
                GL11.glNormalPointer(GL11.GL_BYTE, 24, 20L);
                GL11.glTexCoordPointer(2, GL11.GL_FLOAT, 24, 12L);
                GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
                GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
                GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
                GL11.glViewport(0, 0, 16, 16);
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glLoadIdentity();
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glLoadIdentity();
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glColor4f(0.25F, 0.5F, 0.75F, 1.0F);
                int[] before = state();
                byte[] oldPixels = draw(model, original, true);
                assertState(before);
                byte[] newPixels = draw(model, original, false);
                assertState(before);
                if (!Arrays.equals(oldPixels, newPixels)) throw new AssertionError("pixel mismatch");
                boolean visible = false;
                for (int i = 0; i < newPixels.length; i += 4) {
                    visible |= newPixels[i] != 0 || newPixels[i + 1] != 0 || newPixels[i + 2] != 0;
                }
                if (!visible) throw new AssertionError("empty output");
                if (GL11.glGetError() != GL11.GL_NO_ERROR) throw new AssertionError("GL error");
                System.out.println("model_client_restore pixels_equal=true state_equal=true redundant_bind_removed=true");
                System.out.println("renderer=" + GL11.glGetString(GL11.GL_RENDERER));
            } finally {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
                GL15.glDeleteBuffers(model);
                GL15.glDeleteBuffers(original);
            }
        } finally {
            context.destroy();
        }
    }

    private static byte[] draw(int model, int original, boolean rebind) {
        GL11.glClearColor(0, 0, 0, 0);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        try {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, model);
            GL11.glVertexPointer(3, GL11.GL_FLOAT, 24, 0L);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glTexCoordPointer(2, GL11.GL_FLOAT, 24, 12L);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glNormalPointer(GL11.GL_BYTE, 24, 20L);
            GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        } finally {
            GL11.glPopClientAttrib();
            if (rebind) GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, original);
        }
        ByteBuffer pixels = BufferUtils.createByteBuffer(16 * 16 * 4);
        GL11.glReadPixels(0, 0, 16, 16, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        byte[] bytes = new byte[pixels.remaining()];
        pixels.get(bytes);
        return bytes;
    }

    private static int[] state() {
        return new int[] {
            GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING),
            GL11.glGetInteger(GL15.GL_VERTEX_ARRAY_BUFFER_BINDING),
            GL11.glGetInteger(GL15.GL_NORMAL_ARRAY_BUFFER_BINDING),
            GL11.glGetInteger(GL15.GL_TEXTURE_COORD_ARRAY_BUFFER_BINDING),
            GL11.glGetInteger(GL11.GL_VERTEX_ARRAY_SIZE),
            GL11.glGetInteger(GL11.GL_VERTEX_ARRAY_STRIDE),
            GL11.glIsEnabled(GL11.GL_VERTEX_ARRAY) ? 1 : 0,
            GL11.glIsEnabled(GL11.GL_NORMAL_ARRAY) ? 1 : 0,
            GL11.glIsEnabled(GL11.GL_TEXTURE_COORD_ARRAY) ? 1 : 0
        };
    }

    private static void assertState(int[] before) {
        if (!Arrays.equals(before, state())) throw new AssertionError("client-array state mismatch");
    }
}
