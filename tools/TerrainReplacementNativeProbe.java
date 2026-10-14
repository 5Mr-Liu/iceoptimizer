package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.backend.CapabilityReport;
import dev.rlcraft.ice.optimizer.render.backend.ModernCapability;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.*;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.lang.reflect.Field;
import java.util.Arrays;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.BlockRenderLayer;
import org.lwjgl.opengl.*;
import static dev.rlcraft.ice.optimizer.render.terrain.TerrainOrderedRangesNativeProbe.*;

/** Ordered draw/update stress comparison; run frozen and candidate classes in ABBA order.
 * Synthetic upload cost, not captured-game FPS. Keep the completion wait outside CPU samples.
 */
public final class TerrainReplacementNativeProbe {
    private static final FrameStamp STAMP = new FrameStamp(1, 1, new ClientEpochs().snapshot());
    private static final int SCISSOR = Math.max(1, Math.min(64, Integer.getInteger("ice.replacementScissor", 1)));
    private static long frame;

    public static void main(String[] args) throws Exception {
        Pbuffer context = new Pbuffer(64, 64, new PixelFormat().withDepthBits(24), null, null);
        try {
            context.makeCurrent();
            OpenGlHelper.defaultTexUnit = GL13.GL_TEXTURE0;
            OpenGlHelper.lightmapTexUnit = GL13.GL_TEXTURE1;
            for (boolean persistent : new boolean[] {false, true}) {
                for (int quads : new int[] {8, 128, 1024}) run(persistent, quads);
            }
            require(GL11.glGetError() == 0, "GL error");
            System.out.println("renderer=" + GL11.glGetString(GL11.GL_RENDERER));
        } finally { context.destroy(); }
    }

    private static void run(boolean persistent, int quads) throws Exception {
        CacheBudget budget = new CacheBudget(64L << 20, 64L << 20, 64L << 20);
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger resources = ledger(guard, budget);
        LwjglTerrainArena arena = new LwjglTerrainArena(guard, resources, budget,
            CapabilityReport.builder().pass(ModernCapability.MULTI_DRAW_INDIRECT)
                .pass(ModernCapability.PERSISTENT_MAPPING).build(), 32L << 20, 1L);
        TestChunk[] chunks = new TestChunk[32];
        long[] samples = new long[512];
        try {
            arena.configureNativeBatching(true, false);
            for (int i = 0; i < chunks.length; i++) {
                chunks[i] = chunk(); upload(arena, chunks[i], payload(quads, i), persistent);
            }
            setup();
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor((64 - SCISSOR) / 2, (64 - SCISSOR) / 2, SCISSOR, SCISSOR);
            for (int iteration = 0; iteration < 768; iteration++) {
                // Prepare payloads before timing so Java builders do not obscure upload cost.
                BufferBuilder[] data = new BufferBuilder[4];
                for (int update = 0; update < 4; update++) data[update] = payload(quads, iteration + update);
                GL11.glFinish();
                require(arena.render(new Container(chunks), BlockRenderLayer.SOLID, true, true, ++frame), "draw");
                long start = System.nanoTime();
                for (int update = 0; update < 4; update++)
                    upload(arena, chunks[(iteration * 4 + update) % chunks.length], data[update], persistent);
                arena.endFrame(true);
                long elapsed = System.nanoTime() - start;
                if (iteration >= 256) samples[iteration - 256] = elapsed;
            }
            GL11.glFinish();
            // Check that the stress trace kept ownership; pixel/byte ordering
            // is independently covered by TerrainOrderedRangesNativeProbe.
            for (int i = 0; i < chunks.length; i++) {
                Field meshesField = LwjglTerrainArena.class.getDeclaredField("meshes");
                meshesField.setAccessible(true);
                Object record = ((java.util.Map<?, ?>) meshesField.get(arena)).get(chunks[i].vbo);
                require(record != null, "resident record missing");
            }
            Arrays.sort(samples);
            System.out.println("persistent=" + persistent + " quads=" + quads + " updates=4"
                + " scissor=" + SCISSOR
                + " queued_draw=true cpu_update_p50_ms=" + samples[256] / 1e6
                + " cpu_update_p95_ms=" + samples[486] / 1e6
                + " cpu_update_p99_ms=" + samples[506] / 1e6
                + " rejected=" + arena.getRejectedUploads());
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        } finally {
            for (TestChunk chunk : chunks) if (chunk != null) arena.release(chunk.vbo);
            arena.close(true); GL11.glFinish(); resources.destroyAll(1);
        }
        require(budget.snapshot().getGpuUsed() == 0 && budget.snapshot().getDirectUsed() == 0
            && budget.snapshot().getHeapUsed() == 0, "budget leak");
    }

    private static BufferBuilder payload(int quads, int revision) {
        return builder(-.9F, .9F, revision & 255, 190, 80, quads);
    }

    private static void upload(LwjglTerrainArena arena, TestChunk chunk, BufferBuilder builder, boolean persistent) {
        TerrainUploadContext.begin(BlockRenderLayer.SOLID, chunk, chunk.compiled);
        require(arena.upload(TerrainUploadContext.take(), builder, chunk.vbo, STAMP,
            persistent, persistent, false), "upload rejected");
    }
}
