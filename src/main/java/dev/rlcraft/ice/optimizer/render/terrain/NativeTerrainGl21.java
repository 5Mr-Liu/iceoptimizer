package dev.rlcraft.ice.optimizer.render.terrain;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.GlStateManager;

/** Ordered indexed draws using only OpenGL 2.1 and GLSL 1.20. */
final class NativeTerrainGl21 extends NativeTerrainBackend {
    private final int program, offsetLocation, fogModeLocation, fogDistanceLocation;
    private final boolean radialFogSupported;
    private boolean announced;
    final NativeTerrainPages21 pages;

    NativeTerrainGl21(int stride) {
        super(stride);
        pages = new NativeTerrainPages21(stride);
        radialFogSupported = NativeTerrainStartup.radialFogSupported();
        if (shaderPack) {
            program = 0;
            offsetLocation = fogModeLocation = fogDistanceLocation = -1;
            return;
        }
        program = NativeTerrainProgram.link(true);
        int previous = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        try {
            GL20.glUseProgram(program);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "iceAtlas"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "iceLightmap"), 1);
            offsetLocation = GL20.glGetUniformLocation(program, "iceOffset");
            fogModeLocation = GL20.glGetUniformLocation(program, "iceFogMode");
            fogDistanceLocation = GL20.glGetUniformLocation(program, "iceFogDistance");
        } finally { GL20.glUseProgram(previous); }
    }

    @Override NativeTerrainAllocation upload(ByteBuffer source, int count) { return pages.upload(source, count); }
    @Override void free(NativeTerrainAllocation mesh) { pages.free(mesh); }
    @Override void uploadIndices(ByteBuffer source, int[] copies, int count) { pages.uploadIndices(source, copies, count); }
    @Override long geometryCapacityBytes() { return pages.geometryBytes(); }

    @Override void prepare(int[] words, int bytes) { /* CPU command records only. */ }

    @Override void submit(int[] words, int commandBase, int start, int count) {
        int array = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int element = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int clientTexture = GL11.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE);
        // This also preserves generic attribute pointers/enables in OpenGL 2.1.
        // Restore buffer bindings explicitly for gl4es's client stack.
        NativeTerrainTextureState textures = drawMaterials == null ? null : new NativeTerrainTextureState();
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        try {
            NativeTerrainPages21.Page bound = null;
            if (shaderPack) NativeTerrainShaderPack.enableArrays21();
            if (!shaderPack) {
                GL20.glUseProgram(program);
                GL20.glUniform1i(fogModeLocation,
                    GL11.glIsEnabled(GL11.GL_FOG) ? GL11.glGetInteger(GL11.GL_FOG_MODE) : 0);
                GL20.glUniform1i(fogDistanceLocation,
                    radialFogSupported && GL11.glGetInteger(0x855A) == 0x855B ? 1 : 0);
                for (int attribute = 8; attribute <= 11; attribute++) GL20.glEnableVertexAttribArray(attribute);
            }
            for (int draw = start, end = start + count; draw < end; draw++) {
                if (textures != null) textures.bind(drawMaterials[draw]);
                int offset = draw * 4, command = commandBase + draw * 5;
                int address = words[command + 3];
                NativeTerrainPages21.Page page = pages.page(address);
                if (page != bound) {
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, page.vertices);
                    GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, page.indices);
                    bound = page;
                }
                long vertex = NativeTerrainPages21.local(address) * (long) stride;
                if (shaderPack) {
                    NativeTerrainShaderPack.setupArrays21(vertex);
                    GL11.glPushMatrix();
                    try {
                        GL11.glTranslatef(Float.intBitsToFloat(words[offset]),
                            Float.intBitsToFloat(words[offset + 1]), Float.intBitsToFloat(words[offset + 2]));
                        float scale = Float.intBitsToFloat(words[offset + 3]);
                        GL11.glTranslatef(-8, -8, -8);
                        GL11.glScalef(scale, scale, scale);
                        GL11.glTranslatef(8, 8, 8);
                        draw(words, command);
                    } finally { GL11.glPopMatrix(); }
                } else {
                    GL20.glVertexAttribPointer(8, 3, GL11.GL_FLOAT, false, stride, vertex);
                    GL20.glVertexAttribPointer(9, 4, GL11.GL_UNSIGNED_BYTE, true, stride, vertex + 12);
                    GL20.glVertexAttribPointer(10, 2, GL11.GL_FLOAT, false, stride, vertex + 16);
                    GL20.glVertexAttribPointer(11, 2, GL11.GL_SHORT, false, stride, vertex + 24);
                    GL20.glUniform4f(offsetLocation, Float.intBitsToFloat(words[offset]),
                        Float.intBitsToFloat(words[offset + 1]), Float.intBitsToFloat(words[offset + 2]),
                        Float.intBitsToFloat(words[offset + 3]));
                    draw(words, command);
                }
            }
        } finally {
            if (textures != null) textures.close();
            GL11.glPopClientAttrib();
            GL13.glClientActiveTexture(clientTexture);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, array);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, element);
            if (!shaderPack) GL20.glUseProgram(previousProgram);
            if (shaderPack) GlStateManager.resetColor();
        }
        if (!announced) {
            announced = true;
            System.out.println("ICE_NATIVE_TERRAIN active: OpenGL 2.1 compatibility, resident geometry, ordered indexed draws"
                + (shaderPack ? ", OptiFine shader pack" : ", ICE shaders"));
        }
    }

    private static void draw(int[] words, int command) {
        GL11.glDrawElements(GL11.GL_QUADS, words[command], GL11.GL_UNSIGNED_SHORT, NativeTerrainPages21.local(words[command + 2]) * 2L);
    }

    @Override void delete() {
        pages.delete();
        if (program != 0) GL20.glDeleteProgram(program);
    }
}
