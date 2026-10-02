package dev.rlcraft.ice.optimizer.compat.chunk;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** Early-source ABI implemented by BufferBuilder; included in the single optimizer JAR. */
public interface ChunkBufferAccessor {
    FloatBuffer ice$sortFloatBuffer();
    IntBuffer ice$sortIntBuffer();
    int ice$sortVertexCount();
    int ice$sortVertexStrideInts();
}
