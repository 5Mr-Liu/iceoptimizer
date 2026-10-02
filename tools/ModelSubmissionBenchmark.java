import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyMatrixStateTracker;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.entity.LwjglModelMeshCache;
import dev.rlcraft.ice.optimizer.render.entity.ModelMeshPayload;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.Pbuffer;
import org.lwjgl.opengl.PixelFormat;

/** Local CPU submission benchmark; accepts baseline and current cache classpaths. */
public final class ModelSubmissionBenchmark {
    private static final int MODELS = 128;
    private static final int WARMUP_ROUNDS = 8;
    private static final int MEASURED_ROUNDS = 9;

    public static void main(String[] arguments) throws Exception {
        Pbuffer context = new Pbuffer(32, 32, new PixelFormat().withDepthBits(24), null, null);
        try {
            context.makeCurrent();
            System.out.println("renderer=" + GL11.glGetString(GL11.GL_RENDERER));
            for (int parts : new int[] {16, 64, 128}) benchmark(parts);
        } finally {
            context.destroy();
        }
    }

    private static void benchmark(int parts) {
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        CacheBudget budget = new CacheBudget(16L << 20, 16L << 20, 16L << 20);
        ResourceLedger ledger = new ResourceLedger(guard, budget,
            new ResourceLedger.Destroyer() {
                public void destroy(RenderResourceKind kind, int nativeId) {
                    if (kind == RenderResourceKind.BUFFER) GL15.glDeleteBuffers(nativeId);
                }
            }, 1024);
        LwjglModelMeshCache cache = new LwjglModelMeshCache(guard, ledger, budget);
        ClientEpochs epochs = new ClientEpochs();
        epochs.nextFrame();
        FrameStamp stamp = new FrameStamp(1L, 1L, epochs.snapshot());
        try {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            LwjglModelMeshCache.Entry[] entries = new LwjglModelMeshCache.Entry[parts];
            byte[] vertices = quad();
            for (int index = 0; index < parts; index++) {
                if (!cache.accept(new ModelMeshPayload(index + 1, vertices,
                    DefaultVertexFormats.POSITION_TEX_NORMAL, GL11.GL_QUADS, 4, 1L, 1L), stamp)) {
                    throw new AssertionError("mesh upload declined");
                }
                entries[index] = cache.find(index + 1, stamp);
                if (!cache.validate(entries[index])) throw new AssertionError("mesh upload mismatch");
            }
            GL11.glViewport(0, 0, 32, 32);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glColor4f(.25F, .5F, .75F, 1F);
            EarlyGlStateTracker.useProgram(0);
            EarlyGlStateTracker.clientActiveTexture(GL13.GL_TEXTURE0);
            EarlyGlStateTracker.depthFunction(GL11.GL_LESS);
            EarlyGlStateTracker.seedDrawState(0, 0, 0, false,
                GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ZERO,
                false, true, false, 15, .25F, .5F, .75F, 1F);
            FloatBuffer identity = BufferUtils.createFloatBuffer(16);
            for (int index = 0; index < 16; index++) identity.put(index, index % 5 == 0 ? 1F : 0F);
            EarlyMatrixStateTracker.seed(GL11.GL_MODELVIEW, identity, identity);
            for (int index = 0; index < WARMUP_ROUNDS; index++) submit(cache, entries);
            long[] samples = new long[MEASURED_ROUNDS];
            for (int index = 0; index < samples.length; index++) samples[index] = submit(cache, entries);
            Arrays.sort(samples);
            ByteBuffer pixel = BufferUtils.createByteBuffer(4);
            GL11.glReadPixels(16, 16, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
            int checksum = (pixel.get(0) & 255) << 24 | (pixel.get(1) & 255) << 16
                | (pixel.get(2) & 255) << 8 | (pixel.get(3) & 255);
            if (GL11.glGetError() != GL11.GL_NO_ERROR) throw new AssertionError("GL error");
            System.out.println("parts=" + parts + " models_per_round=" + MODELS
                + " median_cpu_ns_per_model=" + samples[samples.length / 2] / MODELS
                + " median_cpu_ns_per_part=" + samples[samples.length / 2] / MODELS / parts
                + " draws=" + cache.getStateBatchDraws() + " pixel=" + Integer.toHexString(checksum));
        } finally {
            cache.close(true);
            ledger.destroyAll(epochs.currentGlContextGeneration());
        }
    }

    private static long submit(LwjglModelMeshCache cache, LwjglModelMeshCache.Entry[] entries) {
        GL11.glFinish();
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        long started = System.nanoTime();
        for (int model = 0; model < MODELS; model++) {
            if (!cache.beginStateBatch()) throw new AssertionError("batch declined");
            try {
                for (int part = 0; part < entries.length; part++) {
                    GL11.glPushMatrix();
                    GL11.glTranslatef((part % 3 - 1) * .01F, (part % 5 - 2) * .01F, 0F);
                    if (!cache.drawInStateBatch(entries[part])) throw new AssertionError("draw declined");
                    GL11.glPopMatrix();
                }
            } finally {
                cache.endStateBatch();
            }
        }
        long elapsed = System.nanoTime() - started;
        GL11.glFinish();
        return elapsed;
    }

    private static byte[] quad() {
        ByteBuffer vertices = ByteBuffer.allocate(4 * 24).order(ByteOrder.nativeOrder());
        for (float[] position : new float[][] {{-.2F,-.2F},{.2F,-.2F},{.2F,.2F},{-.2F,.2F}}) {
            vertices.putFloat(position[0]).putFloat(position[1]).putFloat(0F);
            vertices.putFloat(0F).putFloat(0F);
            vertices.put((byte) 0).put((byte) 0).put((byte) 127).put((byte) 0);
        }
        return vertices.array();
    }
}
