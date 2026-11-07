package dev.rlcraft.ice.optimizer.render.terrain;

/** Stable addresses for a completed mesh, split only at quad boundaries. */
final class NativeTerrainAllocation {
    static final int PART_VERTICES = 65532;
    final NativeTerrainAllocator.Range vertices, indices;
    final NativeTerrainAllocator.Range[] pages;
    final int count;

    NativeTerrainAllocation(NativeTerrainAllocator.Range vertices, NativeTerrainAllocator.Range indices, int count) {
        this.vertices = vertices; this.indices = indices; this.count = count; pages = null;
    }

    NativeTerrainAllocation(NativeTerrainAllocator.Range[] pages, int count) {
        this.pages = pages.length == 1 ? null : pages;
        this.vertices = this.indices = pages[0]; this.count = count;
    }

    int parts() { return (count - 1) / PART_VERTICES + 1; }
    int vertexFirst(int part) { return pages == null ? vertices.first + part * PART_VERTICES : pages[part].first; }
    int indexFirst(int part) { return pages == null ? indices.first + part * PART_VERTICES : pages[part].first; }
}
