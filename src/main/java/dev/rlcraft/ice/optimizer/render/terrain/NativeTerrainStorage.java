package dev.rlcraft.ice.optimizer.render.terrain;

import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;

/** Growing render-thread storage shared by vertices and ordered indices. */
final class NativeTerrainStorage {
    private static final int PAGE = 65536;
    final int stride;
    int buffer, capacity;
    private int writtenLimit;
    NativeTerrainStorage(int stride) { this.stride = stride; }

    void ensure(int required) {
        if (required <= capacity) return;
        int size = Math.max(PAGE, capacity);
        while (size < required) size = (int) Math.min(Integer.MAX_VALUE - 3L,
            size + Math.max(PAGE, size / 2L));
        int read = GL11.glGetInteger(GL31.GL_COPY_READ_BUFFER_BINDING);
        int write = GL11.glGetInteger(GL31.GL_COPY_WRITE_BUFFER_BINDING);
        int previous = buffer;
        int replacement = GL15.glGenBuffers();
        if (replacement == 0) throw new IllegalStateException("ICE terrain GPU buffer creation failed");
        boolean committed = false;
        try {
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, replacement);
            long bytes = size * (long) stride;
            GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER, bytes, GL15.GL_STATIC_DRAW);
            // GL errors belong to the shared context, not the preceding call.
            // Check this new object's storage without consuming another render
            // operation's pending error. Only growth needs this size query.
            long allocated = GL32.glGetBufferParameteri64(GL31.GL_COPY_WRITE_BUFFER, GL15.GL_BUFFER_SIZE);
            if (allocated != bytes) {
                throw new IllegalStateException("ICE terrain GPU storage size mismatch: requested="
                    + bytes + ", allocated=" + allocated + ", stride=" + stride);
            }
            if (previous != 0) {
                if (writtenLimit != 0) {
                    GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, previous);
                    GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER,
                        0, 0, writtenLimit * (long) stride);
                }
                GL15.glDeleteBuffers(previous);
            }
            buffer = replacement; capacity = size;
            committed = true;
        } finally {
            if (!committed) GL15.glDeleteBuffers(replacement);
            // A saved binding may refer to the storage just retired. Restore
            // its replacement rather than rebinding a deleted object name.
            if (committed && previous != 0) {
                if (read == previous) read = replacement;
                if (write == previous) write = replacement;
            }
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, read);
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, write);
        }
    }

    void upload(ByteBuffer source, int first, int staging) {
        int read = GL11.glGetInteger(GL31.GL_COPY_READ_BUFFER_BINDING);
        int write = GL11.glGetInteger(GL31.GL_COPY_WRITE_BUFFER_BINDING);
        try {
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, staging);
            GL15.glBufferData(GL31.GL_COPY_READ_BUFFER, source, GL15.GL_STREAM_DRAW);
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, buffer);
            GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, 0, first * (long) stride, source.remaining());
            writtenLimit = Math.max(writtenLimit, first + source.remaining() / stride);
        } finally {
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, read);
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, write);
        }
    }

    /** One staging allocation/upload for all changed index ranges this frame. */
    void uploadBatch(ByteBuffer source, int[] copies, int count, int staging) {
        int read = GL11.glGetInteger(GL31.GL_COPY_READ_BUFFER_BINDING);
        int write = GL11.glGetInteger(GL31.GL_COPY_WRITE_BUFFER_BINDING);
        try {
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, staging);
            GL15.glBufferData(GL31.GL_COPY_READ_BUFFER, source, GL15.GL_STREAM_DRAW);
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, buffer);
            for (int index = 0; index < count * 3; index += 3) {
                int sourceByte = copies[index], first = copies[index + 1], bytes = copies[index + 2];
                GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER,
                    sourceByte, first * (long) stride, bytes);
                writtenLimit = Math.max(writtenLimit, first + bytes / stride);
            }
        } finally {
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, read);
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, write);
        }
    }

    void delete() { if (buffer != 0) GL15.glDeleteBuffers(buffer); }
}
