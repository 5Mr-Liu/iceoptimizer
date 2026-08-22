package dev.rlcraft.ice.optimizer.render.backend;

import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyMatrixStateTracker;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.resource.LwjglTemporaryResourceOps;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import dev.rlcraft.ice.optimizer.render.resource.TemporaryGpuResourceScope;
import dev.rlcraft.ice.optimizer.render.visibility.LwjglConservativeDepthReducer;
import dev.rlcraft.ice.optimizer.render.texture.LwjglTextureUploadSelfTest;
import dev.rlcraft.ice.optimizer.render.optifine.LwjglShaderCompilationDriver;
import dev.rlcraft.ice.optimizer.render.particle.LwjglFbpPacketSelfTest;
import dev.rlcraft.ice.optimizer.render.particle.LwjglParticleInstancingSelfTest;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBBufferStorage;
import org.lwjgl.opengl.ARBSync;
import org.lwjgl.opengl.ARBTimerQuery;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.GLSync;

/** Small executable compatibility-context tests with bounded startup waits. */
public final class LwjglCapabilitySelfTest implements CapabilitySelfTest {
    private static final long SELF_TEST_TIMEOUT_NANOS = 8_000_000L;
    // Timestamp queries retire behind all GPU work already queued by the
    // active renderer.  The production consumer is asynchronous, so an 8 ms
    // startup deadline was an invalid capability test on a busy context.
    private static final long TIMER_QUERY_SELF_TEST_TIMEOUT_NANOS = 250_000_000L;
    private static final int GL_DRAW_INDIRECT_BUFFER = 0x8F3F;
    private static final int GL_DRAW_INDIRECT_BUFFER_BINDING = 0x8F43;
    private static final int MAX_FIXED_FUNCTION_TEXTURE_UNITS = 32;
    private static final String STATE_NOT_CAPTURED = "not_captured";
    private static final String ERRORS_NOT_QUERIED = "not_queried";
    private final CacheBudget budget;

    public LwjglCapabilitySelfTest() {
        this(null);
    }

    public LwjglCapabilitySelfTest(CacheBudget budget) {
        this.budget = budget;
    }

    @Override
    public CapabilityReport execute() {
        CapabilityReport.Builder result = CapabilityReport.builder();
        ContextCapabilities capabilities;
        try {
            capabilities = GLContext.getCapabilities();
        } catch (Throwable error) {
            return globalFailureReport("context", error,
                "OpenGL context capabilities unavailable");
        }
        if (capabilities == null) {
            return globalFailureReport("context", null,
                "OpenGL context capabilities unavailable");
        }
        if (budget == null) {
            return globalFailureReport("allocation", null,
                "capability self-test GPU budget unavailable");
        }
        try {
            boolean buffer = testBuffer(result, capabilities, budget);
            boolean framebuffer = testFramebuffer(result, capabilities, budget);
            testFence(result, capabilities, budget);
            boolean timer = testTimer(result, capabilities, budget);

            if (!capabilities.OpenGL20) {
                failPrerequisite(result, ModernCapability.SHADER_PROGRAM,
                    "OpenGL 2.0 shader program unavailable");
            } else {
                LwjglShaderCompilationDriver.SelfTestResult shader =
                    LwjglShaderCompilationDriver.selfTestResult(budget);
                if (shader.isPassed()) {
                    result.pass(ModernCapability.SHADER_PROGRAM);
                } else {
                    failDelegated(result, ModernCapability.SHADER_PROGRAM,
                        "execute", shader.getExceptionType(),
                        shader.getDetail(), "delegated_shader_probe");
                }
            }

            testPersistentMapping(result, capabilities, false, budget);
            testPersistentMapping(result, capabilities, true, budget);
            boolean modelVbo = testMultiDraw(result, capabilities, framebuffer,
                budget);
            if (buffer && modelVbo) {
                result.pass(ModernCapability.MODEL_MESH_VBO);
            } else {
                failPrerequisite(result, ModernCapability.MODEL_MESH_VBO,
                    "BUFFER_OBJECT and MULTI_DRAW executable proofs required");
            }

            LwjglParticleInstancingSelfTest.Result particles = buffer
                && framebuffer
                    ? LwjglParticleInstancingSelfTest.validate(budget) : null;
            if (particles != null && particles.isEquivalent()) {
                result.pass(ModernCapability.PARTICLE_INSTANCING);
            } else if (particles == null) {
                failPrerequisite(result, ModernCapability.PARTICLE_INSTANCING,
                    "buffer and offscreen framebuffer proofs required");
            } else {
                failDelegated(result, ModernCapability.PARTICLE_INSTANCING,
                    "execute", particles.getExceptionType(),
                    particles.getDetail(), "delegated_particle_probe");
            }

            LwjglFbpPacketSelfTest.Result fbp = buffer && framebuffer
                ? LwjglFbpPacketSelfTest.validate(budget) : null;
            if (fbp != null && fbp.isEquivalent()) {
                result.pass(ModernCapability.FBP_PACKET_VBO);
            } else if (fbp == null) {
                failPrerequisite(result, ModernCapability.FBP_PACKET_VBO,
                    "buffer and offscreen framebuffer proofs required");
            } else {
                failDelegated(result, ModernCapability.FBP_PACKET_VBO,
                    "execute", fbp.getExceptionType(), fbp.getDetail(),
                    "delegated_fbp_probe");
            }

            testMultiDrawIndirect(result, capabilities, framebuffer, budget);
            if (capabilities.OpenGL42 || capabilities.GL_ARB_base_instance) {
                result.pass(ModernCapability.BASE_INSTANCE);
            } else {
                failPrerequisite(result, ModernCapability.BASE_INSTANCE,
                    "base-instance unavailable");
            }
            if (capabilities.OpenGL43
                || capabilities.GL_ARB_shader_storage_buffer_object) {
                result.pass(ModernCapability.SHADER_STORAGE_BUFFER);
            } else {
                failPrerequisite(result, ModernCapability.SHADER_STORAGE_BUFFER,
                    "SSBO unavailable");
            }

            LwjglTextureUploadSelfTest.Result pbo = buffer
                ? LwjglTextureUploadSelfTest.validate(false, budget) : null;
            if (pbo != null && pbo.isEquivalent()) {
                result.pass(ModernCapability.PIXEL_UNPACK_BUFFER);
            } else if (pbo == null) {
                failPrerequisite(result, ModernCapability.PIXEL_UNPACK_BUFFER,
                    "buffer executable proof required");
            } else {
                failDelegated(result, ModernCapability.PIXEL_UNPACK_BUFFER,
                    "execute", pbo.getExceptionType(), pbo.getDetail(),
                    "delegated_pbo_probe");
            }

            if (!framebuffer || !timer || !capabilities.OpenGL30) {
                failPrerequisite(result, ModernCapability.CONSERVATIVE_HZB,
                    "offscreen framebuffer, timer query, and OpenGL 3.0 proofs required");
            } else {
                LwjglConservativeDepthReducer.SelfTestResult hzb =
                    LwjglConservativeDepthReducer.selfTestResult(budget);
                if (hzb.isPassed()) {
                    result.pass(ModernCapability.CONSERVATIVE_HZB);
                } else {
                    failDelegated(result, ModernCapability.CONSERVATIVE_HZB,
                        "readback", hzb.getExceptionType(), hzb.getDetail(),
                        "delegated_hzb_probe");
                }
            }
            return result.failUnreported(new CapabilityReport.FailureDetail(
                "orchestration", "", "capability probe produced no outcome",
                STATE_NOT_CAPTURED, ERRORS_NOT_QUERIED)).build();
        } catch (Throwable error) {
            dev.rlcraft.ice.optimizer.FatalErrors.rethrowIfFatal(error);
            return result.failUnreported(failureDetail("orchestration", error,
                "capability self-test orchestration failed",
                STATE_NOT_CAPTURED, true)).build();
        }
    }

    private static boolean testBuffer(CapabilityReport.Builder result,
                                      ContextCapabilities capabilities,
                                      CacheBudget budget) {
        if (capabilities == null || !capabilities.OpenGL15) {
            failPrerequisite(result, ModernCapability.BUFFER_OBJECT,
                "OpenGL 1.5 unavailable");
            return false;
        }
        TemporaryGpuResourceScope scratch =
            new TemporaryGpuResourceScope(budget, 1);
        TemporaryGpuResourceScope.Slot bufferSlot = scratch.reserve(
            RenderResourceKind.BUFFER, 16L,
            LwjglTemporaryResourceOps.DELETE_BUFFER);
        if (bufferSlot == null) {
            failAllocation(result, ModernCapability.BUFFER_OBJECT, null,
                "buffer self-test GPU budget exhausted");
            return false;
        }
        int previous = 0;
        int buffer = 0;
        boolean stateCaptured = false;
        boolean passed = false;
        Throwable failure = null;
        String stage = "state_capture";
        String entryState = STATE_NOT_CAPTURED;
        try {
            previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            stateCaptured = true;
            entryState = "array_buffer=" + previous;
            stage = "allocation";
            buffer = bufferSlot.allocate(LwjglTemporaryResourceOps.GEN_BUFFER);
            if (buffer <= 0) throw new IllegalStateException(
                "buffer name creation failed");
            stage = "execute";
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
            ByteBuffer input = BufferUtils.createByteBuffer(16);
            for (int i = 0; i < 16; i++) input.put((byte) (i * 7 + 3));
            input.flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, input, GL15.GL_STREAM_DRAW);
            stage = "readback";
            ByteBuffer output = BufferUtils.createByteBuffer(16);
            GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, output);
            for (int i = 0; i < 16; i++) {
                if (output.get(i) != (byte) (i * 7 + 3)) {
                    throw new IllegalStateException("buffer round-trip mismatch");
                }
            }
            passed = true;
        } catch (Throwable error) {
            failure = error;
        } finally {
            boolean failedBeforeCleanup = failure != null;
            if (stateCaptured) try {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previous);
            } catch (Throwable error) { failure = append(failure, error); }
            failure = scratch.closeAndAppend(failure);
            if (!failedBeforeCleanup && failure != null) stage = "cleanup";
        }
        if (passed && failure == null) {
            result.pass(ModernCapability.BUFFER_OBJECT);
            return true;
        }
        if (failure != null) EarlyGlStateTracker.invalidate();
        failExecutable(result, ModernCapability.BUFFER_OBJECT, stage, failure,
            "buffer self-test failed", entryState);
        return false;
    }

    private static boolean testFramebuffer(CapabilityReport.Builder result,
                                           ContextCapabilities capabilities,
                                           CacheBudget budget) {
        if (capabilities == null || !capabilities.OpenGL30) {
            failPrerequisite(result, ModernCapability.OFFSCREEN_FRAMEBUFFER,
                "OpenGL 3.0 FBO unavailable");
            return false;
        }
        TemporaryGpuResourceScope scratch =
            new TemporaryGpuResourceScope(budget, 2);
        TemporaryGpuResourceScope.Slot framebufferSlot = scratch.reserveOpaque(
            RenderResourceKind.FRAMEBUFFER,
            LwjglTemporaryResourceOps.DELETE_FRAMEBUFFER);
        TemporaryGpuResourceScope.Slot textureSlot = framebufferSlot == null
            ? null : scratch.reserve(RenderResourceKind.TEXTURE, 4L * 4L * 4L,
                LwjglTemporaryResourceOps.DELETE_TEXTURE);
        if (textureSlot == null) {
            Throwable cleanup = scratch.closeAndAppend(null);
            if (cleanup == null) {
                failAllocation(result, ModernCapability.OFFSCREEN_FRAMEBUFFER,
                    null, "FBO self-test GPU budget exhausted");
            } else {
                failExecutable(result, ModernCapability.OFFSCREEN_FRAMEBUFFER,
                    "cleanup", cleanup, "FBO allocation cleanup failed",
                    STATE_NOT_CAPTURED);
            }
            return false;
        }
        int framebuffer = 0;
        int texture = 0;
        LwjglFramebufferStateSandbox.Snapshot state = null;
        String entryState = "capture_unavailable";
        String stage = "state_capture";
        String failureStage = "";
        boolean stateCaptured = false;
        boolean passed = false;
        Throwable failure = null;
        try {
            state = LwjglFramebufferStateSandbox.capture();
            entryState = state.describe();
            stateCaptured = true;
            stage = "execute.sandbox_known_state";
            LwjglFramebufferStateSandbox.establishKnownState(4, 4);
            stage = "allocation";
            framebuffer = framebufferSlot.allocate(
                LwjglTemporaryResourceOps.GEN_FRAMEBUFFER);
            texture = textureSlot.allocate(LwjglTemporaryResourceOps.GEN_TEXTURE);
            if (framebuffer <= 0 || texture <= 0) {
                throw new IllegalStateException("FBO object creation failed");
            }
            stage = "execute.texture_storage";
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 4, 4,
                0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            stage = "execute.framebuffer_attachment";
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, texture, 0);
            LwjglFramebufferStateSandbox.selectColorAttachment();
            int framebufferStatus = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (framebufferStatus != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("incomplete FBO status="
                    + glHex(framebufferStatus));
            }
            stage = "execute.clear";
            GL11.glClearColor(1.0F, 0.0F, 0.0F, 1.0F);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            stage = "readback";
            ByteBuffer pixel = BufferUtils.createByteBuffer(4);
            GL11.glReadPixels(1, 1, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
            if ((pixel.get(0) & 255) < 250 || (pixel.get(1) & 255) > 5
                || (pixel.get(2) & 255) > 5 || (pixel.get(3) & 255) < 250) {
                throw new IllegalStateException("FBO readback mismatch rgba=["
                    + (pixel.get(0) & 255) + ',' + (pixel.get(1) & 255) + ','
                    + (pixel.get(2) & 255) + ',' + (pixel.get(3) & 255) + ']');
            }
            passed = true;
        } catch (Throwable error) {
            failure = error;
            failureStage = stage;
        } finally {
            if (stateCaptured) {
                LwjglFramebufferStateSandbox.RestoreResult restored =
                    LwjglFramebufferStateSandbox.restore(state, failure);
                failure = restored.getFailure();
                if (!restored.getFailedStages().isEmpty()) {
                    failureStage = failureStage.isEmpty()
                        ? "cleanup." + restored.getFailedStages()
                        : failureStage + ",cleanup."
                            + restored.getFailedStages();
                }
            }
            boolean failedBeforeResourceCleanup = failure != null;
            failure = scratch.closeAndAppend(failure);
            if (!failedBeforeResourceCleanup && failure != null) {
                failureStage = "cleanup.resources";
            }
        }
        if (passed && failure == null) {
            result.pass(ModernCapability.OFFSCREEN_FRAMEBUFFER);
            return true;
        }
        if (failure != null) EarlyGlStateTracker.invalidate();
        failExecutable(result, ModernCapability.OFFSCREEN_FRAMEBUFFER,
            failureStage.isEmpty() ? stage : failureStage, failure,
            "FBO self-test failed", entryState);
        return false;
    }

    /**
     * A capability bit is not sufficient here: several compatibility drivers
     * have exposed the entry point but either rejected client arrays or drawn
     * only the first range.  Exercise two ranges into a private FBO and verify
     * pixels from opposite triangles before admitting terrain batching.
     */
    private static boolean testMultiDraw(CapabilityReport.Builder result,
                                         ContextCapabilities capabilities,
                                         boolean framebufferPassed,
                                         CacheBudget budget) {
        if (capabilities == null || !capabilities.OpenGL14 || !framebufferPassed
            || !capabilities.OpenGL30) {
            failPrerequisite(result, ModernCapability.MULTI_DRAW,
                "offscreen OpenGL 1.4 multi-draw prerequisites unavailable");
            return false;
        }
        TemporaryGpuResourceScope scratch =
            new TemporaryGpuResourceScope(budget, 4);
        TemporaryGpuResourceScope.Slot framebufferSlot = scratch.reserveOpaque(
            RenderResourceKind.FRAMEBUFFER,
            LwjglTemporaryResourceOps.DELETE_FRAMEBUFFER);
        TemporaryGpuResourceScope.Slot textureSlot = framebufferSlot == null
            ? null : scratch.reserve(RenderResourceKind.TEXTURE, 4L * 4L * 4L,
                LwjglTemporaryResourceOps.DELETE_TEXTURE);
        TemporaryGpuResourceScope.Slot vertexArraySlot = textureSlot == null
            ? null : scratch.reserveOpaque(RenderResourceKind.VERTEX_ARRAY,
                LwjglTemporaryResourceOps.DELETE_VERTEX_ARRAY);
        TemporaryGpuResourceScope.Slot bufferSlot = vertexArraySlot == null
            ? null : scratch.reserve(RenderResourceKind.BUFFER, 12L * 4L,
                LwjglTemporaryResourceOps.DELETE_BUFFER);
        if (bufferSlot == null) {
            Throwable cleanup = scratch.closeAndAppend(null);
            if (cleanup == null) {
                failAllocation(result, ModernCapability.MULTI_DRAW, null,
                    "multi-draw GPU budget exhausted");
            } else {
                failExecutable(result, ModernCapability.MULTI_DRAW, "cleanup",
                    cleanup, "multi-draw allocation cleanup failed",
                    STATE_NOT_CAPTURED);
            }
            return false;
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
        boolean attributesPushed = false;
        boolean clientAttributesPushed = false;
        boolean projectionPushed = false;
        boolean modelViewPushed = false;
        boolean stateCaptured = false;
        boolean passed = false;
        Throwable failure = null;
        String stage = "state_capture";
        String entryState = STATE_NOT_CAPTURED;
        try {
            previousDrawFramebuffer = GL11.glGetInteger(
                GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            previousReadFramebuffer = GL11.glGetInteger(
                GL30.GL_READ_FRAMEBUFFER_BINDING);
            previousArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            previousPackBuffer = GL11.glGetInteger(
                GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            previousUnpackBuffer = GL11.glGetInteger(
                GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
            previousMatrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
            stateCaptured = true;
            entryState = "draw_fbo=" + previousDrawFramebuffer
                + ",read_fbo=" + previousReadFramebuffer
                + ",array_buffer=" + previousArrayBuffer
                + ",vao=" + previousVertexArray
                + ",program=" + previousProgram
                + ",pack_pbo=" + previousPackBuffer
                + ",unpack_pbo=" + previousUnpackBuffer
                + ",matrix_mode=" + previousMatrixMode;

            stage = "execute.state_sandbox";
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            attributesPushed = true;
            GL11.glPushClientAttrib(GL11.GL_ALL_CLIENT_ATTRIB_BITS);
            clientAttributesPushed = true;
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            projectionPushed = true;
            GL11.glLoadIdentity();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            modelViewPushed = true;
            GL11.glLoadIdentity();

            stage = "allocation";
            framebuffer = framebufferSlot.allocate(
                LwjglTemporaryResourceOps.GEN_FRAMEBUFFER);
            texture = textureSlot.allocate(LwjglTemporaryResourceOps.GEN_TEXTURE);
            vertexArray = vertexArraySlot.allocate(
                LwjglTemporaryResourceOps.GEN_VERTEX_ARRAY);
            if (framebuffer <= 0 || texture <= 0 || vertexArray <= 0) {
                throw new IllegalStateException(
                    "multi-draw framebuffer object creation failed");
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 4, 4,
                0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER,
                GL11.GL_NEAREST);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
                != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("multi-draw FBO incomplete");
            }

            stage = "execute";
            GL20.glUseProgram(0);
            GL30.glBindVertexArray(vertexArray);
            disableFixedFunctionTextures();
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_DITHER);
            GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
            GL11.glColorMask(true, true, true, true);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            GL11.glViewport(0, 0, 4, 4);
            GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            FloatBuffer vertices = BufferUtils.createFloatBuffer(12);
            // Two triangles form a square.  Opposite corner samples prove
            // that both entries in the multi-draw command were executed.
            vertices.put(-1.0F).put(-1.0F).put(1.0F).put(-1.0F)
                .put(-1.0F).put(1.0F);
            vertices.put(1.0F).put(1.0F).put(-1.0F).put(1.0F)
                .put(1.0F).put(-1.0F);
            vertices.flip();
            buffer = bufferSlot.allocate(LwjglTemporaryResourceOps.GEN_BUFFER);
            if (buffer <= 0) throw new IllegalStateException(
                "multi-draw buffer creation failed");
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STREAM_DRAW);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glVertexPointer(2, GL11.GL_FLOAT, 0, 0L);
            IntBuffer first = BufferUtils.createIntBuffer(2);
            IntBuffer count = BufferUtils.createIntBuffer(2);
            first.put(0).put(3).flip();
            count.put(3).put(3).flip();
            GL14.glMultiDrawArrays(GL11.GL_TRIANGLES, first, count);

            stage = "readback";
            ByteBuffer pixels = BufferUtils.createByteBuffer(4 * 4 * 4);
            GL11.glReadPixels(0, 0, 4, 4, GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE, pixels);
            if (!opaqueWhite(pixels, 0, 0, 4) || !opaqueWhite(pixels, 3, 3, 4)) {
                throw new IllegalStateException("multi-draw output mismatch");
            }
            passed = true;
        } catch (Throwable error) {
            failure = error;
        } finally {
            boolean failedBeforeCleanup = failure != null;
            if (stateCaptured) try {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArrayBuffer);
            } catch (Throwable error) { failure = append(failure, error); }
            if (stateCaptured) try {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,
                    previousPackBuffer);
            } catch (Throwable error) { failure = append(failure, error); }
            if (stateCaptured) try {
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,
                    previousUnpackBuffer);
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
            if (!failedBeforeCleanup && failure != null) stage = "cleanup";
        }
        if (passed && failure == null) {
            result.pass(ModernCapability.MULTI_DRAW);
            return true;
        }
        if (failure != null) {
            EarlyGlStateTracker.invalidate();
            EarlyMatrixStateTracker.invalidate();
        }
        failExecutable(result, ModernCapability.MULTI_DRAW, stage, failure,
            "multi-draw self-test failed", entryState);
        return false;
    }

    private static boolean opaqueWhite(ByteBuffer pixels, int x, int y, int width) {
        int offset = Math.multiplyExact(Math.addExact(Math.multiplyExact(y, width), x), 4);
        return (pixels.get(offset) & 255) >= 250
            && (pixels.get(offset + 1) & 255) >= 250
            && (pixels.get(offset + 2) & 255) >= 250
            && (pixels.get(offset + 3) & 255) >= 250;
    }

    private static boolean testFence(CapabilityReport.Builder result,
                                     ContextCapabilities capabilities,
                                     CacheBudget budget) {
        if (capabilities == null || !(capabilities.OpenGL32 || capabilities.GL_ARB_sync)) {
            failPrerequisite(result, ModernCapability.SYNC_FENCE,
                "sync extension unavailable");
            return false;
        }
        CacheBudget.Reservation fenceReservation = budget.tryReserve(
            BudgetKind.GPU,
            ResourceLedger.nativeObjectCharge(RenderResourceKind.QUERY));
        if (fenceReservation == null) {
            failAllocation(result, ModernCapability.SYNC_FENCE, null,
                "sync Fence self-test GPU budget exhausted");
            return false;
        }
        GLSync fence = null;
        boolean creationAttempted = false;
        boolean creationReturned = false;
        boolean deletionCompleted = false;
        boolean passed = false;
        Throwable failure = null;
        String stage = "allocation";
        try {
            creationAttempted = true;
            fence = capabilities.OpenGL32
                ? GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
                : ARBSync.glFenceSync(ARBSync.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            creationReturned = true;
            if (fence == null) throw new IllegalStateException("null Fence");
            stage = "execute";
            GL11.glFlush();
            long deadline = System.nanoTime() + SELF_TEST_TIMEOUT_NANOS;
            do {
                int state = capabilities.OpenGL32
                    ? GL32.glClientWaitSync(fence, 0, 0L)
                    : ARBSync.glClientWaitSync(fence, 0, 0L);
                if (state == GL32.GL_ALREADY_SIGNALED || state == GL32.GL_CONDITION_SATISFIED) {
                    passed = true;
                    break;
                }
                if (state == GL32.GL_WAIT_FAILED) throw new IllegalStateException("Fence wait failed");
                Thread.yield();
            } while (!passed && System.nanoTime() < deadline);
            if (!passed) throw new IllegalStateException("Fence self-test timeout");
        } catch (Throwable error) {
            failure = error;
        } finally {
            boolean failedBeforeCleanup = failure != null;
            if (fence != null) try {
                if (capabilities.OpenGL32) GL32.glDeleteSync(fence);
                else ARBSync.glDeleteSync(fence);
                deletionCompleted = true;
            } catch (Throwable error) { failure = append(failure, error); }
            if (!creationAttempted || creationReturned && fence == null
                || deletionCompleted) try {
                fenceReservation.close();
                fenceReservation = null;
            } catch (Throwable error) { failure = append(failure, error); }
            if (!failedBeforeCleanup && failure != null) stage = "cleanup";
        }
        if (passed && failure == null) {
            result.pass(ModernCapability.SYNC_FENCE);
            return true;
        }
        failExecutable(result, ModernCapability.SYNC_FENCE, stage, failure,
            "sync Fence self-test failed", "sync_object_state");
        return false;
    }

    private static boolean testTimer(CapabilityReport.Builder result,
                                     ContextCapabilities capabilities,
                                     CacheBudget budget) {
        if (capabilities == null || !(capabilities.OpenGL33 || capabilities.GL_ARB_timer_query)) {
            failPrerequisite(result, ModernCapability.TIMER_QUERY,
                "timer query unavailable");
            return false;
        }
        TemporaryGpuResourceScope scratch =
            new TemporaryGpuResourceScope(budget, 2);
        TemporaryGpuResourceScope.Slot startSlot = scratch.reserveOpaque(
            RenderResourceKind.QUERY, LwjglTemporaryResourceOps.DELETE_QUERY);
        TemporaryGpuResourceScope.Slot endSlot = startSlot == null ? null
            : scratch.reserveOpaque(RenderResourceKind.QUERY,
                LwjglTemporaryResourceOps.DELETE_QUERY);
        if (endSlot == null) {
            Throwable cleanup = scratch.closeAndAppend(null);
            if (cleanup == null) {
                failAllocation(result, ModernCapability.TIMER_QUERY, null,
                    "timer query GPU budget exhausted");
            } else {
                failExecutable(result, ModernCapability.TIMER_QUERY, "cleanup",
                    cleanup, "timer query allocation cleanup failed",
                    STATE_NOT_CAPTURED);
            }
            return false;
        }
        int start = 0;
        int end = 0;
        boolean passed = false;
        Throwable failure = null;
        String stage = "allocation";
        try {
            start = startSlot.allocate(LwjglTemporaryResourceOps.GEN_QUERY);
            end = endSlot.allocate(LwjglTemporaryResourceOps.GEN_QUERY);
            if (start <= 0 || end <= 0 || start == end) {
                throw new IllegalStateException("timer query creation failed");
            }
            stage = "execute";
            ARBTimerQuery.glQueryCounter(start, ARBTimerQuery.GL_TIMESTAMP);
            ARBTimerQuery.glQueryCounter(end, ARBTimerQuery.GL_TIMESTAMP);
            GL11.glFlush();
            stage = "readback";
            final int query = end;
            if (!awaitTimerQuery(new TimerQueryProbe() {
                @Override public boolean available() {
                    return GL15.glGetQueryObjecti(query,
                        GL15.GL_QUERY_RESULT_AVAILABLE) != 0;
                }

                @Override public long nanoTime() { return System.nanoTime(); }

                @Override public void pause() { Thread.yield(); }
            }, TIMER_QUERY_SELF_TEST_TIMEOUT_NANOS)) {
                throw new IllegalStateException("timer query timeout");
            }
            long first = ARBTimerQuery.glGetQueryObjecti64(start, GL15.GL_QUERY_RESULT);
            long second = ARBTimerQuery.glGetQueryObjecti64(end, GL15.GL_QUERY_RESULT);
            if (second < first) throw new IllegalStateException("timer result reversed");
            passed = true;
        } catch (Throwable error) {
            failure = error;
        } finally {
            boolean failedBeforeCleanup = failure != null;
            failure = scratch.closeAndAppend(failure);
            if (!failedBeforeCleanup && failure != null) stage = "cleanup";
        }
        if (passed && failure == null) {
            result.pass(ModernCapability.TIMER_QUERY);
            return true;
        }
        failExecutable(result, ModernCapability.TIMER_QUERY, stage, failure,
            "timer query self-test failed", "timestamp_query_pair");
        return false;
    }

    static boolean awaitTimerQuery(TimerQueryProbe probe, long timeoutNanos) {
        if (probe == null || timeoutNanos <= 0L) {
            throw new IllegalArgumentException("timer query wait");
        }
        long started = probe.nanoTime();
        while (!probe.available()) {
            if (probe.nanoTime() - started >= timeoutNanos) return false;
            probe.pause();
        }
        return true;
    }

    interface TimerQueryProbe {
        boolean available();
        long nanoTime();
        void pause();
    }

    private static boolean testPersistentMapping(CapabilityReport.Builder result,
                                                 ContextCapabilities capabilities,
                                                 boolean coherent,
                                                 CacheBudget budget) {
        ModernCapability capability = coherent ? ModernCapability.COHERENT_MAPPING
            : ModernCapability.PERSISTENT_MAPPING;
        if (capabilities == null || !(capabilities.OpenGL44 || capabilities.GL_ARB_buffer_storage)
            || !capabilities.OpenGL30) {
            recordPersistentMappingDetail(result, coherent, false,
                simpleFailure("prerequisite",
                    "buffer-storage mapping unavailable"));
            return false;
        }
        TemporaryGpuResourceScope scratch =
            new TemporaryGpuResourceScope(budget, 1);
        TemporaryGpuResourceScope.Slot bufferSlot = scratch.reserve(
            RenderResourceKind.BUFFER, 64L,
            LwjglTemporaryResourceOps.DELETE_BUFFER);
        if (bufferSlot == null) {
            recordPersistentMappingDetail(result, coherent, false,
                simpleFailure("allocation",
                    "persistent mapping GPU budget exhausted"));
            return false;
        }
        int previous = 0;
        int buffer = 0;
        boolean stateCaptured = false;
        UnmapState mappedBuffer = new UnmapState();
        boolean passed = false;
        Throwable failure = null;
        String stage = "state_capture";
        String entryState = STATE_NOT_CAPTURED;
        try {
            previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            stateCaptured = true;
            entryState = "array_buffer=" + previous;
            stage = "allocation";
            buffer = bufferSlot.allocate(LwjglTemporaryResourceOps.GEN_BUFFER);
            if (buffer <= 0) throw new IllegalStateException(
                "persistent buffer creation failed");
            stage = "execute";
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
            int storageFlags = GL30.GL_MAP_WRITE_BIT | ARBBufferStorage.GL_MAP_PERSISTENT_BIT;
            int mapFlags = storageFlags;
            if (coherent) {
                storageFlags |= ARBBufferStorage.GL_MAP_COHERENT_BIT;
                mapFlags |= ARBBufferStorage.GL_MAP_COHERENT_BIT;
            } else {
                mapFlags |= GL30.GL_MAP_FLUSH_EXPLICIT_BIT;
            }
            ARBBufferStorage.glBufferStorage(GL15.GL_ARRAY_BUFFER, 64L, storageFlags);
            ByteBuffer mapped = GL30.glMapBufferRange(GL15.GL_ARRAY_BUFFER, 0L, 64L,
                mapFlags, null);
            if (mapped == null) throw new IllegalStateException("persistent map returned null");
            mappedBuffer.markMapped();
            mapped.order(ByteOrder.nativeOrder()).putLong(0, 0x1122334455667788L);
            if (!coherent) GL30.glFlushMappedBufferRange(GL15.GL_ARRAY_BUFFER, 0L, 8L);
            if (!mappedBuffer.beginAttempt()) throw new IllegalStateException(
                "persistent map state lost before unmap");
            boolean intact = GL15.glUnmapBuffer(GL15.GL_ARRAY_BUFFER);
            if (!intact) {
                throw new IllegalStateException("persistent unmap reported corruption");
            }
            stage = "readback";
            ByteBuffer readback = BufferUtils.createByteBuffer(8)
                .order(ByteOrder.nativeOrder());
            GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, readback);
            if (readback.getLong(0) != 0x1122334455667788L) {
                throw new IllegalStateException(
                    "persistent mapping round-trip mismatch");
            }
            passed = true;
        } catch (Throwable error) {
            failure = error;
        } finally {
            boolean failedBeforeCleanup = failure != null;
            if (mappedBuffer.beginAttempt()) try {
                if (!GL15.glUnmapBuffer(GL15.GL_ARRAY_BUFFER)) {
                    failure = append(failure, new IllegalStateException(
                        "persistent cleanup unmap reported corruption"));
                }
            } catch (Throwable error) { failure = append(failure, error); }
            if (stateCaptured) try {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previous);
            } catch (Throwable error) { failure = append(failure, error); }
            failure = scratch.closeAndAppend(failure);
            if (!failedBeforeCleanup && failure != null) stage = "cleanup";
        }
        if (passed && failure == null) {
            recordPersistentMappingResult(result, coherent, true, null);
            return true;
        }
        if (failure != null) EarlyGlStateTracker.invalidate();
        recordPersistentMappingDetail(result, coherent, false,
            failureDetail(stage, failure,
                "persistent mapping self-test failed", entryState, true));
        return false;
    }

    /** Clears ownership before the outcome-producing native unmap call. */
    static final class UnmapState {
        private boolean mapped;

        void markMapped() {
            if (mapped) throw new IllegalStateException(
                "mapping already owned");
            mapped = true;
        }

        boolean beginAttempt() {
            if (!mapped) return false;
            mapped = false;
            return true;
        }
    }

    static void recordPersistentMappingResult(CapabilityReport.Builder result,
                                              boolean coherent, boolean passed,
                                              String detail) {
        recordPersistentMappingDetail(result, coherent, passed,
            detail == null ? null : simpleFailure("execute", detail));
    }

    private static void recordPersistentMappingDetail(
        CapabilityReport.Builder result, boolean coherent, boolean passed,
        CapabilityReport.FailureDetail detail) {
        ModernCapability capability = coherent ? ModernCapability.COHERENT_MAPPING
            : ModernCapability.PERSISTENT_MAPPING;
        if (passed) {
            if (!coherent) result.pass(ModernCapability.BUFFER_STORAGE);
            result.pass(capability);
            return;
        }
        result.fail(capability, detail);
        if (!coherent) result.fail(ModernCapability.BUFFER_STORAGE, detail);
    }

    private static void testMultiDrawIndirect(CapabilityReport.Builder result,
                                              ContextCapabilities capabilities,
                                              boolean framebufferPassed,
                                              CacheBudget budget) {
        if (capabilities == null || !(capabilities.OpenGL43
            || capabilities.GL_ARB_multi_draw_indirect) || !framebufferPassed
            || !capabilities.OpenGL30) {
            failPrerequisite(result, ModernCapability.MULTI_DRAW_INDIRECT,
                "offscreen MDI prerequisites unavailable");
            return;
        }
        TemporaryGpuResourceScope scratch =
            new TemporaryGpuResourceScope(budget, 5);
        TemporaryGpuResourceScope.Slot framebufferSlot = scratch.reserveOpaque(
            RenderResourceKind.FRAMEBUFFER,
            LwjglTemporaryResourceOps.DELETE_FRAMEBUFFER);
        TemporaryGpuResourceScope.Slot textureSlot = framebufferSlot == null
            ? null : scratch.reserve(RenderResourceKind.TEXTURE, 4L * 4L * 4L,
                LwjglTemporaryResourceOps.DELETE_TEXTURE);
        TemporaryGpuResourceScope.Slot vertexArraySlot = textureSlot == null
            ? null : scratch.reserveOpaque(RenderResourceKind.VERTEX_ARRAY,
                LwjglTemporaryResourceOps.DELETE_VERTEX_ARRAY);
        TemporaryGpuResourceScope.Slot verticesSlot = vertexArraySlot == null
            ? null : scratch.reserve(RenderResourceKind.BUFFER, 12L * 4L,
                LwjglTemporaryResourceOps.DELETE_BUFFER);
        TemporaryGpuResourceScope.Slot commandsSlot = verticesSlot == null
            ? null : scratch.reserve(RenderResourceKind.BUFFER, 32L,
                LwjglTemporaryResourceOps.DELETE_BUFFER);
        if (commandsSlot == null) {
            Throwable cleanup = scratch.closeAndAppend(null);
            if (cleanup == null) {
                failAllocation(result, ModernCapability.MULTI_DRAW_INDIRECT,
                    null, "MDI self-test GPU budget exhausted");
            } else {
                failExecutable(result, ModernCapability.MULTI_DRAW_INDIRECT,
                    "cleanup", cleanup, "MDI allocation cleanup failed",
                    STATE_NOT_CAPTURED);
            }
            return;
        }
        int previousIndirect = 0;
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
        int verticesBuffer = 0;
        int commandBuffer = 0;
        boolean attributesPushed = false;
        boolean clientAttributesPushed = false;
        boolean projectionPushed = false;
        boolean modelViewPushed = false;
        boolean stateCaptured = false;
        boolean passed = false;
        Throwable failure = null;
        String stage = "state_capture";
        String entryState = STATE_NOT_CAPTURED;
        try {
            previousIndirect = GL11.glGetInteger(GL_DRAW_INDIRECT_BUFFER_BINDING);
            previousDrawFramebuffer = GL11.glGetInteger(
                GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            previousReadFramebuffer = GL11.glGetInteger(
                GL30.GL_READ_FRAMEBUFFER_BINDING);
            previousArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            previousPackBuffer = GL11.glGetInteger(
                GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            previousUnpackBuffer = GL11.glGetInteger(
                GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
            previousMatrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
            stateCaptured = true;
            entryState = "indirect_buffer=" + previousIndirect
                + ",draw_fbo=" + previousDrawFramebuffer
                + ",read_fbo=" + previousReadFramebuffer
                + ",array_buffer=" + previousArrayBuffer
                + ",vao=" + previousVertexArray
                + ",program=" + previousProgram
                + ",pack_pbo=" + previousPackBuffer
                + ",unpack_pbo=" + previousUnpackBuffer
                + ",matrix_mode=" + previousMatrixMode;

            stage = "execute.state_sandbox";
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            attributesPushed = true;
            GL11.glPushClientAttrib(GL11.GL_ALL_CLIENT_ATTRIB_BITS);
            clientAttributesPushed = true;
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            projectionPushed = true;
            GL11.glLoadIdentity();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            modelViewPushed = true;
            GL11.glLoadIdentity();

            stage = "allocation";
            framebuffer = framebufferSlot.allocate(
                LwjglTemporaryResourceOps.GEN_FRAMEBUFFER);
            texture = textureSlot.allocate(LwjglTemporaryResourceOps.GEN_TEXTURE);
            vertexArray = vertexArraySlot.allocate(
                LwjglTemporaryResourceOps.GEN_VERTEX_ARRAY);
            if (framebuffer <= 0 || texture <= 0 || vertexArray <= 0) {
                throw new IllegalStateException("MDI framebuffer object creation failed");
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 4, 4,
                0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER,
                GL11.GL_NEAREST);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
                != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("MDI FBO incomplete");
            }

            stage = "execute";
            GL20.glUseProgram(0);
            GL30.glBindVertexArray(vertexArray);
            disableFixedFunctionTextures();
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_DITHER);
            GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
            GL11.glColorMask(true, true, true, true);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            GL11.glViewport(0, 0, 4, 4);
            GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            FloatBuffer vertices = BufferUtils.createFloatBuffer(12);
            vertices.put(-1.0F).put(-1.0F).put(1.0F).put(-1.0F)
                .put(-1.0F).put(1.0F);
            vertices.put(1.0F).put(1.0F).put(-1.0F).put(1.0F)
                .put(1.0F).put(-1.0F);
            vertices.flip();
            verticesBuffer = verticesSlot.allocate(
                LwjglTemporaryResourceOps.GEN_BUFFER);
            if (verticesBuffer <= 0) throw new IllegalStateException(
                "MDI vertex buffer creation failed");
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, verticesBuffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STREAM_DRAW);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glVertexPointer(2, GL11.GL_FLOAT, 0, 0L);

            ByteBuffer commands = BufferUtils.createByteBuffer(32)
                .order(ByteOrder.nativeOrder());
            commands.putInt(3).putInt(1).putInt(0).putInt(0);
            commands.putInt(3).putInt(1).putInt(3).putInt(0);
            commands.flip();
            commandBuffer = commandsSlot.allocate(
                LwjglTemporaryResourceOps.GEN_BUFFER);
            if (commandBuffer <= 0) throw new IllegalStateException(
                "MDI command buffer creation failed");
            GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, commandBuffer);
            GL15.glBufferData(GL_DRAW_INDIRECT_BUFFER, commands, GL15.GL_STREAM_DRAW);
            GL43.glMultiDrawArraysIndirect(GL11.GL_TRIANGLES, 0L, 2, 0);

            stage = "readback";
            ByteBuffer pixels = BufferUtils.createByteBuffer(4 * 4 * 4);
            GL11.glReadPixels(0, 0, 4, 4, GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE, pixels);
            if (!opaqueWhite(pixels, 0, 0, 4) || !opaqueWhite(pixels, 3, 3, 4)) {
                throw new IllegalStateException("MDI multi-command output mismatch");
            }
            passed = true;
        } catch (Throwable error) {
            failure = error;
        } finally {
            boolean failedBeforeCleanup = failure != null;
            if (stateCaptured) try {
                GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, previousIndirect);
            } catch (Throwable error) { failure = append(failure, error); }
            if (stateCaptured) try {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArrayBuffer);
            } catch (Throwable error) { failure = append(failure, error); }
            if (stateCaptured) try {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,
                    previousPackBuffer);
            } catch (Throwable error) { failure = append(failure, error); }
            if (stateCaptured) try {
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,
                    previousUnpackBuffer);
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
            if (!failedBeforeCleanup && failure != null) stage = "cleanup";
        }
        if (passed && failure == null) {
            result.pass(ModernCapability.MULTI_DRAW_INDIRECT);
        } else {
            if (failure != null) {
                EarlyGlStateTracker.invalidate();
                EarlyMatrixStateTracker.invalidate();
            }
            failExecutable(result, ModernCapability.MULTI_DRAW_INDIRECT,
                stage, failure, "MDI self-test failed", entryState);
        }
    }

    static CapabilityReport globalFailureReport(String stage, Throwable failure,
                                                String message) {
        return CapabilityReport.builder().failUnreported(failureDetail(stage,
            failure, message, STATE_NOT_CAPTURED, false)).build();
    }

    private static void disableFixedFunctionTextures() {
        int units = GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS);
        if (units < 1 || units > MAX_FIXED_FUNCTION_TEXTURE_UNITS) {
            throw new IllegalStateException(
                "fixed-function texture-unit count outside probe limit: "
                    + units);
        }
        for (int unit = 0; unit < units; unit++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    private static void failPrerequisite(CapabilityReport.Builder result,
                                         ModernCapability capability,
                                         String message) {
        result.fail(capability, simpleFailure("prerequisite", message));
    }

    private static void failAllocation(CapabilityReport.Builder result,
                                       ModernCapability capability,
                                       Throwable failure, String message) {
        result.fail(capability, failureDetail("allocation", failure, message,
            STATE_NOT_CAPTURED, failure != null));
    }

    private static void failExecutable(CapabilityReport.Builder result,
                                       ModernCapability capability,
                                       String stage, Throwable failure,
                                       String message, String glState) {
        result.fail(capability, failureDetail(stage, failure, message, glState,
            true));
    }

    private static void failDelegated(CapabilityReport.Builder result,
                                      ModernCapability capability,
                                      String stage, String exceptionType,
                                      String message, String glState) {
        result.fail(capability, new CapabilityReport.FailureDetail(stage,
            exceptionType, message, glState,
            collectGlErrorsAfterFailure()));
    }

    private static CapabilityReport.FailureDetail simpleFailure(String stage,
                                                                String message) {
        return failureDetail(stage, null, message, STATE_NOT_CAPTURED, false);
    }

    private static CapabilityReport.FailureDetail failureDetail(
        String stage, Throwable failure, String fallbackMessage, String glState,
        boolean queryGlErrors) {
        if (failure != null) {
            dev.rlcraft.ice.optimizer.FatalErrors.rethrowIfFatal(failure);
        }
        return new CapabilityReport.FailureDetail(stage,
            failure == null ? "" : failure.getClass().getName(),
            failure == null ? fallbackMessage : failureMessage(failure),
            glState == null || glState.isEmpty() ? STATE_NOT_CAPTURED : glState,
            queryGlErrors ? collectGlErrorsAfterFailure()
                : ERRORS_NOT_QUERIED);
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

    private static String collectGlErrorsAfterFailure() {
        StringBuilder errors = new StringBuilder(128);
        try {
            for (int i = 0; i < 16; i++) {
                int error = GL11.glGetError();
                if (error == GL11.GL_NO_ERROR) break;
                if (errors.length() > 0) errors.append(',');
                errors.append(glHex(error)).append('(')
                    .append(glErrorName(error)).append(')');
            }
        } catch (Throwable error) {
            dev.rlcraft.ice.optimizer.FatalErrors.rethrowIfFatal(error);
            if (errors.length() > 0) errors.append(',');
            errors.append("query_failed=").append(error.getClass().getName());
        }
        return errors.length() == 0 ? "none" : errors.toString();
    }

    private static String failureMessage(Throwable error) {
        if (error == null) return "self-test failed";
        StringBuilder value = new StringBuilder(256);
        String message = error.getMessage();
        value.append(message == null || message.isEmpty()
            ? error.getClass().getSimpleName() : message);
        Throwable[] suppressed = error.getSuppressed();
        for (int i = 0; i < suppressed.length && i < 8; i++) {
            Throwable item = suppressed[i];
            value.append(" | suppressed=").append(item.getClass().getName());
            String itemMessage = item.getMessage();
            if (itemMessage != null && !itemMessage.isEmpty()) {
                value.append(':').append(itemMessage);
            }
            if (value.length() >= 1024) break;
        }
        return value.length() <= 1024 ? value.toString()
            : value.substring(0, 1024);
    }

    private static String glHex(int value) {
        return "0x" + Integer.toHexString(value)
            .toUpperCase(java.util.Locale.ROOT);
    }

    private static String glErrorName(int error) {
        switch (error) {
            case GL11.GL_INVALID_ENUM: return "GL_INVALID_ENUM";
            case GL11.GL_INVALID_VALUE: return "GL_INVALID_VALUE";
            case GL11.GL_INVALID_OPERATION: return "GL_INVALID_OPERATION";
            case GL11.GL_STACK_OVERFLOW: return "GL_STACK_OVERFLOW";
            case GL11.GL_STACK_UNDERFLOW: return "GL_STACK_UNDERFLOW";
            case GL11.GL_OUT_OF_MEMORY: return "GL_OUT_OF_MEMORY";
            default: return "UNKNOWN";
        }
    }

}
