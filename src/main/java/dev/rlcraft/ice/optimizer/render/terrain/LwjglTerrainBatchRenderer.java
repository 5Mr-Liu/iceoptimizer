package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyModelLightingState;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.LwjglRetirementFence;
import dev.rlcraft.ice.optimizer.render.resource.RenderHandle;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import dev.rlcraft.ice.optimizer.render.resource.TemporaryGpuResourceScope;
import dev.rlcraft.ice.optimizer.render.resource.TemporaryShaderStage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.NVBindlessMultiDrawIndirect;
import org.lwjgl.opengl.NVShaderBufferLoad;
import org.lwjgl.opengl.NVVertexBufferUnifiedMemory;

/**
 * A whole-layer command/offset stream. One orphaned upload replaces per-region
 * uploads and fences. The driver keeps previous backing storage alive; only
 * destruction of the GL object needs the ledger's retirement fence.
 *
 * The vertex-only compatibility program retains the fixed fragment pipeline:
 * atlas/lightmap texture environments, alpha testing, blending and fog colour.
 */
final class LwjglTerrainBatchRenderer {
    static final int POSITION = 8, COLOR = 9, UV = 10, LIGHT = 11, OFFSET = 12;
    static final int BYTES_PER_MESH = 16 + 7 * 16;
    static final int BINDLESS_COMMAND_BYTES = 16 + 4 * 24;
    private static final int FOG_DISTANCE_MODE_NV = 0x855A;
    private static final int EYE_RADIAL_NV = 0x855B;
    private static final int EYE_PLANE_NV = 0x2502;

    private final ResourceLedger ledger;
    private final CacheBudget budget;
    private RenderHandle program, stream, residentOffsets, unifiedVao;
    private long offsetAddress;
    private boolean crossPage;
    private final ArrayList<Page> pages = new ArrayList<Page>();
    private FrameStamp preparedStamp;
    private boolean nativeRun;
    private int boundPageVao;
    private ByteBuffer bytes;
    private CacheBudget.Reservation directReservation;
    private int capacity;
    private int commandBase;
    private int commandCount;
    private Page commandPage;
    private boolean bindlessCommands;
    private int fogModeLocation;
    private int fogMode;
    private boolean attempted;
    private boolean disabled;
    private boolean nvFogDistance;
    private boolean submissionStarted;
    private boolean restorationFailed;
    private Throwable failure;
    private String detail = "not-initialized";
    private final LayerState original = new LayerState();
    private final int[] lightingState = new int[EarlyModelLightingState.SIZE];

    LwjglTerrainBatchRenderer(ResourceLedger ledger, CacheBudget budget) {
        this.ledger = ledger;
        this.budget = budget;
    }

    /** Allocate the layer working set before terrain cache pages consume the
     * shared budget. This capacity remains growable and does not select draws. */
    boolean reserveWorkspace(int meshes, FrameStamp stamp) {
        if (disabled) return false;
        try {
            if (!attempted && !initialize(stamp)) return false;
            EarlyGlStateTracker.CompatibilitySnapshot tracked = EarlyGlStateTracker.compatibilitySnapshot();
            int array = EarlyGlStateTracker.arrayBufferBinding();
            original.arrayBuffer = array != Integer.MIN_VALUE ? array
                : GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            original.vertexArray = tracked != null && tracked.isVertexArraySupported()
                ? tracked.getVertexArray() : GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            return ensureCapacity(meshes, stamp);
        } catch (Throwable error) {
            disabled = true;
            failure = append(failure, error);
            detail = "workspace-initialization-failed: " + error.toString();
            FatalErrors.rethrowIfFatal(error);
            return false;
        }
    }

    boolean prepare(int meshes, FrameStamp stamp,
                    EarlyGlStateTracker.CompatibilitySnapshot state,
                    int savedArrayBuffer, boolean inspectFaces) {
        submissionStarted = false;
        restorationFailed = false;
        commandCount = 0;
        commandPage = null;
        bindlessCommands = false;
        if (disabled) return false;
        // The vertex stage reproduces the normal terrain (unlit vertex-color)
        // pipeline. Unexpected lighting or custom shaders retain the old path.
        try {
            if (!attempted && !initialize(stamp)) return false;
            if (disabled) return false;
            // Foreign renderer boundaries intentionally invalidate ICE's mirror.
            // Query only this draw's bindings once per layer in that case.
            original.capture(state, savedArrayBuffer, inspectFaces);
            boolean lighting = EarlyModelLightingState.copyTo(lightingState)
                ? lightingState[EarlyModelLightingState.LIGHTING] != 0
                : GL11.glIsEnabled(GL11.GL_LIGHTING);
            if (original.program != 0 || lighting) {
                detail = "custom-program-or-fixed-lighting";
                return false;
            }
            if (!ensureCapacity(meshes, stamp)) { detail = "stream-budget"; return false; }
            preparedStamp = stamp;
            commandBase = meshes * 16;
            bytes.clear();
            fogMode = 0;
            if (nvFogDistance) {
                int mode = EarlyGlStateTracker.fogDistanceMode();
                if (mode == 0) {
                    mode = GL11.glGetInteger(FOG_DISTANCE_MODE_NV);
                    EarlyGlStateTracker.fogi(FOG_DISTANCE_MODE_NV, mode);
                }
                fogMode = mode == EYE_RADIAL_NV ? 1 : mode == EYE_PLANE_NV ? 2 : 0;
            }
            detail = crossPage ? "ready-nv-bindless" : "ready-gl43";
            return true;
        } catch (Throwable error) {
            disabled = true;
            failure = append(failure, error);
            detail = "initialization-failed: " + error.toString();
            FatalErrors.rethrowIfFatal(error);
            return false;
        }
    }

    void offset(int mesh, float x, float y, float z) {
        offset(mesh, x, y, z, 1.0F);
    }

    void offset(int mesh, float x, float y, float z, float modelScale) {
        int base = mesh * 16;
        bytes.putFloat(base, x);
        bytes.putFloat(base + 4, y);
        bytes.putFloat(base + 8, z);
        bytes.putFloat(base + 12, modelScale);
    }

    void command(int count, int first, int mesh) {
        // The arena bounds the layer and emits at most seven nonempty faces
        // per mesh; its reserved stream already covers every command.
        putCommand(bytes, commandBase + commandCount * commandStrideBytes(), count, first, mesh);
        commandCount++;
    }

    void command(Page page, int count, int first, int mesh) {
        if (crossPage) {
            if (commandPage == null) commandPage = page;
            else if (!bindlessCommands && commandPage != page) expandCommands();
        }
        int base = commandBase + commandCount * commandStrideBytes();
        putCommand(bytes, base, count, first, mesh);
        if (bindlessCommands) putBindlessPointers(bytes, base + 16, page.gpuAddress, page.storageBytes);
        commandCount++;
    }

    /** A single VBO needs only ordinary 16-byte MDI headers. Promote only when
     * the command stream actually contains another storage object. Backwards
     * expansion preserves the already emitted headers without scratch storage. */
    private void expandCommands() {
        for (int i = commandCount - 1; i >= 0; i--) {
            int source = commandBase + i * 16;
            long first = bytes.getLong(source), second = bytes.getLong(source + 8);
            int destination = commandBase + i * BINDLESS_COMMAND_BYTES;
            bytes.putLong(destination, first);
            bytes.putLong(destination + 8, second);
            putBindlessPointers(bytes, destination + 16, commandPage.gpuAddress, commandPage.storageBytes);
        }
        bindlessCommands = true;
    }

    static void putBindlessPointers(ByteBuffer target, int base, long address, long length) {
        putPointer(target, base, POSITION, address, length);
        putPointer(target, base + 24, COLOR, address + 12, length - 12);
        putPointer(target, base + 48, UV, address + 16, length - 16);
        putPointer(target, base + 72, LIGHT, address + 24, length - 24);
    }

    private static void putPointer(ByteBuffer target, int base, int index, long address, long length) {
        target.putInt(base, index);
        target.putInt(base + 4, 0);
        target.putLong(base + 8, address);
        target.putLong(base + 16, length);
    }

    boolean supportsCrossPage() { return crossPage; }
    int commandBufferId() { return stream.getNativeId(); }
    int commandBaseBytes() { return commandBase; }
    int commandStrideBytes() { return bindlessCommands ? BINDLESS_COMMAND_BYTES : 16; }

    static void putCommand(ByteBuffer target, int byteOffset, int count,
                           int first, int baseInstance) {
        target.putInt(byteOffset, count);
        target.putInt(byteOffset + 4, 1);
        target.putInt(byteOffset + 8, first);
        target.putInt(byteOffset + 12, baseInstance);
    }

    int commandCount() { return commandCount; }

    void upload() {
        if (commandCount == 0) return;
        Throwable error = null;
        try {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, stream.getNativeId());
            EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, stream.getNativeId());
            bytes.position(0);
            bytes.limit(commandBase + commandCount * commandStrideBytes());
            // Replacing the data store also uploads this layer in one GL call.
            // Its payload is bounded by the reserved stream capacity; prior
            // queued draws keep their old store through ordinary GL ordering.
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, bytes, GL15.GL_STREAM_DRAW);
            if (bindlessCommands) {
                // A GPU copy into stable resident storage is ordered after the
                // previous layer's reads. No CPU wait or address query is needed.
                GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, residentOffsets.getNativeId());
                EarlyGlStateTracker.bindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, residentOffsets.getNativeId());
                GL31.glCopyBufferSubData(GL15.GL_ARRAY_BUFFER, GL40.GL_DRAW_INDIRECT_BUFFER,
                    0L, 0L, commandBase);
            }
        } catch (Throwable problem) { error = problem; }
        finally {
            if (bindlessCommands) try {
                GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, original.indirectBuffer);
                EarlyGlStateTracker.bindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, original.indirectBuffer);
            } catch (Throwable problem) { error = restoreFailure(error, problem); }
            try {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, original.arrayBuffer);
                EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, original.arrayBuffer);
            } catch (Throwable problem) {
                EarlyGlStateTracker.invalidate();
                restorationFailed = true;
                error = append(error, problem);
            }
        }
        if (error != null) {
            disabled = true;
            failure = append(failure, error);
            detail = "upload-failed: " + error.toString();
            rethrow(error);
        }
    }

    /** A self-contained draw for callers without a surrounding native run. */
    void draw(int terrainBuffer, int start, int count) {
        Throwable error = null;
        try {
            Page page = page(terrainBuffer);
            if (!preparePage(page)) throw new IllegalStateException("terrain page layout budget");
            drawScoped(page, start, count);
        } catch (Throwable problem) { error = problem; }
        finally {
            try { endNativeRun(); }
            catch (Throwable problem) { error = append(error, problem); }
        }
        if (error != null) rethrow(error);
    }

    /** Keeps shared state across page changes. The arena closes this scope only
     * at a real legacy/compatibility draw or the end of the layer. */
    void drawScoped(Page page, int start, int count) {
        submissionStarted = false;
        if (count <= 0) return;
        try {
            if (page.vao == null && !prepareClassicPage(page))
                throw new IllegalStateException("terrain page layout budget");
            if (!nativeRun) {
                nativeRun = true;
                boundPageVao = 0;
                GL20.glUseProgram(program.getNativeId());
                EarlyGlStateTracker.useProgram(program.getNativeId());
                GL20.glUniform1i(fogModeLocation, fogMode);
                GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, stream.getNativeId());
                EarlyGlStateTracker.bindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, stream.getNativeId());
            }
            int vaoId = page.vao.getNativeId();
            if (boundPageVao != vaoId) {
                GL30.glBindVertexArray(vaoId);
                EarlyGlStateTracker.bindVertexArray(vaoId);
                boundPageVao = vaoId;
            }
            submissionStarted = true;
            GL43.glMultiDrawArraysIndirect(GL11.GL_QUADS,
                (long) commandBase + (long) start * commandStrideBytes(), count, commandStrideBytes());
        } catch (Throwable error) {
            disabled = true;
            failure = append(failure, error);
            detail = "submission-failed: " + error.toString();
            rethrow(error);
        }
    }

    /** One ordered command range can source any number of resident terrain VBOs. */
    void drawCombined(int start, int count) {
        submissionStarted = false;
        if (count <= 0) return;
        if (!bindlessCommands) { drawScoped(commandPage, start, count); return; }
        try {
            if (!nativeRun) {
                nativeRun = true;
                boundPageVao = 0;
                GL20.glUseProgram(program.getNativeId());
                EarlyGlStateTracker.useProgram(program.getNativeId());
                GL20.glUniform1i(fogModeLocation, fogMode);
                GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, stream.getNativeId());
                EarlyGlStateTracker.bindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, stream.getNativeId());
            }
            int vao = unifiedVao.getNativeId();
            if (boundPageVao != vao) {
                GL30.glBindVertexArray(vao);
                EarlyGlStateTracker.bindVertexArray(vao);
                boundPageVao = vao;
            }
            submissionStarted = true;
            NVBindlessMultiDrawIndirect.glMultiDrawArraysIndirectBindlessNV(GL11.GL_QUADS,
                (long) commandBase + (long) start * BINDLESS_COMMAND_BYTES,
                count, BINDLESS_COMMAND_BYTES, 4);
        } catch (Throwable error) {
            disabled = true;
            failure = append(failure, error);
            detail = "submission-failed: " + error.toString();
            rethrow(error);
        }
    }

    void endNativeRun() {
        if (!nativeRun) return;
        nativeRun = false;
        boundPageVao = 0;
        Throwable error = null;
        try {
            GL30.glBindVertexArray(original.vertexArray);
            EarlyGlStateTracker.bindVertexArray(original.vertexArray);
        } catch (Throwable problem) { error = restoreFailure(error, problem); }
        // Scoped draws never change GL_ARRAY_BUFFER; the page layouts were
        // configured and restored before the first possible submission.
        try {
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, original.indirectBuffer);
            EarlyGlStateTracker.bindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, original.indirectBuffer);
        } catch (Throwable problem) { error = restoreFailure(error, problem); }
        try {
            GL20.glUseProgram(original.program);
            EarlyGlStateTracker.useProgram(original.program);
        } catch (Throwable problem) { error = restoreFailure(error, problem); }
        if (error != null) {
            disabled = true;
            failure = append(failure, error);
            detail = "restore-failed: " + error.toString();
            rethrow(error);
        }
    }

    boolean submissionStarted() { return submissionStarted; }

    /** Created once per physical arena page, never looked up on the hot draw path. */
    Page page(int terrainBuffer) {
        for (Page value : pages) if (value.terrainBuffer == terrainBuffer) return value;
        return newPage(terrainBuffer);
    }

    /** The caller owns the buffer identity, so no search over other layouts is needed. */
    Page newPage(int terrainBuffer) {
        Page value = new Page(terrainBuffer);
        pages.add(value);
        return value;
    }

    /** Called while building the plan, before any layer draw can be submitted. */
    boolean preparePage(Page page) {
        return preparePage(page, 0L);
    }

    boolean preparePage(Page page, long storageBytes) {
        if (crossPage && page.gpuAddress == 0L) {
            try {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, page.terrainBuffer);
                EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, page.terrainBuffer);
                long size = storageBytes > 0L ? storageBytes
                    : GL15.glGetBufferParameteri(GL15.GL_ARRAY_BUFFER, GL15.GL_BUFFER_SIZE);
                long address = NVShaderBufferLoad.glGetBufferParameterui64NV(GL15.GL_ARRAY_BUFFER,
                    NVShaderBufferLoad.GL_BUFFER_GPU_ADDRESS_NV);
                NVShaderBufferLoad.glMakeBufferResidentNV(GL15.GL_ARRAY_BUFFER, GL15.GL_READ_ONLY);
                page.storageBytes = size;
                page.gpuAddress = address;
            } finally {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, original.arrayBuffer);
                EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, original.arrayBuffer);
            }
        }
        return crossPage || prepareClassicPage(page);
    }

    private boolean prepareClassicPage(Page page) {
        if (page.vao != null) return true;
        long charge = ResourceLedger.nativeObjectCharge(RenderResourceKind.VERTEX_ARRAY);
        CacheBudget.Reservation reservation = ledger.reserveGpu(charge);
        if (reservation == null) { detail = "page-layout-budget"; return false; }
        page.vao = createReserved(RenderResourceKind.VERTEX_ARRAY, charge, preparedStamp,
            reservation, () -> GL30.glGenVertexArrays(), id -> GL30.glDeleteVertexArrays(id));
        Throwable error = null;
        try {
            GL30.glBindVertexArray(page.vao.getNativeId());
            EarlyGlStateTracker.bindVertexArray(page.vao.getNativeId());
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, page.terrainBuffer);
            EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, page.terrainBuffer);
            attribute(POSITION, 3, GL11.GL_FLOAT, false, 28, 0L, 0);
            attribute(COLOR, 4, GL11.GL_UNSIGNED_BYTE, true, 28, 12L, 0);
            attribute(UV, 2, GL11.GL_FLOAT, false, 28, 16L, 0);
            attribute(LIGHT, 2, GL11.GL_SHORT, false, 28, 24L, 0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, stream.getNativeId());
            EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, stream.getNativeId());
            attribute(OFFSET, 4, GL11.GL_FLOAT, false, 16, 0L, 1);
        } catch (Throwable problem) { error = problem; }
        finally {
            try {
                GL30.glBindVertexArray(original.vertexArray);
                EarlyGlStateTracker.bindVertexArray(original.vertexArray);
            } catch (Throwable problem) { error = restoreFailure(error, problem); }
            try {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, original.arrayBuffer);
                EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, original.arrayBuffer);
            } catch (Throwable problem) { error = restoreFailure(error, problem); }
        }
        if (error != null) {
            disabled = true;
            failure = append(failure, error);
            detail = "layout-failed: " + error.toString();
            rethrow(error);
        }
        return true;
    }

    void releasePage(Page page, boolean validContext) {
        invalidatePageStorage(page, validContext);
        RenderHandle handle = page.vao;
        page.vao = null;
        pages.remove(page);
        if (validContext && handle != null)
            ledger.retire(handle, LwjglRetirementFence.afterCurrentCommands(ledger));
    }

    /** Storage replacement is an existing upload event, never a per-frame scan. */
    void invalidatePageStorage(Page page, boolean validContext) {
        if (page.gpuAddress != 0L && validContext)
            NVShaderBufferLoad.glMakeNamedBufferNonResidentNV(page.terrainBuffer);
        page.gpuAddress = 0L;
        page.storageBytes = 0L;
    }

    private void releasePageLayouts(boolean validContext) {
        Throwable error = null;
        for (Page page : pages) {
            RenderHandle handle = page.vao;
            page.vao = null;
            if (validContext && handle != null) try {
                ledger.retire(handle, LwjglRetirementFence.afterCurrentCommands(ledger));
            } catch (Throwable problem) { error = append(error, problem); }
        }
        if (error != null) rethrow(error);
    }

    static final class Page {
        private final int terrainBuffer;
        private RenderHandle vao;
        private long gpuAddress, storageBytes;
        private Page(int terrainBuffer) { this.terrainBuffer = terrainBuffer; }
    }

    boolean isDisabled() { return disabled; }
    boolean canFallback() { return !submissionStarted && !restorationFailed; }
    Throwable consumeFailure() { Throwable result = failure; failure = null; return result; }
    String detail() { return detail; }
    boolean directionCullingAllowed() {
        return original.directionCulling;
    }

    private static final class LayerState {
        int program, vertexArray, arrayBuffer, indirectBuffer;
        boolean directionCulling;

        void capture(EarlyGlStateTracker.CompatibilitySnapshot tracked,
                     int savedArrayBuffer, boolean inspectFaces) {
            // The arena already captured this binding for its outer restore.
            arrayBuffer = savedArrayBuffer;
            if (tracked != null && tracked.getDrawIndirectBuffer() >= 0
                && tracked.isVertexArraySupported()) {
                program = tracked.getProgram();
                vertexArray = tracked.getVertexArray();
                indirectBuffer = tracked.getDrawIndirectBuffer();
            } else {
                program = EarlyGlStateTracker.modelProgramBinding();
                if (program == Integer.MIN_VALUE) program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
                vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
                indirectBuffer = EarlyGlStateTracker.drawIndirectBufferBinding();
                if (indirectBuffer == Integer.MIN_VALUE)
                    indirectBuffer = GL11.glGetInteger(GL40.GL_DRAW_INDIRECT_BUFFER_BINDING);
            }
            directionCulling = inspectFaces && (tracked != null
                ? tracked.isCull() && !tracked.isBlend() && tracked.getCullFace() == GL11.GL_BACK
                : GL11.glIsEnabled(GL11.GL_CULL_FACE) && !GL11.glIsEnabled(GL11.GL_BLEND)
                    && GL11.glGetInteger(GL11.GL_CULL_FACE_MODE) == GL11.GL_BACK)
                && GL11.glGetInteger(GL11.GL_FRONT_FACE) == GL11.GL_CCW;
        }
    }

    private static void attribute(int index, int size, int type,
                                  boolean normalized, int stride, long offset, int divisor) {
        GL20.glEnableVertexAttribArray(index);
        GL20.glVertexAttribPointer(index, size, type, normalized, stride, offset);
        GL33.glVertexAttribDivisor(index, divisor);
    }

    private boolean initialize(FrameStamp stamp) {
        ContextCapabilities caps = GLContext.getCapabilities();
        if (caps == null || !caps.OpenGL43) {
            attempted = disabled = true;
            detail = "requires-opengl43";
            return false;
        }
        nvFogDistance = caps.GL_NV_fog_distance;
        crossPage = caps.GL_NV_bindless_multi_draw_indirect
            && caps.GL_NV_vertex_buffer_unified_memory && caps.GL_NV_shader_buffer_load;
        long charge = ResourceLedger.nativeObjectCharge(RenderResourceKind.PROGRAM);
        CacheBudget.Reservation reservation = ledger.reserveGpu(charge);
        if (reservation == null) { detail = "program-budget"; return false; }
        TemporaryShaderStage stage = TemporaryShaderStage.reserve(ledger,
            id -> GL20.glDeleteShader(id));
        if (stage == null) {
            reservation.close();
            detail = "shader-budget";
            return false;
        }
        attempted = true;
        Throwable error = null;
        try {
            program = createReserved(RenderResourceKind.PROGRAM, charge, stamp, reservation,
                () -> GL20.glCreateProgram(), id -> GL20.glDeleteProgram(id));
            compileVertexProgram(program.getNativeId(), stage);
            fogModeLocation = GL20.glGetUniformLocation(program.getNativeId(), "iceFogMode");
        } catch (Throwable problem) { error = problem; }
        finally { error = stage.closeAndAppend(error); }
        if (error != null) rethrow(error);
        return true;
    }

    private boolean ensureCapacity(int meshes, FrameStamp stamp) {
        if (capacity >= meshes && stream != null) return true;
        int target = 256;
        while (target < meshes) target <<= 1;
        int size = target * (16 + 7 * (crossPage ? BINDLESS_COMMAND_BYTES : 16));
        int offsetSize = target * 16;
        CacheBudget.Reservation reservation = budget.tryReserve(BudgetKind.DIRECT, size);
        if (reservation == null) return false;
        CacheBudget.Reservation gpuReservation = ledger.reserveGpu(size);
        if (gpuReservation == null) { reservation.close(); return false; }
        CacheBudget.Reservation offsetReservation = crossPage ? ledger.reserveGpu(offsetSize) : null;
        if (crossPage && offsetReservation == null) {
            gpuReservation.close(); reservation.close(); return false;
        }
        long vaoCharge = ResourceLedger.nativeObjectCharge(RenderResourceKind.VERTEX_ARRAY);
        CacheBudget.Reservation vaoReservation = crossPage ? ledger.reserveGpu(vaoCharge) : null;
        if (crossPage && vaoReservation == null) {
            offsetReservation.close(); gpuReservation.close(); reservation.close(); return false;
        }
        ByteBuffer replacement;
        try { replacement = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()); }
        catch (Throwable error) {
            if (vaoReservation != null) vaoReservation.close();
            if (offsetReservation != null) offsetReservation.close();
            gpuReservation.close(); reservation.close(); throw error;
        }
        RenderHandle replacementStream = null, replacementOffsets = null, replacementVao = null;
        long replacementAddress = 0L;
        boolean offsetCreationStarted = false, vaoCreationStarted = false;
        boolean replacementResident = false;
        Throwable creationFailure = null;
        try {
            replacementStream = createReserved(RenderResourceKind.BUFFER, size, stamp, gpuReservation,
                () -> GL15.glGenBuffers(), id -> GL15.glDeleteBuffers(id));
            if (crossPage) {
                offsetCreationStarted = true;
                replacementOffsets = createReserved(RenderResourceKind.BUFFER, offsetSize, stamp,
                    offsetReservation, () -> GL15.glGenBuffers(), id -> GL15.glDeleteBuffers(id));
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, replacementOffsets.getNativeId());
                EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, replacementOffsets.getNativeId());
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) offsetSize, GL15.GL_DYNAMIC_DRAW);
                replacementAddress = NVShaderBufferLoad.glGetBufferParameterui64NV(GL15.GL_ARRAY_BUFFER,
                    NVShaderBufferLoad.GL_BUFFER_GPU_ADDRESS_NV);
                NVShaderBufferLoad.glMakeBufferResidentNV(GL15.GL_ARRAY_BUFFER, GL15.GL_READ_ONLY);
                replacementResident = true;
                vaoCreationStarted = true;
                replacementVao = createReserved(RenderResourceKind.VERTEX_ARRAY, vaoCharge, stamp,
                    vaoReservation, () -> GL30.glGenVertexArrays(), id -> GL30.glDeleteVertexArrays(id));
                GL30.glBindVertexArray(replacementVao.getNativeId());
                EarlyGlStateTracker.bindVertexArray(replacementVao.getNativeId());
                GL11.glEnableClientState(NVVertexBufferUnifiedMemory.GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
                unifiedAttribute(POSITION, 3, GL11.GL_FLOAT, false, 28, 0);
                unifiedAttribute(COLOR, 4, GL11.GL_UNSIGNED_BYTE, true, 28, 0);
                unifiedAttribute(UV, 2, GL11.GL_FLOAT, false, 28, 0);
                unifiedAttribute(LIGHT, 2, GL11.GL_SHORT, false, 28, 0);
                unifiedAttribute(OFFSET, 4, GL11.GL_FLOAT, false, 16, 1);
                NVVertexBufferUnifiedMemory.glBufferAddressRangeNV(
                    NVVertexBufferUnifiedMemory.GL_VERTEX_ATTRIB_ARRAY_ADDRESS_NV,
                    OFFSET, replacementAddress, offsetSize);
            }
        } catch (Throwable error) { creationFailure = error; }
        finally {
            if (crossPage) {
                try {
                    GL30.glBindVertexArray(original.vertexArray);
                    EarlyGlStateTracker.bindVertexArray(original.vertexArray);
                } catch (Throwable error) { creationFailure = restoreFailure(creationFailure, error); }
                try {
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, original.arrayBuffer);
                    EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, original.arrayBuffer);
                } catch (Throwable error) { creationFailure = restoreFailure(creationFailure, error); }
            }
        }
        if (creationFailure != null) {
            if (!vaoCreationStarted && vaoReservation != null) vaoReservation.close();
            if (!offsetCreationStarted && offsetReservation != null) offsetReservation.close();
            if (replacementResident) try {
                NVShaderBufferLoad.glMakeNamedBufferNonResidentNV(replacementOffsets.getNativeId());
            } catch (Throwable cleanup) { creationFailure = append(creationFailure, cleanup); }
            for (RenderHandle value : new RenderHandle[] { replacementStream, replacementOffsets, replacementVao })
                if (value != null) try { ledger.retire(value, LwjglRetirementFence.afterCurrentCommands(ledger)); }
                catch (Throwable cleanup) { creationFailure = append(creationFailure, cleanup); }
            reservation.close();
            rethrow(creationFailure);
        }
        RenderHandle previous = stream;
        RenderHandle previousOffsets = residentOffsets, previousVao = unifiedVao;
        CacheBudget.Reservation previousReservation = directReservation;
        stream = replacementStream;
        residentOffsets = replacementOffsets;
        unifiedVao = replacementVao;
        long previousAddress = offsetAddress;
        offsetAddress = replacementAddress;
        bytes = replacement;
        directReservation = reservation;
        capacity = target;
        Throwable error = null;
        try { releasePageLayouts(true); }
        catch (Throwable problem) { error = append(error, problem); }
        if (previousAddress != 0L && previousOffsets != null) try {
            NVShaderBufferLoad.glMakeNamedBufferNonResidentNV(previousOffsets.getNativeId());
        } catch (Throwable problem) { error = append(error, problem); }
        for (RenderHandle value : new RenderHandle[] { previousOffsets, previousVao })
            if (value != null) try { ledger.retire(value, LwjglRetirementFence.afterCurrentCommands(ledger)); }
            catch (Throwable problem) { error = append(error, problem); }
        if (previous != null) try {
            ledger.retire(previous, LwjglRetirementFence.afterCurrentCommands(ledger));
        } catch (Throwable problem) { error = append(error, problem); }
        if (previousReservation != null) try { previousReservation.close(); }
        catch (Throwable problem) { error = append(error, problem); }
        if (error != null) rethrow(error);
        return true;
    }

    private static void unifiedAttribute(int index, int size, int type,
                                         boolean normalized, int stride, int divisor) {
        GL20.glEnableVertexAttribArray(index);
        NVVertexBufferUnifiedMemory.glVertexAttribFormatNV(index, size, type, normalized, stride);
        GL33.glVertexAttribDivisor(index, divisor);
    }

    private RenderHandle createReserved(RenderResourceKind kind, long size, FrameStamp stamp,
                                CacheBudget.Reservation reservation,
                                TemporaryGpuResourceScope.IntAllocator allocator,
                                TemporaryGpuResourceScope.IntDestroyer destroyer) {
        int id = 0;
        boolean returned = false;
        try {
            id = allocator.allocate();
            returned = true;
            if (id <= 0) throw new IllegalStateException("terrain batch native allocation");
            RenderHandle result = ledger.registerReserved(kind, id, size,
                stamp.getResourceGeneration(), stamp.getGlContextGeneration(), reservation);
            if (result == null) throw new IllegalStateException("terrain batch resource budget");
            return result;
        } catch (Throwable error) {
            boolean deleted = false;
            if (id > 0) try { destroyer.destroy(id); deleted = true; }
            catch (Throwable cleanup) { error = append(error, cleanup); }
            if (deleted || returned && id <= 0) reservation.close();
            rethrow(error);
            return null;
        }
    }

    private void compileVertexProgram(int nativeProgram, TemporaryShaderStage stage) {
        int shader = 0;
        boolean attached = false;
        Throwable error = null;
        try {
            shader = stage.create(() -> GL20.glCreateShader(GL20.GL_VERTEX_SHADER));
            if (shader <= 0) throw new IllegalStateException("terrain shader creation");
            GL20.glShaderSource(shader, vertexSource());
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0)
                throw new IllegalStateException("terrain shader: " + GL20.glGetShaderInfoLog(shader, 2048));
            stage.markAttached();
            attached = true;
            GL20.glAttachShader(nativeProgram, shader);
            GL20.glBindAttribLocation(nativeProgram, POSITION, "icePosition");
            GL20.glBindAttribLocation(nativeProgram, COLOR, "iceColor");
            GL20.glBindAttribLocation(nativeProgram, UV, "iceUv");
            GL20.glBindAttribLocation(nativeProgram, LIGHT, "iceLight");
            GL20.glBindAttribLocation(nativeProgram, OFFSET, "iceOffset");
            GL20.glLinkProgram(nativeProgram);
            if (GL20.glGetProgrami(nativeProgram, GL20.GL_LINK_STATUS) == 0)
                throw new IllegalStateException("terrain program: " + GL20.glGetProgramInfoLog(nativeProgram, 2048));
        } catch (Throwable problem) { error = problem; }
        finally {
            if (attached) try {
                GL20.glDetachShader(nativeProgram, shader);
                stage.markDetached();
            } catch (Throwable problem) { error = append(error, problem); }
            error = stage.closeAndAppend(error);
        }
        if (error != null) rethrow(error);
    }

    static String vertexSource() {
        return "#version 120\n"
            + "attribute vec3 icePosition;\nattribute vec4 iceColor;\n"
            + "attribute vec2 iceUv;\nattribute vec2 iceLight;\nattribute vec4 iceOffset;\n"
            + "uniform int iceFogMode;\n"
            + "void main() {\n"
            + " vec3 position = icePosition;\n"
            + " if (iceOffset.w != 1.0) position = icePosition * iceOffset.w + vec3((iceOffset.w - 1.0) * 8.0);\n"
            + " vec4 eye = gl_ModelViewMatrix * vec4(position + iceOffset.xyz, 1.0);\n"
            + " gl_Position = gl_ProjectionMatrix * eye;\n gl_ClipVertex = eye;\n"
            + " gl_TexCoord[0] = gl_TextureMatrix[0] * vec4(iceUv, 0.0, 1.0);\n"
            + " gl_TexCoord[1] = gl_TextureMatrix[1] * vec4(iceLight, 0.0, 1.0);\n"
            + " gl_FrontColor = iceColor;\n gl_BackColor = iceColor;\n"
            + " gl_FrontSecondaryColor = gl_SecondaryColor;\n"
            + " gl_BackSecondaryColor = gl_SecondaryColor;\n"
            + " gl_FogFragCoord = iceFogMode == 1 ? length(eye.xyz) : (iceFogMode == 2 ? -eye.z : abs(eye.z));\n"
            + "}\n";
    }

    void reset(boolean validContext) {
        Throwable error = null;
        if (validContext) try { endNativeRun(); }
        catch (Throwable problem) { error = append(error, problem); }
        try { releasePageLayouts(validContext); }
        catch (Throwable problem) { error = append(error, problem); }
        for (Page page : pages) try { invalidatePageStorage(page, validContext); }
        catch (Throwable problem) { error = append(error, problem); }
        pages.clear();
        nativeRun = false;
        boundPageVao = 0;
        preparedStamp = null;
        if (validContext && offsetAddress != 0L && residentOffsets != null) try {
            NVShaderBufferLoad.glMakeNamedBufferNonResidentNV(residentOffsets.getNativeId());
        } catch (Throwable problem) { error = append(error, problem); }
        offsetAddress = 0L;
        RenderHandle[] handles = { program, stream, residentOffsets, unifiedVao };
        program = stream = residentOffsets = unifiedVao = null;
        crossPage = false;
        commandPage = null;
        bindlessCommands = false;
        if (validContext) for (RenderHandle handle : handles) {
            if (handle != null) try {
                ledger.retire(handle, LwjglRetirementFence.afterCurrentCommands(ledger));
            } catch (Throwable problem) { error = append(error, problem); }
        }
        attempted = disabled = submissionStarted = false;
        restorationFailed = false;
        failure = null;
        detail = "not-initialized";
        if (error != null) rethrow(error);
    }

    void close(boolean validContext) {
        Throwable error = null;
        try { reset(validContext); } catch (Throwable problem) { error = problem; }
        bytes = null;
        capacity = 0;
        CacheBudget.Reservation reservation = directReservation;
        directReservation = null;
        if (reservation != null) try { reservation.close(); }
        catch (Throwable problem) { error = append(error, problem); }
        if (error != null) rethrow(error);
    }

    private Throwable restoreFailure(Throwable first, Throwable next) {
        restorationFailed = true;
        EarlyGlStateTracker.invalidate();
        return append(first, next);
    }

    private static Throwable append(Throwable first, Throwable next) {
        if (first == null) return next;
        Throwable fatal = FatalErrors.findFatal(next);
        if (fatal != null && FatalErrors.findFatal(first) == null) {
            if (fatal != first) fatal.addSuppressed(first);
            return fatal;
        }
        if (first != next) first.addSuppressed(next);
        return first;
    }

    private static void rethrow(Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        if (error instanceof RuntimeException) throw (RuntimeException) error;
        if (error instanceof Error) throw (Error) error;
        throw new IllegalStateException("terrain batch renderer", error);
    }
}
