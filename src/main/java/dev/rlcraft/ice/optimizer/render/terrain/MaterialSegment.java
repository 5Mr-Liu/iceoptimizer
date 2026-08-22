package dev.rlcraft.ice.optimizer.render.terrain;

public final class MaterialSegment {
    private final int firstVertex;
    private final int vertexCount;
    private final int materialKey;

    public MaterialSegment(int firstVertex, int vertexCount, int materialKey) {
        if (firstVertex < 0 || vertexCount <= 0) throw new IllegalArgumentException("segment range");
        this.firstVertex = firstVertex;
        this.vertexCount = vertexCount;
        this.materialKey = materialKey;
    }

    public int getFirstVertex() { return firstVertex; }
    public int getVertexCount() { return vertexCount; }
    public int getMaterialKey() { return materialKey; }
}
