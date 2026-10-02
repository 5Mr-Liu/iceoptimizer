package dev.rlcraft.ice.optimizer.compat.chunk;

import java.util.List;
import net.minecraft.client.renderer.chunk.RenderChunk;

/** Early CoreMod ABI exposing only the vanilla container data needed by ICE. */
public interface TerrainRenderListAccessor {
    List<RenderChunk> ice$renderChunks();
    boolean ice$initialized();
    double ice$viewEntityX();
    double ice$viewEntityY();
    double ice$viewEntityZ();
}
