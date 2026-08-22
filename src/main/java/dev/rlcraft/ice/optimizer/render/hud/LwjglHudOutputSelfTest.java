package dev.rlcraft.ice.optimizer.render.hud;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyMatrixStateTracker;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.resource.LwjglTemporaryResourceOps;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.TemporaryGpuResourceScope;
import dev.rlcraft.ice.optimizer.render.validation.ImageValidationResult;
import dev.rlcraft.ice.optimizer.render.validation.ShaderImageValidator;
import java.nio.ByteBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Exact offscreen comparison of compatibility immediate and HUD VBO output. */
public final class LwjglHudOutputSelfTest {
    private static final int SIZE = 8;

    private LwjglHudOutputSelfTest() {
    }

    public static Result execute() {
        return execute(null);
    }

    public static Result execute(CacheBudget budget) {
        if (budget == null) {
            return new Result(false, "HUD validation GPU budget unavailable");
        }
        TemporaryGpuResourceScope scratch =
            new TemporaryGpuResourceScope(budget, 4);
        TemporaryGpuResourceScope.Slot outputTextureSlot = scratch.reserve(
            RenderResourceKind.TEXTURE, SIZE * SIZE * 4L,
            LwjglTemporaryResourceOps.DELETE_TEXTURE);
        TemporaryGpuResourceScope.Slot framebufferSlot = outputTextureSlot == null
            ? null : scratch.reserveOpaque(RenderResourceKind.FRAMEBUFFER,
                LwjglTemporaryResourceOps.DELETE_FRAMEBUFFER);
        TemporaryGpuResourceScope.Slot sourceTextureSlot = framebufferSlot == null
            ? null : scratch.reserve(RenderResourceKind.TEXTURE, 2L * 2L * 4L,
                LwjglTemporaryResourceOps.DELETE_TEXTURE);
        TemporaryGpuResourceScope.Slot bufferSlot = sourceTextureSlot == null
            ? null : scratch.reserve(RenderResourceKind.BUFFER, 4L * 5L * 4L,
                LwjglTemporaryResourceOps.DELETE_BUFFER);
        if (bufferSlot == null) {
            Throwable cleanup = scratch.closeAndAppend(null);
            return new Result(false, cleanup == null
                ? "HUD validation GPU budget exhausted" : compact(cleanup));
        }
        int previousDrawFramebuffer = 0;
        int previousReadFramebuffer = 0;
        int previousProgram = 0;
        int previousArrayBuffer = 0;
        int previousVertexArray = 0;
        int previousActiveTexture = GL13.GL_TEXTURE0;
        int previousClientTexture = GL13.GL_TEXTURE0;
        int previousMatrixMode = GL11.GL_MODELVIEW;
        int framebuffer = 0;
        int outputTexture = 0;
        int sourceTexture = 0;
        int buffer = 0;
        boolean attributes = false;
        boolean clientAttributes = false;
        boolean projection = false;
        boolean modelView = false;
        boolean stateCaptured = false;
        Result outcome = null;
        Throwable failure = null;
        try {
            previousDrawFramebuffer = GL11.glGetInteger(
                GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            previousReadFramebuffer = GL11.glGetInteger(
                GL30.GL_READ_FRAMEBUFFER_BINDING);
            previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            previousArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            previousClientTexture = GL11.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE);
            previousMatrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
            stateCaptured = true;

            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            attributes = true;
            GL11.glPushClientAttrib(GL11.GL_ALL_CLIENT_ATTRIB_BITS);
            clientAttributes = true;
            GL20.glUseProgram(0);
            GL30.glBindVertexArray(0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            projection = true;
            GL11.glLoadIdentity();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            modelView = true;
            GL11.glLoadIdentity();

            outputTexture = outputTextureSlot.allocate(
                LwjglTemporaryResourceOps.GEN_TEXTURE);
            if (outputTexture <= 0) throw new IllegalStateException(
                "HUD output texture creation failed");
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, outputTexture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
                SIZE, SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
                (ByteBuffer) null);
            nearest();
            framebuffer = framebufferSlot.allocate(
                LwjglTemporaryResourceOps.GEN_FRAMEBUFFER);
            if (framebuffer <= 0) throw new IllegalStateException(
                "HUD framebuffer creation failed");
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, outputTexture, 0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
                != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("HUD validation FBO incomplete");
            }

            sourceTexture = sourceTextureSlot.allocate(
                LwjglTemporaryResourceOps.GEN_TEXTURE);
            if (sourceTexture <= 0) throw new IllegalStateException(
                "HUD source texture creation failed");
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, sourceTexture);
            ByteBuffer source = BufferUtils.createByteBuffer(16);
            source.put((byte) 255).put((byte) 0).put((byte) 0).put((byte) 255);
            source.put((byte) 0).put((byte) 255).put((byte) 0).put((byte) 255);
            source.put((byte) 0).put((byte) 0).put((byte) 255).put((byte) 255);
            source.put((byte) 255).put((byte) 255).put((byte) 0).put((byte) 255);
            source.flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
                2, 2, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, source);
            nearest();

            GL11.glViewport(0, 0, SIZE, SIZE);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glColorMask(true, true, true, true);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            clear();
            immediateQuad();
            byte[] legacy = readPixels();

            clear();
            ByteBuffer data = BufferUtils.createByteBuffer(4 * 5 * 4);
            put(data, -0.75F, 0.75F, 0.0F, 0.0F, 1.0F);
            put(data, 0.75F, 0.75F, 0.0F, 1.0F, 1.0F);
            put(data, 0.75F, -0.75F, 0.0F, 1.0F, 0.0F);
            put(data, -0.75F, -0.75F, 0.0F, 0.0F, 0.0F);
            data.flip();
            buffer = bufferSlot.allocate(LwjglTemporaryResourceOps.GEN_BUFFER);
            if (buffer <= 0) throw new IllegalStateException(
                "HUD vertex buffer creation failed");
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STREAM_DRAW);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
            GL11.glVertexPointer(3, GL11.GL_FLOAT, 20, 0L);
            GL11.glTexCoordPointer(2, GL11.GL_FLOAT, 20, 12L);
            GL11.glDrawArrays(GL11.GL_QUADS, 0, 4);
            byte[] modern = readPixels();
            int error = GL11.glGetError();
            if (error != GL11.GL_NO_ERROR) {
                throw new IllegalStateException("HUD validation GL error " + error);
            }
            ImageValidationResult comparison = new ShaderImageValidator()
                .compare(legacy, modern, 0);
            outcome = new Result(comparison.isEquivalent(), comparison.getDetail());
        } catch (Throwable error) {
            failure = error;
        } finally {
            if (stateCaptured) try {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArrayBuffer);
            } catch (Throwable error) { failure = append(failure, error); }
            if (stateCaptured) try {
                GL30.glBindVertexArray(previousVertexArray);
            } catch (Throwable error) { failure = append(failure, error); }
            if (stateCaptured) try { GL20.glUseProgram(previousProgram); }
            catch (Throwable error) { failure = append(failure, error); }
            if (stateCaptured) try {
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,
                    previousDrawFramebuffer);
            } catch (Throwable error) { failure = append(failure, error); }
            if (stateCaptured) try {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,
                    previousReadFramebuffer);
            } catch (Throwable error) { failure = append(failure, error); }
            failure = scratch.closeAndAppend(failure);
            if (modelView) try {
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPopMatrix();
            } catch (Throwable error) { failure = append(failure, error); }
            if (projection) try {
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPopMatrix();
            } catch (Throwable error) { failure = append(failure, error); }
            if (clientAttributes) try { GL11.glPopClientAttrib(); }
            catch (Throwable error) { failure = append(failure, error); }
            if (attributes) try { GL11.glPopAttrib(); }
            catch (Throwable error) { failure = append(failure, error); }
            if (stateCaptured) try { GL13.glActiveTexture(previousActiveTexture); }
            catch (Throwable error) { failure = append(failure, error); }
            if (stateCaptured) try {
                GL13.glClientActiveTexture(previousClientTexture);
            } catch (Throwable error) { failure = append(failure, error); }
            if (stateCaptured) try { GL11.glMatrixMode(previousMatrixMode); }
            catch (Throwable error) { failure = append(failure, error); }
        }
        if (failure != null) {
            EarlyGlStateTracker.invalidate();
            EarlyMatrixStateTracker.invalidate();
            return new Result(false, compact(failure));
        }
        return outcome == null ? new Result(false, "HUD validation produced no result")
            : outcome;
    }

    private static void immediateQuad() {
        boolean begun = false;
        Throwable failure = null;
        try {
            GL11.glBegin(GL11.GL_QUADS);
            begun = true;
            GL11.glTexCoord2f(0.0F, 1.0F); GL11.glVertex3f(-0.75F, 0.75F, 0.0F);
            GL11.glTexCoord2f(1.0F, 1.0F); GL11.glVertex3f(0.75F, 0.75F, 0.0F);
            GL11.glTexCoord2f(1.0F, 0.0F); GL11.glVertex3f(0.75F, -0.75F, 0.0F);
            GL11.glTexCoord2f(0.0F, 0.0F); GL11.glVertex3f(-0.75F, -0.75F, 0.0F);
        } catch (Throwable error) {
            failure = error;
        } finally {
            if (begun) try { GL11.glEnd(); }
            catch (Throwable error) { failure = append(failure, error); }
        }
        if (failure != null) rethrow(failure);
    }

    private static void put(ByteBuffer target, float x, float y, float z,
                            float u, float v) {
        target.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v);
    }

    private static void nearest() {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
            GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER,
            GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S,
            GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T,
            GL11.GL_CLAMP);
    }

    private static void clear() {
        GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
    }

    private static byte[] readPixels() {
        ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA,
            GL11.GL_UNSIGNED_BYTE, pixels);
        byte[] result = new byte[pixels.capacity()];
        for (int i = 0; i < result.length; i++) result[i] = pixels.get(i);
        return result;
    }

    private static String compact(Throwable error) {
        dev.rlcraft.ice.optimizer.FatalErrors.rethrowIfFatal(error);
        String message = error.getMessage();
        String value = error.getClass().getSimpleName()
            + (message == null || message.isEmpty() ? "" : ": " + message);
        return value.length() <= 192 ? value : value.substring(0, 192);
    }

    private static Throwable append(Throwable first, Throwable next) {
        if (first == null) return next;
        Throwable nextFatal = FatalErrors.findFatal(next);
        if (nextFatal != null && FatalErrors.findFatal(first) == null) {
            if (nextFatal != first) nextFatal.addSuppressed(first);
            return nextFatal;
        }
        if (next != null && first != next) first.addSuppressed(next);
        return first;
    }

    private static void rethrow(Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("HUD validation operation failed", failure);
    }

    public static final class Result {
        private final boolean equivalent;
        private final String detail;

        private Result(boolean equivalent, String detail) {
            this.equivalent = equivalent;
            this.detail = detail == null ? "" : detail;
        }

        public boolean isEquivalent() { return equivalent; }
        public String getDetail() { return detail; }
    }
}
