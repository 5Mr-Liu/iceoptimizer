package dev.rlcraft.ice.optimizer.render.terrain;

import com.google.common.util.concurrent.ListenableFutureTask;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.BlockRenderLayer;

/** CPU preparation travels with the dispatcher's existing cancellable upload. */
public final class NativeTerrainUploadTask {
    private static final ThreadLocal<Prepared> CURRENT = new ThreadLocal<Prepared>();
    private static final Prepared EMPTY = new Prepared(0, 0, null, null);

    private NativeTerrainUploadTask() { }

    /** Called on the worker, before the original upload queue is locked. */
    public static ListenableFutureTask<Object> create(final Runnable upload, Object result,
            BlockRenderLayer layer, BufferBuilder builder, RenderChunk chunk, CompiledChunk compiled) {
        if (OptimizerRegistry.isRuntimePaused()) return ListenableFutureTask.create(upload, result);
        final Prepared prepared = prepare(layer, builder, chunk, compiled);
        // Keep the SAME future in PendingUpload and the worker's cancellation
        // callback. Cancelling it prevents both publication and builder access.
        return ListenableFutureTask.create(new Runnable() {
            @Override public void run() {
                CURRENT.set(prepared);
                try { upload.run(); }
                finally { CURRENT.remove(); }
            }
        }, result);
    }

    static Prepared take() {
        Prepared value = CURRENT.get();
        CURRENT.remove();
        return value;
    }

    /** Also used by direct uploads when the dispatcher has no worker threads. */
    static Prepared prepare(BlockRenderLayer layer, BufferBuilder builder,
                            RenderChunk chunk, CompiledChunk compiled) {
        // Empty transparency resorts can leave a pooled builder untouched.
        // Inspect neither its old layout nor its old vertex coordinates.
        int count = compiled.isLayerEmpty(layer) ? 0 : builder.getVertexCount();
        if (count == 0) return EMPTY;
        ByteBuffer source = builder.getByteBuffer();
        int stride = builder.getVertexFormat().getSize();
        if ((stride != 28 && stride != 56) || source.remaining() != count * (long) stride
            || count < 0 || (count & 3) != 0 || builder.getDrawMode() != 7)
            throw new IllegalStateException("ICE native terrain needs a block quad layout (28 or 56 bytes): stride="
                + stride + ", count=" + count + ", mode=" + builder.getDrawMode()
                + ", position=" + source.position() + ", limit=" + source.limit()
                + ", layer=" + layer + ", chunk=" + chunk.getPosition());
        TerrainVertexSpace space = TerrainVertexSpace.capture(builder, chunk);
        if (space == null) throw new IllegalStateException("ICE terrain coordinate layout unavailable");
        // Shader packs may move vertices (foliage/water) and render back faces.
        // Static CPU face planes cannot reject geometry in those programs.
        NativeTerrainFaceIndex faces = stride == 56 || layer == BlockRenderLayer.TRANSLUCENT ? null
            : new NativeTerrainFaceIndex(source, space.scale, space.translation);
        return new Prepared(count, stride, space, faces);
    }

    /** No world, chunk or pooled vertex-buffer references survive in this value. */
    static final class Prepared {
        final int count;
        final int stride;
        final TerrainVertexSpace space;
        final NativeTerrainFaceIndex faces;
        Prepared(int count, int stride, TerrainVertexSpace space, NativeTerrainFaceIndex faces) {
            this.count = count;
            this.stride = stride;
            this.space = space;
            this.faces = faces;
        }
    }
}
