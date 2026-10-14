package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.LwjglRetirementFence;
import dev.rlcraft.ice.optimizer.render.resource.RenderHandle;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import dev.rlcraft.ice.optimizer.render.resource.TemporaryShaderStage;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLContext;

/**
 * GPU-only current-view occlusion. Actual opaque faces write depth only for
 * fully covered coarse cells. Parallel queries test outer mesh bounds;
 * the final compute stage changes only each ordered command's instance count.
 * No visibility result or depth survives the next cull call.
 */
public final class LwjglTerrainGpuVisibility {
    public static final int MAX_CANDIDATES = 4096;
    public static final int MAX_FACES = 2048;
    public static final int BOUNDS_BYTES = 64;
    public static final int FACE_BYTES = 128;
    private static final int WIDTH = 160, HEIGHT = 90;
    private static final int DEPTH_CELLS = WIDTH * HEIGHT + 20 * 12;
    private static final int DEPTH_BYTES = (DEPTH_CELLS + MAX_CANDIDATES + MAX_FACES * 24) * 4;
    private final ResourceLedger ledger;
    private final CacheBudget budget;
    private final CacheBudget.Reservation[] reservedStorage = new CacheBudget.Reservation[3];
    private final DoubleBuffer matrix = BufferUtils.createDoubleBuffer(16);
    private final int[] previousBuffers = new int[4];
    private final long[] previousStarts = new long[4];
    private final long[] previousSizes = new long[4];
    private RenderHandle program, boundsBuffer, faceBuffer, depthBuffer;
    private int stageLocation, matrixLocation, candidateLocation;
    private int faceLocation, countLocation, baseLocation, strideLocation;
    private long submittedFrames, submittedCandidates, submittedFaces;

    public LwjglTerrainGpuVisibility(ResourceLedger ledger, CacheBudget budget) {
        this.ledger = ledger;
        this.budget = budget;
        // Reserve the fixed execution workspace before terrain cache uploads
        // consume the shared budget. Native objects are still created lazily.
        reserveStorage();
    }

    /**
     * Bounds: two camera-relative dvec4s per candidate, min.w=1 for known
     * geometry and zero otherwise. Faces: four camera-relative dvec4 corners.
     * baseInstance is the candidate index. The caller owns both direct inputs.
     */
    public boolean cull(FrameStamp stamp, float[] model, float[] projection,
                        ByteBuffer bounds, int candidates, ByteBuffer faces, int faceCount,
                        int commandBuffer, int commandBaseBytes, int strideBytes, int commands) {
        if (!GLContext.getCapabilities().OpenGL43) return false;
        if (candidates == 0 || commands == 0) return true;
        if (candidates < 0 || candidates > MAX_CANDIDATES || faceCount < 0 || faceCount > MAX_FACES
            || bounds.remaining() != candidates * BOUNDS_BYTES || faces.remaining() != faceCount * FACE_BYTES
            || (commandBaseBytes & 3) != 0 || strideBytes < 16 || (strideBytes & 3) != 0) {
            throw new IllegalArgumentException("terrain visibility packet layout");
        }
        int savedProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int savedStorage = GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        for (int binding = 0; binding < 4; binding++) {
            int id = GL30.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, binding);
            previousBuffers[binding] = id;
            if (id != 0) {
                previousStarts[binding] = GL32.glGetInteger64(GL43.GL_SHADER_STORAGE_BUFFER_START, binding);
                previousSizes[binding] = GL32.glGetInteger64(GL43.GL_SHADER_STORAGE_BUFFER_SIZE, binding);
            }
        }
        Throwable failure = null;
        boolean complete = false;
        try {
            if (program == null && !initialize(stamp)) return false;
            upload(boundsBuffer, bounds);
            // An empty source list still writes fresh visible commands; no
            // previous frame's depth is allowed to influence this frame.
            if (faceCount > 0) upload(faceBuffer, faces);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, commandBuffer);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, boundsBuffer.getNativeId());
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, faceBuffer.getNativeId());
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 3, depthBuffer.getNativeId());
            GL20.glUseProgram(program.getNativeId());
            EarlyGlStateTracker.useProgram(program.getNativeId());
            uniformMatrix(model, projection);
            GL20.glUniform1i(candidateLocation, candidates);
            GL20.glUniform1i(faceLocation, faceCount);
            GL20.glUniform1i(countLocation, commands);
            GL20.glUniform1i(baseLocation, commandBaseBytes / 4);
            GL20.glUniform1i(strideLocation, strideBytes / 4);
            dispatch(0, (DEPTH_CELLS + 63) / 64);
            if (faceCount > 0) dispatch(1, (faceCount * 16 + 63) / 64);
            dispatch(3, (candidates + 63) / 64);
            GL20.glUniform1i(stageLocation, 4);
            GL43.glDispatchCompute((commands + 63) / 64, 1, 1);
            GL42.glMemoryBarrier(GL42.GL_COMMAND_BARRIER_BIT | GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            submittedFrames++;
            submittedCandidates += candidates;
            submittedFaces += faceCount;
            complete = true;
        } catch (Throwable problem) { failure = problem; }
        finally {
            for (int binding = 0; binding < 4; binding++) {
                try {
                    if (previousBuffers[binding] != 0 && previousSizes[binding] != 0L) {
                        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, binding,
                            previousBuffers[binding], previousStarts[binding], previousSizes[binding]);
                    } else GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, previousBuffers[binding]);
                } catch (Throwable problem) { failure = append(failure, problem); }
            }
            try { GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, savedStorage); }
            catch (Throwable problem) { failure = append(failure, problem); }
            try {
                GL20.glUseProgram(savedProgram);
                EarlyGlStateTracker.useProgram(savedProgram);
            } catch (Throwable problem) {
                EarlyGlStateTracker.invalidate();
                failure = append(failure, problem);
            }
            if (failure != null) rethrow(failure);
        }
        if (failure != null) rethrow(failure);
        return complete;
    }

    private void dispatch(int stage, int groups) {
        GL20.glUniform1i(stageLocation, stage);
        GL43.glDispatchCompute(groups, 1, 1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }

    private void uniformMatrix(float[] model, float[] projection) {
        matrix.clear();
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                double value = 0.0;
                for (int k = 0; k < 4; k++) value += (double)projection[k * 4 + row] * model[column * 4 + k];
                matrix.put(value);
            }
        }
        matrix.flip();
        GL40.glUniformMatrix4(matrixLocation, false, matrix);
    }

    private static void upload(RenderHandle handle, ByteBuffer bytes) {
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, handle.getNativeId());
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, bytes, GL15.GL_STREAM_DRAW);
    }

    private boolean initialize(FrameStamp stamp) {
        if (!reserveStorage()) return false;
        RenderHandle nextProgram = null, nextBounds = null, nextFaces = null, nextDepth = null;
        Throwable failure = null;
        boolean published = false;
        try {
            nextProgram = create(RenderResourceKind.PROGRAM,
                ResourceLedger.nativeObjectCharge(RenderResourceKind.PROGRAM), stamp);
            if (nextProgram == null) return false;
            compile(nextProgram.getNativeId());
            nextBounds = createStorage(0, MAX_CANDIDATES * BOUNDS_BYTES, stamp);
            if (nextBounds == null) return false;
            nextFaces = createStorage(1, MAX_FACES * FACE_BYTES, stamp);
            if (nextFaces == null) return false;
            nextDepth = createStorage(2, DEPTH_BYTES, stamp);
            if (nextDepth == null) return false;
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, nextDepth.getNativeId());
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, DEPTH_BYTES, GL15.GL_DYNAMIC_DRAW);
            // Empty frames need a legal bound face store although no workgroup reads it.
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, nextFaces.getNativeId());
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, FACE_BYTES, GL15.GL_STREAM_DRAW);
            program = nextProgram; boundsBuffer = nextBounds; faceBuffer = nextFaces; depthBuffer = nextDepth;
            int id = program.getNativeId();
            stageLocation = GL20.glGetUniformLocation(id, "stage");
            matrixLocation = GL20.glGetUniformLocation(id, "viewProjection");
            candidateLocation = GL20.glGetUniformLocation(id, "candidateCount");
            faceLocation = GL20.glGetUniformLocation(id, "faceCount");
            countLocation = GL20.glGetUniformLocation(id, "commandCount");
            baseLocation = GL20.glGetUniformLocation(id, "commandBase");
            strideLocation = GL20.glGetUniformLocation(id, "commandStride");
            published = true;
        } catch (Throwable problem) { failure = problem; }
        finally {
            if (!published) {
                program = boundsBuffer = faceBuffer = depthBuffer = null;
                for (RenderHandle handle : new RenderHandle[] {nextProgram, nextBounds, nextFaces, nextDepth}) {
                    if (handle != null) try { ledger.retire(handle, null); }
                    catch (Throwable problem) { failure = append(failure, problem); }
                }
            }
        }
        if (failure != null) rethrow(failure);
        return published;
    }

    private RenderHandle create(RenderResourceKind kind, long bytes, FrameStamp stamp) {
        CacheBudget.Reservation reservation = ledger.reserveGpu(bytes);
        if (reservation == null) return null;
        return create(kind, bytes, stamp, reservation);
    }

    private boolean reserveStorage() {
        int[] sizes = {MAX_CANDIDATES * BOUNDS_BYTES, MAX_FACES * FACE_BYTES, DEPTH_BYTES};
        boolean[] added = new boolean[3];
        for (int i = 0; i < sizes.length; i++) {
            if (reservedStorage[i] == null) {
                reservedStorage[i] = budget.tryReserve(BudgetKind.GPU, sizes[i]);
                added[i] = reservedStorage[i] != null;
            }
            if (reservedStorage[i] == null) {
                for (int j = 0; j < i; j++) if (added[j]) {
                    reservedStorage[j].close();
                    reservedStorage[j] = null;
                }
                return false;
            }
        }
        return true;
    }

    private RenderHandle createStorage(int index, int bytes, FrameStamp stamp) {
        CacheBudget.Reservation reservation = reservedStorage[index];
        reservedStorage[index] = null;
        return create(RenderResourceKind.BUFFER, bytes, stamp, reservation);
    }

    private RenderHandle create(RenderResourceKind kind, long bytes, FrameStamp stamp,
                                CacheBudget.Reservation reservation) {
        int id = 0;
        try {
            id = kind == RenderResourceKind.PROGRAM ? GL20.glCreateProgram() : GL15.glGenBuffers();
            if (id == 0) throw new IllegalStateException("terrain visibility allocation");
            RenderHandle handle = ledger.registerReserved(kind, id, bytes,
                stamp.getResourceGeneration(), stamp.getGlContextGeneration(), reservation);
            if (handle == null) throw new IllegalStateException("terrain visibility resource capacity");
            return handle;
        } catch (Throwable failure) {
            if (id != 0) {
                if (kind == RenderResourceKind.PROGRAM) GL20.glDeleteProgram(id); else GL15.glDeleteBuffers(id);
            }
            reservation.close();
            rethrow(failure);
            return null;
        }
    }

    private void compile(int nativeProgram) {
        TemporaryShaderStage shader = TemporaryShaderStage.reserve(ledger, GL20::glDeleteShader);
        if (shader == null) throw new IllegalStateException("terrain visibility shader budget");
        int id = 0;
        boolean attached = false;
        Throwable failure = null;
        try {
            id = shader.create(() -> GL20.glCreateShader(GL43.GL_COMPUTE_SHADER));
            GL20.glShaderSource(id, TerrainGpuVisibilityShader.source());
            GL20.glCompileShader(id);
            if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == 0) {
                throw new IllegalStateException("terrain visibility shader: " + GL20.glGetShaderInfoLog(id, 8192));
            }
            GL20.glAttachShader(nativeProgram, id);
            shader.markAttached(); attached = true;
            GL20.glLinkProgram(nativeProgram);
            if (GL20.glGetProgrami(nativeProgram, GL20.GL_LINK_STATUS) == 0) {
                throw new IllegalStateException("terrain visibility program: " + GL20.glGetProgramInfoLog(nativeProgram, 8192));
            }
        } catch (Throwable problem) { failure = problem; }
        finally {
            if (attached) try { GL20.glDetachShader(nativeProgram, id); shader.markDetached(); }
            catch (Throwable problem) { failure = append(failure, problem); }
            failure = shader.closeAndAppend(failure);
        }
        if (failure != null) rethrow(failure);
    }

    public void reset(boolean validContext) {
        RenderHandle[] handles = {program, boundsBuffer, faceBuffer, depthBuffer};
        program = boundsBuffer = faceBuffer = depthBuffer = null;
        Throwable failure = null;
        if (validContext) for (RenderHandle handle : handles) {
            if (handle != null) try { ledger.retire(handle, LwjglRetirementFence.afterCurrentCommands(ledger)); }
            catch (Throwable problem) { failure = append(failure, problem); }
        }
        if (failure != null) rethrow(failure);
    }

    public void close(boolean validContext) {
        reset(validContext);
        for (int i = 0; i < reservedStorage.length; i++) {
            if (reservedStorage[i] != null) { reservedStorage[i].close(); reservedStorage[i] = null; }
        }
    }
    public long getSubmittedFrames() { return submittedFrames; }
    public long getSubmittedCandidates() { return submittedCandidates; }
    public long getSubmittedFaces() { return submittedFaces; }

    private static Throwable append(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }

    private static void rethrow(Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("terrain GPU visibility", failure);
    }
}
