package dev.rlcraft.ice.optimizer.client;

import dev.rlcraft.ice.optimizer.render.terrain.NativeTerrainRenderer;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainVisibilityAccessor;
import dev.rlcraft.ice.optimizer.compat.chunk.NativeTerrainWorldAccessor;
import dev.rlcraft.ice.optimizer.compat.chunk.ChunkVertexBufferAccessor;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;

/** Render-thread handoff: no upload can cross between the two geometry owners. */
class NativeTerrainPauseLifecycle {
    private static final ByteBuffer EMPTY = ByteBuffer.allocateDirect(0);
    void beforeChange() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!minecraft.isCallingFromMinecraftThread())
            throw new IllegalStateException("ICE terrain toggle must run on the client thread");
        if (minecraft.world != null) {
            Object[] chunks = ((TerrainVisibilityAccessor) minecraft.renderGlobal).ice$renderChunks();
            cancelAndDrain(chunks, () -> ((NativeTerrainWorldAccessor) minecraft.renderGlobal).ice$stopChunkUpdates());
        }
    }

    static void cancelAndDrain(Object[] chunks, Runnable drain) {
        // stopChunkUpdates only cancels queued tasks, then blocks taking every
        // builder. A running task can enqueue its upload AFTER that method's
        // last upload drain. It cannot return its builder until the client
        // executes the upload, while the client is waiting for the builder.
        // Vanilla loadRenderers avoids this by stopping all RenderChunks first
        // (via deleteGlResources). Do the same cancellation before the wait,
        // without deleting geometry until the ownership switch below.
        if (chunks != null) for (Object chunk : chunks)
            if (chunk != null) ((RenderChunk) chunk).stopCompileTask();
        drain.run();
    }

    void afterChange() {
        NativeTerrainRenderer.reset(true);
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.world != null) {
            rebuildMeshes(((TerrainVisibilityAccessor) minecraft.renderGlobal).ice$renderChunks());
            minecraft.renderGlobal.setDisplayListEntitiesDirty();
        }
    }

    static void rebuildMeshes(Object[] chunks) {
        // The world, positions, VBO mode and view distance have not changed.
        // Recreating ViewFrustum would allocate every RenderChunk and its four
        // VBOs again. Keep those objects, but retire ALL old geometry before
        // the new owner's workers rebuild it. beforeChange has already drained
        // the dispatcher, so no old upload can race this invalidation.
        if (chunks == null) return;
        for (Object value : chunks) if (value != null) {
            RenderChunk chunk = (RenderChunk) value;
            chunk.setCompiledChunk(CompiledChunk.DUMMY);
            for (int layer = 0; layer < 4; layer++) {
                VertexBuffer buffer = chunk.getVertexBufferByLayer(layer);
                ChunkVertexBufferAccessor access = (ChunkVertexBufferAccessor) buffer;
                if (access.ice$capacityBytes() != 0) buffer.bufferData(EMPTY);
                access.ice$setVertexCount(0);
            }
            // Match newly constructed chunks: dirty, not forced immediate.
            chunk.clearNeedsUpdate();
            chunk.setNeedsUpdate(false);
        }
    }
}
