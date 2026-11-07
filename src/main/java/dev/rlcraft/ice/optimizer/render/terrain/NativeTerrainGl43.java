package dev.rlcraft.ice.optimizer.render.terrain;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import net.minecraft.client.renderer.GlStateManager;

/** Original OpenGL 4.3 submission, selected once at startup. */
final class NativeTerrainGl43 extends NativeTerrainBackend {
    private final NativeTerrainAllocator ranges = new NativeTerrainAllocator(), indexRanges = new NativeTerrainAllocator();
    final NativeTerrainStorage vertices, indices = new NativeTerrainStorage(2);
    private int uploadStream;
    private final NativeTerrainStorage commands = new NativeTerrainStorage(1);
    private ByteBuffer submissions = BufferUtils.createByteBuffer(32768);
    private IntBuffer submissionInts = submissions.asIntBuffer();
    private final NativeTerrainCommandData commandData = new NativeTerrainCommandData();
    private final boolean radialFogSupported;
    private int vao, program, vaoGeometry, vaoCommands;
    private int fogModeLocation, fogDistanceLocation;
    private boolean announced;

    NativeTerrainGl43(int stride) {
        super(stride);
        vertices = new NativeTerrainStorage(stride);
        radialFogSupported = NativeTerrainStartup.radialFogSupported();
        uploadStream = GL15.glGenBuffers();
        vao = GL30.glGenVertexArrays();
        // OptiFine owns the pack program, uniforms, textures and draw buffers.
        // The native arena must not replace it with ICE's unlit/fog program.
        if (shaderPack) return;
        program = NativeTerrainProgram.link(false);
        int previous = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(program);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "iceAtlas"), 0);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "iceLightmap"), 1);
        fogModeLocation = GL20.glGetUniformLocation(program, "iceFogMode");
        fogDistanceLocation = GL20.glGetUniformLocation(program, "iceFogDistance");
        GL20.glUseProgram(previous);
    }

    // Resident arenas survive free(). Reuse the old mesh's hole before asking
    // ensure() to grow/copy the entire GPU buffer, as in the 1.20.1 path.
    @Override boolean retireBeforeUpload() { return true; }

    @Override NativeTerrainAllocation upload(ByteBuffer source, int count) {
        NativeTerrainAllocator.Range vertex = ranges.allocate(count);
        NativeTerrainAllocator.Range index = null;
        boolean committed = false;
        try {
            vertices.ensure(vertex.first + vertex.count);
            vertices.upload(source, vertex.first, uploadStream);
            index = indexRanges.allocate(count);
            indices.ensure(index.first + index.count);
            NativeTerrainAllocation allocation = new NativeTerrainAllocation(vertex, index, count);
            committed = true;
            return allocation;
        } finally {
            if (!committed) { ranges.free(vertex); if (index != null) indexRanges.free(index); }
        }
    }

    @Override void free(NativeTerrainAllocation mesh) { ranges.free(mesh.vertices); indexRanges.free(mesh.indices); }
    @Override void uploadIndices(ByteBuffer source, int[] copies, int count) { indices.uploadBatch(source, copies, count, uploadStream); }
    @Override long geometryCapacityBytes() { return vertices.capacity * (long) stride; }

    @Override void submit(int[] submissionWords, int commandBase, int start, int count) {
        if (shaderPack) {
            submitShaderPack(submissionWords, commandBase, start, count);
            return;
        }
        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousArray = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int previousIndirect = GL11.glGetInteger(GL40.GL_DRAW_INDIRECT_BUFFER_BINDING);
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        boolean restart = GL11.glIsEnabled(GL31.GL_PRIMITIVE_RESTART);
        boolean fixedRestart = GL11.glIsEnabled(GL43.GL_PRIMITIVE_RESTART_FIXED_INDEX);
        NativeTerrainTextureState textures = drawMaterials == null ? null : new NativeTerrainTextureState();
        try {
            if (restart) GL11.glDisable(GL31.GL_PRIMITIVE_RESTART);
            if (fixedRestart) GL11.glDisable(GL43.GL_PRIMITIVE_RESTART_FIXED_INDEX);
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indices.buffer);
            if (vaoGeometry != vertices.buffer) {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertices.buffer);
                attribute(8, 3, GL11.GL_FLOAT, false, stride, 0L);
                attribute(9, 4, GL11.GL_UNSIGNED_BYTE, true, stride, 12L);
                attribute(10, 2, GL11.GL_FLOAT, false, stride, 16L);
                attribute(11, 2, GL11.GL_SHORT, false, stride, 24L);
                vaoGeometry = vertices.buffer;
            }
            if (vaoCommands != commands.buffer) {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, commands.buffer);
                attribute(12, 4, GL11.GL_FLOAT, false, 16, 0L);
                GL33.glVertexAttribDivisor(12, 1);
                vaoCommands = commands.buffer;
            }
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, commands.buffer);
            GL20.glUseProgram(program);
            GL20.glUniform1i(fogModeLocation,
                GL11.glIsEnabled(GL11.GL_FOG) ? GL11.glGetInteger(GL11.GL_FOG_MODE) : 0);
            GL20.glUniform1i(fogDistanceLocation,
                radialFogSupported && GL11.glGetInteger(0x855A) == 0x855B ? 1 : 0);
            if (textures == null) {
                GL43.glMultiDrawElementsIndirect(GL11.GL_QUADS, GL11.GL_UNSIGNED_SHORT,
                    commandBase * 4L + start * 20L, count, 20);
            } else for (int first=start, end=start+count; first<end;) {
                int last=first+1, texture=drawMaterials[first];
                while(last<end && drawMaterials[last]==texture)last++;
                textures.bind(texture);
                GL43.glMultiDrawElementsIndirect(GL11.GL_QUADS, GL11.GL_UNSIGNED_SHORT,
                    commandBase * 4L + first * 20L, last-first, 20);
                first=last;
            }
        } finally {
            if (textures != null) textures.close();
            if (restart) GL11.glEnable(GL31.GL_PRIMITIVE_RESTART);
            if (fixedRestart) GL11.glEnable(GL43.GL_PRIMITIVE_RESTART_FIXED_INDEX);
            GL20.glUseProgram(previousProgram);
            GL30.glBindVertexArray(previousVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArray);
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, previousIndirect);
        }
        if (!announced) {
            announced = true;
            System.out.println("ICE_NATIVE_TERRAIN active: resident geometry, ordered MDI, ICE shaders; no external renderer");
        }
    }

    private void submitShaderPack(int[] submissionWords, int commandBase, int start, int count) {
        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousArray = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        boolean restart = GL11.glIsEnabled(GL31.GL_PRIMITIVE_RESTART);
        boolean fixedRestart = GL11.glIsEnabled(GL43.GL_PRIMITIVE_RESTART_FIXED_INDEX);
        try {
            if (restart) GL11.glDisable(GL31.GL_PRIMITIVE_RESTART);
            if (fixedRestart) GL11.glDisable(GL43.GL_PRIMITIVE_RESTART_FIXED_INDEX);
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indices.buffer);
            if (vaoGeometry != vertices.buffer) {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertices.buffer);
                NativeTerrainShaderPack.setupArrays();
                vaoGeometry = vertices.buffer;
            }
            NativeTerrainShaderPack.draw(submissionWords, commandBase, start, count);
        } finally {
            GL30.glBindVertexArray(previousVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArray);
            if (restart) GL11.glEnable(GL31.GL_PRIMITIVE_RESTART);
            if (fixedRestart) GL11.glEnable(GL43.GL_PRIMITIVE_RESTART_FIXED_INDEX);
            GlStateManager.resetColor();
        }
        if (!announced) {
            announced = true;
            System.out.println("ICE_NATIVE_TERRAIN active: resident geometry, ordered indexed draws, OptiFine shader pack");
        }
    }

    private static void attribute(int index, int size, int type, boolean normalized, int stride, long offset) {
        GL20.glEnableVertexAttribArray(index);
        GL20.glVertexAttribPointer(index, size, type, normalized, stride, offset);
    }

    @Override void prepare(int[] words, int bytes) {
        if (shaderPack || bytes == 0) return;
        int changed = commandData.changedBytes(words, bytes);
        if (changed == 0) return;
        if (submissions.capacity() < changed) {
            submissions = BufferUtils.createByteBuffer(
                (int) Math.max(changed, Math.min(Integer.MAX_VALUE - 8L, submissions.capacity() * 2L)));
            submissionInts = submissions.asIntBuffer();
        }
        submissions.clear();
        submissions.limit(changed);
        submissionInts.clear();
        submissionInts.put(words, 0, changed / 4);
        // Stable resident commands, with orphaning confined to the copy stream.
        // Preserve the Intel workaround: never orphan an indirect/attribute
        // buffer. An unchanged command suffix remains resident on camera moves.
        commands.ensure(bytes);
        commands.upload(submissions, 0, uploadStream);
        commandData.uploaded(words, bytes);
    }

    @Override void delete() {
        GL30.glDeleteVertexArrays(vao);
        commands.delete();
        GL15.glDeleteBuffers(uploadStream);
        vertices.delete();
        indices.delete();
        if (program != 0) GL20.glDeleteProgram(program);
    }
}
