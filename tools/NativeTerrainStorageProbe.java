package dev.rlcraft.ice.optimizer.render.terrain;

import java.nio.ByteBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** External real-driver regression; never installed into the optimizer. */
public final class NativeTerrainStorageProbe {
    private static final NativeTerrainStorage commandStorage = new NativeTerrainStorage(1);
    private static int commandStaging;
    public static int uploadCommands(ByteBuffer data) {
        if (commandStaging == 0) commandStaging = GL15.glGenBuffers();
        commandStorage.ensure(data.remaining());
        commandStorage.upload(data, 0, commandStaging);
        return commandStorage.buffer;
    }
    public static void main(String[] args) throws Exception {
        Pbuffer context = new Pbuffer(32, 32, new PixelFormat(), null, null);
        try {
            context.makeCurrent();
            System.out.println("vendor=" + GL11.glGetString(GL11.GL_VENDOR));
            System.out.println("renderer=" + GL11.glGetString(GL11.GL_RENDERER));
            System.out.println("version=" + GL11.glGetString(GL11.GL_VERSION));
            require(GLContext.getCapabilities().OpenGL43, "requires OpenGL 4.3");
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "context setup error");
            if (Boolean.getBoolean("ice.probe.oldStorage")) {
                pendingInvalidOperation();
                try {
                    new NativeTerrainStorage(28).ensure(180000);
                    throw new AssertionError("old implementation did not reproduce");
                } catch (IllegalStateException expected) {
                    require(expected.getMessage().contains("1282 at 5505024"), expected.toString());
                    System.out.println("old_false_failure_reproduced=" + expected.getMessage());
                }
                return;
            }
            for (int stride : new int[] { 2, 28, 56 }) exercise(stride);
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "final GL error");
            System.out.println("PASS: pending error preserved; exact storage/upload/batch/growth bytes; bindings restored");
        } finally { context.destroy(); }
    }

    private static void exercise(int stride) {
        NativeTerrainStorage storage = new NativeTerrainStorage(stride);
        int staging = GL15.glGenBuffers();
        int sentinelRead = GL15.glGenBuffers(), sentinelWrite = GL15.glGenBuffers();
        try {
            // Reproduce the reported allocation size for the 28-byte format.
            // The preceding invalid operation belongs to another caller.
            pendingInvalidOperation();
            bindings(sentinelRead, sentinelWrite);
            storage.ensure(180000);
            require(GL11.glGetError() == GL11.GL_INVALID_OPERATION, "pending error consumed");
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "allocation introduced error");
            checkBindings(sentinelRead, sentinelWrite);
            require(size(storage.buffer) == (long) storage.capacity * stride, "allocated bytes");

            ByteBuffer payload = pattern(64 * stride, stride);
            storage.upload(payload, 12, staging);
            checkBindings(sentinelRead, sentinelWrite);
            require(payload.position() == 0, "source position changed");
            checkBytes(storage.buffer, 12L * stride, payload);
            int old = storage.buffer;
            pendingInvalidOperation();
            bindings(sentinelRead, sentinelWrite);
            storage.ensure(storage.capacity + 1);
            require(GL11.glGetError() == GL11.GL_INVALID_OPERATION, "growth consumed prior error");
            checkBindings(sentinelRead, sentinelWrite);
            require(!GL15.glIsBuffer(old), "retired storage leaked");
            checkBytes(storage.buffer, 12L * stride, payload);

            ByteBuffer batch = pattern(8 * stride, 117);
            storage.uploadBatch(batch, new int[] {0, 100, 4 * stride, 4 * stride, 200, 4 * stride}, 2, staging);
            checkBindings(sentinelRead, sentinelWrite);
            ByteBuffer first = batch.duplicate(); first.limit(4 * stride);
            ByteBuffer second = batch.duplicate(); second.position(4 * stride);
            checkBytes(storage.buffer, 100L * stride, first);
            checkBytes(storage.buffer, 200L * stride, second);

            // Growth also has to replace bindings that reference its own old
            // object, without recreating a deleted name or disturbing others.
            old = storage.buffer;
            bindings(old, old);
            storage.ensure(storage.capacity + 1);
            checkBindings(storage.buffer, storage.buffer);
            require(!GL15.glIsBuffer(old), "deleted buffer rebound");
            checkBytes(storage.buffer, 12L * stride, payload);
            checkBytes(storage.buffer, 200L * stride, second);
            int current = storage.buffer;
            storage.ensure(storage.capacity);
            require(storage.buffer == current, "unnecessary reallocation");
            checkBindings(current, current);
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "upload/growth GL error");
            System.out.println("stride=" + stride + " capacity_bytes=" + size(current) + " verified=true");
        } finally {
            bindings(0, 0);
            storage.delete();
            GL15.glDeleteBuffers(staging);
            GL15.glDeleteBuffers(sentinelRead);
            GL15.glDeleteBuffers(sentinelWrite);
        }
    }

    private static void pendingInvalidOperation() {
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
        GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER, 16L, GL15.GL_STATIC_DRAW);
    }
    private static void bindings(int read, int write) {
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, read);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, write);
    }
    private static void checkBindings(int read, int write) {
        require(GL11.glGetInteger(GL31.GL_COPY_READ_BUFFER_BINDING) == read, "read binding");
        require(GL11.glGetInteger(GL31.GL_COPY_WRITE_BUFFER_BINDING) == write, "write binding");
    }
    private static long size(int buffer) {
        int read = GL11.glGetInteger(GL31.GL_COPY_READ_BUFFER_BINDING);
        try {
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, buffer);
            return GL32.glGetBufferParameteri64(GL31.GL_COPY_READ_BUFFER, GL15.GL_BUFFER_SIZE);
        } finally { GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, read); }
    }
    private static ByteBuffer pattern(int bytes, int seed) {
        ByteBuffer result = BufferUtils.createByteBuffer(bytes);
        for (int i = 0; i < bytes; i++) result.put((byte) (i * 37 + seed));
        result.flip();
        return result;
    }
    private static void checkBytes(int buffer, long offset, ByteBuffer expected) {
        int read = GL11.glGetInteger(GL31.GL_COPY_READ_BUFFER_BINDING);
        try {
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, buffer);
            ByteBuffer actual = BufferUtils.createByteBuffer(expected.remaining());
            GL15.glGetBufferSubData(GL31.GL_COPY_READ_BUFFER, offset, actual);
            for (int i = 0; i < actual.remaining(); i++)
                require(actual.get(i) == expected.get(expected.position() + i), "GPU byte mismatch at " + i);
        } finally { GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, read); }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
