package dev.rlcraft.ice.optimizer.render.texture;

import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.resource.LwjglTemporaryResourceOps;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.TemporaryGpuResourceScope;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBBufferStorage;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GLContext;

/** Executable PBO-to-texture output test using Minecraft's exact pixel format. */
public final class LwjglTextureUploadSelfTest {
    private static final int PIXEL_UNPACK_BUFFER = 0x88EC;
    private static final int PIXEL_UNPACK_BUFFER_BINDING = 0x88EF;
    private static final int CLIENT_MAPPED_BUFFER_BARRIER_BIT = 0x00004000;

    private LwjglTextureUploadSelfTest() {
    }

    public static Result validate(boolean persistent) {
        return validate(persistent, null);
    }

    public static Result validate(boolean persistent, CacheBudget budget) {
        if (budget == null) {
            return Result.failure("PBO self-test GPU budget unavailable");
        }
        ContextCapabilities capabilities;
        try {
            capabilities = GLContext.getCapabilities();
        } catch (Throwable error) {
            return Result.failure(error);
        }
        if (capabilities == null || !capabilities.OpenGL15
            || !(capabilities.OpenGL21
                || capabilities.GL_ARB_pixel_buffer_object)) {
            return Result.failure("PBO unavailable");
        }
        if (persistent && !(capabilities.OpenGL44
            || capabilities.GL_ARB_buffer_storage)) {
            return Result.failure("persistent buffer storage unavailable");
        }
        TemporaryGpuResourceScope scratch =
            new TemporaryGpuResourceScope(budget, 2);
        TemporaryGpuResourceScope.Slot textureSlot = scratch.reserve(
            RenderResourceKind.TEXTURE, 4L * 4L * 4L,
            LwjglTemporaryResourceOps.DELETE_TEXTURE);
        TemporaryGpuResourceScope.Slot bufferSlot = textureSlot == null
            ? null : scratch.reserve(RenderResourceKind.BUFFER, 20L,
                LwjglTemporaryResourceOps.DELETE_BUFFER);
        if (bufferSlot == null) {
            Throwable cleanup = scratch.closeAndAppend(null);
            return cleanup == null
                ? Result.failure("PBO self-test GPU budget exhausted")
                : Result.failure(cleanup);
        }
        int previousTexture = 0;
        int previousUnpack = 0;
        int previousPack = 0;
        int previousUnpackAlignment = 4;
        int previousPackAlignment = 4;
        int texture = 0;
        int buffer = 0;
        ByteBuffer mapped = null;
        boolean stateCaptured = false;
        boolean passed = false;
        Throwable failure = null;
        try {
            previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            previousUnpack = GL11.glGetInteger(PIXEL_UNPACK_BUFFER_BINDING);
            previousPack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            previousUnpackAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
            previousPackAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
            stateCaptured = true;
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);

            texture = textureSlot.allocate(LwjglTemporaryResourceOps.GEN_TEXTURE);
            if (texture <= 0) throw new IllegalStateException(
                "PBO self-test texture creation failed");
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,
                GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,
                GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            IntBuffer empty = BufferUtils.createIntBuffer(16);
            for (int index = 0; index < 16; index++) empty.put(0);
            empty.flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
                4, 4, 0, 32993, 33639, empty);

            IntBuffer input = BufferUtils.createIntBuffer(5);
            input.put(0xFF112233).put(0xFF445566)
                .put(0xFF778899).put(0xFFAABBCC).put(0xFF0A0B0C).flip();
            buffer = bufferSlot.allocate(LwjglTemporaryResourceOps.GEN_BUFFER);
            if (buffer <= 0) throw new IllegalStateException(
                "PBO self-test buffer creation failed");
            GL15.glBindBuffer(PIXEL_UNPACK_BUFFER, buffer);
            if (persistent) {
                int flags = GL30.GL_MAP_WRITE_BIT
                    | ARBBufferStorage.GL_MAP_PERSISTENT_BIT
                    | GL30.GL_MAP_FLUSH_EXPLICIT_BIT;
                ARBBufferStorage.glBufferStorage(PIXEL_UNPACK_BUFFER, 20L, flags);
                mapped = GL30.glMapBufferRange(PIXEL_UNPACK_BUFFER, 0L, 20L,
                    flags, null);
                if (mapped == null) throw new IllegalStateException(
                    "persistent PBO self-test map returned null");
                mapped.order(ByteOrder.nativeOrder()).asIntBuffer().put(input);
                GL30.glFlushMappedBufferRange(PIXEL_UNPACK_BUFFER, 0L, 20L);
                GL42.glMemoryBarrier(CLIENT_MAPPED_BUFFER_BARRIER_BIT);
            } else {
                GL15.glBufferData(PIXEL_UNPACK_BUFFER, input, GL15.GL_STREAM_DRAW);
            }
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 1, 1,
                2, 2, 32993, 33639, 0L);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 2, 2,
                1, 1, 32993, 33639, 16L);

            GL15.glBindBuffer(PIXEL_UNPACK_BUFFER, 0);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            IntBuffer output = BufferUtils.createIntBuffer(16);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, 32993, 33639, output);
            if (output.get(5) != 0xFF112233 || output.get(6) != 0xFF445566
                || output.get(9) != 0xFF778899 || output.get(10) != 0xFF0A0B0C
                || output.get(0) != 0 || output.get(15) != 0) {
                throw new IllegalStateException("PBO texture/readback mismatch");
            }
            passed = true;
        } catch (Throwable error) {
            failure = error;
        } finally {
            if (mapped != null && buffer != 0) try {
                GL15.glBindBuffer(PIXEL_UNPACK_BUFFER, buffer);
                if (!GL15.glUnmapBuffer(PIXEL_UNPACK_BUFFER)) {
                    failure = append(failure, new IllegalStateException(
                        "PBO self-test unmap reported corruption"));
                }
            } catch (Throwable error) { failure = append(failure, error); }
            if (stateCaptured) try {
                GL15.glBindBuffer(PIXEL_UNPACK_BUFFER, previousUnpack);
            } catch (Throwable error) { failure = append(failure, error); }
            if (stateCaptured) try {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPack);
            } catch (Throwable error) { failure = append(failure, error); }
            if (stateCaptured) try {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            } catch (Throwable error) { failure = append(failure, error); }
            if (stateCaptured) try { GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT,
                previousUnpackAlignment); }
            catch (Throwable error) { failure = append(failure, error); }
            if (stateCaptured) try { GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT,
                previousPackAlignment); }
            catch (Throwable error) { failure = append(failure, error); }
            failure = scratch.closeAndAppend(failure);
        }
        if (failure != null) EarlyGlStateTracker.invalidate();
        return passed && failure == null ? Result.success()
            : failure == null ? Result.failure("PBO self-test failed")
                : Result.failure(failure);
    }

    private static String compact(Throwable error) {
        dev.rlcraft.ice.optimizer.FatalErrors.rethrowIfFatal(error);
        String message = error.getMessage();
        String value = error.getClass().getSimpleName()
            + (message == null || message.isEmpty() ? "" : ": " + message);
        return value.length() <= 160 ? value : value.substring(0, 160);
    }

    private static Throwable append(Throwable first, Throwable next) {
        if (first == null) return next;
        Throwable nextFatal = dev.rlcraft.ice.optimizer.FatalErrors.findFatal(next);
        if (nextFatal != null
            && dev.rlcraft.ice.optimizer.FatalErrors.findFatal(first) == null) {
            if (nextFatal != first) nextFatal.addSuppressed(first);
            return nextFatal;
        }
        if (next != null && first != next) first.addSuppressed(next);
        return first;
    }

    public static final class Result {
        private static final Result SUCCESS = new Result(true, null, "");
        private final boolean equivalent;
        private final String detail;
        private final String exceptionType;

        private Result(boolean equivalent, String detail, String exceptionType) {
            this.equivalent = equivalent;
            this.detail = detail;
            this.exceptionType = exceptionType;
        }

        private static Result success() { return SUCCESS; }
        private static Result failure(String detail) {
            return new Result(false,
                detail == null ? "PBO self-test failed" : detail, "");
        }
        private static Result failure(Throwable error) {
            dev.rlcraft.ice.optimizer.FatalErrors.rethrowIfFatal(error);
            return new Result(false, compact(error),
                error == null ? "" : error.getClass().getName());
        }

        public boolean isEquivalent() { return equivalent; }
        public String getDetail() { return detail; }
        public String getExceptionType() { return exceptionType; }
    }
}
