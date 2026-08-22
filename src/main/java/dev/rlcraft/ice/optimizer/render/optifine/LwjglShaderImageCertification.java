package dev.rlcraft.ice.optimizer.render.optifine;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyMatrixStateTracker;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.TemporaryGpuResourceScope;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBGeometryShader4;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

/**
 * Bounded synchronous A/B probe for one already-compiled OptiFine permutation.
 * It is invoked only at a frame ownership boundary; no readback enters a draw
 * hot path.  Every color attachment and depth is reset and captured for both
 * programs under the same uniforms, textures, fixed state and vertex stream.
 */
public final class LwjglShaderImageCertification {
    private static final int SIZE = 8;
    private static final int MAX_ATTACHMENTS = 16;
    private static final int MAX_TEXTURE_COORDS = 8;
    private static final int VERTICES = 6;
    private static final int STRIDE = 64;
    private static final long POSITION_OFFSET = 0L;
    private static final long COLOR_OFFSET = 12L;
    private static final long NORMAL_OFFSET = 28L;
    private static final long GENERIC_OFFSET = 40L;
    private static final long TEXCOORD_OFFSET = 56L;
    private static final int GL_TEXTURE_RED_SIZE = 0x805C;
    private static final int GL_TEXTURE_GREEN_SIZE = 0x805D;
    private static final int GL_TEXTURE_BLUE_SIZE = 0x805E;
    private static final int GL_TEXTURE_ALPHA_SIZE = 0x805F;
    private static final int GL_TEXTURE_DEPTH_SIZE = 0x884A;
    private static final int GL_TEXTURE_RED_TYPE = 0x8C10;
    private static final int GL_TEXTURE_GREEN_TYPE = 0x8C11;
    private static final int GL_TEXTURE_BLUE_TYPE = 0x8C12;
    private static final int GL_TEXTURE_ALPHA_TYPE = 0x8C13;
    private static final int GL_TEXTURE_DEPTH_TYPE = 0x8C16;
    private static final int GL_UNSIGNED_NORMALIZED = 0x8C17;
    private static final int GL_SIGNED_NORMALIZED = 0x8F9C;
    private static final int GL_RGBA_INTEGER = 0x8D99;
    private static final int GL_HALF_FLOAT = 0x140B;
    private static final long MAX_COLOR_TEXTURE_BYTES = SIZE * SIZE * 16L;
    private static final long MAX_DEPTH_TEXTURE_BYTES = SIZE * SIZE * 4L;
    private static final long VERTEX_BUFFER_BYTES = VERTICES * STRIDE;
    static final int WORKSPACE_BYTES = 2048;
    private static final TemporaryGpuResourceScope.IntAllocator GEN_FRAMEBUFFER =
        new TemporaryGpuResourceScope.IntAllocator() {
            @Override public int allocate() { return GL30.glGenFramebuffers(); }
        };
    private static final TemporaryGpuResourceScope.IntAllocator GEN_TEXTURE =
        new TemporaryGpuResourceScope.IntAllocator() {
            @Override public int allocate() { return GL11.glGenTextures(); }
        };
    private static final TemporaryGpuResourceScope.IntAllocator GEN_VERTEX_ARRAY =
        new TemporaryGpuResourceScope.IntAllocator() {
            @Override public int allocate() { return GL30.glGenVertexArrays(); }
        };
    private static final TemporaryGpuResourceScope.IntAllocator GEN_BUFFER =
        new TemporaryGpuResourceScope.IntAllocator() {
            @Override public int allocate() { return GL15.glGenBuffers(); }
        };
    private static final TemporaryGpuResourceScope.IntDestroyer DELETE_FRAMEBUFFER =
        new TemporaryGpuResourceScope.IntDestroyer() {
            @Override public void destroy(int id) {
                GL30.glDeleteFramebuffers(id);
            }
        };
    private static final TemporaryGpuResourceScope.IntDestroyer DELETE_TEXTURE =
        new TemporaryGpuResourceScope.IntDestroyer() {
            @Override public void destroy(int id) { GL11.glDeleteTextures(id); }
        };
    private static final TemporaryGpuResourceScope.IntDestroyer DELETE_VERTEX_ARRAY =
        new TemporaryGpuResourceScope.IntDestroyer() {
            @Override public void destroy(int id) {
                GL30.glDeleteVertexArrays(id);
            }
        };
    private static final TemporaryGpuResourceScope.IntDestroyer DELETE_BUFFER =
        new TemporaryGpuResourceScope.IntDestroyer() {
            @Override public void destroy(int id) { GL15.glDeleteBuffers(id); }
        };

    private final RenderThreadGuard threadGuard;
    private final CacheBudget budget;
    private Workspace workspace;

    public LwjglShaderImageCertification(RenderThreadGuard threadGuard) {
        if (threadGuard == null) throw new IllegalArgumentException(
            "shader image certification thread guard");
        this.threadGuard = threadGuard;
        this.budget = null;
        this.workspace = null;
    }

    public LwjglShaderImageCertification(RenderThreadGuard threadGuard,
                                          CacheBudget budget) {
        if (threadGuard == null || budget == null) {
            throw new IllegalArgumentException(
                "shader image certification dependencies");
        }
        this.threadGuard = threadGuard;
        this.budget = budget;
        CacheBudget.Reservation reservation = budget.tryReserve(
            BudgetKind.DIRECT, WORKSPACE_BYTES);
        if (reservation != null) {
            try {
                this.workspace = new Workspace(reservation);
            } catch (Throwable failure) {
                try { reservation.close(); }
                catch (Throwable cleanup) {
                    failure = append(failure, cleanup);
                }
                rethrow(failure);
            }
        }
    }

    public Result certify(PreparedShaderPermutation prepared,
                          int legacyProgram, int candidateProgram,
                          OptifineProgramState state) {
        threadGuard.check();
        if (prepared == null || legacyProgram <= 0 || candidateProgram <= 0
            || legacyProgram == candidateProgram || state == null
            || state.getProgramId() != legacyProgram) {
            return Result.rejected("invalid shader image certification input");
        }
        if (budget == null) {
            return Result.rejected(
                "shader image certification GPU budget unavailable");
        }
        Workspace work = workspace;
        if (work == null || work.isClosed()) {
            return Result.failure(
                "shader image certification Direct budget unavailable");
        }
        ShaderValidationPolicy.Result policy = ShaderValidationPolicy.inspect(prepared);
        if (!policy.isSafe()) return Result.rejected(policy.getDetail());
        ShaderFramebufferState layout = state.getFramebufferState();
        if (layout == null || layout.getSamples() != 0) {
            return Result.rejected(
                "missing or multisampled OptiFine framebuffer layout");
        }
        int[] formats = layout.getColorInternalFormats();
        if (formats.length > MAX_ATTACHMENTS) {
            return Result.rejected("shader attachment limit exceeded");
        }
        for (int format : formats) {
            if (!supportedRequestedColorFormat(format)) {
                return Result.rejected(
                    "unsupported exact shader color attachment format " + format);
            }
        }
        if (layout.getDepthInternalFormat() != 0
            && !depthFormat(layout.getDepthInternalFormat())) {
            return Result.rejected("unsupported exact shader depth attachment format");
        }

        LwjglShaderUniformMirror.Snapshot uniforms =
            LwjglShaderUniformMirror.snapshot(legacyProgram, candidateProgram,
                work.uniformFloats(), work.uniformIntegers());
        if (!uniforms.isValid()) return Result.rejected(uniforms.getDetail());
        try {
            boolean geometry = prepared.getGeometry() != null;
            LwjglShaderLinkInterface.verify(candidateProgram, legacyProgram,
                geometry);
            return execute(legacyProgram, candidateProgram, state, layout,
                formats, geometry, uniforms, work);
        } catch (Throwable error) {
            return Result.failure(compact(error));
        }
    }

    private Result execute(int legacyProgram, int candidateProgram,
                           OptifineProgramState state,
                           ShaderFramebufferState layout,
                           int[] formats, boolean geometry,
                           LwjglShaderUniformMirror.Snapshot uniforms,
                           Workspace work) {
        int previousDrawFramebuffer = 0;
        int previousReadFramebuffer = 0;
        int previousRenderbuffer = 0;
        int previousProgram = 0;
        int previousVertexArray = 0;
        int previousArrayBuffer = 0;
        int previousPackBuffer = 0;
        int previousUnpackBuffer = 0;
        int previousActiveTexture = GL13.GL_TEXTURE0;
        int previousClientTexture = GL13.GL_TEXTURE0;
        int previousTexture2d = 0;
        int previousMatrixMode = GL11.GL_MODELVIEW;
        int framebuffer = 0;
        int depthTexture = 0;
        int vertexArray = 0;
        int vertexBuffer = 0;
        int[] colorTextures = new int[formats.length];
        CaptureSpec[] colorCaptures = new CaptureSpec[formats.length];
        CaptureSpec depthCapture = null;
        TemporaryGpuResourceScope scratch = new TemporaryGpuResourceScope(
            budget, formats.length + 4);
        TemporaryGpuResourceScope.Slot framebufferSlot = scratch.reserveOpaque(
            RenderResourceKind.FRAMEBUFFER, DELETE_FRAMEBUFFER);
        TemporaryGpuResourceScope.Slot[] colorTextureSlots =
            new TemporaryGpuResourceScope.Slot[formats.length];
        boolean budgetAvailable = framebufferSlot != null;
        for (int index = 0; index < formats.length && budgetAvailable; index++) {
            colorTextureSlots[index] = scratch.reserve(RenderResourceKind.TEXTURE,
                MAX_COLOR_TEXTURE_BYTES, DELETE_TEXTURE);
            budgetAvailable = colorTextureSlots[index] != null;
        }
        TemporaryGpuResourceScope.Slot depthTextureSlot = null;
        if (budgetAvailable && layout.getDepthInternalFormat() != 0) {
            depthTextureSlot = scratch.reserve(RenderResourceKind.TEXTURE,
                MAX_DEPTH_TEXTURE_BYTES, DELETE_TEXTURE);
            budgetAvailable = depthTextureSlot != null;
        }
        TemporaryGpuResourceScope.Slot vertexArraySlot = budgetAvailable
            ? scratch.reserveOpaque(RenderResourceKind.VERTEX_ARRAY,
                DELETE_VERTEX_ARRAY) : null;
        budgetAvailable = vertexArraySlot != null;
        TemporaryGpuResourceScope.Slot vertexBufferSlot = budgetAvailable
            ? scratch.reserve(RenderResourceKind.BUFFER, VERTEX_BUFFER_BYTES,
                DELETE_BUFFER) : null;
        budgetAvailable = vertexBufferSlot != null;
        if (!budgetAvailable) {
            Throwable cleanup = scratch.closeAndAppend(null);
            return Result.failure(cleanup == null
                ? "shader image certification GPU budget exhausted"
                : compact(cleanup));
        }
        boolean attributesPushed = false;
        boolean clientAttributesPushed = false;
        boolean stateCaptured = false;
        Throwable failure = null;
        try {
            previousDrawFramebuffer = GL11.glGetInteger(
                GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            previousReadFramebuffer = GL11.glGetInteger(
                GL30.GL_READ_FRAMEBUFFER_BINDING);
            previousRenderbuffer = GL11.glGetInteger(
                GL30.GL_RENDERBUFFER_BINDING);
            previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            previousArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            previousPackBuffer = GL11.glGetInteger(
                GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            previousUnpackBuffer = GL11.glGetInteger(
                GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
            previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            previousClientTexture = GL11.glGetInteger(
                GL13.GL_CLIENT_ACTIVE_TEXTURE);
            previousTexture2d = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            previousMatrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
            stateCaptured = true;

            // glGetError is destructive shared-context state.  Certification
            // must neither consume an error raised by an earlier mod callback
            // nor hide it from that callback's later check.  Every operation
            // below is instead validated through object/status metadata and
            // exact attachment output.

            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            attributesPushed = true;
            GL11.glPushClientAttrib(GL11.GL_ALL_CLIENT_ATTRIB_BITS);
            clientAttributesPushed = true;
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            configurePixelPack();

            int maximumAttachments = GL11.glGetInteger(
                GL30.GL_MAX_COLOR_ATTACHMENTS);
            int maximumDrawBuffers = GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS);
            if (formats.length > maximumAttachments) {
                throw new IllegalStateException(
                    "scratch FBO color attachment capability is too small");
            }
            int[] drawBuffers = validatedDrawBuffers(state.getDrawBuffers(),
                formats.length, maximumDrawBuffers);

            framebuffer = framebufferSlot.allocate(GEN_FRAMEBUFFER);
            if (framebuffer <= 0) throw new IllegalStateException(
                "scratch shader framebuffer creation failed");
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
            for (int index = 0; index < formats.length; index++) {
                colorTextures[index] = createColorTexture(formats[index],
                    colorTextureSlots[index]);
                colorCaptures[index] = captureBoundTexture(false);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
                    GL30.GL_COLOR_ATTACHMENT0 + index, GL11.GL_TEXTURE_2D,
                    colorTextures[index], 0);
            }
            if (layout.getDepthInternalFormat() != 0) {
                depthTexture = createDepthTexture(layout.getDepthInternalFormat(),
                    depthTextureSlot);
                depthCapture = captureBoundTexture(true);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
                    GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depthTexture, 0);
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture2d);
            setDrawBuffers(drawBuffers, formats.length, work);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
                != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException(
                    "scratch shader framebuffer is incomplete");
            }

            vertexArray = vertexArraySlot.allocate(GEN_VERTEX_ARRAY);
            vertexBuffer = vertexBufferSlot.allocate(GEN_BUFFER);
            if (vertexArray <= 0 || vertexBuffer <= 0) {
                throw new IllegalStateException(
                    "scratch shader vertex objects creation failed");
            }
            GL30.glBindVertexArray(vertexArray);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, work.vertices(),
                GL15.GL_STREAM_DRAW);
            configureVertexInputs(legacyProgram);
            configureRasterState();

            int mode = geometry ? geometryMode(legacyProgram) : GL11.GL_TRIANGLES;
            int count = geometry ? geometryVertexCount(mode) : VERTICES;
            clearAttachments(formats.length, layout.getDepthInternalFormat() != 0,
                drawBuffers, work);
            Map<String, byte[]> baseline = readAttachments(colorCaptures,
                depthCapture, work);

            clearAttachments(formats.length, layout.getDepthInternalFormat() != 0,
                drawBuffers, work);
            GL20.glUseProgram(legacyProgram);
            GL11.glDrawArrays(mode, 0, count);
            Map<String, byte[]> legacy = readAttachments(colorCaptures,
                depthCapture, work);

            clearAttachments(formats.length, layout.getDepthInternalFormat() != 0,
                drawBuffers, work);
            GL20.glUseProgram(candidateProgram);
            uniforms.apply(work.uniformFloats(), work.uniformIntegers());
            GL11.glDrawArrays(mode, 0, count);
            Map<String, byte[]> candidate = readAttachments(colorCaptures,
                depthCapture, work);

            boolean signal = hasObservableSignal(baseline, legacy, candidate);
            return Result.executed(legacy, candidate, signal,
                signal ? "all scratch attachments captured"
                    : "scratch draw produced no observable attachment output");
        } catch (Throwable error) {
            failure = error;
            throw error;
        } finally {
            Throwable cleanupFailure = null;
            if (stateCaptured) try { GL20.glUseProgram(0); }
            catch (Throwable error) {
                cleanupFailure = append(cleanupFailure, error);
            }
            if (stateCaptured) try {
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,
                    previousDrawFramebuffer);
            } catch (Throwable error) {
                cleanupFailure = append(cleanupFailure, error);
            }
            if (stateCaptured) try {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,
                    previousReadFramebuffer);
            } catch (Throwable error) {
                cleanupFailure = append(cleanupFailure, error);
            }
            if (stateCaptured) try { GL30.glBindVertexArray(0); }
            catch (Throwable error) {
                cleanupFailure = append(cleanupFailure, error);
            }
            cleanupFailure = scratch.closeAndAppend(cleanupFailure);
            if (clientAttributesPushed) try { GL11.glPopClientAttrib(); }
            catch (Throwable error) {
                cleanupFailure = append(cleanupFailure, error);
            }
            if (attributesPushed) try { GL11.glPopAttrib(); }
            catch (Throwable error) {
                cleanupFailure = append(cleanupFailure, error);
            }
            if (stateCaptured) try { GL30.glBindVertexArray(previousVertexArray); }
            catch (Throwable error) {
                cleanupFailure = append(cleanupFailure, error);
            }
            if (stateCaptured) try {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArrayBuffer);
            } catch (Throwable error) {
                cleanupFailure = append(cleanupFailure, error);
            }
            if (stateCaptured) try {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPackBuffer);
            } catch (Throwable error) {
                cleanupFailure = append(cleanupFailure, error);
            }
            if (stateCaptured) try { GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,
                previousUnpackBuffer); }
            catch (Throwable error) {
                cleanupFailure = append(cleanupFailure, error);
            }
            if (stateCaptured) try { GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER,
                previousRenderbuffer); }
            catch (Throwable error) {
                cleanupFailure = append(cleanupFailure, error);
            }
            if (stateCaptured) try { GL20.glUseProgram(previousProgram); }
            catch (Throwable error) {
                cleanupFailure = append(cleanupFailure, error);
            }
            if (stateCaptured) try { GL13.glActiveTexture(previousActiveTexture); }
            catch (Throwable error) {
                cleanupFailure = append(cleanupFailure, error);
            }
            if (stateCaptured) try {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture2d);
            } catch (Throwable error) {
                cleanupFailure = append(cleanupFailure, error);
            }
            if (stateCaptured) try {
                GL13.glClientActiveTexture(previousClientTexture);
            } catch (Throwable error) {
                cleanupFailure = append(cleanupFailure, error);
            }
            if (stateCaptured) try { GL11.glMatrixMode(previousMatrixMode); }
            catch (Throwable error) {
                cleanupFailure = append(cleanupFailure, error);
            }
            if (cleanupFailure != null) {
                EarlyGlStateTracker.invalidate();
                EarlyMatrixStateTracker.invalidate();
                if (failure != null) {
                    Throwable combined = append(failure, cleanupFailure);
                    // The original non-fatal exception is already propagating,
                    // but a fatal cleanup signal must take priority over it.
                    FatalErrors.rethrowIfFatal(combined);
                } else rethrow(cleanupFailure);
            }
        }
    }

    private static int createColorTexture(int internalFormat,
                                          TemporaryGpuResourceScope.Slot slot) {
        if (!supportedRequestedColorFormat(internalFormat)) {
            throw new IllegalStateException("invalid scratch color format");
        }
        int texture = slot.allocate(GEN_TEXTURE);
        if (texture <= 0) throw new IllegalStateException(
            "scratch color texture creation failed");
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat,
            SIZE, SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
            (ByteBuffer) null);
        textureParameters();
        return texture;
    }

    private static int createDepthTexture(int internalFormat,
                                          TemporaryGpuResourceScope.Slot slot) {
        if (!depthFormat(internalFormat)) {
            throw new IllegalStateException("unsupported scratch depth format");
        }
        int texture = slot.allocate(GEN_TEXTURE);
        if (texture <= 0) throw new IllegalStateException(
            "scratch depth texture creation failed");
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat,
            SIZE, SIZE, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT,
            (ByteBuffer) null);
        textureParameters();
        return texture;
    }

    private static boolean depthFormat(int value) {
        return value == GL11.GL_DEPTH_COMPONENT || value == 33189
            || value == 33190 || value == 33191 || value == 36012;
    }

    /** G5's declared formats for which null-data allocation is unambiguous. */
    static boolean supportedRequestedColorFormat(int value) {
        return value == GL11.GL_RGB || value == GL11.GL_RGBA
            || value == 32849 || value == 32856
            || value == 32852 || value == 32859
            || value == 34843 || value == 34842
            || value == 34837 || value == 34836
            || value == 33321 || value == 33322
            || value == 33323 || value == 33324
            || value == 33325 || value == 33326
            || value == 33327 || value == 33328
            || value == 35905 || value == 35907;
    }

    private static CaptureSpec captureBoundTexture(boolean depth) {
        int bits;
        int componentType;
        if (depth) {
            bits = textureLevelParameter(GL_TEXTURE_DEPTH_SIZE);
            componentType = textureLevelParameter(GL_TEXTURE_DEPTH_TYPE);
        } else {
            int[] sizeNames = { GL_TEXTURE_RED_SIZE, GL_TEXTURE_GREEN_SIZE,
                GL_TEXTURE_BLUE_SIZE, GL_TEXTURE_ALPHA_SIZE };
            int[] typeNames = { GL_TEXTURE_RED_TYPE, GL_TEXTURE_GREEN_TYPE,
                GL_TEXTURE_BLUE_TYPE, GL_TEXTURE_ALPHA_TYPE };
            bits = 0;
            componentType = 0;
            for (int index = 0; index < sizeNames.length; index++) {
                int candidateBits = textureLevelParameter(sizeNames[index]);
                if (candidateBits <= 0) continue;
                int candidateType = textureLevelParameter(typeNames[index]);
                if (bits == 0) {
                    bits = candidateBits;
                    componentType = candidateType;
                } else if (bits != candidateBits
                    || componentType != candidateType) {
                    throw new IllegalStateException(
                        "packed or mixed shader attachment cannot be captured exactly");
                }
            }
        }
        CaptureSpec result = captureSpec(componentType, bits, depth);
        if (result == null) {
            throw new IllegalStateException(
                "shader attachment component representation is unsupported");
        }
        return result;
    }

    private static int textureLevelParameter(int name) {
        return GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, name);
    }

    private static CaptureSpec captureSpec(int componentType, int bits,
                                           boolean depth) {
        int readFormat = depth ? GL11.GL_DEPTH_COMPONENT : GL11.GL_RGBA;
        int readType;
        int bytes;
        if (componentType == GL11.GL_FLOAT) {
            if (bits == 16) {
                readType = GL_HALF_FLOAT;
                bytes = 2;
            } else if (bits == 32) {
                readType = GL11.GL_FLOAT;
                bytes = 4;
            } else {
                return null;
            }
        } else if (componentType == GL_UNSIGNED_NORMALIZED
            || componentType == GL11.GL_UNSIGNED_INT) {
            if (componentType == GL11.GL_UNSIGNED_INT && !depth) {
                readFormat = GL_RGBA_INTEGER;
            }
            if (bits <= 8) {
                readType = GL11.GL_UNSIGNED_BYTE;
                bytes = 1;
            } else if (bits <= 16) {
                readType = GL11.GL_UNSIGNED_SHORT;
                bytes = 2;
            } else if (bits <= 32) {
                readType = GL11.GL_UNSIGNED_INT;
                bytes = 4;
            } else {
                return null;
            }
        } else if (!depth && (componentType == GL_SIGNED_NORMALIZED
            || componentType == GL11.GL_INT)) {
            if (componentType == GL11.GL_INT) readFormat = GL_RGBA_INTEGER;
            if (bits <= 8) {
                readType = GL11.GL_BYTE;
                bytes = 1;
            } else if (bits <= 16) {
                readType = GL11.GL_SHORT;
                bytes = 2;
            } else if (bits <= 32) {
                readType = GL11.GL_INT;
                bytes = 4;
            } else {
                return null;
            }
        } else {
            return null;
        }
        return new CaptureSpec(readFormat, readType,
            Math.multiplyExact(depth ? 1 : 4, bytes));
    }

    /** Pure precision gate used by attachment-format regression tests. */
    static int exactCaptureBytesPerPixel(int componentType, int bits,
                                         boolean depth) {
        CaptureSpec spec = captureSpec(componentType, bits, depth);
        return spec == null ? 0 : spec.bytesPerPixel;
    }

    private static void textureParameters() {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
            GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER,
            GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S,
            GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T,
            GL12.GL_CLAMP_TO_EDGE);
    }

    private static int[] validatedDrawBuffers(int[] requested, int colors,
                                              int maximumDrawBuffers) {
        if (maximumDrawBuffers <= 0 || maximumDrawBuffers > MAX_ATTACHMENTS) {
            maximumDrawBuffers = MAX_ATTACHMENTS;
        }
        int[] values;
        if (requested == null || requested.length == 0) {
            values = colors == 0 ? new int[] { GL11.GL_NONE }
                : new int[] { GL30.GL_COLOR_ATTACHMENT0 };
        } else {
            if (requested.length > maximumDrawBuffers) {
                throw new IllegalStateException("shader drawbuffer limit exceeded");
            }
            values = requested.clone();
        }
        boolean[] used = new boolean[Math.max(1, colors)];
        for (int value : values) {
            if (value == GL11.GL_NONE) continue;
            int index = value - GL30.GL_COLOR_ATTACHMENT0;
            if (index < 0 || index >= colors || used[index]) {
                throw new IllegalStateException("invalid shader drawbuffer layout");
            }
            used[index] = true;
        }
        return values;
    }

    private static void setDrawBuffers(int[] values, int colors,
                                       Workspace workspace) {
        if (values.length == 1) {
            GL11.glDrawBuffer(values[0]);
        } else {
            IntBuffer buffer = workspace.drawBuffers(values.length);
            buffer.put(values).flip();
            GL20.glDrawBuffers(buffer);
        }
        GL11.glReadBuffer(colors == 0 ? GL11.GL_NONE
            : GL30.GL_COLOR_ATTACHMENT0);
    }

    private static void configurePixelPack() {
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SWAP_BYTES, GL11.GL_FALSE);
        GL11.glPixelStorei(GL11.GL_PACK_LSB_FIRST, GL11.GL_FALSE);
    }

    private static void fillVertices(ByteBuffer data) {
        data.clear();
        putVertex(data, -0.8F, -0.8F, 0.2F, 0.0F, 0.0F, 0.15F);
        putVertex(data, 0.8F, -0.8F, 0.2F, 1.0F, 0.0F, 0.30F);
        putVertex(data, 0.8F, 0.8F, 0.2F, 1.0F, 1.0F, 0.45F);
        putVertex(data, -0.8F, -0.8F, 0.2F, 0.0F, 0.0F, 0.60F);
        putVertex(data, 0.8F, 0.8F, 0.2F, 1.0F, 1.0F, 0.75F);
        putVertex(data, -0.8F, 0.8F, 0.2F, 0.0F, 1.0F, 0.90F);
        data.flip();
    }

    private static void putVertex(ByteBuffer target, float x, float y, float z,
                                  float u, float v, float marker) {
        target.putFloat(x).putFloat(y).putFloat(z);
        target.putFloat(0.25F + marker * 0.5F).putFloat(0.75F - marker * 0.25F)
            .putFloat(0.5F).putFloat(1.0F);
        target.putFloat(0.0F).putFloat(0.0F).putFloat(1.0F);
        target.putFloat(marker).putFloat(1.0F - marker)
            .putFloat(u).putFloat(v);
        target.putFloat(u).putFloat(v);
    }

    private static void configureVertexInputs(int program) {
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
        GL11.glVertexPointer(3, GL11.GL_FLOAT, STRIDE, POSITION_OFFSET);
        GL11.glColorPointer(4, GL11.GL_FLOAT, STRIDE, COLOR_OFFSET);
        GL11.glNormalPointer(GL11.GL_FLOAT, STRIDE, NORMAL_OFFSET);

        int textureCoordinates = GL11.glGetInteger(GL20.GL_MAX_TEXTURE_COORDS);
        textureCoordinates = Math.max(1, Math.min(MAX_TEXTURE_COORDS,
            textureCoordinates));
        for (int index = 0; index < textureCoordinates; index++) {
            GL13.glClientActiveTexture(GL13.GL_TEXTURE0 + index);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glTexCoordPointer(2, GL11.GL_FLOAT, STRIDE, TEXCOORD_OFFSET);
        }

        int attributes = GL20.glGetProgrami(program, GL20.GL_ACTIVE_ATTRIBUTES);
        int maximumName = GL20.glGetProgrami(program,
            GL20.GL_ACTIVE_ATTRIBUTE_MAX_LENGTH);
        int maximumLocations = GL11.glGetInteger(GL20.GL_MAX_VERTEX_ATTRIBS);
        if (attributes < 0 || attributes > 64 || maximumName < 0
            || maximumName > 4096 || maximumLocations <= 0
            || maximumLocations > 256) {
            throw new IllegalStateException("shader vertex input interface exceeds limits");
        }
        int capacity = Math.max(1, maximumName);
        boolean[] used = new boolean[maximumLocations];
        for (int index = 0; index < attributes; index++) {
            String name = GL20.glGetActiveAttrib(program, index, capacity);
            int size = GL20.glGetActiveAttribSize(program, index);
            int type = GL20.glGetActiveAttribType(program, index);
            int location = name == null ? -1
                : GL20.glGetAttribLocation(program, name);
            int components = attributeComponents(type);
            if (name == null || name.isEmpty() || name.startsWith("gl_")
                || size != 1 || components == 0 || location < 0
                || location >= used.length || used[location]) {
                throw new IllegalStateException(
                    "unsupported shader generic attribute input");
            }
            used[location] = true;
            GL20.glEnableVertexAttribArray(location);
            GL20.glVertexAttribPointer(location, components, GL11.GL_FLOAT,
                false, STRIDE, GENERIC_OFFSET);
        }
    }

    private static int attributeComponents(int type) {
        if (type == GL11.GL_FLOAT) return 1;
        if (type == GL20.GL_FLOAT_VEC2) return 2;
        if (type == GL20.GL_FLOAT_VEC3) return 3;
        if (type == GL20.GL_FLOAT_VEC4) return 4;
        return 0;
    }

    private static void configureRasterState() {
        GL11.glViewport(0, 0, SIZE, SIZE);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DITHER);
        GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
        GL11.glDisable(GL13.GL_MULTISAMPLE);
        GL11.glDisable(GL13.GL_SAMPLE_ALPHA_TO_COVERAGE);
        GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
        for (int index = 0; index < 6; index++) {
            GL11.glDisable(GL11.GL_CLIP_PLANE0 + index);
        }
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_ALWAYS);
        GL11.glDepthMask(true);
        GL11.glDepthRange(0.0D, 1.0D);
        GL11.glColorMask(true, true, true, true);
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
    }

    private static int geometryMode(int program) {
        int mode = GL20.glGetProgrami(program,
            ARBGeometryShader4.GL_GEOMETRY_INPUT_TYPE_ARB);
        if (mode == GL11.GL_POINTS || mode == GL11.GL_LINES
            || mode == GL32.GL_LINES_ADJACENCY || mode == GL11.GL_TRIANGLES
            || mode == GL32.GL_TRIANGLES_ADJACENCY) return mode;
        throw new IllegalStateException("unsupported geometry input primitive");
    }

    private static int geometryVertexCount(int mode) {
        if (mode == GL11.GL_POINTS) return 1;
        if (mode == GL11.GL_LINES) return 2;
        if (mode == GL32.GL_LINES_ADJACENCY) return 4;
        return mode == GL11.GL_TRIANGLES ? 3 : 6;
    }

    private static void clearAttachments(int colors, boolean depth,
                                         int[] drawBuffers,
                                         Workspace workspace) {
        GL11.glClearColor(0.0625F, 0.125F, 0.25F, 0.5F);
        GL11.glClearDepth(0.875D);
        for (int index = 0; index < colors; index++) {
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0 + index);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        }
        setDrawBuffers(drawBuffers, colors, workspace);
        if (depth) GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
    }

    private static Map<String, byte[]> readAttachments(CaptureSpec[] colors,
                                                       CaptureSpec depth,
                                                       Workspace workspace) {
        LinkedHashMap<String, byte[]> result =
            new LinkedHashMap<String, byte[]>();
        for (int index = 0; index < colors.length; index++) {
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0 + index);
            CaptureSpec capture = colors[index];
            int bytes = Math.multiplyExact(SIZE * SIZE,
                capture.bytesPerPixel);
            ByteBuffer pixels = workspace.readback(bytes);
            GL11.glReadPixels(0, 0, SIZE, SIZE, capture.format,
                capture.type, pixels);
            byte[] values = new byte[bytes];
            for (int offset = 0; offset < values.length; offset++) {
                values[offset] = pixels.get(offset);
            }
            result.put("color" + index, values);
        }
        if (depth != null) {
            int bytes = Math.multiplyExact(SIZE * SIZE,
                depth.bytesPerPixel);
            ByteBuffer pixels = workspace.readback(bytes);
            GL11.glReadPixels(0, 0, SIZE, SIZE, depth.format,
                depth.type, pixels);
            byte[] values = new byte[bytes];
            for (int offset = 0; offset < values.length; offset++) {
                values[offset] = pixels.get(offset);
            }
            result.put("depth", values);
        }
        return result;
    }

    private static final class CaptureSpec {
        private final int format;
        private final int type;
        private final int bytesPerPixel;

        private CaptureSpec(int format, int type, int bytesPerPixel) {
            this.format = format;
            this.type = type;
            this.bytesPerPixel = bytesPerPixel;
        }
    }

    public void close() {
        Workspace owned = workspace;
        workspace = null;
        if (owned != null) owned.close();
    }

    boolean isWorkspaceAvailableForTest() {
        Workspace value = workspace;
        return value != null && !value.isClosed();
    }

    private static final class Workspace implements AutoCloseable {
        private CacheBudget.Reservation reservation;
        private ByteBuffer storage;
        private ByteBuffer vertices;
        private ByteBuffer readback;
        private IntBuffer drawBuffers;
        private FloatBuffer uniformFloats;
        private IntBuffer uniformIntegers;

        private Workspace(CacheBudget.Reservation reservation) {
            if (reservation == null) throw new IllegalArgumentException(
                "shader certification workspace reservation");
            ByteBuffer allocated = BufferUtils.createByteBuffer(WORKSPACE_BYTES)
                .order(ByteOrder.nativeOrder());
            storage = allocated;
            vertices = bytes(allocated, 0, (int) VERTEX_BUFFER_BYTES);
            readback = bytes(allocated, 384, 1024);
            drawBuffers = bytes(allocated, 1408, 64).asIntBuffer();
            uniformFloats = bytes(allocated, 1472, 64).asFloatBuffer();
            uniformIntegers = bytes(allocated, 1536, 16).asIntBuffer();
            fillVertices(vertices);
            this.reservation = reservation;
        }

        private ByteBuffer vertices() {
            checkOpen();
            vertices.position(0);
            vertices.limit((int) VERTEX_BUFFER_BYTES);
            return vertices;
        }

        private ByteBuffer readback(int count) {
            checkOpen();
            if (count <= 0 || count > readback.capacity()) {
                throw new IllegalArgumentException(
                    "shader certification readback size");
            }
            readback.clear();
            readback.limit(count);
            return readback;
        }

        private IntBuffer drawBuffers(int count) {
            checkOpen();
            if (count <= 0 || count > drawBuffers.capacity()) {
                throw new IllegalArgumentException(
                    "shader certification drawbuffer count");
            }
            drawBuffers.clear();
            drawBuffers.limit(count);
            return drawBuffers;
        }

        private FloatBuffer uniformFloats() {
            checkOpen();
            uniformFloats.clear();
            return uniformFloats;
        }

        private IntBuffer uniformIntegers() {
            checkOpen();
            uniformIntegers.clear();
            return uniformIntegers;
        }

        private boolean isClosed() { return storage == null; }

        @Override public void close() {
            CacheBudget.Reservation owned = reservation;
            reservation = null;
            storage = null;
            vertices = null;
            readback = null;
            drawBuffers = null;
            uniformFloats = null;
            uniformIntegers = null;
            if (owned != null) owned.close();
        }

        private void checkOpen() {
            if (storage == null) throw new IllegalStateException(
                "shader certification workspace is closed");
        }

        private static ByteBuffer bytes(ByteBuffer source, int offset,
                                        int count) {
            ByteBuffer view = source.duplicate().order(ByteOrder.nativeOrder());
            view.position(offset);
            view.limit(Math.addExact(offset, count));
            return view.slice().order(ByteOrder.nativeOrder());
        }
    }

    private static boolean differs(Map<String, byte[]> baseline,
                                   Map<String, byte[]> output) {
        if (!baseline.keySet().equals(output.keySet())) return false;
        for (Map.Entry<String, byte[]> entry : baseline.entrySet()) {
            if (!Arrays.equals(entry.getValue(), output.get(entry.getKey()))) {
                return true;
            }
        }
        return false;
    }

    /** Pure fail-closed signal gate shared with attachment logic tests. */
    static boolean hasObservableSignal(Map<String, byte[]> baseline,
                                       Map<String, byte[]> legacy,
                                       Map<String, byte[]> candidate) {
        return baseline != null && legacy != null && candidate != null
            && differs(baseline, legacy) && differs(baseline, candidate);
    }

    private static String compact(Throwable error) {
        dev.rlcraft.ice.optimizer.FatalErrors.rethrowIfFatal(error);
        String message = error.getMessage();
        String value = error.getClass().getSimpleName()
            + (message == null || message.isEmpty() ? "" : ": " + message);
        return value.length() <= 256 ? value : value.substring(0, 256);
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
        throw new IllegalStateException("shader image certification cleanup failed",
            failure);
    }

    public static final class Result {
        private final boolean executed;
        private final boolean signal;
        private final boolean infrastructureFailure;
        private final String detail;
        private final Map<String, byte[]> legacy;
        private final Map<String, byte[]> candidate;

        private Result(boolean executed, boolean signal,
                       boolean infrastructureFailure, String detail,
                       Map<String, byte[]> legacy,
                       Map<String, byte[]> candidate) {
            this.executed = executed;
            this.signal = signal;
            this.infrastructureFailure = infrastructureFailure;
            this.detail = detail == null ? "" : detail;
            this.legacy = copy(legacy);
            this.candidate = copy(candidate);
        }

        private static Result executed(Map<String, byte[]> legacy,
                                       Map<String, byte[]> candidate,
                                       boolean signal, String detail) {
            return new Result(true, signal, false, detail, legacy, candidate);
        }

        private static Result rejected(String detail) {
            return new Result(false, false, false, detail, null, null);
        }

        private static Result failure(String detail) {
            return new Result(false, false, true, detail, null, null);
        }

        private static Map<String, byte[]> copy(Map<String, byte[]> source) {
            if (source == null) return Collections.emptyMap();
            LinkedHashMap<String, byte[]> result =
                new LinkedHashMap<String, byte[]>();
            for (Map.Entry<String, byte[]> entry : source.entrySet()) {
                result.put(entry.getKey(), entry.getValue().clone());
            }
            return Collections.unmodifiableMap(result);
        }

        public boolean wasExecuted() { return executed; }
        public boolean hasSignal() { return signal; }
        public boolean isInfrastructureFailure() { return infrastructureFailure; }
        public String getDetail() { return detail; }
        public Map<String, byte[]> getLegacyImages() { return copy(legacy); }
        public Map<String, byte[]> getCandidateImages() { return copy(candidate); }
    }
}
