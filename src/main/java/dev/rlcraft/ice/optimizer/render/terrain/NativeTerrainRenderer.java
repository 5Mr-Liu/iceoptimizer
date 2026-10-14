package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.compat.chunk.ChunkVertexBufferAccessor;
import dev.rlcraft.ice.optimizer.compat.chunk.NativeTerrainChunkAccessor;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderInfoAccessor;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderListAccessor;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainVisibilityAccessor;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.BlockRenderLayer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLContext;

/**
 * A direct terrain owner: original mesh construction and visible order in,
 * one resident geometry store and one indirect submission per layer out.
 * No shadow VBO copy, runtime oracle, adaptive admission or legacy replay.
 */
public final class NativeTerrainRenderer {
    private static final int STRIDE = 28;
    private static final int PART_VERTICES = 65532;
    private static NativeTerrainRenderer instance;
    private final NativeTerrainAllocator ranges = new NativeTerrainAllocator();
    private final NativeTerrainAllocator indexRanges = new NativeTerrainAllocator();
    private final NativeTerrainStorage vertices = new NativeTerrainStorage(STRIDE);
    private final NativeTerrainStorage indices = new NativeTerrainStorage(2);
    private final IdentityHashMap<VertexBuffer, Mesh> meshes = new IdentityHashMap<VertexBuffer, Mesh>();
    private final float[] animation = new float[3];
    private static final BlockRenderLayer[] LAYERS = BlockRenderLayer.values();
    private final NativeTerrainFrameData frameData = new NativeTerrainFrameData();
    private final NativeTerrainDrawQueue drawQueue = new NativeTerrainDrawQueue();
    private volatile boolean drawQueueDirty = true;
    private final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
    private final float[] matrix = new float[16];
    private final double[] eye = new double[3];
    private ByteBuffer indexUpload = BufferUtils.createByteBuffer(32768);
    private int[] indexCopies = new int[768];
    private int indexCopyCount;
    private boolean cullBackFaces;
    private volatile boolean framePrepared;
    private int frameCommands;
    private final boolean radialFogSupported;
    private ByteBuffer submissions = BufferUtils.createByteBuffer(32768);
    private IntBuffer submissionInts = submissions.asIntBuffer();
    private int[] submissionWords = new int[8192];
    private int stream, uploadStream, vao, program, vaoGeometry;
    private int fogModeLocation, fogDistanceLocation;
    private boolean announced;

    private NativeTerrainRenderer() {
        if (!GLContext.getCapabilities().OpenGL43)
            throw new IllegalStateException("ICE native terrain requires OpenGL 4.3");
        radialFogSupported = GLContext.getCapabilities().GL_NV_fog_distance;
        program = link();
        stream = GL15.glGenBuffers();
        uploadStream = GL15.glGenBuffers();
        vao = GL30.glGenVertexArrays();
        int previous = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(program);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "iceAtlas"), 0);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "iceLightmap"), 1);
        fogModeLocation = GL20.glGetUniformLocation(program, "iceFogMode");
        fogDistanceLocation = GL20.glGetUniformLocation(program, "iceFogDistance");
        GL20.glUseProgram(previous);
    }

    public static void upload(BufferBuilder builder, VertexBuffer buffer) {
        upload(TerrainUploadContext.take(), builder, buffer);
    }

    /** Dispatcher owns the completed builder; publication and reset happen here. */
    public static void upload(TerrainUploadContext.Value context, BufferBuilder builder, VertexBuffer buffer) {
        NativeTerrainUploadTask.Prepared prepared = NativeTerrainUploadTask.take();
        if (context == null) throw new IllegalStateException("ICE terrain upload context missing");
        // Direct dispatcher uploads do not have a queued future. The same CPU
        // preparation applies, without creating a second upload queue or wait.
        if (prepared == null) prepared = NativeTerrainUploadTask.prepare(
            context.getLayer(), builder, context.getChunk(), context.getCompiledChunk());
        int count = prepared.count;
        if (count == 0) {
            release(buffer);
            ((ChunkVertexBufferAccessor) buffer).ice$setVertexCount(0);
            builder.reset();
            return;
        }
        if (instance == null) instance = new NativeTerrainRenderer();
        instance.store(context, builder, buffer, prepared);
    }

    private void store(TerrainUploadContext.Value context, BufferBuilder builder, VertexBuffer buffer,
                       NativeTerrainUploadTask.Prepared prepared) {
        int count = prepared.count;
        ByteBuffer source = builder.getByteBuffer();
        TerrainVertexSpace space = prepared.space;
        Mesh old = meshes.remove(buffer);
        if (old != null) free(old);
        if (count != 0) {
            RenderChunk chunk = context.getChunk();
            NativeTerrainChunkAccessor access = (NativeTerrainChunkAccessor) chunk;
            Section section = (Section) access.ice$nativeSection();
            if (section == null) {
                section = new Section(chunk);
                section.publish(chunk.getCompiledChunk());
                access.ice$nativeSection(section);
            }
            section.animated = NativeChunkAnimation.pending(chunk);
            if (!section.animated) section.offsetX = section.offsetY = section.offsetZ = 0;
            NativeTerrainAllocator.Range range = ranges.allocate(count);
            vertices.ensure(range.first + range.count);
            vertices.upload(source, range.first, uploadStream);
            NativeTerrainAllocator.Range indexRange = indexRanges.allocate(count);
            indices.ensure(indexRange.first + indexRange.count);
            Mesh mesh = new Mesh(section, context.getLayer().ordinal(), range, indexRange, count, space, prepared.faces);
            meshes.put(buffer, mesh);
            section.put(mesh);
        }
        framePrepared = false;
        drawQueueDirty = true;
        ((ChunkVertexBufferAccessor) buffer).ice$setVertexCount(count);
        builder.reset();
    }

    private void free(Mesh mesh) {
        ranges.free(mesh.range);
        indexRanges.free(mesh.indexRange);
        Section section = mesh.section;
        section.remove(mesh);
        if (section.residentMask == 0)
            ((NativeTerrainChunkAccessor) section.chunk).ice$nativeSection(null);
    }

    public static void release(VertexBuffer buffer) {
        if (instance == null) return;
        Mesh mesh = instance.meshes.remove(buffer);
        if (mesh != null) instance.free(mesh);
        instance.framePrepared = false;
        instance.drawQueueDirty = true;
    }

    public static void beginFrame() {
        if (instance != null) instance.framePrepared = false;
    }

    /** Published with the compiled mesh, including DUMMY on cancellation. */
    public static void publish(RenderChunk chunk, CompiledChunk compiled) {
        Section section = (Section) ((NativeTerrainChunkAccessor) chunk).ice$nativeSection();
        if (section != null) section.publish(compiled);
        if (instance != null) {
            instance.framePrepared = false;
            instance.drawQueueDirty = true;
        }
    }

    static NativeTerrainDrawQueue beginVisibility() {
        if (instance == null) return null;
        // Publication after traversal starts leaves this dirty. Its new meshes
        // are collected before drawing, including uploads after setupTerrain.
        instance.drawQueueDirty = false;
        instance.drawQueue.clear();
        return instance.drawQueue;
    }

    public static int prepareLayer(Object owner, Object container, BlockRenderLayer layer) {
        if (instance == null) return 0;
        if (layer != BlockRenderLayer.TRANSLUCENT || !instance.framePrepared) {
            boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE)
                && GL11.glGetInteger(GL11.GL_CULL_FACE_MODE) == GL11.GL_BACK
                && GL11.glGetInteger(GL11.GL_FRONT_FACE) == GL11.GL_CCW;
            if (instance.cullBackFaces != cull) instance.framePrepared = false;
            instance.cullBackFaces = cull;
        }
        if (!instance.framePrepared) instance.prepare((TerrainVisibilityAccessor) owner, (TerrainRenderListAccessor) container);
        return instance.frameData.counts[layer.ordinal()];
    }

    public static void draw(Object container, BlockRenderLayer layer) {
        if (instance != null && instance.frameData.counts[layer.ordinal()] != 0) instance.submit(layer);
    }

    private void prepare(TerrainVisibilityAccessor owner, TerrainRenderListAccessor view) {
        frameData.clear();
        indexUpload.clear();
        indexCopyCount = 0;
        matrixBuffer.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, matrixBuffer);
        matrixBuffer.get(matrix);
        boolean filter = cullBackFaces && TerrainCamera.eye(matrix, eye);
        if (drawQueueDirty) {
            drawQueueDirty = false;
            drawQueue.clear();
            List visible = owner.ice$renderInfos();
            for (int index = 0, size = visible.size(); index < size; index++)
                drawQueue.add((RenderChunk) ((TerrainRenderInfoAccessor) visible.get(index)).ice$renderChunk());
        }
        drawQueue.animate(animation);
        double viewX = view.ice$viewEntityX(), viewY = view.ice$viewEntityY(), viewZ = view.ice$viewEntityZ();
        for (int layer = 0; layer < 4; layer++) {
            Mesh[] meshes = drawQueue.layers[layer];
            for (int index = 0, size = drawQueue.counts[layer]; index < size; index++) {
                Mesh mesh = meshes[index];
                Section section = mesh.section;
                float x = (float) (mesh.originX - viewX) + section.offsetX;
                float y = (float) (mesh.originY - viewY) + section.offsetY;
                float z = (float) (mesh.originZ - viewZ) + section.offsetZ;
                int mask = mesh.faces == null ? 0 : filter
                    ? mesh.faces.visibleMask(eye[0] - x, eye[1] - y, eye[2] - z)
                    : mesh.faces.allFaces();
                if (mask != mesh.faceMask) updateIndices(mesh, mask);
                if (mesh.partCounts == null) {
                    if (mesh.drawCount != 0) frameData.add(layer, mesh.drawCount,
                        mesh.indexRange.first, mesh.firstVertex, x, y, z, mesh.scale);
                    continue;
                }
                // Reversing the transparent queue must reverse chunks, never
                // the original order of quads inside a chunk split into parts.
                for (int p = 0; p < mesh.partCounts.length; p++) {
                    int part = layer == 3 ? mesh.partCounts.length - 1 - p : p;
                    if (mesh.partCounts[part] != 0) frameData.add(layer, mesh.partCounts[part],
                        mesh.partStarts[part], mesh.firstVertex + part * PART_VERTICES, x, y, z, mesh.scale);
                }
            }
        }
        // Keep CPU generation ordered, then publish all changed ranges with a
        // single staging upload before any of this frame's draws can consume it.
        indexUpload.flip();
        if (indexUpload.hasRemaining())
            indices.uploadBatch(indexUpload, indexCopies, indexCopyCount, uploadStream);
        int count = frameData.size();
        int bytes = Math.multiplyExact(count, 36);
        if (submissions.capacity() < bytes) {
            submissions = BufferUtils.createByteBuffer(Integer.highestOneBit(bytes - 1) << 1);
            submissionInts = submissions.asIntBuffer();
            submissionWords = new int[submissions.capacity() / 4];
        }
        submissions.clear();
        submissions.limit(bytes);
        frameData.pack(submissionWords);
        submissionInts.clear();
        submissionInts.put(submissionWords, 0, bytes / 4);
        int previousArray = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, stream);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, submissions, GL15.GL_STREAM_DRAW);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArray);
        frameCommands = count * 16;
        framePrepared = true;
    }

    private void updateIndices(Mesh mesh, int mask) {
        int bytes = Math.multiplyExact(mesh.count, 2);
        int uploadOffset = indexUpload.position();
        int required = Math.addExact(uploadOffset, bytes);
        if (indexUpload.capacity() < required) {
            int capacity = (int) Math.max(required, Math.min(Integer.MAX_VALUE - 8L, indexUpload.capacity() * 2L));
            ByteBuffer replacement = BufferUtils.createByteBuffer(capacity);
            indexUpload.flip();
            replacement.put(indexUpload);
            indexUpload = replacement;
        }
        int parts = mesh.partCounts == null ? 1 : mesh.partCounts.length;
        for (int part = 0; part < parts; part++) {
            int first = part * PART_VERTICES;
            int count = Math.min(PART_VERTICES, mesh.count - first);
            if (mesh.partCounts != null)
                mesh.partStarts[part] = mesh.indexRange.first + (indexUpload.position() - uploadOffset) / 2;
            if (mesh.faces != null) count = mesh.faces.writeShortIndices(indexUpload, mask, first, count);
            else NativeTerrainFaceIndex.writeSequentialShortIndices(indexUpload, count);
            if (mesh.partCounts == null) mesh.drawCount = count;
            else mesh.partCounts[part] = count;
        }
        int written = indexUpload.position() - uploadOffset;
        if (written != 0) {
            int slot = indexCopyCount++ * 3;
            if (slot + 3 > indexCopies.length) indexCopies = Arrays.copyOf(indexCopies, indexCopies.length * 2);
            indexCopies[slot] = uploadOffset;
            indexCopies[slot + 1] = mesh.indexRange.first;
            indexCopies[slot + 2] = written;
        }
        mesh.faceMask = mask;
    }

    private void submit(BlockRenderLayer layer) {
        int layerIndex = layer.ordinal();
        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousArray = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int previousIndirect = GL11.glGetInteger(GL40.GL_DRAW_INDIRECT_BUFFER_BINDING);
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        boolean restart = GL11.glIsEnabled(GL31.GL_PRIMITIVE_RESTART);
        boolean fixedRestart = GL11.glIsEnabled(GL43.GL_PRIMITIVE_RESTART_FIXED_INDEX);
        try {
            if (restart) GL11.glDisable(GL31.GL_PRIMITIVE_RESTART);
            if (fixedRestart) GL11.glDisable(GL43.GL_PRIMITIVE_RESTART_FIXED_INDEX);
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indices.buffer);
            if (vaoGeometry != vertices.buffer) {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertices.buffer);
                attribute(8, 3, GL11.GL_FLOAT, false, STRIDE, 0L);
                attribute(9, 4, GL11.GL_UNSIGNED_BYTE, true, STRIDE, 12L);
                attribute(10, 2, GL11.GL_FLOAT, false, STRIDE, 16L);
                attribute(11, 2, GL11.GL_SHORT, false, STRIDE, 24L);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, stream);
                attribute(12, 4, GL11.GL_FLOAT, false, 16, 0L);
                GL33.glVertexAttribDivisor(12, 1);
                vaoGeometry = vertices.buffer;
            }
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, stream);
            GL20.glUseProgram(program);
            GL20.glUniform1i(fogModeLocation,
                GL11.glIsEnabled(GL11.GL_FOG) ? GL11.glGetInteger(GL11.GL_FOG_MODE) : 0);
            GL20.glUniform1i(fogDistanceLocation,
                radialFogSupported && GL11.glGetInteger(0x855A) == 0x855B ? 1 : 0);
            GL43.glMultiDrawElementsIndirect(GL11.GL_QUADS, GL11.GL_UNSIGNED_SHORT,
                frameCommands + frameData.starts[layerIndex] * 20L, frameData.counts[layerIndex], 20);
        } finally {
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

    private static void attribute(int index, int size, int type, boolean normalized, int stride, long offset) {
        GL20.glEnableVertexAttribArray(index);
        GL20.glVertexAttribPointer(index, size, type, normalized, stride, offset);
    }

    private static int link() {
        int vertex = compile(GL20.GL_VERTEX_SHADER, NativeTerrainShader.vertex());
        int fragment = 0, result = 0;
        try {
            fragment = compile(GL20.GL_FRAGMENT_SHADER, NativeTerrainShader.fragment());
            result = GL20.glCreateProgram();
            GL20.glAttachShader(result, vertex); GL20.glAttachShader(result, fragment);
            GL20.glBindAttribLocation(result, 8, "icePosition");
            GL20.glBindAttribLocation(result, 9, "iceColor");
            GL20.glBindAttribLocation(result, 10, "iceUv");
            GL20.glBindAttribLocation(result, 11, "iceLight");
            GL20.glBindAttribLocation(result, 12, "iceOffset");
            GL20.glLinkProgram(result);
            if (GL20.glGetProgrami(result, GL20.GL_LINK_STATUS) == 0)
                throw new IllegalStateException(GL20.glGetProgramInfoLog(result, 4096));
            return result;
        } catch (RuntimeException failure) {
            if (result != 0) GL20.glDeleteProgram(result);
            throw failure;
        } finally {
            GL20.glDeleteShader(vertex);
            if (fragment != 0) GL20.glDeleteShader(fragment);
        }
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source); GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            String log = GL20.glGetShaderInfoLog(shader, 4096);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException(log);
        }
        return shader;
    }

    public static int residentMeshes() { return instance == null ? 0 : instance.meshes.size(); }
    public static long geometryCapacityBytes() { return instance == null ? 0 : instance.vertices.capacity * (long) STRIDE; }

    public static void reset(boolean contextValid) {
        NativeTerrainVisibility.reset();
        NativeTerrainRenderer old = instance;
        instance = null;
        if (old == null) return;
        if (contextValid) {
            GL30.glDeleteVertexArrays(old.vao);
            GL15.glDeleteBuffers(old.stream);
            GL15.glDeleteBuffers(old.uploadStream);
            old.vertices.delete();
            old.indices.delete();
            GL20.glDeleteProgram(old.program);
        }
        for (Mesh mesh : old.meshes.values())
            ((NativeTerrainChunkAccessor) mesh.section.chunk).ice$nativeSection(null);
        old.meshes.clear();
    }

    static final class Section {
        final RenderChunk chunk;
        final Mesh[] layers = new Mesh[4];
        int residentMask;
        volatile int activeMask;
        boolean animated;
        float offsetX, offsetY, offsetZ;
        Section(RenderChunk chunk) { this.chunk = chunk; }
        void publish(CompiledChunk compiled) {
            int mask = 0;
            for (int layer = 0; layer < 4; layer++)
                if (!compiled.isLayerEmpty(LAYERS[layer])) mask |= 1 << layer;
            activeMask = mask;
        }
        void put(Mesh mesh) { layers[mesh.layer] = mesh; residentMask |= 1 << mesh.layer; }
        void remove(Mesh mesh) {
            if (layers[mesh.layer] == mesh) {
                layers[mesh.layer] = null;
                residentMask &= ~(1 << mesh.layer);
            }
        }
    }

    static final class Mesh {
        final Section section;
        final int layer;
        final NativeTerrainAllocator.Range range;
        final NativeTerrainAllocator.Range indexRange;
        final NativeTerrainFaceIndex faces;
        final int[] partCounts, partStarts;
        final int originX, originY, originZ, firstVertex;
        final float scale;
        int drawCount;
        int faceMask = -1;
        final int count;
        Mesh(Section section, int layer, NativeTerrainAllocator.Range range, NativeTerrainAllocator.Range indexRange,
             int count, TerrainVertexSpace space, NativeTerrainFaceIndex faces) {
            this.section = section; this.layer = layer;
            this.range = range; this.count = count;
            this.originX = space.originX; this.originY = space.originY; this.originZ = space.originZ;
            this.scale = space.scale; this.firstVertex = range.first;
            this.indexRange = indexRange; this.faces = faces;
            int parts = (count - 1) / PART_VERTICES + 1;
            partCounts = parts == 1 ? null : new int[parts];
            partStarts = parts == 1 ? null : new int[parts];
        }
    }
}
