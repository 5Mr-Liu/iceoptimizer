package dev.rlcraft.ice.optimizer.render.terrain;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL32;

/** Draws ICE-owned geometry through the shader pack's original vertex program. */
final class NativeTerrainShaderPack {
    private static final MethodHandle POINTERS;
    private static final int MID_UV, TANGENT, ENTITY;

    static {
        try {
            ClassLoader loader = NativeTerrainShaderPack.class.getClassLoader();
            Class<?> shaders = Class.forName("net.optifine.shaders.Shaders", false, loader);
            Class<?> render = Class.forName("net.optifine.shaders.ShadersRender", false, loader);
            POINTERS = MethodHandles.publicLookup().unreflect(render.getMethod("setupArrayPointersVbo"));
            MID_UV = shaders.getField("midTexCoordAttrib").getInt(null);
            TANGENT = shaders.getField("tangentAttrib").getInt(null);
            ENTITY = shaders.getField("entityAttrib").getInt(null);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private NativeTerrainShaderPack() { }

    static void enableArrays21() {
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL20.glEnableVertexAttribArray(MID_UV);
        GL20.glEnableVertexAttribArray(TANGENT);
        GL20.glEnableVertexAttribArray(ENTITY);
    }

    /** G5's 56-byte layout, rebased instead of using DrawElementsBaseVertex. */
    static void setupArrays21(long base) {
        GL11.glVertexPointer(3, GL11.GL_FLOAT, 56, base);
        GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, 56, base + 12);
        GL11.glNormalPointer(GL11.GL_BYTE, 56, base + 28);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glTexCoordPointer(2, GL11.GL_SHORT, 56, base + 24);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, 56, base + 16);
        GL20.glVertexAttribPointer(MID_UV, 2, GL11.GL_FLOAT, false, 56, base + 32);
        GL20.glVertexAttribPointer(TANGENT, 4, GL11.GL_SHORT, false, 56, base + 40);
        GL20.glVertexAttribPointer(ENTITY, 3, GL11.GL_SHORT, false, 56, base + 48);
    }

    static void setupArrays() {
        int clientTexture = GL11.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE);
        try {
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL20.glEnableVertexAttribArray(MID_UV);
            GL20.glEnableVertexAttribArray(TANGENT);
            GL20.glEnableVertexAttribArray(ENTITY);
            // Use the installed OptiFine layout including its material IDs,
            // normal, mid-UV and tangent. No shader source rewriting is needed.
            try { POINTERS.invokeExact(); }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Throwable failure) { throw new IllegalStateException("OptiFine terrain pointers", failure); }
        } finally {
            OpenGlHelper.setClientActiveTexture(clientTexture);
        }
    }

    static void draw(int[] records, int commandBase, int start, int count) {
        for (int draw = start, end = start + count; draw < end; draw++) {
            int offset = draw * 4;
            int command = commandBase + draw * 5;
            float scale = Float.intBitsToFloat(records[offset + 3]);
            GL11.glPushMatrix();
            try {
                GL11.glTranslatef(Float.intBitsToFloat(records[offset]),
                    Float.intBitsToFloat(records[offset + 1]), Float.intBitsToFloat(records[offset + 2]));
                // Match RenderChunk's model transform. Arbitrary shader packs
                // read the compatibility matrices directly, so per-section
                // transforms cannot be replaced with ICE's instanced attribute.
                GL11.glTranslatef(-8, -8, -8);
                GL11.glScalef(scale, scale, scale);
                GL11.glTranslatef(8, 8, 8);
                GL32.glDrawElementsBaseVertex(GL11.GL_QUADS, records[command], GL11.GL_UNSIGNED_SHORT,
                    records[command + 2] * 2L, records[command + 3]);
            } finally {
                GL11.glPopMatrix();
            }
        }
    }
}
