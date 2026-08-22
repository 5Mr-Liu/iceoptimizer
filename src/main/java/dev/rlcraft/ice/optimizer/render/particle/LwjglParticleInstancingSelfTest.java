package dev.rlcraft.ice.optimizer.render.particle;

import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyMatrixStateTracker;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.resource.LwjglTemporaryResourceOps;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.TemporaryGpuResourceScope;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

/** Executable offscreen proof for the exact final-corner particle instance path. */
public final class LwjglParticleInstancingSelfTest {
    private static final int WIDTH = 4;
    private static final int HEIGHT = 2;

    private LwjglParticleInstancingSelfTest() { }

    public static Result validate() {
        return validate(null);
    }

    public static Result validate(CacheBudget budget) {
        ContextCapabilities capabilities;
        try { capabilities = GLContext.getCapabilities(); }
        catch (Throwable error) { return Result.failure(error); }
        if (!LwjglParticleRenderer.supportsInstancing(capabilities)) {
            return Result.failure("instanced draw/attribute/VAO prerequisites unavailable");
        }
        if (budget == null) {
            return Result.failure(
                "particle self-test GPU budget unavailable");
        }

        TemporaryGpuResourceScope scratch =
            new TemporaryGpuResourceScope(budget, 5);
        TemporaryGpuResourceScope.Slot framebufferSlot = scratch.reserveOpaque(
            RenderResourceKind.FRAMEBUFFER,
            LwjglTemporaryResourceOps.DELETE_FRAMEBUFFER);
        TemporaryGpuResourceScope.Slot textureSlot = framebufferSlot == null
            ? null : scratch.reserve(RenderResourceKind.TEXTURE,
                WIDTH * HEIGHT * 4L, LwjglTemporaryResourceOps.DELETE_TEXTURE);
        TemporaryGpuResourceScope.Slot programSlot = textureSlot == null
            ? null : scratch.reserveOpaque(RenderResourceKind.PROGRAM,
                LwjglTemporaryResourceOps.DELETE_PROGRAM);
        TemporaryGpuResourceScope.Slot vertexArraySlot = programSlot == null
            ? null : scratch.reserveOpaque(RenderResourceKind.VERTEX_ARRAY,
                LwjglTemporaryResourceOps.DELETE_VERTEX_ARRAY);
        TemporaryGpuResourceScope.Slot bufferSlot = vertexArraySlot == null
            ? null : scratch.reserve(RenderResourceKind.BUFFER,
                ParticleGpuInstanceEncoder.BYTES_PER_INSTANCE * 2L,
                LwjglTemporaryResourceOps.DELETE_BUFFER);
        if (bufferSlot == null) {
            Throwable cleanup = scratch.closeAndAppend(null);
            return cleanup == null
                ? Result.failure("particle self-test GPU budget exhausted")
                : Result.failure(cleanup);
        }

        int previousDrawFramebuffer = 0;
        int previousReadFramebuffer = 0;
        int previousArrayBuffer = 0;
        int previousVertexArray = 0;
        int previousProgram = 0;
        int previousPackBuffer = 0;
        int previousUnpackBuffer = 0;
        int previousMatrixMode = GL11.GL_MODELVIEW;
        int framebuffer = 0;
        int texture = 0;
        int vertexArray = 0;
        int buffer = 0;
        int program = 0;
        boolean attributesPushed = false;
        boolean clientAttributesPushed = false;
        boolean projectionPushed = false;
        boolean modelViewPushed = false;
        boolean texture0Pushed = false;
        boolean texture1Pushed = false;
        boolean stateCaptured = false;
        boolean passed = false;
        Throwable failure = null;
        try {
            previousDrawFramebuffer = GL11.glGetInteger(
                GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            previousReadFramebuffer = GL11.glGetInteger(
                GL30.GL_READ_FRAMEBUFFER_BINDING);
            previousArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            previousPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            previousUnpackBuffer = GL11.glGetInteger(
                GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
            previousMatrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
            stateCaptured = true;

            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            attributesPushed = true;
            GL11.glPushClientAttrib(GL11.GL_ALL_CLIENT_ATTRIB_BITS);
            clientAttributesPushed = true;

            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            projectionPushed = true;
            GL11.glLoadIdentity();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            modelViewPushed = true;
            GL11.glLoadIdentity();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glMatrixMode(GL11.GL_TEXTURE);
            GL11.glPushMatrix();
            texture0Pushed = true;
            GL11.glLoadIdentity();
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glMatrixMode(GL11.GL_TEXTURE);
            GL11.glPushMatrix();
            texture1Pushed = true;
            GL11.glLoadIdentity();

            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            framebuffer = framebufferSlot.allocate(
                LwjglTemporaryResourceOps.GEN_FRAMEBUFFER);
            texture = textureSlot.allocate(LwjglTemporaryResourceOps.GEN_TEXTURE);
            if (framebuffer <= 0 || texture <= 0) {
                throw new IllegalStateException(
                    "particle self-test framebuffer object creation failed");
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
                WIDTH, HEIGHT, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
                (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER,
                GL11.GL_NEAREST);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
                != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("particle FBO incomplete");
            }
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);

            program = programSlot.allocate(
                new TemporaryGpuResourceScope.IntAllocator() {
                    @Override public int allocate() {
                        return LwjglParticleRenderer.createInstancingProgram(
                            budget,
                            new LwjglParticleRenderer.ProgramBuildState());
                    }
                });
            vertexArray = vertexArraySlot.allocate(
                LwjglTemporaryResourceOps.GEN_VERTEX_ARRAY);
            buffer = bufferSlot.allocate(LwjglTemporaryResourceOps.GEN_BUFFER);
            if (vertexArray <= 0 || buffer <= 0) {
                throw new IllegalStateException("particle self-test object creation failed");
            }
            ByteBuffer instances = BufferUtils.createByteBuffer(
                ParticleGpuInstanceEncoder.BYTES_PER_INSTANCE * 2)
                .order(ByteOrder.nativeOrder());
            putInstance(instances, -1.0D, 0.0D, 1.0F, 0.0F, 0.0F);
            putInstance(instances, 0.0D, 1.0D, 0.0F, 1.0F, 0.0F);
            instances.flip();

            GL30.glBindVertexArray(vertexArray);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, instances,
                GL15.GL_STREAM_DRAW);
            LwjglParticleRenderer.setupInstanceAttributes(capabilities);

            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_DITHER);
            GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
            GL11.glColorMask(true, true, true, true);
            GL11.glViewport(0, 0, WIDTH, HEIGHT);
            GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GL20.glUseProgram(program);
            LwjglParticleRenderer.drawInstanced(capabilities, 2);

            ByteBuffer pixels = BufferUtils.createByteBuffer(WIDTH * HEIGHT * 4);
            GL11.glReadPixels(0, 0, WIDTH, HEIGHT, GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE, pixels);
            if (!pixel(pixels, 0, 0, 255, 0, 0)
                || !pixel(pixels, WIDTH - 1, 0, 0, 255, 0)) {
                throw new IllegalStateException(
                    "instanced billboard output/divisor mismatch");
            }
            passed = true;
        } catch (Throwable error) {
            failure = error;
        } finally {
            if (stateCaptured) try { GL20.glUseProgram(previousProgram); }
            catch (Throwable error) { failure = append(failure, error); }
            if (stateCaptured) try {
                GL30.glBindVertexArray(previousVertexArray);
            } catch (Throwable error) { failure = append(failure, error); }
            if (stateCaptured) try {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArrayBuffer);
            } catch (Throwable error) { failure = append(failure, error); }
            if (stateCaptured) try {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPackBuffer);
            } catch (Throwable error) { failure = append(failure, error); }
            if (stateCaptured) try {
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,
                    previousUnpackBuffer);
            } catch (Throwable error) { failure = append(failure, error); }
            if (stateCaptured) try {
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,
                    previousDrawFramebuffer);
            } catch (Throwable error) { failure = append(failure, error); }
            if (stateCaptured) try {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,
                    previousReadFramebuffer);
            } catch (Throwable error) { failure = append(failure, error); }
            if (texture1Pushed) try {
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                GL11.glMatrixMode(GL11.GL_TEXTURE);
                GL11.glPopMatrix();
            } catch (Throwable error) { failure = append(failure, error); }
            if (texture0Pushed) try {
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glMatrixMode(GL11.GL_TEXTURE);
                GL11.glPopMatrix();
            } catch (Throwable error) { failure = append(failure, error); }
            if (modelViewPushed) try {
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPopMatrix();
            } catch (Throwable error) { failure = append(failure, error); }
            if (projectionPushed) try {
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPopMatrix();
            } catch (Throwable error) { failure = append(failure, error); }
            if (clientAttributesPushed) try { GL11.glPopClientAttrib(); }
            catch (Throwable error) { failure = append(failure, error); }
            if (attributesPushed) try { GL11.glPopAttrib(); }
            catch (Throwable error) { failure = append(failure, error); }
            if (stateCaptured) try { GL11.glMatrixMode(previousMatrixMode); }
            catch (Throwable error) { failure = append(failure, error); }
            failure = scratch.closeAndAppend(failure);
        }
        if (failure != null) {
            EarlyGlStateTracker.invalidate();
            EarlyMatrixStateTracker.invalidate();
        }
        return passed && failure == null ? Result.success()
            : failure == null ? Result.failure("particle self-test failed")
                : Result.failure(failure);
    }

    private static void putInstance(ByteBuffer output, double minimumX,
                                    double maximumX, float red, float green,
                                    float blue) {
        double[] corners = {
            minimumX, -1.0D, 0.0D,
            minimumX, 1.0D, 0.0D,
            maximumX, 1.0D, 0.0D,
            maximumX, -1.0D, 0.0D
        };
        if (!ParticleGpuInstanceEncoder.put(output, 0.0F, 0.0F, 0.0F,
            corners, 0.0F, 0.0F, 1.0F, 1.0F, red, green, blue, 1.0F,
            0, 0)) {
            throw new IllegalStateException("particle self-test encoder failed");
        }
    }

    private static boolean pixel(ByteBuffer pixels, int x, int y,
                                 int red, int green, int blue) {
        int offset = Math.multiplyExact(Math.addExact(
            Math.multiplyExact(y, WIDTH), x), 4);
        return near(pixels.get(offset) & 255, red)
            && near(pixels.get(offset + 1) & 255, green)
            && near(pixels.get(offset + 2) & 255, blue)
            && near(pixels.get(offset + 3) & 255, 255);
    }

    private static boolean near(int actual, int expected) {
        return Math.abs(actual - expected) <= 1;
    }

    private static String compact(Throwable error) {
        dev.rlcraft.ice.optimizer.FatalErrors.rethrowIfFatal(error);
        String message = error.getMessage();
        String value = error.getClass().getSimpleName()
            + (message == null || message.length() == 0 ? "" : ": " + message);
        return value.length() <= 4096 ? value : value.substring(0, 4096);
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
        private final boolean equivalent;
        private final String detail;
        private final String exceptionType;

        private Result(boolean equivalent, String detail, String exceptionType) {
            this.equivalent = equivalent;
            this.detail = detail;
            this.exceptionType = exceptionType;
        }

        private static Result success() {
            return new Result(true,
                "instanced final-corner billboard output verified", "");
        }

        private static Result failure(String detail) {
            return new Result(false,
                detail == null ? "particle self-test failed" : detail, "");
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
