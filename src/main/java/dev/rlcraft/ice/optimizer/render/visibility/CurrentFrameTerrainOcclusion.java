package dev.rlcraft.ice.optimizer.render.visibility;

import dev.rlcraft.ice.optimizer.compat.chunk.ChunkAnimatorRenderBridge;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderListAccessor;
import dev.rlcraft.ice.optimizer.render.terrain.TerrainUploadContext;
import dev.rlcraft.ice.optimizer.render.terrain.TerrainVertexSpace;
import dev.rlcraft.ice.optimizer.render.terrain.TerrainCamera;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;

/**
 * Independent of arena ownership: snapshots originate at actual VBO uploads,
 * and the final common SOLID list is compacted before native or legacy drawing.
 * Compiler threads prepare immutable snapshots; upload and visibility state
 * belongs to the render thread. The caller supplies current main-view matrices
 * with ordinary fixed-pipeline depth/cull semantics and no ShaderPack.
 */
public final class CurrentFrameTerrainOcclusion {
    private static final int MAX_MESHES = 4096;
    private static final long MAX_BYTES = 8L * 1024L * 1024L;
    private static final int FRAME_CELLS = 120000;
    private static final int FRAME_QUADS = 2048;
    private static final int PRIORITY_FRAME_QUADS = 128;
    private final double[] occluderEye = new double[3];
    private final LinkedHashMap<VertexBuffer, Entry> uploaded =
        new LinkedHashMap<VertexBuffer, Entry>();
    private final LinkedHashMap<VertexBuffer, Entry> pending =
        new LinkedHashMap<VertexBuffer, Entry>();
    // Uploaded VBO ownership already bounds the lifetime of these entries.
    private final IdentityHashMap<RenderChunk, Entry> chunks = new IdentityHashMap<RenderChunk, Entry>();
    private final WeakHashMap<BufferBuilder, Prepared> prepared = new WeakHashMap<BufferBuilder, Prepared>();
    private volatile long generation;
    private final SoftwareOcclusionRaster raster = new SoftwareOcclusionRaster(160, 90);
    private final Work[] work = new Work[MAX_MESHES];
    private final boolean[] hidden = new boolean[MAX_MESHES];
    private OpaqueTerrainTextures textures;
    private long retainedBytes;
    private long tested;
    private long occluded;
    private long rasterizedQuads;
    private long readOnlyTailPasses;
    private long readOnlyTailBudgetStops;
    private final OcclusionWorkScheduler scheduler = new OcclusionWorkScheduler();

    public CurrentFrameTerrainOcclusion() {
        for (int i = 0; i < work.length; i++) work[i] = new Work();
    }

    /** Main-thread atlas publication after stitching; workers never read mutable sprites. */
    public void publishTextures(TextureMap atlas) {
        OpaqueTerrainTextures next = new OpaqueTerrainTextures(atlas);
        clear();
        synchronized (prepared) {
            textures = next;
            generation++;
        }
    }

    /**
     * Dispatcher entry, normally the chunk compiler thread before it queues the
     * render-thread upload. Its second entry on the render thread reuses this
     * immutable result while the builder still belongs to the same compile.
     */
    public void prepare(BlockRenderLayer layer, BufferBuilder builder, RenderChunk chunk,
                        CompiledChunk compiled) {
        if (layer != BlockRenderLayer.SOLID || builder == null || chunk == null || compiled == null
            || !standardBlockFormat(builder.getVertexFormat())) return;
        final long epoch;
        final OpaqueTerrainTextures opacity;
        synchronized (prepared) {
            Prepared existing = prepared.get(builder);
            if (existing != null && existing.matches(chunk, compiled, generation)) return;
            opacity = textures;
            if (opacity == null) return;
            epoch = generation;
        }
        BlockPos position = chunk.getPosition();
        int x = position.getX(), y = position.getY(), z = position.getZ();
        TerrainVertexSpace space = TerrainVertexSpace.capture(builder, chunk);
        TerrainOccluderMesh mesh = space == null ? null : TerrainOccluderMesh.capture(builder.getByteBuffer(),
            builder.getVertexFormat().getSize(), builder.getVertexCount(), opacity, space.scale, space.translation);
        Prepared value = new Prepared(chunk, compiled, epoch,
            mesh == null ? null : new Entry(chunk, compiled, mesh, space, x, y, z));
        synchronized (prepared) {
            if (epoch == generation) prepared.put(builder, value);
        }
    }

    /** Main-thread upload now publishes prepared evidence without rescanning vertices. */
    public void capture(TerrainUploadContext.Value context, BufferBuilder builder, VertexBuffer vbo) {
        release(vbo);
        if (context.getLayer() != BlockRenderLayer.SOLID || pending.size() >= 8) return;
        Prepared value;
        synchronized (prepared) { value = prepared.remove(builder); }
        if (value == null || !value.matches(context.getChunk(), context.getCompiledChunk(), generation)
            || value.entry == null) return;
        pending.put(vbo, value.entry);
    }

    /** Successful dispatcher upload return, shared by native, staging and ordinary glBufferData. */
    public void uploaded(VertexBuffer vbo) {
        Entry entry = pending.remove(vbo);
        if (entry == null) return;
        removeUploaded(vbo);
        while (!uploaded.isEmpty() && (uploaded.size() >= MAX_MESHES
            || retainedBytes + entry.bytes() > MAX_BYTES)) {
            Iterator<VertexBuffer> oldest = uploaded.keySet().iterator();
            VertexBuffer key = oldest.next();
            removeUploaded(key);
        }
        if (retainedBytes + entry.bytes() > MAX_BYTES) return;
        RenderChunk chunk = entry.chunk.get();
        if (chunk == null) return;
        uploaded.put(vbo, entry);
        retainedBytes += entry.bytes();
        chunks.put(chunk, entry);
    }

    /** Invalidates any old bytes while preserving the current captured upload transaction. */
    public void legacyUploadStarting(VertexBuffer vbo) { removeUploaded(vbo); }

    public void release(VertexBuffer vbo) {
        if (vbo == null) return;
        pending.remove(vbo);
        removeUploaded(vbo);
    }

    private void removeUploaded(VertexBuffer vbo) {
        Entry previous = uploaded.remove(vbo);
        if (previous == null) return;
        retainedBytes -= previous.bytes();
        RenderChunk chunk = previous.chunk.get();
        if (chunk != null && chunks.get(chunk) == previous) chunks.remove(chunk);
    }

    /** World, resource, atlas and GL-context transitions discard source evidence together. */
    public void clear() {
        scheduler.reset();
        pending.clear();
        uploaded.clear();
        chunks.clear();
        retainedBytes = 0L;
        synchronized (prepared) {
            generation++;
            prepared.clear();
        }
        for (Work value : work) value.entry = null;
    }

    /** Rendering entry: empty-result backoff never suppresses any draw by itself. */
    public int filterAdaptive(Object container, BlockRenderLayer layer, RenderMatrixBridge.Snapshot matrices) {
        if (matrices == null || layer != BlockRenderLayer.SOLID
            || !(container instanceof TerrainRenderListAccessor)) return 0;
        TerrainRenderListAccessor view = (TerrainRenderListAccessor) container;
        if (!view.ice$initialized() || view.ice$renderChunks() == null) return 0;
        if (!scheduler.shouldRun(view.ice$viewEntityX(), view.ice$viewEntityY(),
            view.ice$viewEntityZ(), matrices.modelViewUnsafe())) return 0;
        int removed = filterLimited(container, layer, matrices, true);
        scheduler.result(removed);
        return removed;
    }

    public int filter(Object container, BlockRenderLayer layer, RenderMatrixBridge.Snapshot matrices) {
        return filterLimited(container, layer, matrices, false);
    }

    private int filterLimited(Object container, BlockRenderLayer layer, RenderMatrixBridge.Snapshot matrices, boolean priority) {
        if (matrices == null || layer != BlockRenderLayer.SOLID
            || !(container instanceof TerrainRenderListAccessor)) return 0;
        TerrainRenderListAccessor view = (TerrainRenderListAccessor) container;
        List<RenderChunk> visible = view.ice$renderChunks();
        if (!view.ice$initialized() || visible == null) return 0;
        int count = Math.min(visible.size(), MAX_MESHES);
        if (count < 2) return 0;
        int mode = TerrainVertexSpace.currentMode();
        if (mode < 0) return 0;
        float[] projection = matrices.projectionUnsafe();
        boolean knownEye = priority && projection[15] == 0F && projection[11] < 0F
            && TerrainCamera.eye(matrices.modelViewUnsafe(), occluderEye);
        if (knownEye) {
            occluderEye[0] += view.ice$viewEntityX();
            occluderEye[1] += view.ice$viewEntityY();
            occluderEye[2] += view.ice$viewEntityZ();
        }
        java.util.Arrays.fill(hidden, 0, count, false);
        raster.begin(matrices.modelViewUnsafe(), matrices.projectionUnsafe(),
            view.ice$viewEntityX(), view.ice$viewEntityY(), view.ice$viewEntityZ(), FRAME_CELLS);
        int collected = 0;
        int sources = 0;
        try {
            for (int i = 0; i < count; i++) {
                RenderChunk chunk = visible.get(i);
                Entry entry = chunks.get(chunk);
                if (entry == null || !entry.current(chunk)
                    || entry.space.renderRegions != (mode == 1)
                    || ChunkAnimatorRenderBridge.requiresCompatibilityDraw(chunk)) continue;
                TerrainOccluderMesh mesh = entry.mesh;
                Work item = work[collected];
                item.queryable = raster.projectBounds(entry.space.originX + mesh.minX, entry.space.originY + mesh.minY,
                    entry.space.originZ + mesh.minZ, entry.space.originX + mesh.maxX,
                    entry.space.originY + mesh.maxY, entry.space.originZ + mesh.maxZ, item.bounds);
                // A section crossing the near plane must stay visible, but its
                // individual walls can still lie entirely in front of the eye.
                // Keep those sources first; rasterize validates every quad.
                if (!item.queryable) item.bounds.nearest = 0.0D;
                item.source = mesh.quads.length != 0 && (!item.queryable || item.bounds.canCoverCell);
                if (item.source) sources++;
                item.entry = entry;
                item.index = i;
                collected++;
            }
            // With fewer than two known layers there is no other query that
            // any retained source could hide.
            if (collected < 2 || sources == 0) return 0;
            heapNearFirst(collected);
            int remainingQuads = priority ? PRIORITY_FRAME_QUADS : FRAME_QUADS;
            int removed = 0;
            for (int end = collected - 1; end >= 0 && !raster.exhausted(); end--) {
                if (remainingQuads == 0) {
                    removed += filterReadOnlyTail(end + 1);
                    break;
                }
                Work item = work[0];
                work[0] = work[end]; work[end] = item;
                sift(0, end);
                if (item.queryable) {
                    tested++;
                    if (raster.occluded(item.bounds)) {
                        hidden[item.index] = true;
                        removed++;
                        continue;
                    }
                }
                if (!item.source) continue;
                Entry entry = item.entry;
                float[] faces = entry.mesh.quads;
                for (int face = 0; face < faces.length / 12 && remainingQuads > 0 && !raster.exhausted(); face++) {
                    int index = priority ? entry.mesh.priority[face] : face;
                    if (knownEye && !entry.mesh.facesCamera(index, occluderEye[0] - entry.space.originX,
                        occluderEye[1] - entry.space.originY, occluderEye[2] - entry.space.originZ)) continue;
                    int q = index * 12;
                    raster.rasterize(faces, q, entry.space.originX, entry.space.originY, entry.space.originZ);
                    remainingQuads--;
                    rasterizedQuads++;
                }
            }
            if (removed == 0) return 0;
            // Preserve the original list order. No operation above mutated the
            // list, and untouched/unknown/over-budget entries always survive.
            int write = 0;
            int original = visible.size();
            for (int read = 0; read < original; read++) {
                if (read >= count || !hidden[read]) visible.set(write++, visible.get(read));
            }
            visible.subList(write, original).clear();
            occluded += removed;
            return removed;
        } finally {
            for (int i = 0; i < collected; i++) work[i].entry = null;
        }
    }

    /** Once no more occluders can be written, query order cannot change depth.
     * Stop heap extraction and test its remaining entries linearly. The same
     * conservative raster proof is required for every hidden mesh. Exhausted
     * or untouched queries remain visible; no result from an older frame is used. */
    private int filterReadOnlyTail(int count) {
        readOnlyTailPasses++;
        int removed = 0;
        int i = 0;
        for (; i < count && !raster.exhausted(); i++) {
            Work item = work[i];
            if (!item.queryable) continue;
            tested++;
            if (raster.occluded(item.bounds)) {
                hidden[item.index] = true;
                removed++;
            }
        }
        if (i < count) readOnlyTailBudgetStops++;
        return removed;
    }

    /** Packs current uploaded evidence; projection, coverage and command decisions run on the GPU. */
    public int writeGpuInput(TerrainRenderListAccessor view, ByteBuffer bounds, ByteBuffer faces,
                             int candidates, int faceCapacity) {
        bounds.clear();
        faces.clear();
        int mode = TerrainVertexSpace.currentMode();
        int faceCount = 0;
        List<RenderChunk> visible = view.ice$renderChunks();
        for (int i = 0; i < candidates; i++) {
            RenderChunk chunk = visible.get(i);
            Entry entry = chunks.get(chunk);
            if (entry == null || !entry.current(chunk) || mode < 0
                || entry.space.renderRegions != (mode == 1)
                || ChunkAnimatorRenderBridge.requiresCompatibilityDraw(chunk)) {
                for (int word = 0; word < 8; word++) bounds.putDouble(0D);
                continue;
            }
            TerrainOccluderMesh mesh = entry.mesh;
            double x = entry.space.originX - view.ice$viewEntityX();
            double y = entry.space.originY - view.ice$viewEntityY();
            double z = entry.space.originZ - view.ice$viewEntityZ();
            bounds.putDouble(x + mesh.minX).putDouble(y + mesh.minY).putDouble(z + mesh.minZ).putDouble(1D);
            bounds.putDouble(x + mesh.maxX).putDouble(y + mesh.maxY).putDouble(z + mesh.maxZ).putDouble(0D);
            // Minecraft's existing visible order supplies sources. No second CPU projection/sort.
            for (int q = 0; q < mesh.quads.length && faceCount < faceCapacity; q += 12) {
                for (int v = 0; v < 12; v += 3) {
                    faces.putDouble(x + mesh.quads[q + v]).putDouble(y + mesh.quads[q + v + 1])
                        .putDouble(z + mesh.quads[q + v + 2]).putDouble(0D);
                }
                faceCount++;
            }
        }
        bounds.flip();
        faces.flip();
        return faceCount;
    }

    /** Build only the heap; candidates are ordered as the raster consumes them. */
    private void heapNearFirst(int count) {
        for (int root = count / 2 - 1; root >= 0; root--) sift(root, count);
    }

    private void sift(int root, int end) {
        while (root * 2 + 1 < end) {
            int child = root * 2 + 1;
            if (child + 1 < end && nearer(work[child + 1], work[child])) child++;
            if (!nearer(work[child], work[root])) return;
            Work swap = work[root]; work[root] = work[child]; work[child] = swap;
            root = child;
        }
    }

    private static boolean nearer(Work left, Work right) {
        return left.bounds.nearest < right.bounds.nearest
            || left.bounds.nearest == right.bounds.nearest && left.index < right.index;
    }

    private static boolean standardBlockFormat(VertexFormat format) {
        return format != null && format.getSize() == 28 && format.getElementCount() == 4
            && format.getOffset(0) == 0 && format.getOffset(1) == 12
            && format.getOffset(2) == 16 && format.getOffset(3) == 24
            && DefaultVertexFormats.POSITION_3F.equals(format.getElement(0))
            && DefaultVertexFormats.COLOR_4UB.equals(format.getElement(1))
            && DefaultVertexFormats.TEX_2F.equals(format.getElement(2))
            && DefaultVertexFormats.TEX_2S.equals(format.getElement(3));
    }

    public long getTested() { return tested; }
    public long getOccluded() { return occluded; }
    public long getRasterizedQuads() { return rasterizedQuads; }
    public long getReadOnlyTailPasses() { return readOnlyTailPasses; }
    public long getReadOnlyTailBudgetStops() { return readOnlyTailBudgetStops; }
    public long getSkippedProbes() { return scheduler.skipped(); }
    public long getRetainedBytes() { return retainedBytes; }
    public int getMeshCount() { return uploaded.size(); }

    private static final class Work {
        private final SoftwareOcclusionRaster.Bounds bounds = new SoftwareOcclusionRaster.Bounds();
        private Entry entry;
        private int index;
        private boolean queryable;
        private boolean source;
    }

    private static final class Prepared {
        private final WeakReference<RenderChunk> chunk;
        private final WeakReference<CompiledChunk> compiled;
        private final long generation;
        private final Entry entry;
        private Prepared(RenderChunk chunk, CompiledChunk compiled, long generation, Entry entry) {
            this.chunk = new WeakReference<RenderChunk>(chunk);
            this.compiled = new WeakReference<CompiledChunk>(compiled);
            this.generation = generation;
            this.entry = entry;
        }
        private boolean matches(RenderChunk chunk, CompiledChunk compiled, long generation) {
            return this.chunk.get() == chunk && this.compiled.get() == compiled && this.generation == generation;
        }
    }

    private static final class Entry {
        private final WeakReference<RenderChunk> chunk;
        private final WeakReference<CompiledChunk> compiled;
        private final TerrainOccluderMesh mesh;
        private final TerrainVertexSpace space;
        private final int x, y, z;
        private Entry(RenderChunk chunk, CompiledChunk compiled, TerrainOccluderMesh mesh,
                      TerrainVertexSpace space) {
            this(chunk, compiled, mesh, space, chunk.getPosition().getX(),
                chunk.getPosition().getY(), chunk.getPosition().getZ());
        }
        private Entry(RenderChunk chunk, CompiledChunk compiled, TerrainOccluderMesh mesh,
                      TerrainVertexSpace space, int x, int y, int z) {
            this.chunk = new WeakReference<RenderChunk>(chunk);
            this.compiled = new WeakReference<CompiledChunk>(compiled);
            this.mesh = mesh;
            this.space = space;
            this.x = x; this.y = y; this.z = z;
        }
        private boolean current(RenderChunk value) {
            if (value == null || value.getCompiledChunk() != compiled.get()) return false;
            BlockPos position = value.getPosition();
            return position.getX() == x && position.getY() == y && position.getZ() == z;
        }
        private long bytes() { return 288L + mesh.quads.length * 4L + mesh.faces.length * 3L; }
    }
}
