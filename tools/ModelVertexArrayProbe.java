import dev.rlcraft.ice.optimizer.compat.gl.*;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.entity.*;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.*;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.nio.*;
import java.util.Arrays;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Offline real-driver comparison against a compiled display list, never run in game. */
public final class ModelVertexArrayProbe {
    private static final int SIZE = 96, PARTS = 8;
    private static int callerVao, callerBuffer, callerElements, list;
    private static LwjglModelMeshCache cache;
    private static FrameStamp stamp;
    private static LwjglModelMeshCache.Entry[] entries;

    public static void main(String[] args) throws Exception {
        Pbuffer context = new Pbuffer(SIZE, SIZE, new PixelFormat().withDepthBits(24), null, null);
        try {
            context.makeCurrent();
            RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
            CacheBudget budget = new CacheBudget(4 << 20, 4 << 20, 4 << 20);
            final int[] arraysDeleted = {0};
            ResourceLedger ledger = new ResourceLedger(guard, budget, (kind, id) -> {
                if (kind == RenderResourceKind.VERTEX_ARRAY) {
                    GL30.glDeleteVertexArrays(id); arraysDeleted[0]++;
                } else if (kind == RenderResourceKind.BUFFER) GL15.glDeleteBuffers(id);
                else if (kind == RenderResourceKind.PROGRAM) GL20.glDeleteProgram(id);
                else if (kind == RenderResourceKind.SHADER) GL20.glDeleteShader(id);
            }, 128);
            cache = new LwjglModelMeshCache(guard, ledger, budget);
            ClientEpochs epochs = new ClientEpochs(); epochs.nextFrame();
            stamp = new FrameStamp(1, 1, epochs.snapshot());
            callerVao = GL30.glGenVertexArrays();
            callerBuffer = GL15.glGenBuffers(); callerElements = GL15.glGenBuffers();
            GL30.glBindVertexArray(callerVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, callerBuffer);
            ByteBuffer junk = BufferUtils.createByteBuffer(65536);
            for (int i = 0; i < junk.capacity() / 4; i++) junk.putFloat(.1F);
            junk.flip(); GL15.glBufferData(GL15.GL_ARRAY_BUFFER, junk, GL15.GL_STATIC_DRAW);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, callerElements);
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, 128, GL15.GL_STATIC_DRAW);
            seed();
            entries = new LwjglModelMeshCache.Entry[PARTS];
            byte[] vertices = quad();
            // Capture with client texture 1 active to prove initialization restores it.
            GL13.glClientActiveTexture(GL13.GL_TEXTURE1);
            EarlyGlStateTracker.clientActiveTexture(GL13.GL_TEXTURE1);
            for (int i = 0; i < PARTS; i++) {
                if (!cache.accept(new ModelMeshPayload(i + 1, vertices,
                    DefaultVertexFormats.POSITION_TEX_NORMAL, GL11.GL_QUADS, 4, 1, 1), stamp))
                    throw new AssertionError("upload");
                entries[i] = cache.find(i + 1, stamp);
            }
            eq(GL13.GL_TEXTURE1, GL11.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE), "upload client texture");
            eq(callerVao, GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING), "upload VAO");
            eq(callerBuffer, GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING), "upload buffer");
            GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
            list = GL11.glGenLists(1);
            GL11.glNewList(list, GL11.GL_COMPILE);
            GL11.glBegin(GL11.GL_QUADS);
            ByteBuffer data = ByteBuffer.wrap(vertices).order(ByteOrder.nativeOrder());
            for (int i = 0; i < 4; i++) {
                float x = data.getFloat(), y = data.getFloat(), z = data.getFloat();
                float u = data.getFloat(), v = data.getFloat();
                byte nx = data.get(), ny = data.get(), nz = data.get(); data.get();
                GL11.glNormal3b(nx, ny, nz); GL11.glTexCoord2f(u, v); GL11.glVertex3f(x, y, z);
            }
            GL11.glEnd(); GL11.glEndList();
            try {
                contaminateCaller();
                int[] state = state();
                for (int frame = 0; frame < 12; frame++) {
                    byte[] reference = render(0, frame);
                    for (int mode = 1; mode <= 4; mode++) {
                        byte[] result = render(mode, frame);
                        if (!Arrays.equals(reference, result)) throw new AssertionError(
                            "display-list pixel mismatch mode=" + mode + " frame=" + frame);
                        if (!Arrays.equals(state, state())) throw new AssertionError("caller VAO/client state changed mode=" + mode);
                        eq(GL11.GL_NO_ERROR, GL11.glGetError(), "GL error");
                    }
                    for (int mode : new int[]{1, 2}) {
                        byte[] fallback = withoutPageVertexArray(mode, frame);
                        if (!Arrays.equals(reference, fallback)) throw new AssertionError("no VAO fallback pixel mismatch mode=" + mode);
                        if (!Arrays.equals(state, state())) throw new AssertionError("no VAO fallback changed caller state");
                    }
                }
                EarlyGlStateTracker.invalidate(); seed();
                if (EarlyGlStateTracker.vertexArrayBinding() != Integer.MIN_VALUE)
                    throw new AssertionError("VAO binding should still need one seed");
                render(1, 12);
                eq(callerVao, EarlyGlStateTracker.vertexArrayBinding(), "VAO recovered after invalidation");
                if (args.length > 0 && "--timing".equals(args[0])) timing();
                epochs.invalidateGlContext();
                FrameStamp stale = new FrameStamp(2, 2, epochs.snapshot());
                if (cache.drawCurrent(entries[0], stale)) throw new AssertionError("generation accepted");
                cache.invalidate(1);
                if (cache.drawCurrent(entries[0], stamp)) throw new AssertionError("retired entry accepted");
                System.out.println("PASS display_list_pixels_equal=72 moving_frames=12 unknown_matrix_immediate=true unknown_matrix_array_scope=true pose_mid_scope_invalidation=true caller_nonzero_vao=true caller_color_array=true caller_texture1_array=true caller_ebo=true unknown_vao_recovered=true no_page_vao_fallback=true generations_retirement=true");
            } finally {
                cache.close(true); GL11.glFinish(); ledger.destroyAll(1);
                if (arraysDeleted[0] != 2) throw new AssertionError("one page and one pose VAO expected, got " + arraysDeleted[0]);
                GL11.glDeleteLists(list, 1);
                GL30.glBindVertexArray(0); GL30.glDeleteVertexArrays(callerVao);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
                GL15.glDeleteBuffers(callerBuffer); GL15.glDeleteBuffers(callerElements);
            }
            if (budget.snapshot().getGpuUsed() != 0 || budget.snapshot().getDirectUsed() != 0)
                throw new AssertionError("budget leak");
            System.out.println("PASS shared_page_and_pose_vaos_deleted_once=true budget_zero=true renderer=" + GL11.glGetString(GL11.GL_RENDERER));
        } finally { context.destroy(); }
    }

    private static byte[] withoutPageVertexArray(int mode, int frame) throws Exception {
        java.lang.reflect.Field allocationField = LwjglModelMeshCache.Entry.class.getDeclaredField("allocation");
        allocationField.setAccessible(true);
        Object allocation = allocationField.get(entries[0]);
        java.lang.reflect.Field pageField = allocation.getClass().getDeclaredField("page");
        pageField.setAccessible(true);
        Object page = pageField.get(allocation);
        java.lang.reflect.Field vaoField = page.getClass().getDeclaredField("vertexArray");
        vaoField.setAccessible(true);
        Object vao = vaoField.get(page);
        vaoField.set(page, null);
        try { return render(mode, frame); }
        finally { vaoField.set(page, vao); }
    }

    private static void timing() {
        GL30.glBindVertexArray(0); EarlyGlStateTracker.bindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0); EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL11.glViewport(0, 0, 1, 1); GL11.glDisable(GL11.GL_BLEND);
        final int draws = 12000, rounds = 15;
        long[][] elapsed = new long[2][rounds];
        for (int round = -6; round < rounds; round++) {
            for (int order = 0; order < 2; order++) {
                int mode = (round + order) & 1;
                GL11.glFinish();
                long start = System.nanoTime();
                for (int part = 0; part < draws; part++) {
                    LwjglModelMeshCache.Entry entry = entries[part % PARTS];
                    if (mode == 0) {
                        // Exact pre-change immediate submission, with its original
                        // model array layout, client stack, and software restore.
                        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
                        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, entry.getNativeBuffer());
                        EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, entry.getNativeBuffer());
                        GL11.glVertexPointer(3, GL11.GL_FLOAT, 24, 0L);
                        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
                        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, 24, 12L);
                        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
                        GL11.glNormalPointer(GL11.GL_BYTE, 24, 20L);
                        GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
                        GL11.glDrawArrays(GL11.GL_QUADS, entry.getFirstVertex(), 4);
                        GL11.glPopClientAttrib();
                        EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, 0);
                    } else if (!cache.drawCurrent(entry, stamp)) throw new AssertionError("timing draw declined");
                }
                long nanos = System.nanoTime() - start;
                GL11.glFinish();
                if (round >= 0) elapsed[mode][round] = nanos;
            }
        }
        Arrays.sort(elapsed[0]); Arrays.sort(elapsed[1]);
        System.out.printf(java.util.Locale.ROOT,
            "OFFLINE_CPU_SUBMISSION draws=%d rounds=%d old_median_ns_per_part=%.1f vao_median_ns_per_part=%.1f old_gl_calls=10 vao_gl_calls=3 scope_gl_calls_N_parts=N+2 game_fps_measured=false%n",
            draws, rounds, elapsed[0][rounds / 2] / (double)draws,
            elapsed[1][rounds / 2] / (double)draws);
        GL30.glBindVertexArray(callerVao); EarlyGlStateTracker.bindVertexArray(callerVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, callerBuffer); EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, callerBuffer);
    }

    private static byte[] render(int mode, int frame) {
        GL11.glViewport(0, 0, SIZE, SIZE);
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glLoadIdentity();
        FloatBuffer identity = floats(1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1);
        EarlyMatrixStateTracker.seed(GL11.GL_MODELVIEW, identity, identity);
        GL11.glDisable(GL11.GL_LIGHTING); GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE); GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND); GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(.4F, .7F, .9F, .55F);
        GL11.glClearColor(0,0,0,0); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        seed(); cache.seedPoseState();
        if (mode == 1 || mode == 2 || mode == 4) EarlyMatrixStateTracker.invalidate();
        boolean batch = mode >= 2;
        if (batch && !(mode == 2 ? cache.beginStateBatch() : cache.beginStateBatch(entries, entries.length)))
            throw new AssertionError("unknown matrix scope rejected");
        try {
            for (int i = 0; i < PARTS; i++) {
                if (!EarlyModelBatchBarrier.deferPush()) { GL11.glPushMatrix(); EarlyMatrixStateTracker.pushMatrix(); }
                float x = (i % 4 - 1.5F) * .35F + (float)Math.sin(frame * .3) * .04F;
                float y = (i / 4 - .5F) * .65F;
                if (!EarlyModelBatchBarrier.deferTranslate(x, y, 0)) { GL11.glTranslatef(x, y, 0); EarlyMatrixStateTracker.translate(x, y, 0); }
                if (mode == 3 && i == 3) EarlyMatrixStateTracker.invalidate();
                if (mode == 0) GL11.glCallList(list);
                else if (!(batch ? cache.drawInStateBatch(entries[i]) : cache.drawCurrent(entries[i], stamp)))
                    throw new AssertionError("draw rejected mode=" + mode);
                if (!EarlyModelBatchBarrier.deferPop()) { GL11.glPopMatrix(); EarlyMatrixStateTracker.popMatrix(); }
            }
        } finally { if (batch) cache.endStateBatch(); }
        ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        byte[] bytes = new byte[pixels.remaining()]; pixels.get(bytes); return bytes;
    }

    private static void contaminateCaller() {
        GL11.glVertexPointer(3, GL11.GL_FLOAT, 24, 0L); GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glNormalPointer(GL11.GL_BYTE, 24, 20L); GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
        GL11.glColorPointer(4, GL11.GL_FLOAT, 16, 0L); GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, 24, 12L); GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL13.glClientActiveTexture(GL13.GL_TEXTURE1);
        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, 16, 8L); GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
    }

    private static int[] state() {
        int[] values = new int[14];
        values[0] = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        values[1] = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        values[2] = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        values[3] = GL11.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE);
        values[4] = GL11.glIsEnabled(GL11.GL_COLOR_ARRAY) ? 1 : 0;
        values[5] = GL11.glGetInteger(GL15.GL_COLOR_ARRAY_BUFFER_BINDING);
        values[6] = GL11.glGetInteger(GL11.GL_COLOR_ARRAY_STRIDE);
        values[7] = GL11.glIsEnabled(GL11.GL_NORMAL_ARRAY) ? 1 : 0;
        values[8] = GL11.glGetInteger(GL15.GL_NORMAL_ARRAY_BUFFER_BINDING);
        values[9] = GL11.glGetInteger(GL11.GL_VERTEX_ARRAY_STRIDE);
        values[10] = GL11.glIsEnabled(GL11.GL_TEXTURE_COORD_ARRAY) ? 1 : 0;
        GL13.glClientActiveTexture(GL13.GL_TEXTURE1);
        values[11] = GL11.glIsEnabled(GL11.GL_TEXTURE_COORD_ARRAY) ? 1 : 0;
        values[12] = GL11.glGetInteger(GL15.GL_TEXTURE_COORD_ARRAY_BUFFER_BINDING);
        values[13] = GL11.glGetInteger(GL11.GL_TEXTURE_COORD_ARRAY_STRIDE);
        GL13.glClientActiveTexture(values[3]);
        return values;
    }

    private static void seed() {
        EarlyGlStateTracker.useProgram(0); EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, callerBuffer);
        EarlyGlStateTracker.clientActiveTexture(GL13.GL_TEXTURE0); EarlyGlStateTracker.depthFunction(GL11.GL_LESS);
        EarlyGlStateTracker.seedDrawState(0, 0, 0, true, GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, false, true, false, 15, .4F, .7F, .9F, .55F);
    }
    private static FloatBuffer floats(float... values) { FloatBuffer b = BufferUtils.createFloatBuffer(values.length); b.put(values).flip(); return b; }
    private static byte[] quad() {
        ByteBuffer data = ByteBuffer.allocate(96).order(ByteOrder.nativeOrder());
        for (float[] xy : new float[][]{{-.16F,-.16F}, {.16F,-.16F}, {.16F,.16F}, {-.16F,.16F}}) {
            data.putFloat(xy[0]).putFloat(xy[1]).putFloat(0).putFloat(0).putFloat(0);
            data.put((byte)0).put((byte)0).put((byte)127).put((byte)0);
        }
        return data.array();
    }
    private static void eq(int expected, int actual, String name) {
        if (expected != actual) throw new AssertionError(name + " expected=" + expected + " actual=" + actual);
    }
}
