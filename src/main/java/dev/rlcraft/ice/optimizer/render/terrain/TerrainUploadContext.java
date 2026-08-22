package dev.rlcraft.ice.optimizer.render.terrain;

import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.BlockRenderLayer;

/**
 * One-shot context spanning the structurally verified dispatcher call into its
 * private VBO uploader. The value is consumed before any upload policy check,
 * so a disabled module cannot strand a ThreadLocal reference to a world.
 */
public final class TerrainUploadContext {
    private static final ThreadLocal<Value> CURRENT = new ThreadLocal<Value>();

    private TerrainUploadContext() {
    }

    public static void begin(BlockRenderLayer layer, RenderChunk chunk,
                             CompiledChunk compiledChunk) {
        if (layer == null || chunk == null || compiledChunk == null) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(new Value(layer, chunk, compiledChunk));
    }

    public static Value take() {
        Value value = CURRENT.get();
        CURRENT.remove();
        return value;
    }

    public static final class Value {
        private final BlockRenderLayer layer;
        private final RenderChunk chunk;
        private final CompiledChunk compiledChunk;

        private Value(BlockRenderLayer layer, RenderChunk chunk,
                      CompiledChunk compiledChunk) {
            this.layer = layer;
            this.chunk = chunk;
            this.compiledChunk = compiledChunk;
        }

        public BlockRenderLayer getLayer() { return layer; }
        public RenderChunk getChunk() { return chunk; }
        public CompiledChunk getCompiledChunk() { return compiledChunk; }
    }
}
