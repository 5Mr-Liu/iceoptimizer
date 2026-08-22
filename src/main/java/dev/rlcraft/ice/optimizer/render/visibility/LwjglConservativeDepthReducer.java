package dev.rlcraft.ice.optimizer.render.visibility;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.LwjglTemporaryResourceOps;
import dev.rlcraft.ice.optimizer.render.resource.RenderHandle;
import dev.rlcraft.ice.optimizer.render.resource.LwjglRetirementFence;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import dev.rlcraft.ice.optimizer.render.resource.TemporaryGpuResourceScope;
import dev.rlcraft.ice.optimizer.render.resource.TemporaryShaderStage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

/** GPU max reduction from the active depth buffer into a bounded R32F image. */
public final class LwjglConservativeDepthReducer {
    private static final int TARGET_WIDTH = 320;
    private static final int TARGET_HEIGHT = 180;
    private static final int MAX_SCALE = 16;
    private static final String VERTEX_SOURCE =
        "#version 130\n"
            + "void main() { gl_Position = gl_Vertex; }\n";
    private static final String FRAGMENT_SOURCE =
        "#version 130\n"
            + "uniform sampler2D sourceDepth;\n"
            + "uniform ivec2 sourceSize;\n"
            + "uniform int reductionScale;\n"
            + "out float reducedDepth;\n"
            + "void main() {\n"
            + "  ivec2 cell = ivec2(gl_FragCoord.xy);\n"
            + "  ivec2 base = cell * reductionScale;\n"
            + "  float value = 0.0;\n"
            + "  for (int y = 0; y < 16; ++y) {\n"
            + "    for (int x = 0; x < 16; ++x) {\n"
            + "      ivec2 p = base + ivec2(x, y);\n"
            + "      if (x < reductionScale && y < reductionScale"
            + " && p.x < sourceSize.x && p.y < sourceSize.y)\n"
            + "        value = max(value, texelFetch(sourceDepth, p, 0).r);\n"
            + "    }\n"
            + "  }\n"
            + "  reducedDepth = value;\n"
            + "}\n";

    private final RenderThreadGuard guard;
    private final ResourceLedger resources;
    private int program;
    private int sourceTexture;
    private int reducedTexture;
    private int framebuffer;
    private int sourceWidth;
    private int sourceHeight;
    private int lowWidth;
    private int lowHeight;
    private int scale;
    private int sourceSizeLocation;
    private int scaleLocation;
    private int samplerLocation;
    private RenderHandle programHandle;
    private RenderHandle sourceHandle;
    private RenderHandle reducedHandle;
    private RenderHandle framebufferHandle;
    private long certifiedContextGeneration = Long.MIN_VALUE;
    private long certifiedViewGeneration = Long.MIN_VALUE;
    private long certifiedShaderGeneration = Long.MIN_VALUE;
    private int certifiedReadFramebuffer = Integer.MIN_VALUE;
    private int certifiedWidth = -1;
    private int certifiedHeight = -1;
    private boolean certifiedSource;

    public LwjglConservativeDepthReducer(RenderThreadGuard guard,
                                         ResourceLedger resources) {
        if (guard == null || resources == null) throw new IllegalArgumentException("reducer");
        this.guard = guard;
        this.resources = resources;
    }

    public Reduction reduce(RenderMatrixBridge.Snapshot matrices,
                            EarlyGlStateTracker.Snapshot state,
                            int pixelPackBuffer, FrameStamp stamp) {
        return reduce(matrices, state, pixelPackBuffer, stamp, false);
    }

    public Reduction reduce(RenderMatrixBridge.Snapshot matrices,
                            EarlyGlStateTracker.Snapshot state,
                            int pixelPackBuffer, FrameStamp stamp,
                            boolean validateSource) {
        guard.check();
        if (matrices == null || state == null || stamp == null
            || pixelPackBuffer <= 0 || state.getProgram() != 0
            || (state.getDepthFunction() != GL11.GL_LESS
                && state.getDepthFunction() != GL11.GL_LEQUAL)) return null;
        int width = matrices.getWidth();
        int height = matrices.getHeight();
        if (width <= 0 || height <= 0) return null;
        boolean attributes = false;
        boolean clientAttributes = false;
        Throwable failure = null;
        try {
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            attributes = true;
            GL11.glPushClientAttrib(GL11.GL_ALL_CLIENT_ATTRIB_BITS);
            clientAttributes = true;
            if (!certifySource(width, height, state, stamp)) return null;
            if (!ensure(width, height, stamp)) return null;

            // Image creation binds ICE's private FBO.  Always restore the
            // certified source before copying, including the first frame and
            // every resize generation.
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,
                state.getReadFramebuffer());
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,
                state.getDrawFramebuffer());

            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, sourceTexture);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0,
                matrices.getViewportX(), matrices.getViewportY(), width, height);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glViewport(0, 0, lowWidth, lowHeight);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_DITHER);
            GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
            GL11.glColorMask(true, true, true, true);
            GL20.glUseProgram(program);
            GL20.glUniform1i(samplerLocation, 0);
            GL20.glUniform2i(sourceSizeLocation, width, height);
            GL20.glUniform1i(scaleLocation, scale);
            drawFullscreen();

            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pixelPackBuffer);
            GL11.glReadPixels(0, 0, lowWidth, lowHeight, GL11.GL_RED,
                GL11.GL_FLOAT, 0L);
            Oracle oracle = validateSource
                ? readSourceOracle(matrices, state) : null;
            return new Reduction(scale, lowWidth, lowHeight,
                Math.multiplyExact(Math.multiplyExact(lowWidth, lowHeight), 4), oracle);
        } catch (Throwable error) {
            failure = error;
            EarlyGlStateTracker.invalidate();
            throw error;
        } finally {
            Throwable restoreFailure = null;
            try {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,
                    state.getPixelPackBuffer());
            } catch (Throwable error) {
                restoreFailure = appendFailure(restoreFailure, error);
            }
            try { GL20.glUseProgram(state.getProgram()); }
            catch (Throwable error) {
                restoreFailure = appendFailure(restoreFailure, error);
            }
            try {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,
                    state.getReadFramebuffer());
            } catch (Throwable error) {
                restoreFailure = appendFailure(restoreFailure, error);
            }
            try {
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,
                    state.getDrawFramebuffer());
            } catch (Throwable error) {
                restoreFailure = appendFailure(restoreFailure, error);
            }
            if (clientAttributes) try { GL11.glPopClientAttrib(); }
            catch (Throwable error) {
                restoreFailure = appendFailure(restoreFailure, error);
            }
            if (attributes) try { GL11.glPopAttrib(); }
            catch (Throwable error) {
                restoreFailure = appendFailure(restoreFailure, error);
            }
            if (restoreFailure != null) {
                EarlyGlStateTracker.invalidate();
                if (failure != null) {
                    Throwable combined = appendFailure(failure, restoreFailure);
                    if (combined != failure) rethrow(combined);
                }
                else throw new IllegalStateException("depth reducer state restore failed",
                    restoreFailure);
            }
        }
    }

    public void reset(boolean contextValid) {
        guard.check();
        RenderHandle oldSource = sourceHandle;
        RenderHandle oldReduced = reducedHandle;
        RenderHandle oldFramebuffer = framebufferHandle;
        RenderHandle oldProgram = programHandle;
        sourceHandle = null;
        reducedHandle = null;
        framebufferHandle = null;
        programHandle = null;
        sourceTexture = 0;
        reducedTexture = 0;
        framebuffer = 0;
        program = 0;
        sourceSizeLocation = -1;
        scaleLocation = -1;
        samplerLocation = -1;
        sourceWidth = sourceHeight = lowWidth = lowHeight = scale = 0;
        clearSourceCertification();

        Throwable failure = null;
        if (contextValid) {
            // Reference containers retire before their attachment payloads.
            Throwable containerFailure = retireInFlight(oldFramebuffer, null);
            failure = appendFailure(failure, containerFailure);
            if (containerFailure == null) {
                failure = retireInFlight(oldSource, failure);
                failure = retireInFlight(oldReduced, failure);
            }
            failure = retireInFlight(oldProgram, failure);
        }
        if (failure != null) rethrow(failure);
    }

    public int getLowWidth() { return lowWidth; }
    public int getLowHeight() { return lowHeight; }

    private boolean ensure(int width, int height, FrameStamp stamp) {
        int requestedScale = Math.max(1, Math.max(
            ceilDivPositive(width, TARGET_WIDTH),
            ceilDivPositive(height, TARGET_HEIGHT)));
        if (requestedScale > MAX_SCALE) return false;
        int requestedLowWidth = ceilDivPositive(width, requestedScale);
        int requestedLowHeight = ceilDivPositive(height, requestedScale);
        if (program == 0 && !createProgram(stamp)) return false;
        if (sourceTexture != 0 && sourceWidth == width && sourceHeight == height
            && lowWidth == requestedLowWidth && lowHeight == requestedLowHeight) {
            scale = requestedScale;
            return true;
        }
        releaseImages(stamp.getGlContextGeneration());
        return createImages(width, height, requestedScale, requestedLowWidth,
            requestedLowHeight, stamp);
    }

    private boolean createProgram(FrameStamp stamp) {
        int created = 0;
        boolean creationReturned = false;
        ProgramBuildState programBuild = new ProgramBuildState();
        CacheBudget.Reservation reservation = resources.reserveNativeObject(
            RenderResourceKind.PROGRAM);
        if (reservation == null) return false;
        RenderHandle handle = null;
        boolean published = false;
        Throwable failure = null;
        try {
            created = compileProgram(resources, VERTEX_SOURCE,
                FRAGMENT_SOURCE, programBuild);
            creationReturned = true;
            if (created <= 0) throw new IllegalStateException(
                "depth reducer program creation failed");
            handle = resources.registerReservedObject(
                RenderResourceKind.PROGRAM, created,
                stamp.getResourceGeneration(), stamp.getGlContextGeneration(),
                reservation);
            if (handle != null) {
                reservation = null;
                int sampler = GL20.glGetUniformLocation(created, "sourceDepth");
                int size = GL20.glGetUniformLocation(created, "sourceSize");
                int reduction = GL20.glGetUniformLocation(created, "reductionScale");
                if (sampler >= 0 && size >= 0 && reduction >= 0) {
                    program = created;
                    programHandle = handle;
                    samplerLocation = sampler;
                    sourceSizeLocation = size;
                    scaleLocation = reduction;
                    published = true;
                }
            }
        } catch (Throwable error) {
            failure = error;
            creationReturned = programBuild.isKnownAbsent();
        }
        if (!published) {
            failure = cleanupCreatedReserved(RenderResourceKind.PROGRAM,
                created, handle, reservation, creationReturned, failure);
        }
        if (failure != null) rethrow(failure);
        return published;
    }

    private boolean createImages(int width, int height, int requestedScale,
                                 int requestedLowWidth, int requestedLowHeight,
                                 FrameStamp stamp) {
        long sourceBytes = checkedImageBytes(width, height);
        long reducedBytes = checkedImageBytes(requestedLowWidth,
            requestedLowHeight);
        int source = 0;
        int reduced = 0;
        int fbo = 0;
        boolean sourceAllocationReturned = false;
        boolean reducedAllocationReturned = false;
        boolean fboAllocationReturned = false;
        CacheBudget.Reservation sourceReservation = resources.reserveGpu(
            sourceBytes);
        if (sourceReservation == null) return false;
        CacheBudget.Reservation reducedReservation = null;
        CacheBudget.Reservation fboReservation = null;
        RenderHandle newSource = null;
        RenderHandle newReduced = null;
        RenderHandle newFbo = null;
        boolean published = false;
        Throwable failure = null;
        try {
            source = GL11.glGenTextures();
            sourceAllocationReturned = true;
            if (source <= 0) throw new IllegalStateException(
                "depth reducer source texture creation failed");
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, source);
            textureParameters();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F,
                width, height, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT,
                (ByteBuffer) null);
            newSource = resources.registerReserved(RenderResourceKind.TEXTURE,
                source, sourceBytes, stamp.getResourceGeneration(),
                stamp.getGlContextGeneration(), sourceReservation);
            if (newSource != null) {
                sourceReservation = null;
                reducedReservation = resources.reserveGpu(reducedBytes);
            }
            if (newSource != null && reducedReservation != null) {
                reduced = GL11.glGenTextures();
                reducedAllocationReturned = true;
                if (reduced <= 0) throw new IllegalStateException(
                    "depth reducer image creation failed");
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, reduced);
                textureParameters();
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32F,
                    requestedLowWidth, requestedLowHeight, 0, GL11.GL_RED,
                    GL11.GL_FLOAT, (ByteBuffer) null);
                newReduced = resources.registerReserved(
                    RenderResourceKind.TEXTURE, reduced, reducedBytes,
                    stamp.getResourceGeneration(),
                    stamp.getGlContextGeneration(), reducedReservation);
                if (newReduced != null) reducedReservation = null;
            }
            if (newReduced != null) {
                fboReservation = resources.reserveNativeObject(
                    RenderResourceKind.FRAMEBUFFER);
                if (fboReservation != null) {
                    fbo = GL30.glGenFramebuffers();
                    fboAllocationReturned = true;
                    if (fbo <= 0) throw new IllegalStateException(
                        "depth reducer framebuffer creation failed");
                    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
                    GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
                        GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, reduced, 0);
                    GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
                    GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
                    if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
                        == GL30.GL_FRAMEBUFFER_COMPLETE) {
                        newFbo = resources.registerReservedObject(
                            RenderResourceKind.FRAMEBUFFER, fbo,
                            stamp.getResourceGeneration(),
                            stamp.getGlContextGeneration(), fboReservation);
                        if (newFbo != null) fboReservation = null;
                    }
                }
            }
            if (newFbo != null) {
                sourceTexture = source;
                reducedTexture = reduced;
                framebuffer = fbo;
                sourceHandle = newSource;
                reducedHandle = newReduced;
                framebufferHandle = newFbo;
                sourceWidth = width;
                sourceHeight = height;
                lowWidth = requestedLowWidth;
                lowHeight = requestedLowHeight;
                scale = requestedScale;
                published = true;
            }
        } catch (Throwable error) {
            failure = error;
        }
        if (!published) {
            // Retire the attachment point before its images.  Every cleanup is
            // attempted even if another ledger publication/driver call fails.
            failure = cleanupCreatedReserved(RenderResourceKind.FRAMEBUFFER,
                fbo, newFbo, fboReservation, fboAllocationReturned, failure);
            failure = cleanupCreatedReserved(RenderResourceKind.TEXTURE,
                reduced, newReduced, reducedReservation,
                reducedAllocationReturned, failure);
            failure = cleanupCreatedReserved(RenderResourceKind.TEXTURE,
                source, newSource, sourceReservation,
                sourceAllocationReturned, failure);
        }
        if (failure != null) rethrow(failure);
        return published;
    }

    private void releaseImages(long contextGeneration) {
        RenderHandle oldSource = sourceHandle;
        RenderHandle oldReduced = reducedHandle;
        RenderHandle oldFramebuffer = framebufferHandle;
        sourceHandle = null;
        reducedHandle = null;
        framebufferHandle = null;
        sourceTexture = reducedTexture = framebuffer = 0;
        sourceWidth = sourceHeight = lowWidth = lowHeight = scale = 0;
        Throwable failure = null;
        Throwable containerFailure = retireInFlight(oldFramebuffer, null);
        failure = appendFailure(failure, containerFailure);
        if (containerFailure == null) {
            failure = retireInFlight(oldSource, failure);
            failure = retireInFlight(oldReduced, failure);
        }
        try { resources.collect(contextGeneration, 8); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        if (failure != null) rethrow(failure);
    }

    private Throwable cleanupCreated(RenderResourceKind kind, int id,
                                     RenderHandle handle, Throwable failure) {
        try {
            if (handle != null) resources.retire(handle, null);
            else if (id != 0) {
                if (kind == RenderResourceKind.TEXTURE) GL11.glDeleteTextures(id);
                else if (kind == RenderResourceKind.FRAMEBUFFER) {
                    GL30.glDeleteFramebuffers(id);
                } else if (kind == RenderResourceKind.PROGRAM) {
                    GL20.glDeleteProgram(id);
                }
            }
        } catch (Throwable error) {
            failure = appendFailure(failure, error);
        }
        return failure;
    }

    /**
     * Cleans an unpublished byte-backed object and releases its reservation
     * only after the native name is proven absent.  A throwing allocator or
     * delete keeps the charge poisoned to bound outcome-uncertain leaks.
     */
    private Throwable cleanupCreatedReserved(RenderResourceKind kind, int id,
                                             RenderHandle handle,
                                             CacheBudget.Reservation reservation,
                                             boolean allocationReturned,
                                             Throwable failure) {
        if (handle != null) return cleanupCreated(kind, id, handle, failure);
        boolean deleted = false;
        if (id > 0) {
            try {
                switch (kind) {
                    case TEXTURE:
                        GL11.glDeleteTextures(id);
                        break;
                    case FRAMEBUFFER:
                        GL30.glDeleteFramebuffers(id);
                        break;
                    case PROGRAM:
                        GL20.glDeleteProgram(id);
                        break;
                    case VERTEX_ARRAY:
                        GL30.glDeleteVertexArrays(id);
                        break;
                    default:
                        throw new IllegalArgumentException(
                            "unsupported reserved cleanup kind " + kind);
                }
                deleted = true;
            } catch (Throwable error) {
                failure = appendFailure(failure, error);
            }
        }
        boolean noNameCreated = allocationReturned && id <= 0;
        if (reservation != null && (deleted || noNameCreated)) {
            try { reservation.close(); }
            catch (Throwable error) {
                failure = appendFailure(failure, error);
            }
        }
        return failure;
    }

    private Throwable retireInFlight(RenderHandle handle, Throwable failure) {
        if (handle == null) return failure;
        try {
            resources.retire(handle,
                LwjglRetirementFence.afterCurrentCommands(resources));
        } catch (Throwable error) {
            failure = appendFailure(failure, error);
        }
        return failure;
    }

    private boolean certifySource(int width, int height,
                                  EarlyGlStateTracker.Snapshot state,
                                  FrameStamp stamp) {
        if (certifiedContextGeneration == stamp.getGlContextGeneration()
            && certifiedViewGeneration == stamp.getViewFrustumGeneration()
            && certifiedShaderGeneration == stamp.getShaderPermutationGeneration()
            && certifiedReadFramebuffer == state.getReadFramebuffer()
            && certifiedWidth == width && certifiedHeight == height) {
            return certifiedSource;
        }
        certifiedContextGeneration = stamp.getGlContextGeneration();
        certifiedViewGeneration = stamp.getViewFrustumGeneration();
        certifiedShaderGeneration = stamp.getShaderPermutationGeneration();
        certifiedReadFramebuffer = state.getReadFramebuffer();
        certifiedWidth = width;
        certifiedHeight = height;
        certifiedSource = false;
        Throwable failure = null;
        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, state.getReadFramebuffer());
            int samples = GL11.glGetInteger(0x80A9); // GL_SAMPLES
            int depthBits = GL11.glGetInteger(GL11.GL_DEPTH_BITS);
            boolean complete = state.getReadFramebuffer() == 0
                || GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
                    == GL30.GL_FRAMEBUFFER_COMPLETE;
            certifiedSource = samples == 0 && depthBits > 0 && complete;
            return certifiedSource;
        } catch (Throwable error) {
            failure = error;
            throw error;
        } finally {
            Throwable restoreFailure = null;
            try {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,
                    state.getReadFramebuffer());
            } catch (Throwable error) {
                restoreFailure = appendFailure(restoreFailure, error);
            }
            try {
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,
                    state.getDrawFramebuffer());
            } catch (Throwable error) {
                restoreFailure = appendFailure(restoreFailure, error);
            }
            if (restoreFailure != null) {
                EarlyGlStateTracker.invalidate();
                if (failure != null) {
                    Throwable combined = appendFailure(failure, restoreFailure);
                    if (combined != failure) rethrow(combined);
                }
                else rethrow(restoreFailure);
            }
        }
    }

    private Oracle readSourceOracle(RenderMatrixBridge.Snapshot matrices,
                                    EarlyGlStateTracker.Snapshot state) {
        int total = Math.multiplyExact(lowWidth, lowHeight);
        int count = Math.min(16, total);
        int[] indices = new int[count];
        float[] depths = new float[count];
        FloatBuffer pixel = BufferUtils.createFloatBuffer(1);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,
            state.getReadFramebuffer());
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        for (int i = 0; i < count; i++) {
            int index = count == 1 ? 0
                : (int) (((long) i * (total - 1L)) / (count - 1L));
            int lowX = index % lowWidth;
            int lowY = index / lowWidth;
            int sourceX = Math.min(sourceWidth - 1, lowX * scale + (scale >>> 1));
            int sourceY = Math.min(sourceHeight - 1, lowY * scale + (scale >>> 1));
            pixel.clear();
            GL11.glReadPixels(matrices.getViewportX() + sourceX,
                matrices.getViewportY() + sourceY, 1, 1, GL11.GL_DEPTH_COMPONENT,
                GL11.GL_FLOAT, pixel);
            float value = pixel.get(0);
            if (Float.isNaN(value) || value < 0.0F || value > 1.0F) {
                throw new IllegalStateException("invalid source depth oracle");
            }
            indices[i] = index;
            depths[i] = value;
        }
        return new Oracle(indices, depths);
    }

    private void clearSourceCertification() {
        certifiedContextGeneration = Long.MIN_VALUE;
        certifiedViewGeneration = Long.MIN_VALUE;
        certifiedShaderGeneration = Long.MIN_VALUE;
        certifiedReadFramebuffer = Integer.MIN_VALUE;
        certifiedWidth = -1;
        certifiedHeight = -1;
        certifiedSource = false;
    }

    private static void textureParameters() {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
            GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER,
            GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S,
            org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T,
            org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12_MAX_LEVEL, 0);
    }

    private static final int GL12_BASE_LEVEL = 0x813C;
    private static final int GL12_MAX_LEVEL = 0x813D;

    private static void drawFullscreen() {
        boolean begun = false;
        Throwable failure = null;
        try {
            GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
            begun = true;
            GL11.glVertex2f(-1.0F, -1.0F);
            GL11.glVertex2f(1.0F, -1.0F);
            GL11.glVertex2f(-1.0F, 1.0F);
            GL11.glVertex2f(1.0F, 1.0F);
        } catch (Throwable error) {
            failure = error;
        } finally {
            if (begun) try { GL11.glEnd(); }
            catch (Throwable error) { failure = appendFailure(failure, error); }
        }
        if (failure != null) rethrow(failure);
    }

    private static int compileProgram(final ResourceLedger resources,
                                      String vertexSource,
                                      String fragmentSource,
                                      ProgramBuildState state) {
        return compileProgram(new StageFactory() {
            @Override public TemporaryShaderStage reserve() {
                return TemporaryShaderStage.reserve(resources,
                    LwjglTemporaryResourceOps.DELETE_SHADER);
            }
        }, vertexSource, fragmentSource, state);
    }

    private static int compileProgram(final CacheBudget budget,
                                      String vertexSource,
                                      String fragmentSource,
                                      ProgramBuildState state) {
        return compileProgram(new StageFactory() {
            @Override public TemporaryShaderStage reserve() {
                return TemporaryShaderStage.reserve(budget,
                    LwjglTemporaryResourceOps.DELETE_SHADER);
            }
        }, vertexSource, fragmentSource, state);
    }

    private static int compileProgram(StageFactory stages,
                                      String vertexSource,
                                      String fragmentSource,
                                      ProgramBuildState state) {
        if (stages == null || state == null) throw new IllegalArgumentException(
            "depth reducer shader build dependencies");
        TemporaryShaderStage vertexStage = stages.reserve();
        if (vertexStage == null) throw new IllegalStateException(
            "depth reducer vertex-stage GPU budget exhausted");
        TemporaryShaderStage fragmentStage = stages.reserve();
        if (fragmentStage == null) {
            Throwable cleanup = vertexStage.closeAndAppend(null);
            if (cleanup != null) rethrow(cleanup);
            throw new IllegalStateException(
                "depth reducer fragment-stage GPU budget exhausted");
        }
        int vertex = 0;
        int fragment = 0;
        int linked = 0;
        Throwable failure = null;
        try {
            vertex = compileShader(vertexStage, GL20.GL_VERTEX_SHADER,
                vertexSource);
            fragment = compileShader(fragmentStage, GL20.GL_FRAGMENT_SHADER,
                fragmentSource);
            state.creationAttempted = true;
            linked = GL20.glCreateProgram();
            state.creationReturned = true;
            state.nativeId = linked;
            if (linked <= 0) throw new IllegalStateException(
                "depth reducer program creation failed");
            vertexStage.markAttached();
            GL20.glAttachShader(linked, vertex);
            fragmentStage.markAttached();
            GL20.glAttachShader(linked, fragment);
            GL20.glLinkProgram(linked);
            if (GL20.glGetProgrami(linked, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException("depth reducer link: "
                    + GL20.glGetProgramInfoLog(linked, 4096));
            }
            GL20.glDetachShader(linked, vertex);
            vertexStage.markDetached();
            GL20.glDetachShader(linked, fragment);
            fragmentStage.markDetached();
        } catch (Throwable error) {
            failure = error;
        }
        if (failure == null) {
            failure = fragmentStage.closeAndAppend(failure);
            failure = vertexStage.closeAndAppend(failure);
        }
        if (failure != null && linked > 0) {
            try {
                GL20.glDeleteProgram(linked);
                state.deletionCompleted = true;
                vertexStage.markDetached();
                fragmentStage.markDetached();
            } catch (Throwable error) {
                failure = appendFailure(failure, error);
            }
        }
        failure = fragmentStage.closeAndAppend(failure);
        failure = vertexStage.closeAndAppend(failure);
        if (failure != null) rethrow(failure);
        return linked;
    }

    private static int compileShader(final TemporaryShaderStage stage,
                                     final int type, String source) {
        int shader = stage.create(
            new TemporaryGpuResourceScope.IntAllocator() {
                @Override public int allocate() {
                    return GL20.glCreateShader(type);
                }
            });
        if (shader <= 0) throw new IllegalStateException(
            "depth reducer shader creation failed");
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS)
            == GL11.GL_FALSE) {
            throw new IllegalStateException("depth reducer compile: "
                + GL20.glGetShaderInfoLog(shader, 4096));
        }
        return shader;
    }

    private interface StageFactory {
        TemporaryShaderStage reserve();
    }

    private static final class ProgramBuildState {
        private boolean creationAttempted;
        private boolean creationReturned;
        private boolean deletionCompleted;
        private int nativeId;

        private boolean isKnownAbsent() {
            return !creationAttempted || creationReturned
                && (nativeId <= 0 || deletionCompleted);
        }
    }

    private static long checkedImageBytes(int width, int height) {
        return Math.multiplyExact(Math.multiplyExact((long) width, (long) height), 4L);
    }

    static int ceilDivPositive(int value, int divisor) {
        if (value <= 0 || divisor <= 0) {
            throw new IllegalArgumentException("positive ceil-div operands required");
        }
        return (int) (((long) value + (long) divisor - 1L) / (long) divisor);
    }

    private static Throwable appendFailure(Throwable first, Throwable next) {
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
        throw new IllegalStateException("depth reducer operation failed", failure);
    }

    /** Executable shader/output test used by the capability gate. */
    public static boolean selfTest() {
        return selfTest(null);
    }

    public static boolean selfTest(CacheBudget budget) {
        return selfTestResult(budget).isPassed();
    }

    public static SelfTestResult selfTestResult(CacheBudget budget) {
        if (budget == null) {
            return SelfTestResult.failure(
                "depth reducer self-test GPU budget unavailable", "");
        }
        TemporaryGpuResourceScope scratch =
            new TemporaryGpuResourceScope(budget, 4);
        TemporaryGpuResourceScope.Slot sourceSlot = scratch.reserve(
            RenderResourceKind.TEXTURE, 4L * 4L * 4L,
            LwjglTemporaryResourceOps.DELETE_TEXTURE);
        TemporaryGpuResourceScope.Slot targetSlot = sourceSlot == null
            ? null : scratch.reserve(RenderResourceKind.TEXTURE, 2L * 2L * 4L,
                LwjglTemporaryResourceOps.DELETE_TEXTURE);
        TemporaryGpuResourceScope.Slot framebufferSlot = targetSlot == null
            ? null : scratch.reserveOpaque(RenderResourceKind.FRAMEBUFFER,
                LwjglTemporaryResourceOps.DELETE_FRAMEBUFFER);
        TemporaryGpuResourceScope.Slot programSlot = framebufferSlot == null
            ? null : scratch.reserveOpaque(RenderResourceKind.PROGRAM,
                LwjglTemporaryResourceOps.DELETE_PROGRAM);
        if (programSlot == null) {
            Throwable cleanup = scratch.closeAndAppend(null);
            return cleanup == null
                ? SelfTestResult.failure(
                    "depth reducer self-test GPU budget exhausted", "")
                : SelfTestResult.failure(cleanup);
        }
        int previousProgram = 0;
        int previousReadFramebuffer = 0;
        int previousDrawFramebuffer = 0;
        int previousActiveTexture = GL13.GL_TEXTURE0;
        int previousTexture0 = 0;
        int previousPackBuffer = 0;
        int previousUnpackBuffer = 0;
        int source = 0;
        int target = 0;
        int fbo = 0;
        int testProgram = 0;
        boolean activeTextureCaptured = false;
        boolean stateCaptured = false;
        boolean attributes = false;
        boolean passed = false;
        Throwable failure = null;
        try {
            previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            previousReadFramebuffer = GL11.glGetInteger(
                GL30.GL_READ_FRAMEBUFFER_BINDING);
            previousDrawFramebuffer = GL11.glGetInteger(
                GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            activeTextureCaptured = true;
            previousPackBuffer = GL11.glGetInteger(
                GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            previousUnpackBuffer = GL11.glGetInteger(
                GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            previousTexture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            stateCaptured = true;
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            attributes = true;
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            FloatBuffer depth = BufferUtils.createFloatBuffer(16);
            float[] values = {
                0.1F, 0.2F, 0.3F, 0.4F,
                0.5F, 0.6F, 0.7F, 0.8F,
                0.9F, 0.1F, 0.2F, 0.3F,
                0.4F, 0.5F, 0.6F, 1.0F
            };
            depth.put(values).flip();
            source = sourceSlot.allocate(LwjglTemporaryResourceOps.GEN_TEXTURE);
            if (source <= 0) throw new IllegalStateException(
                "depth reducer self-test source creation failed");
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, source);
            textureParameters();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F,
                4, 4, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
            target = targetSlot.allocate(LwjglTemporaryResourceOps.GEN_TEXTURE);
            if (target <= 0) throw new IllegalStateException(
                "depth reducer self-test target creation failed");
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, target);
            textureParameters();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32F, 2, 2,
                0, GL11.GL_RED, GL11.GL_FLOAT, (ByteBuffer) null);
            fbo = framebufferSlot.allocate(
                LwjglTemporaryResourceOps.GEN_FRAMEBUFFER);
            if (fbo <= 0) throw new IllegalStateException(
                "depth reducer self-test framebuffer creation failed");
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, target, 0);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
                != GL30.GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException(
                    "depth reducer self-test framebuffer incomplete");
            testProgram = programSlot.allocate(
                new TemporaryGpuResourceScope.IntAllocator() {
                    @Override public int allocate() {
                        return compileProgram(budget, VERTEX_SOURCE,
                            FRAGMENT_SOURCE, new ProgramBuildState());
                    }
                });
            GL20.glUseProgram(testProgram);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, source);
            GL20.glUniform1i(GL20.glGetUniformLocation(testProgram, "sourceDepth"), 0);
            GL20.glUniform2i(GL20.glGetUniformLocation(testProgram, "sourceSize"), 4, 4);
            GL20.glUniform1i(GL20.glGetUniformLocation(testProgram, "reductionScale"), 2);
            GL11.glViewport(0, 0, 2, 2);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_DITHER);
            GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
            GL11.glColorMask(true, true, true, true);
            drawFullscreen();
            FloatBuffer result = BufferUtils.createFloatBuffer(4);
            GL11.glReadPixels(0, 0, 2, 2, GL11.GL_RED, GL11.GL_FLOAT, result);
            float[] expected = { 0.6F, 0.8F, 0.9F, 1.0F };
            for (int i = 0; i < expected.length; i++) {
                if (Math.abs(result.get(i) - expected[i]) > 0.00001F) {
                    throw new IllegalStateException(
                        "depth reducer self-test output mismatch");
                }
            }
            passed = true;
        } catch (Throwable error) {
            failure = error;
        } finally {
            failure = scratch.closeAndAppend(failure);
            if (stateCaptured) try { GL20.glUseProgram(previousProgram); }
            catch (Throwable error) { failure = appendFailure(failure, error); }
            if (stateCaptured) try { GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,
                previousPackBuffer); }
            catch (Throwable error) { failure = appendFailure(failure, error); }
            if (stateCaptured) try { GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,
                previousUnpackBuffer); }
            catch (Throwable error) { failure = appendFailure(failure, error); }
            if (stateCaptured) try {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,
                    previousReadFramebuffer);
            } catch (Throwable error) { failure = appendFailure(failure, error); }
            if (stateCaptured) try {
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,
                    previousDrawFramebuffer);
            } catch (Throwable error) { failure = appendFailure(failure, error); }
            if (attributes) try { GL11.glPopAttrib(); }
            catch (Throwable error) { failure = appendFailure(failure, error); }
            if (stateCaptured) try { GL13.glActiveTexture(GL13.GL_TEXTURE0); }
            catch (Throwable error) { failure = appendFailure(failure, error); }
            if (stateCaptured) try {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture0);
            }
            catch (Throwable error) { failure = appendFailure(failure, error); }
            if (activeTextureCaptured) try {
                GL13.glActiveTexture(previousActiveTexture);
            }
            catch (Throwable error) { failure = appendFailure(failure, error); }
        }
        if (failure != null) EarlyGlStateTracker.invalidate();
        FatalErrors.rethrowIfFatal(failure);
        return passed && failure == null ? SelfTestResult.success()
            : failure == null ? SelfTestResult.failure(
                "depth reducer self-test failed", "")
                : SelfTestResult.failure(failure);
    }

    public static final class SelfTestResult {
        private final boolean passed;
        private final String detail;
        private final String exceptionType;

        private SelfTestResult(boolean passed, String detail,
                               String exceptionType) {
            this.passed = passed;
            this.detail = detail == null ? "" : detail;
            this.exceptionType = exceptionType == null ? "" : exceptionType;
        }

        private static SelfTestResult success() {
            return new SelfTestResult(true, "", "");
        }

        private static SelfTestResult failure(String detail,
                                              String exceptionType) {
            return new SelfTestResult(false, detail, exceptionType);
        }

        private static SelfTestResult failure(Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            String message = error == null ? "depth reducer self-test failed"
                : error.getMessage();
            return failure(message == null || message.isEmpty()
                ? error.getClass().getSimpleName() : message,
                error == null ? "" : error.getClass().getName());
        }

        public boolean isPassed() { return passed; }
        public String getDetail() { return detail; }
        public String getExceptionType() { return exceptionType; }
    }

    public static final class Reduction {
        private final int scale;
        private final int width;
        private final int height;
        private final int bytes;
        private final Oracle oracle;
        private Reduction(int scale, int width, int height, int bytes, Oracle oracle) {
            this.scale = scale;
            this.width = width;
            this.height = height;
            this.bytes = bytes;
            this.oracle = oracle;
        }
        public int getScale() { return scale; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public int getBytes() { return bytes; }
        boolean hasOracle() { return oracle != null; }

        boolean validatesOracle(float[] reduced) {
            if (oracle == null) return true;
            for (int i = 0; i < oracle.indices.length; i++) {
                int index = oracle.indices[i];
                if (index < 0 || index >= reduced.length
                    || reduced[index] + 0.000001F < oracle.depths[i]) return false;
            }
            return true;
        }
    }

    private static final class Oracle {
        private final int[] indices;
        private final float[] depths;
        private Oracle(int[] indices, float[] depths) {
            this.indices = indices;
            this.depths = depths;
        }
    }
}
