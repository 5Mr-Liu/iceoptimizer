package dev.rlcraft.ice.benchmark;

import dev.rlcraft.ice.optimizer.compat.chunk.ChunkVertexBufferAccessor;
import dev.rlcraft.ice.optimizer.compat.chunk.NativeTerrainChunkAccessor;
import dev.rlcraft.ice.optimizer.render.terrain.NativeTerrainRenderer;
import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.chunk.ChunkCompileTaskGenerator;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.BlockRenderLayer;

/** Test-only live GL publication cycle; never added to the optimizer artifact. */
public final class EmptyTerrainUploadProbe {
    public static void run() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            ChunkRenderDispatcher dispatcher = null;
            for (Field field : mc.renderGlobal.getClass().getDeclaredFields())
                if (field.getType() == ChunkRenderDispatcher.class) {
                    field.setAccessible(true);
                    dispatcher = (ChunkRenderDispatcher) field.get(mc.renderGlobal);
                    break;
                }
            if (dispatcher == null) throw new AssertionError("Missing live chunk dispatcher");
            int before = NativeTerrainRenderer.residentMeshes();
            RenderChunk chunk = new RenderChunk(mc.world, mc.renderGlobal, -1);
            try {
                chunk.setPosition(16, 64, 16);
                BufferBuilder builder = new BufferBuilder(256);
                VertexBuffer vbo = chunk.getVertexBufferByLayer(BlockRenderLayer.TRANSLUCENT.ordinal());
                CompiledChunk used = new Used();
                emit(builder);
                dispatcher.uploadChunk(BlockRenderLayer.TRANSLUCENT, builder, chunk, used, 0).get();
                require(NativeTerrainRenderer.residentMeshes() == before + 1, "Initial geometry absent");
                require(((ChunkVertexBufferAccessor) vbo).ice$vertexCount() == 4, "Wrong initial count");
                CompiledChunk empty = new CompiledChunk();
                empty.setLayerStarted(BlockRenderLayer.TRANSLUCENT);
                ChunkCompileTaskGenerator task = new ChunkCompileTaskGenerator(chunk,
                    ChunkCompileTaskGenerator.Type.RESORT_TRANSPARENCY, 0);
                task.setCompiledChunk(empty);
                chunk.resortTransparency(0, 0, 0, task);
                require(builder.getVertexCount() == 0 && builder.getByteBuffer().remaining() == 112,
                    "Missing recycled-buffer boundary");
                dispatcher.uploadChunk(BlockRenderLayer.TRANSLUCENT, builder, chunk, empty, 0).get();
                require(NativeTerrainRenderer.residentMeshes() == before, "Empty geometry retained");
                require(((ChunkVertexBufferAccessor) vbo).ice$vertexCount() == 0, "Stale vertex count");
                require(((NativeTerrainChunkAccessor) chunk).ice$nativeSection() == null, "Stale section");
                emit(builder);
                dispatcher.uploadChunk(BlockRenderLayer.TRANSLUCENT, builder, chunk, used, 0).get();
                require(NativeTerrainRenderer.residentMeshes() == before + 1, "Cannot restore geometry");
            } finally { chunk.deleteGlResources(); }
            require(NativeTerrainRenderer.residentMeshes() == before, "Probe leaked geometry");
            System.out.println("ICE_BENCH empty_upload_probe=passed nonempty_empty_nonempty");
        } catch (Exception failure) { throw new IllegalStateException("Empty terrain upload probe failed", failure); }
    }

    private static void emit(BufferBuilder builder) {
        builder.begin(7, DefaultVertexFormats.BLOCK);
        builder.setTranslation(-16, -64, -16);
        for (int i = 0; i < 4; i++)
            builder.pos(16 + (i & 1), 64 + (i >> 1), 16).color(255, 255, 255, 255)
                .tex(0, 0).lightmap(240, 240).endVertex();
        builder.finishDrawing();
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final class Used extends CompiledChunk {
        Used() { setLayerUsed(BlockRenderLayer.TRANSLUCENT); }
    }
}
