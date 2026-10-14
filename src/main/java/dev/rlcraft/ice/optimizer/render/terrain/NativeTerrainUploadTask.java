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
    private static final Prepared EMPTY = new Prepared(0, null, null);

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
        if (builder.getVertexFormat().getSize() != 28 || source.remaining() != count * 28L
            || count < 0 || (count & 3) != 0)
            throw new IllegalStateException("ICE native terrain needs the 28-byte quad layout: stride="
                + builder.getVertexFormat().getSize() + ", count=" + count + ", mode=" + builder.getDrawMode()
                + ", position=" + source.position() + ", limit=" + source.limit()
                + ", layer=" + layer + ", chunk=" + chunk.getPosition());
        TerrainVertexSpace space = TerrainVertexSpace.capture(builder, chunk);
        if (space == null) throw new IllegalStateException("ICE terrain coordinate layout unavailable");
        NativeTerrainFaceIndex faces = layer == BlockRenderLayer.TRANSLUCENT ? null
            : new NativeTerrainFaceIndex(source, space.scale, space.translation);
        return new Prepared(count, space, faces);
    }

    /** No world, chunk or pooled vertex-buffer references survive in this value. */
    static final class Prepared {
        final int count;
        final TerrainVertexSpace space;
        final NativeTerrainFaceIndex faces;
        Prepared(int count, TerrainVertexSpace space, NativeTerrainFaceIndex faces) {
            this.count = count;
            this.space = space;
            this.faces = faces;
        }
    }
}
