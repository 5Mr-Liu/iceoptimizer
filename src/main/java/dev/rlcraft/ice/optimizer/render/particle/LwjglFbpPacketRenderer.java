package dev.rlcraft.ice.optimizer.render.particle;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.resource.LwjglRetirementFence;
import dev.rlcraft.ice.optimizer.render.resource.RenderHandle;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import org.lwjgl.opengl.ARBSync;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.GLSync;

/**
 * Exact raw BLOCK-format packet submission for FBP's internal Tessellator draw.
 * It does not reorder packets: each accepted packet is uploaded and issued at
 * the original draw call before the original BufferBuilder is reset.
 */
public final class LwjglFbpPacketRenderer {
    public enum SubmitResult {
        MODERN(true, true), LEGACY_STATE(false, false), LEGACY_BUSY(false, false),
        UNSUPPORTED(false, false), FAILED_BEFORE_DRAW(false, true),
        FAILED_AFTER_DRAW(true, true);

        private final boolean submitted;
        private final boolean failure;
        SubmitResult(boolean submitted, boolean failure) {
            this.submitted = submitted;
            this.failure = failure;
        }
        public boolean submittedModern() { return submitted; }
        public boolean failed() { return failure; }
    }

    private static final int SLOT_COUNT = 3;
    // Vanilla 1.12.2 BLOCK is position(12) + color(4) + base UV(8)
    // + lightmap UV(4). NORMAL/PADDING belong to ITEM, not BLOCK.
    static final int BLOCK_STRIDE = 28;
    private final RenderThreadGuard threadGuard;
    private final ResourceLedger ledger;
    private final int maximumPacketBytes;
    private final Slot[] slots = new Slot[SLOT_COUNT];
    private long resourceGeneration;
    private long contextGeneration;
    private int vertexArrayId;
    private RenderHandle vertexArrayHandle;
    private int nextSlot;
    private boolean closed;
    private Throwable lastError;
    private long modernPackets;
    private long legacyPackets;
    private long fenceBusy;

    public LwjglFbpPacketRenderer(RenderThreadGuard threadGuard,
                                 ResourceLedger ledger,
                                 int maximumPacketBytes) {
        if (threadGuard == null || ledger == null) {
            throw new IllegalArgumentException("FBP renderer dependencies");
        }
        this.threadGuard = threadGuard;
        this.ledger = ledger;
        this.maximumPacketBytes = Math.max(BLOCK_STRIDE * 4,
            Math.min(16 * 1024 * 1024, maximumPacketBytes));
        for (int index = 0; index < slots.length; index++) slots[index] = new Slot();
    }

    public void prepare(long resources, long context) {
        threadGuard.check();
        if (closed || resources <= 0L || context <= 0L) {
            throw new IllegalStateException("invalid FBP generation");
        }
        if (resourceGeneration == 0L) {
            resourceGeneration = resources;
            contextGeneration = context;
        } else if (resourceGeneration != resources || contextGeneration != context) {
            reset(contextGeneration == context, resources, context);
        }
    }

    public SubmitResult submitRawPacket(BufferBuilder buffer,
                                        EarlyGlStateTracker.Snapshot current,
                                        EarlyGlStateTracker.CompatibilitySnapshot compatibility) {
        threadGuard.check();
        lastError = null;
        if (!supports(buffer)) {
            legacyPackets++;
            return SubmitResult.UNSUPPORTED;
        }
        if (!safe(current, compatibility)) {
            legacyPackets++;
            return SubmitResult.LEGACY_STATE;
        }
        int vertices = buffer.getVertexCount();
        int bytes;
        try { bytes = Math.multiplyExact(vertices, BLOCK_STRIDE); }
        catch (ArithmeticException overflow) {
            lastError = overflow;
            legacyPackets++;
            return SubmitResult.FAILED_BEFORE_DRAW;
        }
        if (bytes <= 0 || bytes > maximumPacketBytes) {
            legacyPackets++;
            return SubmitResult.UNSUPPORTED;
        }

        Slot slot;
        try {
            ensureVertexArray();
            slot = acquireSlot();
        } catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            lastError = error;
            legacyPackets++;
            return SubmitResult.FAILED_BEFORE_DRAW;
        }
        if (slot == null) {
            FatalErrors.rethrowIfFatal(lastError);
            legacyPackets++;
            if (lastError == null) fenceBusy++;
            return lastError == null ? SubmitResult.LEGACY_BUSY
                : SubmitResult.FAILED_BEFORE_DRAW;
        }

        ByteBuffer upload = buffer.getByteBuffer().duplicate()
            .order(ByteOrder.nativeOrder());
        upload.position(0);
        upload.limit(bytes);
        boolean finished = false;
        boolean issued = false;
        try {
            GL30.glBindVertexArray(vertexArrayId);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, slot.bufferId);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, upload, GL15.GL_STREAM_DRAW);
            setupBlockArrays();
            // Everything that can fail without consuming the original packet
            // is complete. From this point the finished BufferBuilder is owned
            // by this submission and must be reset exactly once.
            buffer.finishDrawing();
            finished = true;
            issued = true;
            GL11.glDrawArrays(GL11.GL_QUADS, 0, vertices);
        } catch (Throwable error) {
            lastError = error;
        } finally {
            try { GL30.glBindVertexArray(compatibility.getVertexArray()); }
            catch (Throwable restoreError) {
                lastError = restoreFailure(lastError, restoreError);
            }
            try { GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,
                current.getArrayBuffer()); } catch (Throwable restoreError) {
                lastError = restoreFailure(lastError, restoreError);
            }
            if (issued) {
                // WorldVertexBufferUploader's BLOCK postDraw leaves the
                // client unit at the default unit and resets current color.
                try { OpenGlHelper.setClientActiveTexture(
                    OpenGlHelper.defaultTexUnit); }
                catch (Throwable restoreError) {
                    lastError = restoreFailure(lastError, restoreError);
                }
                try { GlStateManager.resetColor(); }
                catch (Throwable restoreError) {
                    lastError = restoreFailure(lastError, restoreError);
                }
            } else {
                try { GL13.glClientActiveTexture(GL13.GL_TEXTURE0
                    + current.getClientActiveTexture()); }
                catch (Throwable restoreError) {
                    lastError = restoreFailure(lastError, restoreError);
                }
            }
            if (finished) {
                try { buffer.reset(); }
                catch (Throwable resetError) {
                    lastError = appendFailure(lastError, resetError);
                }
            }
        }

        if (lastError != null) {
            if (issued) {
                finishFailedSubmission(slot);
                return SubmitResult.FAILED_AFTER_DRAW;
            }
            FatalErrors.rethrowIfFatal(lastError);
            legacyPackets++;
            return SubmitResult.FAILED_BEFORE_DRAW;
        }
        try {
            slot.fence = LwjglRetirementFence.tryAfterCurrentCommands(ledger);
            if (slot.fence == null) {
                slot.poisoned = true;
                slot.fence = NeverReadyFence.INSTANCE;
                lastError = new IllegalStateException("FBP Fence creation failed");
            }
        } catch (Throwable error) {
            slot.poisoned = true;
            slot.fence = NeverReadyFence.INSTANCE;
            lastError = error;
        }
        FatalErrors.rethrowIfFatal(lastError);
        modernPackets++;
        return lastError == null ? SubmitResult.MODERN
            : SubmitResult.FAILED_AFTER_DRAW;
    }

    public Throwable getLastError() { return lastError; }
    public long getModernPackets() { return modernPackets; }
    public long getLegacyPackets() { return legacyPackets; }
    public long getFenceBusy() { return fenceBusy; }
    public static int exactStrideBytes() { return BLOCK_STRIDE; }

    public void reset(boolean validContext, long resources, long context) {
        threadGuard.check();
        Throwable failure = null;
        for (Slot slot : slots) {
            try {
                if (slot.handle != null && validContext) {
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
                slot.clear(validContext);
            }
        }
        RenderHandle vaoHandle = vertexArrayHandle;
        int vao = vertexArrayId;
        vertexArrayHandle = null;
        vertexArrayId = 0;
        if (validContext) try {
            if (vaoHandle != null) ledger.retire(vaoHandle,
                LwjglRetirementFence.afterCurrentCommands(ledger));
            else if (vao != 0) GL30.glDeleteVertexArrays(vao);
        } catch (Throwable error) {
            failure = appendFailure(failure, error);
        }
        resourceGeneration = resources;
        contextGeneration = context;
        nextSlot = 0;
        if (failure != null) rethrow(failure);
    }

    public void close(boolean validContext) {
        threadGuard.check();
        if (closed) return;
        try {
            reset(validContext, Math.max(1L, resourceGeneration),
                Math.max(1L, contextGeneration));
        } finally {
            closed = true;
        }
    }

    public static Validation validateFormat() {
        return isExactBlockFormat(DefaultVertexFormats.BLOCK)
            ? new Validation(true, "exact 28-byte BLOCK packet layout verified")
            : new Validation(false, "DefaultVertexFormats.BLOCK layout changed");
    }

    public static boolean isExactBlockFormat(VertexFormat format) {
        if (format != DefaultVertexFormats.BLOCK || format.getSize() != BLOCK_STRIDE) {
            return false;
        }
        List<VertexFormatElement> elements = format.getElements();
        return elements.size() == 4
            && element(format, elements, 0, 0,
                VertexFormatElement.EnumUsage.POSITION,
                VertexFormatElement.EnumType.FLOAT, 3, 0)
            && element(format, elements, 1, 12,
                VertexFormatElement.EnumUsage.COLOR,
                VertexFormatElement.EnumType.UBYTE, 4, 0)
            && element(format, elements, 2, 16,
                VertexFormatElement.EnumUsage.UV,
                VertexFormatElement.EnumType.FLOAT, 2, 0)
            && element(format, elements, 3, 24,
                VertexFormatElement.EnumUsage.UV,
                VertexFormatElement.EnumType.SHORT, 2, 1);
    }

    private static boolean element(VertexFormat format,
                                   List<VertexFormatElement> elements,
                                   int position, int offset,
                                   VertexFormatElement.EnumUsage usage,
                                   VertexFormatElement.EnumType type,
                                   int count, int index) {
        VertexFormatElement element = elements.get(position);
        return format.getOffset(position) == offset
            && element.getUsage() == usage && element.getType() == type
            && element.getElementCount() == count && element.getIndex() == index;
    }

    private boolean supports(BufferBuilder buffer) {
        if (closed || buffer == null || buffer.getDrawMode() != GL11.GL_QUADS
            || buffer.getVertexCount() <= 0
            || !isExactBlockFormat(buffer.getVertexFormat())) return false;
        long bytes = (long) buffer.getVertexCount() * BLOCK_STRIDE;
        return bytes > 0L && bytes <= maximumPacketBytes;
    }

    private static boolean safe(EarlyGlStateTracker.Snapshot current,
                                EarlyGlStateTracker.CompatibilitySnapshot compatibility) {
        return current != null && compatibility != null
            && current.hasDrawState() && current.hasArrayBufferBinding()
            && current.getProgram() == 0 && current.getArrayBuffer() == 0
            && compatibility.isVertexArraySupported()
            && compatibility.getProgram() == current.getProgram()
            && compatibility.getArrayBuffer() == current.getArrayBuffer()
            && compatibility.getClientActiveTexture()
                == current.getClientActiveTexture();
    }

    private void ensureVertexArray() {
        if (vertexArrayId != 0 && vertexArrayHandle != null) return;
        if (resourceGeneration <= 0L || contextGeneration <= 0L) {
            throw new IllegalStateException("FBP generation not prepared");
        }
        ContextCapabilities capabilities = GLContext.getCapabilities();
        if (capabilities == null || !capabilities.OpenGL30) {
            throw new IllegalStateException("FBP VAO unavailable");
        }
        CacheBudget.Reservation reservation = ledger.reserveNativeObject(
            RenderResourceKind.VERTEX_ARRAY);
        if (reservation == null) {
            throw new IllegalStateException("FBP VAO resource budget exhausted");
        }
        int id = 0;
        boolean allocationReturned = false;
        boolean nativeNameCreated = false;
        try {
            id = GL30.glGenVertexArrays();
            allocationReturned = true;
            if (id <= 0) throw new IllegalStateException(
                "FBP glGenVertexArrays failed");
            nativeNameCreated = true;
            RenderHandle handle = ledger.registerReservedObject(
                RenderResourceKind.VERTEX_ARRAY, id, resourceGeneration,
                contextGeneration, reservation);
            if (handle == null) throw new IllegalStateException(
                "FBP VAO resource budget exhausted");
            int publishedId = id;
            id = 0;
            nativeNameCreated = false;
            reservation = null;
            vertexArrayId = publishedId;
            vertexArrayHandle = handle;
        } catch (Throwable error) {
            Throwable failure = error;
            boolean deleteCompleted = false;
            if (nativeNameCreated) try {
                GL30.glDeleteVertexArrays(id);
                deleteCompleted = true;
            } catch (Throwable cleanup) {
                failure = appendFailure(failure, cleanup);
            }
            boolean noNameCreated = allocationReturned && !nativeNameCreated;
            if (reservation != null && (deleteCompleted || noNameCreated)) {
                try { reservation.close(); }
                catch (Throwable cleanup) {
                    failure = appendFailure(failure, cleanup);
                }
            }
            rethrow(failure);
            return;
        }
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
            throw new IllegalStateException("FBP generation not prepared");
        }
        CacheBudget.Reservation reservation = ledger.reserveGpu(
            maximumPacketBytes);
        if (reservation == null) {
            throw new IllegalStateException("FBP GPU budget exhausted");
        }
        int id = 0;
        boolean allocationReturned = false;
        boolean nativeNameCreated = false;
        try {
            id = GL15.glGenBuffers();
            allocationReturned = true;
            if (id <= 0) throw new IllegalStateException(
                "FBP glGenBuffers failed");
            nativeNameCreated = true;
            RenderHandle handle = ledger.registerReserved(
                RenderResourceKind.BUFFER, id, maximumPacketBytes,
                resourceGeneration, contextGeneration, reservation);
            if (handle == null) {
                throw new IllegalStateException("FBP GPU budget exhausted");
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

    static void setupBlockArrays() {
        int stride = BLOCK_STRIDE;
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glVertexPointer(3, GL11.GL_FLOAT, stride, 0L);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, stride, 12L);
        GL13.glClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, stride, 16L);
        GL13.glClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glTexCoordPointer(2, GL11.GL_SHORT, stride, 24L);
        // ForgeHooksClient.preDraw restores the selector after every UV
        // element. The selector does not choose which arrays are consumed,
        // but matching it before submission preserves draw-site state.
        GL13.glClientActiveTexture(OpenGlHelper.defaultTexUnit);
    }

    private void quarantineSubmitted(Slot slot) {
        slot.poisoned = true;
        slot.fence = NeverReadyFence.INSTANCE;
        if (FatalErrors.findFatal(lastError) != null) return;
        try {
            LwjglRetirementFence fence =
                LwjglRetirementFence.tryAfterCurrentCommands(ledger);
            slot.fence = fence == null ? NeverReadyFence.INSTANCE : fence;
            if (fence == null && lastError != null) {
                lastError = appendFailure(lastError,
                    new IllegalStateException(
                        "FBP Fence creation failed after uncertain draw"));
            }
        } catch (Throwable error) {
            slot.fence = NeverReadyFence.INSTANCE;
            lastError = appendFailure(lastError, error);
        }
    }

    private void finishFailedSubmission(Slot slot) {
        quarantineSubmitted(slot);
        Throwable submittedFailure = lastError;
        modernPackets++;
        FatalErrors.rethrowIfFatal(submittedFailure);
    }

    private static Throwable appendFailure(Throwable first, Throwable next) {
        if (first == null) return next;
        Throwable nextFatal = FatalErrors.findFatal(next);
        if (nextFatal != null && FatalErrors.findFatal(first) == null) {
            if (first != nextFatal) nextFatal.addSuppressed(first);
            return nextFatal;
        }
        addSuppressed(first, next);
        return first;
    }

    static Throwable restoreFailure(Throwable first, Throwable next) {
        EarlyGlStateTracker.invalidate();
        return appendFailure(first, next);
    }

    private static void addSuppressed(Throwable first, Throwable next) {
        if (first != null && next != null && first != next) {
            first.addSuppressed(next);
        }
    }

    private static void rethrow(Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("FBP renderer cleanup failed", failure);
    }

    private static final class Slot {
        private int bufferId;
        private RenderHandle handle;
        private ResourceLedger.RetirementFence fence;
        private ResourceLedger.RetirementFence uncertainFence;
        private boolean poisoned;
        private void clear(boolean preserveUncertain) {
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

    public static final class Validation {
        private final boolean equivalent;
        private final String detail;
        private Validation(boolean equivalent, String detail) {
            this.equivalent = equivalent;
            this.detail = detail;
        }
        public boolean isEquivalent() { return equivalent; }
        public String getDetail() { return detail; }
    }
}
