package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.compat.chunk.NativeTerrainChunkAccessor;
import java.util.Arrays;
import net.minecraft.client.renderer.chunk.RenderChunk;

/** Layer queues filled during visibility traversal, in that traversal's order. */
final class NativeTerrainDrawQueue {
    final NativeTerrainRenderer.Mesh[][] layers = new NativeTerrainRenderer.Mesh[4][256];
    final int[] counts = new int[4];
    private NativeTerrainRenderer.Section[] animations = new NativeTerrainRenderer.Section[64];
    private int animationCount;

    void clear() {
        for (int layer = 0; layer < 4; layer++) {
            Arrays.fill(layers[layer], 0, counts[layer], null);
            counts[layer] = 0;
        }
        Arrays.fill(animations, 0, animationCount, null);
        animationCount = 0;
    }

    void add(RenderChunk chunk) {
        NativeTerrainRenderer.Section section = (NativeTerrainRenderer.Section)
            ((NativeTerrainChunkAccessor) chunk).ice$nativeSection();
        if (section != null) add(section);
    }

    void add(NativeTerrainRenderer.Section section) {
        // CompiledChunk records the block renderer's used-layer flags, not
        // uploaded vertex counts. A used layer can emit zero vertices, and
        // individual layer uploads can also replace storage between frames.
        // Only resident geometry can enter a draw queue. Upload/release and
        // publication already invalidate the queue when either side changes.
        int mask = section.activeMask & section.residentMask;
        if (mask != 0 && section.animated) {
            if (animationCount == animations.length) animations = Arrays.copyOf(animations, animationCount * 2);
            animations[animationCount++] = section;
        }
        while (mask != 0) {
            int layer = Integer.numberOfTrailingZeros(mask);
            mask &= mask - 1;
            NativeTerrainRenderer.Mesh mesh = section.layers[layer];
            if (mesh == null) throw new IllegalStateException("ICE visible terrain has no resident mesh");
            int count = counts[layer]++;
            if (count == layers[layer].length) layers[layer] = Arrays.copyOf(layers[layer], count * 2);
            layers[layer][count] = mesh;
        }
    }

    void animate(float[] offset) {
        for (int i = 0; i < animationCount;) {
            NativeTerrainRenderer.Section section = animations[i];
            NativeChunkAnimation.offset(section.chunk, section.chunk.getPosition(), offset);
            section.offsetX = offset[0]; section.offsetY = offset[1]; section.offsetZ = offset[2];
            if (NativeChunkAnimation.pending(section.chunk)) i++;
            else {
                section.animated = false;
                animations[i] = animations[--animationCount];
                animations[animationCount] = null;
            }
        }
    }
}
