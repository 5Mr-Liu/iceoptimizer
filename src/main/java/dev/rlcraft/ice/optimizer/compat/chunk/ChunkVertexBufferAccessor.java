package dev.rlcraft.ice.optimizer.compat.chunk;

/** Early CoreMod ABI implemented by VertexBuffer; packaged only in the optimizer Core JAR. */
public interface ChunkVertexBufferAccessor {
    int ice$glBufferId();
    int ice$vertexStrideBytes();
    int ice$capacityBytes();
    void ice$setCapacityBytes(int capacityBytes);
    void ice$setVertexCount(int vertexCount);
}
