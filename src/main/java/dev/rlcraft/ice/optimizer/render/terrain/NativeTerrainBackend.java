package dev.rlcraft.ice.optimizer.render.terrain;

import java.nio.ByteBuffer;

/** Fixed backend selection; no capability probing or automatic runtime switching. */
abstract class NativeTerrainBackend {
    int[] drawMaterials;
    final int stride;
    final boolean shaderPack;

    NativeTerrainBackend(int stride) {
        this.stride = stride;
        this.shaderPack = stride == 56;
    }

    abstract NativeTerrainAllocation upload(ByteBuffer source, int count);
    // Fixed pages retain the old allocation until publication; retiring their
    // last mesh first would destroy a page that the replacement can still use.
    boolean retireBeforeUpload() { return false; }
    abstract void free(NativeTerrainAllocation mesh);
    abstract void uploadIndices(ByteBuffer source, int[] copies, int count);
    abstract long geometryCapacityBytes();
    abstract void prepare(int[] words, int bytes);
    abstract void submit(int[] words, int commandBase, int start, int count);
    abstract void delete();
}
