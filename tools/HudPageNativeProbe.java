package dev.rlcraft.ice.optimizer.render.hud;

import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.resource.*;
import java.nio.ByteBuffer;
import java.util.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Offline page-wrap, mixed topology and low-budget output/cost comparison. */
public final class HudPageNativeProbe {
    private static final int SIZE = 64, BATCHES = 4096;
    private static final Map<String, Long> calls = new TreeMap<String, Long>();
    private static boolean counting;
    private static int texture;
    private static final float[] glyph = new float[20];
    public static void count(String name) {
        if (counting) calls.put(name, calls.containsKey(name) ? calls.get(name) + 1 : 1L);
    }
    public static void main(String[] args) throws Exception {
        boolean old = args.length > 0 && args[0].equals("old");
        Pbuffer context = new Pbuffer(SIZE, SIZE, new PixelFormat(), null, null);
        try {
            context.makeCurrent();
            texture = GL11.glGenTextures(); GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            ByteBuffer white = BufferUtils.createByteBuffer(4); white.putInt(-1).flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, white);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            Fixture fixture = new Fixture(1 << 20);
            setup(); for (int i = 0; i < 32; i++) batch(fixture.hud, i, false, false);
            GL11.glFinish();
            setup(); for (int i = 0; i < BATCHES; i++) batch(fixture.hud, i, true, false);
            byte[] expected = pixels();
            setup(); calls.clear(); counting = true;
            for (int i = 0; i < BATCHES; i++) batch(fixture.hud, i, false, false);
            counting = false;
            require(Arrays.equals(expected, pixels()), "page wrap/queued output changed");
            Map<String, Long> pageCalls = new TreeMap<String, Long>(calls);
            setup(); for (int i = 0; i < 512; i++) batch(fixture.hud, i, true, true);
            byte[] mixedExpected = pixels();
            setup(); long flushes = fixture.hud.getModernFlushes();
            for (int i = 0; i < 512; i++) batch(fixture.hud, i, false, true);
            require(Arrays.equals(mixedExpected, pixels()), "mixed topology painter order changed");
            long mixedFlushes = fixture.hud.getModernFlushes() - flushes;
            require(mixedFlushes == (old ? 1536 : 512), "unexpected topology splits " + mixedFlushes);
            // Oversize upload allocates a larger page, then later small batches
            // append behind it. No draw is finished until the final readback.
            setup(); growth(fixture.hud, true); byte[] growthExpected = pixels();
            setup(); growth(fixture.hud, false);
            require(Arrays.equals(growthExpected, pixels()), "growth/reuse output changed");

            long[] costs = new long[11];
            for (int round = -4; round < costs.length; round++) {
                setup(); GL11.glViewport(0, 0, 1, 1); GL11.glFinish();
                long start = System.nanoTime();
                for (int i = 0; i < BATCHES; i++) batch(fixture.hud, i, false, false);
                long elapsed = System.nanoTime() - start;
                GL11.glFinish(); if (round >= 0) costs[round] = elapsed;
            }
            Arrays.sort(costs); fixture.close();
            Fixture tight = new Fixture(4096);
            setup(); for (int i = 0; i < 256; i++) batch(tight.hud, i, false, false);
            require(tight.hud.getLegacyFlushes() == 0, "page preference rejected a fitting small batch");
            tight.close();
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "GL error");
            System.out.println("PASS mode=" + (old ? "old" : "new") + " batches=" + BATCHES
                + " exact_pixels=true growth_pixels=true low_budget_modern=true mixed_flushes=" + mixedFlushes
                + " native_calls=" + pageCalls + " median_cpu_submission_ns_per_batch="
                + costs[costs.length / 2] / (double) BATCHES + " rounds=" + costs.length
                + " renderer=" + GL11.glGetString(GL11.GL_RENDERER));
            GL11.glDeleteTextures(texture);
        } finally { context.destroy(); }
    }
    private static void batch(LwjglHudRenderer hud, int i, boolean legacy, boolean mixed) {
        int tile = i * 73 % 256;
        float x = (tile % 16) / 8F - 1F, y = (tile / 16) / 8F - 1F;
        GL11.glColor4f((i % 7 + 1) / 8F, (i % 5 + 1) / 6F, (i % 3 + 1) / 4F, .5F);
        int count = mixed ? 3 : 1 + (i % 4);
        for (int j = 0; j < count; j++) primitive(hud, x, y, mixed ? j == 1 : (i & 1) != 0, legacy);
        if (!legacy) flush(hud);
    }
    private static void growth(LwjglHudRenderer hud, boolean legacy) {
        for (int n = 1; n <= 512; n *= 2) {
            GL11.glColor4f(.1F, .2F, .3F, .05F);
            for (int i = 0; i < n; i++) primitive(hud, (i % 16) / 8F - 1F,
                (i / 16 % 16) / 8F - 1F, false, legacy);
            if (!legacy) flush(hud);
        }
        for (int i = 0; i < 256; i++) batch(hud, i, legacy, false);
    }
    private static void primitive(LwjglHudRenderer hud, float x, float y, boolean strip, boolean legacy) {
        float w = .125F;
        if (strip) for (int c = 0; c < 4; c++) {
            int offset = c * 5;
            glyph[offset] = x + ((c & 1) != 0 ? w : 0);
            glyph[offset + 1] = y + (c >= 2 ? w : 0);
            glyph[offset + 2] = 0; glyph[offset + 3] = c & 1; glyph[offset + 4] = c / 2;
        }
        if (!legacy) {
            boolean recorded = strip ? hud.recordGlyph(glyph) : hud.recordQuad(x,y,0,0,0,x+w,y+w,1,1);
            if (!recorded) { flush(hud); recorded = strip ? hud.recordGlyph(glyph) : hud.recordQuad(x,y,0,0,0,x+w,y+w,1,1); }
            require(recorded, "record failed"); return;
        }
        GL11.glBegin(strip ? GL11.GL_TRIANGLE_STRIP : GL11.GL_QUADS);
        if (strip) for (int c = 0; c < 4; c++) {
            GL11.glTexCoord2f(glyph[c * 5 + 3], glyph[c * 5 + 4]);
            GL11.glVertex3f(glyph[c * 5], glyph[c * 5 + 1], 0);
        } else for (int c = 0; c < 4; c++) {
            boolean right = c == 1 || c == 2, top = c < 2;
            GL11.glTexCoord2f(right ? 1 : 0, top ? 1 : 0);
            GL11.glVertex3f(x + (right ? w : 0), y + (top ? w : 0), 0);
        }
        GL11.glEnd();
    }
    private static void flush(LwjglHudRenderer hud) {
        require(hud.flush(EarlyGlStateTracker.hudSnapshot()) == LwjglHudRenderer.FlushResult.MODERN,
            "modern flush failed " + hud.getLastError());
    }
    private static void setup() {
        GL20.glUseProgram(0); GL30.glBindVertexArray(0); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,0);
        GL11.glViewport(0,0,SIZE,SIZE); GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glLoadIdentity();
        GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE); GL11.glDisable(GL11.GL_FOG); GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_BLEND); GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL13.glActiveTexture(GL13.GL_TEXTURE0); GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture); GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glClearColor(0,0,0,0); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        EarlyGlStateTracker.beginProbe(); EarlyGlStateTracker.useProgram(0);
        EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER,0); EarlyGlStateTracker.bindVertexArray(0);
        EarlyGlStateTracker.activeTexture(GL13.GL_TEXTURE0); EarlyGlStateTracker.clientActiveTexture(GL13.GL_TEXTURE0);
        EarlyGlStateTracker.bindTexture(texture); EarlyGlStateTracker.texture2dEnabled(true);
        EarlyGlStateTracker.viewport(0,0,SIZE,SIZE);
    }
    private static byte[] pixels() {
        ByteBuffer buffer = BufferUtils.createByteBuffer(SIZE*SIZE*4);
        GL11.glReadPixels(0,0,SIZE,SIZE,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,buffer);
        byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); return bytes;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static final class Fixture {
        final CacheBudget budget;
        final ResourceLedger ledger;
        final LwjglHudRenderer hud;
        Fixture(int gpuBytes) {
            RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
            budget = new CacheBudget(1 << 20, 1 << 20, gpuBytes);
            ledger = new ResourceLedger(guard, budget, new LwjglResourceDestroyer(), 128);
            hud = new LwjglHudRenderer(guard, ledger, budget, 1024); hud.prepare(1,1);
        }
        void close() {
            hud.close(true); GL11.glFinish(); ledger.collect(1,4096); ledger.collect(1,4096); ledger.destroyAll(1);
            require(budget.snapshot().getGpuUsed()==0 && budget.snapshot().getDirectUsed()==0, "resource leak");
        }
    }
}
