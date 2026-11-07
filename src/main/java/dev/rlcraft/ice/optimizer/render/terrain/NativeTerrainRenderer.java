package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.compat.chunk.ChunkVertexBufferAccessor;
import dev.rlcraft.ice.optimizer.compat.chunk.NativeTerrainChunkAccessor;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderInfoAccessor;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderListAccessor;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainVisibilityAccessor;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
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

/** Shared terrain construction, resident mesh ownership and ordered visibility queue. */
public final class NativeTerrainRenderer {
    private static final int PART_VERTICES = NativeTerrainAllocation.PART_VERTICES;
    private static NativeTerrainRenderer instance;
    private static volatile long generation;
    static long generation() { return generation; }
    private final int stride;
    private final boolean shaderPack;
    private final NativeTerrainBackend backend;
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
    private int[] submissionWords = new int[8192];

    private NativeTerrainRenderer(int stride) {
        this.stride = stride;
        shaderPack = stride == 56;
        backend = NativeTerrainStartup.create(stride);
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
        if (count != 0 && prepared.generation != generation) {
            context.getChunk().setNeedsUpdate(false);
            builder.reset();
            return;
        }
        if (count == 0) {
            release(buffer);
            ((ChunkVertexBufferAccessor) buffer).ice$setVertexCount(0);
            builder.reset();
            return;
        }
        if (instance == null) instance = new NativeTerrainRenderer(prepared.stride);
        if (instance.stride != prepared.stride)
            throw new IllegalStateException("ICE terrain vertex format changed without retiring old meshes");
        instance.store(context, builder, buffer, prepared);
    }

    private void store(TerrainUploadContext.Value context, BufferBuilder builder, VertexBuffer buffer,
                       NativeTerrainUploadTask.Prepared prepared) {
        int count = prepared.count;
        ByteBuffer source = builder.getByteBuffer();
        TerrainVertexSpace space = prepared.space;
        if (prepared.materials != null) prepared.materials.resolve();
        RenderChunk chunk = context.getChunk();
        if (backend.retireBeforeUpload()) {
            Mesh old = meshes.remove(buffer);
            if (old != null) free(old);
            // No draw may retain an address returned to the arena, including
            // when the following upload fails before it publishes a new mesh.
            framePrepared = false;
            drawQueueDirty = true;
            ((ChunkVertexBufferAccessor) buffer).ice$setVertexCount(0);
        }
        NativeTerrainAllocation allocation = null;
        boolean published = false;
        try {
            allocation = backend.upload(source, count);
            NativeTerrainChunkAccessor access = (NativeTerrainChunkAccessor) chunk;
            Section section = (Section) access.ice$nativeSection();
            if (section == null) {
                section = new Section(chunk);
                section.publish(chunk.getCompiledChunk());
            }
            Mesh mesh = new Mesh(section, context.getLayer().ordinal(), allocation, count, space, prepared.faces, prepared.materials);
            section.animated = NativeChunkAnimation.pending(chunk);
            if (!section.animated) section.offsetX = section.offsetY = section.offsetZ = 0;
            Mesh old = meshes.put(buffer, mesh);
            section.put(mesh);
            access.ice$nativeSection(section);
            published = true;
            if (old != null) free(old);
        } finally {
            if (!published) {
                if (allocation != null) backend.free(allocation);
                chunk.setNeedsUpdate(false);
                builder.reset();
            }
        }
        framePrepared = false;
        drawQueueDirty = true;
        ((ChunkVertexBufferAccessor) buffer).ice$setVertexCount(count);
        builder.reset();
    }

    private void free(Mesh mesh) {
        backend.free(mesh.allocation);
        Section section = mesh.section;
        section.remove(mesh);
        NativeTerrainChunkAccessor access = (NativeTerrainChunkAccessor) section.chunk;
        if (section.residentMask == 0 && access.ice$nativeSection() == section)
            access.ice$nativeSection(null);
    }

    public static void release(VertexBuffer buffer) {
        NativeTerrainPausedMaterials.release(buffer);
        if (instance == null) return;
        Mesh mesh = instance.meshes.remove(buffer);
        if (mesh != null) instance.free(mesh);
        instance.framePrepared = false;
        instance.drawQueueDirty = true;
    }

    public static void beginFrame() {
        if (instance != null) {
            instance.framePrepared = false;
            // OptiFine swaps its active visible list for the shadow pass, whose
            // traversal bypasses the ordinary camera BFS. Never reuse that
            // pass's queue for the main camera (or vice versa).
            if (instance.shaderPack) instance.drawQueueDirty = true;
        }
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
                if (mesh.materials != null) {
                    for(int d=0;d<mesh.materialDrawCount;d++) {
                        int draw=layer==3?mesh.materialDrawCount-1-d:d;
                        int at=draw*4;
                        frameData.add(layer,mesh.materialDraws[at],mesh.materialDraws[at+1],mesh.materialDraws[at+2],
                            x,y,z,mesh.scale,mesh.materialDraws[at+3]);
                    }
                    continue;
                }
                if (mesh.partCounts == null) {
                    if (mesh.drawCount != 0) frameData.add(layer, mesh.drawCount,
                        mesh.allocation.indexFirst(0), mesh.allocation.vertexFirst(0), x, y, z, mesh.scale);
                    continue;
                }
                // Reversing the transparent queue must reverse chunks, never
                // the original order of quads inside a chunk split into parts.
                for (int p = 0; p < mesh.partCounts.length; p++) {
                    int part = layer == 3 ? mesh.partCounts.length - 1 - p : p;
                    if (mesh.partCounts[part] != 0) frameData.add(layer, mesh.partCounts[part],
                        mesh.allocation.indexFirst(part), mesh.allocation.vertexFirst(part), x, y, z, mesh.scale);
                }
            }
        }
        // Keep CPU generation ordered, then publish all changed ranges with a
        // single staging upload before any of this frame's draws can consume it.
        indexUpload.flip();
        if (indexUpload.hasRemaining())
            backend.uploadIndices(indexUpload, indexCopies, indexCopyCount);
        int count = frameData.size();
        int bytes = Math.multiplyExact(count, 36);
        if (submissionWords.length < bytes / 4)
            submissionWords = new int[(int) Math.max(bytes / 4L,
                Math.min(Integer.MAX_VALUE - 8L, submissionWords.length * 2L))];
        frameData.pack(submissionWords);
        backend.drawMaterials = frameData.materials();
        backend.prepare(submissionWords, bytes);
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
        if (mesh.materials != null) {
            updateMaterialIndices(mesh,mask);
            mesh.faceMask=mask;
            return;
        }
        int parts = mesh.partCounts == null ? 1 : mesh.partCounts.length;
        for (int part = 0; part < parts; part++) {
            int first = part * PART_VERTICES;
            int count = Math.min(PART_VERTICES, mesh.count - first);
            int partOffset = indexUpload.position();
            if (mesh.faces != null) count = mesh.faces.writeShortIndices(indexUpload, mask, first, count);
            else NativeTerrainFaceIndex.writeSequentialShortIndices(indexUpload, count);
            if (mesh.partCounts == null) mesh.drawCount = count;
            else mesh.partCounts[part] = count;
            int written = indexUpload.position() - partOffset;
            if (written != 0) {
                int slot = indexCopyCount++ * 3;
                if (slot + 3 > indexCopies.length) indexCopies = Arrays.copyOf(indexCopies, indexCopies.length * 2);
                indexCopies[slot] = partOffset;
                indexCopies[slot + 1] = mesh.allocation.indexFirst(part);
                indexCopies[slot + 2] = written;
            }
        }
        mesh.faceMask = mask;
    }

    private void updateMaterialIndices(Mesh mesh,int mask) {
        NativeTerrainMaterials materials=mesh.materials;
        int[] used=new int[mesh.allocation.parts()];
        mesh.materialDrawCount=0;
        for(int run=0;run<materials.first.length;run++) {
            int first=materials.first[run],end=first+materials.count[run];
            while(first<end) {
                int part=first/PART_VERTICES,base=part*PART_VERTICES;
                int count=Math.min(end-first,PART_VERTICES-(first-base));
                int uploadOffset=indexUpload.position();
                if(mesh.faces!=null)mesh.faces.writeShortIndices(indexUpload,mask,first,count,base);
                else NativeTerrainSequentialIndices.append(indexUpload, first-base, count);
                int written=(indexUpload.position()-uploadOffset)/2;
                if(written!=0) {
                    int destination=mesh.allocation.indexFirst(part)+used[part];
                    int slot=indexCopyCount++*3;
                    if(slot+3>indexCopies.length)indexCopies=Arrays.copyOf(indexCopies,indexCopies.length*2);
                    indexCopies[slot]=uploadOffset;indexCopies[slot+1]=destination;indexCopies[slot+2]=written*2;
                    int draw=mesh.materialDrawCount++*4;
                    if(mesh.materialDraws==null)mesh.materialDraws=new int[32];
                    if(draw+4>mesh.materialDraws.length)mesh.materialDraws=Arrays.copyOf(mesh.materialDraws,mesh.materialDraws.length*2);
                    mesh.materialDraws[draw]=written;mesh.materialDraws[draw+1]=destination;
                    mesh.materialDraws[draw+2]=mesh.allocation.vertexFirst(part);
                    mesh.materialDraws[draw+3]=materials.texture(run);
                    used[part]+=written;
                }
                first+=count;
            }
        }
    }

    private void submit(BlockRenderLayer layer) {
        backend.submit(submissionWords, frameCommands / 4,
            frameData.starts[layer.ordinal()], frameData.counts[layer.ordinal()]);
    }

    public static int residentMeshes() { return instance == null ? 0 : instance.meshes.size(); }
    public static long geometryCapacityBytes() { return instance == null ? 0 : instance.backend.geometryCapacityBytes(); }

    public static void reset(boolean contextValid) {
        generation++;
        NativeTerrainPausedMaterials.clear();
        NativeTerrainVisibility.reset();
        NativeTerrainRenderer old = instance;
        instance = null;
        if (old == null) return;
        if (contextValid) old.backend.delete();
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
        final NativeTerrainAllocation allocation;
        final NativeTerrainFaceIndex faces;
        final NativeTerrainMaterials materials;
        int[] materialDraws;
        int materialDrawCount;
        final int[] partCounts;
        final int originX, originY, originZ;
        final float scale;
        int drawCount;
        int faceMask = -1;
        final int count;
        Mesh(Section section, int layer, NativeTerrainAllocation allocation,
             int count, TerrainVertexSpace space, NativeTerrainFaceIndex faces) {
            this(section,layer,allocation,count,space,faces,null);
        }
        Mesh(Section section, int layer, NativeTerrainAllocation allocation,
             int count, TerrainVertexSpace space, NativeTerrainFaceIndex faces, NativeTerrainMaterials materials) {
            this.materials=materials;
            this.section = section; this.layer = layer;
            this.allocation = allocation; this.count = count;
            this.originX = space.originX; this.originY = space.originY; this.originZ = space.originZ;
            this.scale = space.scale;
            this.faces = faces;
            int parts = (count - 1) / PART_VERTICES + 1;
            partCounts = parts == 1 ? null : new int[parts];
        }
    }
}
