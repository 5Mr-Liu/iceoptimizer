package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyModelLightingState;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.*;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.nio.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Hidden native proof of the production vertex shader, offsets and GL43 MDI. */
public final class TerrainBatchNativeProbe {
    private static final int SIZE = 96;
    private static final int QUADS = 4;
    private static final float[][] CORNERS = {{-.35F,-.35F},{.35F,-.35F},{.35F,.35F},{-.35F,.35F}};
    private static int originalVao, originalArray, originalIndirect;
    private static final int[] textures = new int[32];
    private static final boolean[] textureEnabled = new boolean[32];

    public static void main(String[] args) throws Exception {
        Pbuffer context = new Pbuffer(SIZE, SIZE, new PixelFormat().withDepthBits(24), null, null);
        try {
            context.makeCurrent();
            if (!GLContext.getCapabilities().OpenGL43) throw new AssertionError("GL43 unavailable");
            RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
            CacheBudget budget = new CacheBudget(4 << 20, 4 << 20, 4 << 20);
            ResourceLedger ledger = new ResourceLedger(guard, budget, (kind, id) -> {
                if (kind == RenderResourceKind.BUFFER) GL15.glDeleteBuffers(id);
                else if (kind == RenderResourceKind.VERTEX_ARRAY) GL30.glDeleteVertexArrays(id);
                else if (kind == RenderResourceKind.PROGRAM) GL20.glDeleteProgram(id);
            }, 64);
            LwjglTerrainBatchRenderer renderer = new LwjglTerrainBatchRenderer(ledger, budget);
            ClientEpochs epochs = new ClientEpochs();
            epochs.nextFrame();
            FrameStamp stamp = new FrameStamp(1, 1, epochs.snapshot());
            int terrain = GL15.glGenBuffers();
            originalArray = GL15.glGenBuffers();
            originalIndirect = GL15.glGenBuffers();
            originalVao = GL30.glGenVertexArrays();
            try {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, terrain);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices(), GL15.GL_STATIC_DRAW);
                GL30.glBindVertexArray(originalVao);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, originalArray);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, 128L, GL15.GL_STATIC_DRAW);
                GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, originalIndirect);
                GL15.glBufferData(GL40.GL_DRAW_INDIRECT_BUFFER, 128L, GL15.GL_STATIC_DRAW);
                GL11.glViewport(0, 0, SIZE, SIZE);
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glLoadIdentity();
                GL11.glOrtho(-1, 1, -1, 1, .1D, 10D);
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glLoadIdentity();
                GL11.glTranslatef(.03125F, -.0625F, -2F);
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GL11.glAlphaFunc(GL11.GL_GREATER, .1F);
                configureTexture(0, new int[] {0xff7090ff,0xfff0b050,0x00805080,0xff90e060});
                configureTexture(1, new int[] {0xff606080,0xffc09080,0xff90e0a0,0xffffffff});
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                GL11.glMatrixMode(GL11.GL_TEXTURE);
                GL11.glLoadIdentity();
                GL11.glScalef(1F / 256F, 1F / 256F, 1F / 256F);
                GL11.glTranslatef(8F, 8F, 8F);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                FloatBuffer fogColor = BufferUtils.createFloatBuffer(4).put(new float[] {.25F,.1F,.4F,1F});
                fogColor.flip();
                GL11.glFog(GL11.GL_FOG_COLOR, fogColor);
                GL11.glFogi(GL11.GL_FOG_MODE, GL11.GL_LINEAR);
                GL11.glFogf(GL11.GL_FOG_START, .5F);
                GL11.glFogf(GL11.GL_FOG_END, 5F);
                int scenarios = GLContext.getCapabilities().GL_NV_fog_distance ? 5 : 4;
                for (int scenario = 0; scenario < scenarios; scenario++) {
                    GL11.glShadeModel(scenario == scenarios - 1 ? GL11.GL_FLAT : GL11.GL_SMOOTH);
                    if (scenario == 0) GL11.glDisable(GL11.GL_FOG); else GL11.glEnable(GL11.GL_FOG);
                    boolean blend = scenario >= 2;
                    boolean cull = scenario < 2;
                    if (cull) GL11.glEnable(GL11.GL_CULL_FACE); else GL11.glDisable(GL11.GL_CULL_FACE);
                    GL11.glCullFace(GL11.GL_BACK);
                    GL11.glFrontFace(GL11.GL_CCW);
                    if (blend) GL11.glEnable(GL11.GL_BLEND); else GL11.glDisable(GL11.GL_BLEND);
                    GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                    int fogMode = scenario == 3 ? 0x855B : 0x855C;
                    if (GLContext.getCapabilities().GL_NV_fog_distance) GL11.glFogi(0x855A, fogMode);
                    byte[] baseline = legacy(terrain);
                    restoreOriginalBindings();
                    seed(blend, fogMode);
                    EarlyGlStateTracker.cullEnabled(cull);
                    EarlyModelLightingState.seed(new int[EarlyModelLightingState.SIZE], new int[8], 0);
                    if (scenario == 1) {
                        EarlyGlStateTracker.invalidate();
                        EarlyModelLightingState.invalidate();
                    }
                    boolean inspectFaces = scenario < 3;
                    if (!renderer.prepare(8, stamp, EarlyGlStateTracker.compatibilitySnapshot(), originalArray, inspectFaces))
                        throw new AssertionError("prepare: " + renderer.detail(), renderer.consumeFailure());
                    if (renderer.directionCullingAllowed() != (inspectFaces && cull && !blend))
                        throw new AssertionError("direction culling state scenario=" + scenario);
                    for (int quad = 0; quad < QUADS; quad++) {
                        int instance = quad * 2;
                        renderer.offset(instance, offsetX(quad), offsetY(quad), -quad * .125F);
                        renderer.command(4, quad * 4, instance);
                    }
                    renderer.upload();
                    GL11.glClearColor(0,0,0,0);
                    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                    // Nonzero command slices exercise restoration and resumed MDI.
                    renderer.draw(terrain, 0, 2);
                    requireOriginalBindings();
                    renderer.draw(terrain, 2, 2);
                    requireOriginalBindings();
                    byte[] actual = pixels();
                    int visible = 0, maxError = 0, totalError = 0;
                    for (int index = 0; index < actual.length; index++) {
                        if (actual[index] != 0) visible++;
                        int error = Math.abs((actual[index] & 255) - (baseline[index] & 255));
                        maxError = Math.max(maxError, error);
                        totalError += error;
                    }
                    if (visible == 0 || maxError > 1) throw new AssertionError(
                        "pixels scenario=" + scenario + " maxError=" + maxError + " total=" + totalError);
                    if (GL11.glGetError() != GL11.GL_NO_ERROR) throw new AssertionError("GL error scenario=" + scenario);
                    System.out.println("scenario=" + scenario + " max_pixel_error=" + maxError
                        + " total_error=" + totalError + " visible_bytes=" + visible);
                }
                GL11.glEnable(GL11.GL_LIGHTING);
                EarlyModelLightingState.capability(GL11.GL_LIGHTING, true);
                if (renderer.prepare(8, stamp, EarlyGlStateTracker.compatibilitySnapshot(), originalArray, false))
                    throw new AssertionError("known fixed lighting must retain compatibility path");
                EarlyModelLightingState.invalidate();
                if (renderer.prepare(8, stamp, EarlyGlStateTracker.compatibilitySnapshot(), originalArray, false))
                    throw new AssertionError("unknown fixed lighting must retain compatibility path");
                requireOriginalBindings();
                GL11.glDisable(GL11.GL_LIGHTING);
                System.out.println("production_terrain_batch=true region_offsets=true animation_offsets=true"
                    + " nonzero_base_instances=true command_slices=true atlas_lightmap=true fog=true"
                    + " alpha_test=true translucent_order=true flat_shading=true binding_restore=true invalid_tracker_recovery=true"
                    + " directional_state=true lighting_mirror=true lighting_fallback=true renderer="
                    + GL11.glGetString(GL11.GL_RENDERER));
            } finally {
                renderer.close(true);
                GL11.glFinish();
                ledger.destroyAll(1);
                GL30.glBindVertexArray(0);
                GL30.glDeleteVertexArrays(originalVao);
                GL15.glDeleteBuffers(terrain);
                GL15.glDeleteBuffers(originalArray);
                GL15.glDeleteBuffers(originalIndirect);
                GL11.glDeleteTextures(textures[0]);
                GL11.glDeleteTextures(textures[1]);
            }
        } finally { context.destroy(); }
    }

    private static float offsetX(int quad) { return -quad * 256F + (quad % 2 == 0 ? -.25F : .25F); }
    private static float offsetY(int quad) { return (quad / 2 == 0 ? -.25F : .25F) - quad * .0625F; }
    private static ByteBuffer vertices() {
        ByteBuffer bytes = BufferUtils.createByteBuffer(QUADS * 112).order(ByteOrder.nativeOrder());
        for (int quad = 0; quad < QUADS; quad++) for (int vertex = 0; vertex < 4; vertex++) {
            float[] xy = CORNERS[vertex];
            bytes.putFloat(quad * 256F + xy[0]).putFloat(xy[1]).putFloat(0F);
            bytes.put((byte) (64 + quad * 50 + vertex * 10)).put((byte) (230 - quad * 30 - vertex * 20)).put((byte) 180).put((byte) 176);
            bytes.putFloat(vertex == 1 || vertex == 2 ? 1F : 0F).putFloat(vertex >= 2 ? 1F : 0F);
            bytes.putShort((short) (quad * 70)).putShort((short) (240 - quad * 50));
        }
        bytes.flip();
        return bytes;
    }

    private static byte[] legacy(int terrain) {
        GL11.glClearColor(0,0,0,0);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, terrain);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        GL11.glVertexPointer(3, GL11.GL_FLOAT, 28, 0L);
        GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, 28, 12L);
        GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, 28, 16L);
        GL13.glClientActiveTexture(GL13.GL_TEXTURE1);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glTexCoordPointer(2, GL11.GL_SHORT, 28, 24L);
        GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
        for (int quad = 0; quad < QUADS; quad++) {
            GL11.glPushMatrix();
            GL11.glTranslatef(offsetX(quad), offsetY(quad), -quad * .125F);
            GL11.glDrawArrays(GL11.GL_QUADS, quad * 4, 4);
            GL11.glPopMatrix();
        }
        return pixels();
    }

    private static void configureTexture(int unit, int[] rgba) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        textures[unit] = GL11.glGenTextures();
        textureEnabled[unit] = true;
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[unit]);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        ByteBuffer bytes = BufferUtils.createByteBuffer(16);
        for (int value : rgba) bytes.putInt(value);
        bytes.flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 2, 2, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, bytes);
    }

    private static void seed(boolean blend, int fogMode) {
        EarlyGlStateTracker.beginProbe();
        EarlyGlStateTracker.useProgram(0);
        EarlyGlStateTracker.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        EarlyGlStateTracker.bindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, originalArray);
        EarlyGlStateTracker.bindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, originalIndirect);
        EarlyGlStateTracker.clientActiveTexture(GL13.GL_TEXTURE0);
        EarlyGlStateTracker.depthFunction(GL11.GL_LESS);
        EarlyGlStateTracker.lightingEnabled(false);
        EarlyGlStateTracker.fogi(0x855A, fogMode);
        EarlyGlStateTracker.seedDrawState(0, textures[0], textures[1], blend, GL11.GL_SRC_ALPHA,
            GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
            false, true, false, 15, 1F, 1F, 1F, 1F);
        EarlyGlStateTracker.seedHudState(true, true, 0, 0, SIZE, SIZE);
        EarlyGlStateTracker.seedCompatibilityState(originalVao, true, 0, 0, 2, textures, textureEnabled,
            GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD, GL11.GL_BACK, false, false, 0, 0, SIZE, SIZE);
    }
    private static void restoreOriginalBindings() {
        GL30.glBindVertexArray(originalVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, originalArray);
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, originalIndirect);
    }
    private static void requireOriginalBindings() {
        if (GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING) != originalVao
            || GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING) != originalArray
            || GL11.glGetInteger(GL40.GL_DRAW_INDIRECT_BUFFER_BINDING) != originalIndirect
            || GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) != 0
            || EarlyGlStateTracker.arrayBufferBinding() != originalArray
            || EarlyGlStateTracker.drawIndirectBufferBinding() != originalIndirect)
            throw new AssertionError("native binding restore");
    }
    private static byte[] pixels() {
        ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        GL11.glReadPixels(0,0,SIZE,SIZE,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
        byte[] bytes = new byte[pixels.remaining()];
        pixels.get(bytes);
        return bytes;
    }
}
