package dev.rlcraft.ice.optimizer.compat.chunk;

/** Early-source ABI implemented by VertexBuffer; included in the single optimizer JAR. */
public interface ChunkVertexBufferAccessor {
    int ice$glBufferId();
    int ice$vertexCount();
    int ice$vertexStrideBytes();
    int ice$capacityBytes();
    void ice$setCapacityBytes(int capacityBytes);
    void ice$setVertexCount(int vertexCount);
}
