package dev.rlcraft.ice.optimizer.compat.chunk;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** Early CoreMod ABI implemented by BufferBuilder; packaged only in the optimizer Core JAR. */
public interface ChunkBufferAccessor {
    FloatBuffer ice$sortFloatBuffer();
    IntBuffer ice$sortIntBuffer();
    int ice$sortVertexCount();
    int ice$sortVertexStrideInts();
}
