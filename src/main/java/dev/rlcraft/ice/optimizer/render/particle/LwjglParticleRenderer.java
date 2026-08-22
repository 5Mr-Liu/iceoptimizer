package dev.rlcraft.ice.optimizer.render.particle;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.resource.LwjglRetirementFence;
import dev.rlcraft.ice.optimizer.render.resource.RenderHandle;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import dev.rlcraft.ice.optimizer.render.resource.TemporaryGpuResourceScope;
import dev.rlcraft.ice.optimizer.render.resource.TemporaryShaderStage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBSync;
import org.lwjgl.opengl.ARBDrawInstanced;
import org.lwjgl.opengl.ARBInstancedArrays;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.GLSync;

/**
 * Exact final-corner instanced particle stream. Three orphaning slots are polled
 * with zero-timeout fences; unavailable slots append the original bytes back
 * to ParticleManager's active BufferBuilder in original list order.
 */
public final class LwjglParticleRenderer {
    public enum FlushResult {
        EMPTY, MODERN, LEGACY_APPENDED, FAILED_APPENDED, FAILED_AFTER_DRAW
    }

    private static final int SLOT_COUNT = 3;
    /*
     * Compatibility GLSL assigns conventional inputs to low generic slots;
     * in particular gl_SecondaryColor aliases slot 4 on NVIDIA.  This shader
     * reads that built-in, so all private instance inputs live in the 8..14
     * range guaranteed by OpenGL 2.0's minimum of 16 generic attributes.
     */
    private static final int CORNER_0_ATTRIBUTE = 8;
    private static final int CORNER_1_ATTRIBUTE = 9;
    private static final int CORNER_2_ATTRIBUTE = 10;
    private static final int CORNER_3_ATTRIBUTE = 11;
    private static final int UV_BOUNDS_ATTRIBUTE = 12;
    private static final int COLOR_ATTRIBUTE = 13;
    private static final int LIGHT_ATTRIBUTE = 14;
    private static final String VERTEX_SHADER_130 =
        "#version 130\n"
            + "in vec3 iceCorner0;\n"
            + "in vec3 iceCorner1;\n"
            + "in vec3 iceCorner2;\n"
            + "in vec3 iceCorner3;\n"
            + "in vec4 iceUvBounds;\n"
            + "in vec4 iceColor;\n"
            + "in vec2 iceLight;\n"
            + "void main() {\n"
            + "  int vertex = gl_VertexID & 3;\n"
            + "  vec3 position = vertex == 0 ? iceCorner0"
            + " : (vertex == 1 ? iceCorner1"
            + " : (vertex == 2 ? iceCorner2 : iceCorner3));\n"
            + "  vec2 uv = vertex == 0 ? iceUvBounds.zw"
            + " : (vertex == 1 ? vec2(iceUvBounds.z, iceUvBounds.y)"
            + " : (vertex == 2 ? iceUvBounds.xy"
            + " : vec2(iceUvBounds.x, iceUvBounds.w)));\n"
            + "  vec4 objectPosition = vec4(position, 1.0);\n"
            + "  vec4 eyePosition = gl_ModelViewMatrix * objectPosition;\n"
            + "  gl_Position = gl_ProjectionMatrix * eyePosition;\n"
            + "  gl_ClipVertex = eyePosition;\n"
            + "  gl_TexCoord[0] = gl_TextureMatrix[0] * vec4(uv, 0.0, 1.0);\n"
            + "  gl_TexCoord[1] = gl_TextureMatrix[1] * vec4(iceLight, 0.0, 1.0);\n"
            + "  gl_FrontColor = iceColor;\n"
            + "  gl_BackColor = iceColor;\n"
            + "  gl_FrontSecondaryColor = gl_SecondaryColor;\n"
            + "  gl_BackSecondaryColor = gl_SecondaryColor;\n"
            + "  gl_FogFragCoord = abs(eyePosition.z);\n"
            + "}\n";
    /*
     * NVIDIA's compatibility linker can reject a vertex-only 1.30 program
     * after accepting the deprecated fixed-function varyings at compile time.
     * The 1.20 + EXT_gpu_shader4 form exposes the same gl_VertexID operation
     * while keeping the fixed fragment pipeline in its native language era.
     */
    private static final String VERTEX_SHADER_120 =
        "#version 120\n"
            + "#extension GL_EXT_gpu_shader4 : require\n"
            + VERTEX_SHADER_130.substring(VERTEX_SHADER_130.indexOf('\n') + 1)
                .replace("in vec", "attribute vec");
    private final RenderThreadGuard threadGuard;
    private final ResourceLedger ledger;
    private final CacheBudget budget;
    private final CacheBudget.Reservation directReservation;
    private final ByteBuffer instances;
    private final ByteBuffer legacyVertices;
    private final Slot[] slots = new Slot[SLOT_COUNT];
    private final int maximumInstances;
    private long resourceGeneration;
    private long contextGeneration;
    private ContextCapabilities capabilities;
    private int programId;
    private RenderHandle programHandle;
    private int vertexArrayId;
    private RenderHandle vertexArrayHandle;
    private int instanceCount;
    private int nextSlot;
    private boolean closed;
    private Throwable lastError;
    private long modernFlushes;
    private long legacyFlushes;
    private long fenceBusy;

    public LwjglParticleRenderer(RenderThreadGuard threadGuard,
                                 ResourceLedger ledger, CacheBudget budget,
                                 int maximumInstances) {
        if (threadGuard == null || ledger == null || budget == null) {
            throw new IllegalArgumentException("particle renderer dependencies");
        }
        this.threadGuard = threadGuard;
        this.ledger = ledger;
        this.budget = budget;
        this.maximumInstances = Math.max(64,
            Math.min(131072, maximumInstances));
        int instanceBytes = Math.multiplyExact(this.maximumInstances,
            ParticleGpuInstanceEncoder.BYTES_PER_INSTANCE);
        int legacyBytes = Math.multiplyExact(this.maximumInstances,
            ParticleVertexEncoder.BYTES_PER_QUAD);
        int bytes = Math.addExact(instanceBytes, legacyBytes);
        directReservation = budget.tryReserve(BudgetKind.DIRECT, bytes);
        if (directReservation == null) {
            throw new IllegalStateException("particle direct-memory budget exhausted");
        }
        try {
            instances = BufferUtils.createByteBuffer(instanceBytes)
                .order(ByteOrder.nativeOrder());
            legacyVertices = BufferUtils.createByteBuffer(legacyBytes)
                .order(ByteOrder.nativeOrder());
            for (int index = 0; index < slots.length; index++) {
                slots[index] = new Slot();
            }
        } catch (Throwable error) {
            directReservation.close();
            throw error;
        }
    }

    public void prepare(long resources, long context) {
        threadGuard.check();
        if (closed || resources <= 0L || context <= 0L) {
            throw new IllegalStateException("invalid particle generation");
        }
        if (resourceGeneration == 0L) {
            resourceGeneration = resources;
            contextGeneration = context;
        } else if (resourceGeneration != resources || contextGeneration != context) {
            reset(contextGeneration == context, resources, context);
        }
    }

    public boolean canRecord() {
        return !closed && instanceCount < maximumInstances
            && instances.remaining()
                >= ParticleGpuInstanceEncoder.BYTES_PER_INSTANCE;
    }

    public boolean recordQuad(float x, float y, float z, double[] corners,
                              float minU, float minV, float maxU, float maxV,
                              float red, float green, float blue, float alpha,
                              int lightU, int lightV) {
        threadGuard.check();
        if (!canRecord()) return false;
        int position = instances.position();
        if (!ParticleGpuInstanceEncoder.put(instances, x, y, z, corners,
            minU, minV, maxU, maxV, red, green, blue, alpha,
            lightU, lightV)) {
            instances.position(position);
            return false;
        }
        instanceCount++;
        return true;
    }

    public void rollbackLastQuad() {
        threadGuard.check();
        if (instanceCount <= 0) return;
        instanceCount--;
        instances.position(Math.multiplyExact(instanceCount,
            ParticleGpuInstanceEncoder.BYTES_PER_INSTANCE));
    }

    public int size() { return instanceCount; }
    public Throwable getLastError() { return lastError; }
    public long getModernFlushes() { return modernFlushes; }
    public long getLegacyFlushes() { return legacyFlushes; }
    public long getFenceBusy() { return fenceBusy; }

    public FlushResult flush(ParticleState expected,
                             EarlyGlStateTracker.Snapshot current,
                             BufferBuilder fallback) {
        threadGuard.check();
        lastError = null;
        if (instanceCount == 0) return FlushResult.EMPTY;
        EarlyGlStateTracker.CompatibilitySnapshot compatibility =
            EarlyGlStateTracker.compatibilitySnapshot();
        if (!safe(expected, current, compatibility) || !safeFallback(fallback)) {
            appendTo(fallback);
            legacyFlushes++;
            clear();
            return FlushResult.LEGACY_APPENDED;
        }
        try {
            ensurePipeline();
        } catch (Throwable error) {
            lastError = error;
            clearAndRethrowFatal(error);
            appendTo(fallback);
            legacyFlushes++;
            clear();
            return FlushResult.FAILED_APPENDED;
        }

        Slot slot = acquireSlot();
        if (slot == null) {
            clearAndRethrowFatal(lastError);
            appendTo(fallback);
            legacyFlushes++;
            if (lastError == null) fenceBusy++;
            clear();
            return lastError == null ? FlushResult.LEGACY_APPENDED
                : FlushResult.FAILED_APPENDED;
        }

        boolean issued = false;
        try {
            GL30.glBindVertexArray(vertexArrayId);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, slot.bufferId);
            ByteBuffer upload = view();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, upload, GL15.GL_STREAM_DRAW);
            setupInstanceAttributes();
            GL20.glUseProgram(programId);
            issued = true;
            drawInstanced(instanceCount);
        } catch (Throwable error) {
            lastError = error;
        } finally {
            try { GL20.glUseProgram(current.getProgram()); }
            catch (Throwable restoreError) {
                lastError = restoreFailure(lastError, restoreError);
            }
            try { GL30.glBindVertexArray(compatibility.getVertexArray()); }
            catch (Throwable restoreError) {
                lastError = restoreFailure(lastError, restoreError);
            }
            try { GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, current.getArrayBuffer()); }
            catch (Throwable restoreError) {
                lastError = restoreFailure(lastError, restoreError);
            }
        }

        if (lastError != null) {
            if (!issued) {
                clearAndRethrowFatal(lastError);
                appendTo(fallback);
                legacyFlushes++;
                clear();
                return FlushResult.FAILED_APPENDED;
            }
            finishFailedSubmission(slot);
            return FlushResult.FAILED_AFTER_DRAW;
        }

        try {
            slot.fence = LwjglRetirementFence.tryAfterCurrentCommands(ledger);
            if (slot.fence == null) {
                slot.poisoned = true;
                slot.fence = NeverReadyFence.INSTANCE;
                lastError = new IllegalStateException(
                    "particle Fence creation failed");
            }
        } catch (Throwable error) {
            slot.poisoned = true;
            slot.fence = NeverReadyFence.INSTANCE;
            lastError = error;
        }
        Throwable fenceFailure = lastError;
        modernFlushes++;
        clear();
        FatalErrors.rethrowIfFatal(fenceFailure);
        return lastError == null ? FlushResult.MODERN
            : FlushResult.FAILED_AFTER_DRAW;
    }

    public void discard() {
        threadGuard.check();
        clear();
    }

    public void reset(boolean validContext, long resources, long context) {
        threadGuard.check();
        clear();
        Throwable failure = null;
        for (Slot slot : slots) {
            try {
                if (slot.handle != null && validContext) {
                    // retire() owns the Fence even for a stale/duplicate handle.
                    ResourceLedger.RetirementFence fence = slot.fence;
                    slot.fence = null;
                    ledger.retire(slot.handle, fence);
                } else if (slot.fence != null && validContext) {
                    destroySlotFence(slot);
                } else if (slot.fence != null) {
                    LwjglRetirementFence.abandon(slot.fence);
                }
                if (!validContext && slot.uncertainFence != null) {
                    LwjglRetirementFence.abandon(slot.uncertainFence);
                    slot.uncertainFence = null;
                }
            } catch (Throwable error) {
                failure = appendFailure(failure, error);
            } finally {
                slot.clearReferences(validContext);
            }
        }
        failure = retirePipeline(validContext, failure);
        resourceGeneration = resources;
        contextGeneration = context;
        nextSlot = 0;
        capabilities = null;
        if (failure != null) rethrow(failure);
    }

    public void close(boolean validContext) {
        threadGuard.check();
        if (closed) return;
        Throwable failure = null;
        try {
            reset(validContext, Math.max(1L, resourceGeneration),
                Math.max(1L, contextGeneration));
        } catch (Throwable error) {
            failure = error;
        }
        try { directReservation.close(); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        finally { closed = true; }
        if (failure != null) rethrow(failure);
    }

    private Slot acquireSlot() {
        for (int checked = 0; checked < slots.length; checked++) {
            int index = (nextSlot + checked) % slots.length;
            Slot slot = slots[index];
            if (slot.poisoned) continue;
            if (slot.bufferId == 0) {
                try { createSlot(slot); }
                catch (Throwable error) {
                    slot.poisoned = true;
                    lastError = error;
                    return null;
                }
            }
            if (slot.fence != null) {
                boolean signaled;
                try { signaled = slot.fence.isSignaled(); }
                catch (Throwable error) {
                    slot.poisoned = true;
                    lastError = error;
                    return null;
                }
                if (!signaled) continue;
                try {
                    destroySlotFence(slot);
                } catch (Throwable error) {
                    slot.poisoned = true;
                    lastError = error;
                    return null;
                }
            }
            nextSlot = (index + 1) % slots.length;
            return slot;
        }
        return null;
    }

    private static void destroySlotFence(Slot slot) {
        ResourceLedger.RetirementFence fence = slot.fence;
        slot.fence = null;
        if (fence == null) return;
        try { fence.destroy(); }
        catch (Throwable error) {
            slot.uncertainFence = fence;
            slot.poisoned = true;
            throw error;
        }
    }

    private void createSlot(Slot slot) {
        if (resourceGeneration <= 0L || contextGeneration <= 0L) {
            throw new IllegalStateException("particle generation not prepared");
        }
        CacheBudget.Reservation reservation = ledger.reserveGpu(
            instances.capacity());
        if (reservation == null) {
            throw new IllegalStateException("particle GPU budget exhausted");
        }
        int id = 0;
        boolean allocationReturned = false;
        boolean nativeNameCreated = false;
        try {
            id = GL15.glGenBuffers();
            allocationReturned = true;
            if (id <= 0) {
                throw new IllegalStateException("particle glGenBuffers failed");
            }
            nativeNameCreated = true;
            RenderHandle handle = ledger.registerReserved(
                RenderResourceKind.BUFFER, id, instances.capacity(),
                resourceGeneration, contextGeneration, reservation);
            if (handle == null) {
                throw new IllegalStateException("particle GPU budget exhausted");
            }
            int publishedId = id;
            id = 0;
            nativeNameCreated = false;
            reservation = null;
            slot.bufferId = publishedId;
            slot.handle = handle;
        } catch (Throwable error) {
            Throwable failure = error;
            boolean deleteCompleted = false;
            if (nativeNameCreated) {
                try {
                    GL15.glDeleteBuffers(id);
                    deleteCompleted = true;
                }
                catch (Throwable cleanupFailure) {
                    failure = appendFailure(failure, cleanupFailure);
                }
            }
            // A non-positive returned name proves that no usable object was
            // published.  A throwing allocator or throwing delete is
            // outcome-uncertain and deliberately keeps the reservation
            // poisoned so repeated retries cannot bypass the hard limit.
            boolean noNameCreated = allocationReturned && !nativeNameCreated;
            if (reservation != null && (deleteCompleted || noNameCreated)) {
                try { reservation.close(); }
                catch (Throwable cleanupFailure) {
                    failure = appendFailure(failure, cleanupFailure);
                }
            }
            rethrow(failure);
            return;
        }
    }

    private void ensurePipeline() {
        if (programId != 0 && vertexArrayId != 0
            && programHandle != null && vertexArrayHandle != null) return;
        if (resourceGeneration <= 0L || contextGeneration <= 0L) {
            throw new IllegalStateException("particle generation not prepared");
        }
        ContextCapabilities current = GLContext.getCapabilities();
        if (!supportsInstancing(current)) {
            throw new IllegalStateException("particle instancing prerequisites unavailable");
        }

        int createdProgram = 0;
        int createdVertexArray = 0;
        ProgramBuildState programBuild = new ProgramBuildState();
        boolean vertexArrayAllocationReturned = false;
        CacheBudget.Reservation programReservation =
            ledger.reserveNativeObject(RenderResourceKind.PROGRAM);
        if (programReservation == null) {
            throw new IllegalStateException("particle program budget exhausted");
        }
        CacheBudget.Reservation vertexArrayReservation = null;
        RenderHandle createdProgramHandle = null;
        RenderHandle createdVertexArrayHandle = null;
        Throwable failure = null;
        try {
            createdProgram = createInstancingProgram(budget, programBuild);
            vertexArrayReservation = ledger.reserveNativeObject(
                RenderResourceKind.VERTEX_ARRAY);
            if (vertexArrayReservation == null) {
                throw new IllegalStateException("particle VAO budget exhausted");
            }
            createdVertexArray = GL30.glGenVertexArrays();
            vertexArrayAllocationReturned = true;
            if (createdVertexArray <= 0) {
                throw new IllegalStateException("particle glGenVertexArrays failed");
            }
            createdProgramHandle = ledger.registerReservedObject(
                RenderResourceKind.PROGRAM, createdProgram,
                resourceGeneration, contextGeneration, programReservation);
            if (createdProgramHandle == null) {
                throw new IllegalStateException("particle program budget exhausted");
            }
            createdProgram = 0;
            programReservation = null;
            createdVertexArrayHandle = ledger.registerReservedObject(
                RenderResourceKind.VERTEX_ARRAY, createdVertexArray,
                resourceGeneration, contextGeneration,
                vertexArrayReservation);
            if (createdVertexArrayHandle == null) {
                throw new IllegalStateException("particle VAO budget exhausted");
            }
            int publishedProgram = createdProgramHandle.getNativeId();
            int publishedVertexArray = createdVertexArray;
            createdVertexArray = 0;
            vertexArrayReservation = null;
            programId = publishedProgram;
            vertexArrayId = publishedVertexArray;
            programHandle = createdProgramHandle;
            vertexArrayHandle = createdVertexArrayHandle;
            capabilities = current;
            return;
        } catch (Throwable error) {
            failure = error;
        }

        if (createdVertexArrayHandle != null) {
            try { ledger.retire(createdVertexArrayHandle, null); }
            catch (Throwable error) { failure = appendFailure(failure, error); }
        } else if (createdVertexArray != 0) {
            boolean deleted = false;
            try {
                GL30.glDeleteVertexArrays(createdVertexArray);
                deleted = true;
            } catch (Throwable error) {
                failure = appendFailure(failure, error);
            }
            if (deleted && vertexArrayReservation != null) try {
                vertexArrayReservation.close();
                vertexArrayReservation = null;
            } catch (Throwable error) {
                failure = appendFailure(failure, error);
            }
        } else if (vertexArrayAllocationReturned
            && vertexArrayReservation != null) {
            try { vertexArrayReservation.close(); }
            catch (Throwable error) {
                failure = appendFailure(failure, error);
            }
        }
        if (createdProgramHandle != null) {
            try { ledger.retire(createdProgramHandle, null); }
            catch (Throwable error) { failure = appendFailure(failure, error); }
        } else if (createdProgram != 0) {
            boolean deleted = false;
            try {
                GL20.glDeleteProgram(createdProgram);
                deleted = true;
            } catch (Throwable error) {
                failure = appendFailure(failure, error);
            }
            if (deleted && programReservation != null) try {
                programReservation.close();
                programReservation = null;
            } catch (Throwable error) {
                failure = appendFailure(failure, error);
            }
        } else if (programBuild.isKnownAbsent()
            && programReservation != null) {
            try { programReservation.close(); }
            catch (Throwable error) {
                failure = appendFailure(failure, error);
            }
        }
        rethrow(failure);
    }

    static boolean supportsInstancing(ContextCapabilities value) {
        return value != null && value.OpenGL20 && value.OpenGL30
            && (value.OpenGL31 || value.GL_ARB_draw_instanced)
            && (value.OpenGL33 || value.GL_ARB_instanced_arrays);
    }

    static int createInstancingProgram(CacheBudget budget,
                                       ProgramBuildState programBuild) {
        if (budget == null || programBuild == null) {
            throw new IllegalArgumentException(
                "particle shader build dependencies");
        }
        TemporaryShaderStage stage = TemporaryShaderStage.reserve(budget,
            new TemporaryGpuResourceScope.IntDestroyer() {
                @Override public void destroy(int id) {
                    GL20.glDeleteShader(id);
                }
            });
        if (stage == null) throw new IllegalStateException(
            "particle shader-stage GPU budget exhausted");
        int shader = 0;
        int program = 0;
        Throwable failure = null;
        try {
            shader = stage.create(new TemporaryGpuResourceScope.IntAllocator() {
                @Override public int allocate() {
                    return GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
                }
            });
            if (shader <= 0) throw new IllegalStateException(
                "particle vertex shader creation failed");
            ContextCapabilities current = GLContext.getCapabilities();
            String source = instancingVertexShader(current != null
                && current.GL_EXT_gpu_shader4);
            GL20.glShaderSource(shader, source);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
                throw new IllegalStateException("particle vertex shader compile: "
                    + compactLog(GL20.glGetShaderInfoLog(shader, 8192)));
            }
            programBuild.creationAttempted = true;
            program = GL20.glCreateProgram();
            programBuild.creationReturned = true;
            programBuild.nativeId = program;
            if (program <= 0) throw new IllegalStateException(
                "particle program creation failed");
            // Mark first: a throwing attach has an outcome-uncertain
            // reference from the program to this stage.
            stage.markAttached();
            GL20.glAttachShader(program, shader);
            GL20.glBindAttribLocation(program, CORNER_0_ATTRIBUTE, "iceCorner0");
            GL20.glBindAttribLocation(program, CORNER_1_ATTRIBUTE, "iceCorner1");
            GL20.glBindAttribLocation(program, CORNER_2_ATTRIBUTE, "iceCorner2");
            GL20.glBindAttribLocation(program, CORNER_3_ATTRIBUTE, "iceCorner3");
            GL20.glBindAttribLocation(program, UV_BOUNDS_ATTRIBUTE, "iceUvBounds");
            GL20.glBindAttribLocation(program, COLOR_ATTRIBUTE, "iceColor");
            GL20.glBindAttribLocation(program, LIGHT_ATTRIBUTE, "iceLight");
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
                throw new IllegalStateException("particle program link: "
                    + compactLog(GL20.glGetProgramInfoLog(program, 8192)));
            }
            GL20.glDetachShader(program, shader);
            stage.markDetached();
        } catch (Throwable error) {
            failure = error;
        }
        if (failure == null) failure = stage.closeAndAppend(null);
        if (failure != null && program > 0) {
            try {
                GL20.glDeleteProgram(program);
                programBuild.deletionCompleted = true;
                stage.markDetached();
            } catch (Throwable cleanupFailure) {
                failure = appendFailure(failure, cleanupFailure);
            }
        }
        failure = stage.closeAndAppend(failure);
        if (failure != null) rethrow(failure);
        return program;
    }

    static String instancingVertexShader(boolean compatibility120) {
        return compatibility120 ? VERTEX_SHADER_120 : VERTEX_SHADER_130;
    }

    static final class ProgramBuildState {
        private boolean creationAttempted;
        private boolean creationReturned;
        private boolean deletionCompleted;
        private int nativeId;

        private boolean isKnownAbsent() {
            return !creationAttempted || creationReturned
                && (nativeId <= 0 || deletionCompleted);
        }
    }

    private void setupInstanceAttributes() {
        setupInstanceAttributes(capabilities);
    }

    static void setupInstanceAttributes(ContextCapabilities value) {
        int stride = ParticleGpuInstanceEncoder.BYTES_PER_INSTANCE;
        attribute(value, CORNER_0_ATTRIBUTE, 3, GL11.GL_FLOAT, false, stride,
            ParticleGpuInstanceEncoder.POSITION_0);
        attribute(value, CORNER_1_ATTRIBUTE, 3, GL11.GL_FLOAT, false, stride,
            ParticleGpuInstanceEncoder.POSITION_1);
        attribute(value, CORNER_2_ATTRIBUTE, 3, GL11.GL_FLOAT, false, stride,
            ParticleGpuInstanceEncoder.POSITION_2);
        attribute(value, CORNER_3_ATTRIBUTE, 3, GL11.GL_FLOAT, false, stride,
            ParticleGpuInstanceEncoder.POSITION_3);
        attribute(value, UV_BOUNDS_ATTRIBUTE, 4, GL11.GL_FLOAT, false, stride,
            ParticleGpuInstanceEncoder.UV_BOUNDS);
        attribute(value, COLOR_ATTRIBUTE, 4, GL11.GL_UNSIGNED_BYTE, true, stride,
            ParticleGpuInstanceEncoder.COLOR);
        attribute(value, LIGHT_ATTRIBUTE, 2, GL11.GL_SHORT, false, stride,
            ParticleGpuInstanceEncoder.LIGHT);
    }

    private static void attribute(ContextCapabilities value, int index,
                                  int size, int type, boolean normalized,
                                  int stride, long offset) {
        GL20.glEnableVertexAttribArray(index);
        GL20.glVertexAttribPointer(index, size, type, normalized, stride, offset);
        if (value.OpenGL33) GL33.glVertexAttribDivisor(index, 1);
        else ARBInstancedArrays.glVertexAttribDivisorARB(index, 1);
    }

    private void drawInstanced(int count) {
        drawInstanced(capabilities, count);
    }

    static void drawInstanced(ContextCapabilities value, int count) {
        if (value.OpenGL31) {
            GL31.glDrawArraysInstanced(GL11.GL_QUADS, 0, 4, count);
        } else {
            ARBDrawInstanced.glDrawArraysInstancedARB(GL11.GL_QUADS, 0, 4,
                count);
        }
    }

    private void quarantineSubmitted(Slot slot) {
        slot.poisoned = true;
        slot.fence = NeverReadyFence.INSTANCE;
        if (FatalErrors.findFatal(lastError) != null) return;
        try {
            LwjglRetirementFence value =
                LwjglRetirementFence.tryAfterCurrentCommands(ledger);
            slot.fence = value == null ? NeverReadyFence.INSTANCE : value;
            if (value == null && lastError != null) {
                lastError.addSuppressed(new IllegalStateException(
                    "particle Fence creation failed after uncertain draw"));
            }
        } catch (Throwable fenceError) {
            slot.fence = NeverReadyFence.INSTANCE;
            lastError = appendFailure(lastError, fenceError);
        }
    }

    private void finishFailedSubmission(Slot slot) {
        quarantineSubmitted(slot);
        Throwable submittedFailure = lastError;
        modernFlushes++;
        clear();
        FatalErrors.rethrowIfFatal(submittedFailure);
    }

    private void clearAndRethrowFatal(Throwable failure) {
        if (FatalErrors.findFatal(failure) == null) return;
        clear();
        FatalErrors.rethrowIfFatal(failure);
    }

    private Throwable retirePipeline(boolean validContext, Throwable failure) {
        RenderHandle vaoHandle = vertexArrayHandle;
        RenderHandle shaderHandle = programHandle;
        int vao = vertexArrayId;
        int program = programId;
        vertexArrayHandle = null;
        programHandle = null;
        vertexArrayId = 0;
        programId = 0;
        if (!validContext) return failure;
        try {
            if (vaoHandle != null) ledger.retire(vaoHandle,
                LwjglRetirementFence.afterCurrentCommands(ledger));
            else if (vao != 0) GL30.glDeleteVertexArrays(vao);
        } catch (Throwable error) {
            failure = appendFailure(failure, error);
        }
        try {
            if (shaderHandle != null) ledger.retire(shaderHandle,
                LwjglRetirementFence.afterCurrentCommands(ledger));
            else if (program != 0) GL20.glDeleteProgram(program);
        } catch (Throwable error) {
            failure = appendFailure(failure, error);
        }
        return failure;
    }

    private void appendTo(BufferBuilder fallback) {
        if (!safeFallback(fallback)) {
            throw new IllegalStateException("particle legacy buffer unavailable");
        }
        legacyVertices.clear();
        for (int index = 0; index < instanceCount; index++) {
            ParticleGpuInstanceEncoder.appendLegacyQuad(instances, index,
                legacyVertices);
        }
        legacyVertices.flip();
        fallback.putBulkData(legacyVertices);
    }

    private ByteBuffer view() {
        ByteBuffer value = instances.duplicate().order(ByteOrder.nativeOrder());
        value.position(0);
        value.limit(Math.multiplyExact(instanceCount,
            ParticleGpuInstanceEncoder.BYTES_PER_INSTANCE));
        return value;
    }

    private void clear() {
        instanceCount = 0;
        instances.clear();
        legacyVertices.clear();
    }

    private static boolean safe(ParticleState expected,
                                EarlyGlStateTracker.Snapshot current,
                                EarlyGlStateTracker.CompatibilitySnapshot compatibility) {
        if (expected == null || current == null || compatibility == null
            || !compatibility.isVertexArraySupported()
            || !current.hasParticleState() || !current.hasArrayBufferBinding()
            || expected.getProgram() != 0 || current.getProgram() != 0
            || compatibility.getProgram() != current.getProgram()
            || compatibility.getArrayBuffer() != current.getArrayBuffer()
            || compatibility.getActiveTexture() != current.getActiveTexture()
            || compatibility.getClientActiveTexture()
                != current.getClientActiveTexture()
            || compatibility.getTexture2d(0) != current.getTexture0()
            || compatibility.getTexture2d(1) != current.getTexture1()
            || compatibility.isTexture2dEnabled(0)
                != current.isTexture0Enabled()
            || compatibility.isTexture2dEnabled(1)
                != current.isTexture1Enabled()) {
            return false;
        }
        for (int unit = 2; unit < compatibility.getTextureUnitCount(); unit++) {
            if (compatibility.isTexture2dEnabled(unit)) return false;
        }
        return current.hasArrayBufferBinding()
            && current.isTexture0Enabled() && current.isTexture1Enabled()
            && current.getTexture0() > 0 && current.getTexture1() > 0
            && expected.getTexture() == current.getTexture0()
            && expected.getLightmapTexture() == current.getTexture1()
            && expected.getProgram() == current.getProgram()
            && expected.isBlend() == current.isBlend()
            && expected.getBlendSource() == current.getBlendSourceRgb()
            && expected.getBlendDestination() == current.getBlendDestinationRgb()
            && expected.getBlendSourceAlpha() == current.getBlendSourceAlpha()
            && expected.getBlendDestinationAlpha()
                == current.getBlendDestinationAlpha()
            && expected.isDepthTest() == current.isDepthTest()
            && expected.isDepthMask() == current.isDepthMask()
            && expected.isCull() == current.isCull()
            && !current.isLighting()
            && expected.getColorMask() == current.getColorMask();
    }

    public static boolean safeFallback(BufferBuilder fallback) {
        return fallback != null && fallback.getDrawMode() == GL11.GL_QUADS
            && fallback.getVertexFormat()
                == DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP
            && fallback.getVertexFormat().getSize()
                == ParticleVertexEncoder.BYTES_PER_VERTEX;
    }

    private static String compactLog(String value) {
        if (value == null || value.length() == 0) return "no driver log";
        String compact = value.replace('\r', ' ').replace('\n', ' ').trim();
        return compact.length() <= 4096 ? compact : compact.substring(0, 4096);
    }

    private static Throwable appendFailure(Throwable first, Throwable next) {
        if (first == null) return next;
        Throwable nextFatal = FatalErrors.findFatal(next);
        if (nextFatal != null && FatalErrors.findFatal(first) == null) {
            if (first != nextFatal) nextFatal.addSuppressed(first);
            return nextFatal;
        }
        if (first != next) first.addSuppressed(next);
        return first;
    }

    static Throwable restoreFailure(Throwable first, Throwable next) {
        EarlyGlStateTracker.invalidate();
        return appendFailure(first, next);
    }

    private static void rethrow(Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("particle renderer cleanup failed", failure);
    }

    private static final class Slot {
        private int bufferId;
        private RenderHandle handle;
        private ResourceLedger.RetirementFence fence;
        private ResourceLedger.RetirementFence uncertainFence;
        private boolean poisoned;
        private void clearReferences(boolean preserveUncertain) {
            bufferId = 0;
            handle = null;
            fence = null;
            if (!preserveUncertain) uncertainFence = null;
            poisoned = preserveUncertain && uncertainFence != null;
        }
    }

    private enum NeverReadyFence implements ResourceLedger.RetirementFence {
        INSTANCE;
        @Override public boolean isSignaled() { return false; }
        @Override public void destroy() { }
    }

}
