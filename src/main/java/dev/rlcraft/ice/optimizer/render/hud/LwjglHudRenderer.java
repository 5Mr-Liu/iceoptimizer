package dev.rlcraft.ice.optimizer.render.hud;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.resource.LwjglRetirementFence;
import dev.rlcraft.ice.optimizer.render.resource.RenderHandle;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBSync;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.GLSync;

/**
 * Render-thread-only bounded HUD stream.  It stores primitive vertex and run
 * metadata, rotates three VBOs without waiting, and can replay every queued
 * primitive through compatibility immediate mode when no slot is safe.
 */
public final class LwjglHudRenderer {
    public enum FlushResult {
        EMPTY(false), MODERN(true), LEGACY_BUSY(false), LEGACY_STATE(false),
        FAILED_BEFORE_DRAW(false), FAILED_AFTER_DRAW(true);

        private final boolean submitted;
        FlushResult(boolean submitted) { this.submitted = submitted; }
        public boolean submittedModern() { return submitted; }
        public boolean consumed() { return this != EMPTY; }
    }

    private static final int FLOATS_PER_VERTEX = 5;
    private static final int BYTES_PER_VERTEX = FLOATS_PER_VERTEX * 4;
    private static final int VERTICES_PER_QUAD = 4;
    private static final int SLOT_COUNT = 3;
    private final RenderThreadGuard threadGuard;
    private final ResourceLedger ledger;
    private final CacheBudget.Reservation directReservation;
    private final ByteBuffer vertices;
    private final int[] modes;
    private final int[] firsts;
    private final int[] counts;
    private final IntBuffer multiFirst;
    private final IntBuffer multiCount;
    private final Slot[] slots = new Slot[SLOT_COUNT];
    private final int maximumVertices;
    private long resourceGeneration;
    private long contextGeneration;
    private int vertexCount;
    private int commandCount;
    private int immediateMode;
    private int immediateFirst;
    private int immediateVertices;
    private float immediateU;
    private float immediateV;
    private boolean immediate;
    private boolean closed;
    private int nextSlot;
    private long modernFlushes;
    private long legacyFlushes;
    private long fenceBusy;
    private long submittedVertices;
    private Throwable lastError;

    public LwjglHudRenderer(RenderThreadGuard threadGuard, ResourceLedger ledger,
                            CacheBudget budget, int maximumQuads) {
        if (threadGuard == null || ledger == null || budget == null) {
            throw new IllegalArgumentException("HUD renderer dependencies");
        }
        this.threadGuard = threadGuard;
        this.ledger = ledger;
        int quads = Math.max(64, Math.min(65536, maximumQuads));
        maximumVertices = Math.multiplyExact(quads, VERTICES_PER_QUAD);
        int vertexBytes = Math.multiplyExact(maximumVertices, BYTES_PER_VERTEX);
        int metadataBytes = Math.multiplyExact(quads, 20);
        long directBytes = Math.addExact((long) vertexBytes, metadataBytes);
        directReservation = budget.tryReserve(BudgetKind.DIRECT, directBytes);
        if (directReservation == null) {
            throw new IllegalStateException("HUD direct-memory budget exhausted");
        }
        try {
            vertices = BufferUtils.createByteBuffer(vertexBytes)
                .order(ByteOrder.nativeOrder());
            modes = new int[quads];
            firsts = new int[quads];
            counts = new int[quads];
            multiFirst = BufferUtils.createIntBuffer(quads);
            multiCount = BufferUtils.createIntBuffer(quads);
            for (int i = 0; i < slots.length; i++) slots[i] = new Slot();
        } catch (Throwable error) {
            directReservation.close();
            throw error;
        }
    }

    public void prepare(long resources, long context) {
        threadGuard.check();
        if (closed || resources <= 0L || context <= 0L) {
            throw new IllegalStateException("invalid HUD generation");
        }
        if (resourceGeneration == 0L) {
            resourceGeneration = resources;
            contextGeneration = context;
        } else if (resourceGeneration != resources || contextGeneration != context) {
            reset(contextGeneration == context, resources, context);
        }
    }

    public boolean recordQuad(float x0, float y0, float z, float u0, float v0,
                              float x1, float y1, float u1, float v1) {
        threadGuard.check();
        if (closed || immediate || !topologyCompatible(GL11.GL_QUADS)
            || !room(4, 1)) return false;
        int first = vertexCount;
        put(x0, y1, z, u0, v1);
        put(x1, y1, z, u1, v1);
        put(x1, y0, z, u1, v0);
        put(x0, y0, z, u0, v0);
        append(GL11.GL_QUADS, first, 4, true);
        return true;
    }

    /** Records the original four-vertex font triangle strip without changing topology. */
    public boolean recordGlyph(float[] xyzuv) {
        return recordGlyphRun(xyzuv, 1, 0.0F, 0.0F);
    }

    /** Atomically appends a translation-normalized cached glyph run. */
    boolean recordGlyphRun(float[] xyzuv, int glyphs, float translateX,
                           float translateY) {
        threadGuard.check();
        if (closed || immediate || !topologyCompatible(GL11.GL_TRIANGLE_STRIP)
            || xyzuv == null || glyphs < 0 || !finite(translateX)
            || !finite(translateY)) return false;
        int floats;
        int verticesNeeded;
        try {
            verticesNeeded = Math.multiplyExact(glyphs, VERTICES_PER_QUAD);
            floats = Math.multiplyExact(verticesNeeded, FLOATS_PER_VERTEX);
        } catch (ArithmeticException overflow) {
            return false;
        }
        if (xyzuv.length != floats || !room(verticesNeeded, glyphs)) return false;
        for (int offset = 0; offset < floats; offset += FLOATS_PER_VERTEX) {
            if (!finite(xyzuv[offset]) || !finite(xyzuv[offset + 1])
                || !finite(xyzuv[offset + 2]) || !finite(xyzuv[offset + 3])
                || !finite(xyzuv[offset + 4])) return false;
        }
        int firstVertex = vertexCount;
        int firstCommand = commandCount;
        try {
            for (int glyph = 0; glyph < glyphs; glyph++) {
                int first = vertexCount;
                int base = glyph * VERTICES_PER_QUAD * FLOATS_PER_VERTEX;
                for (int vertex = 0; vertex < VERTICES_PER_QUAD; vertex++) {
                    int offset = base + vertex * FLOATS_PER_VERTEX;
                    put(xyzuv[offset] + translateX,
                        xyzuv[offset + 1] + translateY, xyzuv[offset + 2],
                        xyzuv[offset + 3], xyzuv[offset + 4]);
                }
                append(GL11.GL_TRIANGLE_STRIP, first, VERTICES_PER_QUAD, false);
            }
        } catch (Throwable failure) {
            vertexCount = firstVertex;
            commandCount = firstCommand;
            vertices.position(Math.multiplyExact(vertexCount, BYTES_PER_VERTEX));
            throw failure;
        }
        return true;
    }

    public boolean beginImmediate(int mode) {
        threadGuard.check();
        if (closed || immediate || mode != GL11.GL_TRIANGLE_STRIP
            || !topologyCompatible(mode)
            || !room(4, 1)) return false;
        immediate = true;
        immediateMode = mode;
        immediateFirst = vertexCount;
        immediateVertices = 0;
        immediateU = 0.0F;
        immediateV = 0.0F;
        return true;
    }

    public void immediateTexCoord(float u, float v) {
        threadGuard.check();
        if (!immediate) throw new IllegalStateException("font texcoord outside begin/end");
        immediateU = u;
        immediateV = v;
    }

    public void immediateVertex(float x, float y, float z) {
        threadGuard.check();
        if (!immediate || immediateVertices >= 4) {
            throw new IllegalStateException("font vertex graph changed");
        }
        put(x, y, z, immediateU, immediateV);
        immediateVertices++;
    }

    public boolean endImmediate() {
        threadGuard.check();
        if (!immediate) return false;
        boolean valid = immediateMode == GL11.GL_TRIANGLE_STRIP
            && immediateVertices == 4;
        if (valid) append(immediateMode, immediateFirst, immediateVertices, false);
        else rollbackImmediate();
        immediate = false;
        immediateVertices = 0;
        return valid;
    }

    /** Discards an incomplete glyph; the caller must execute its legacy path. */
    public void rollbackImmediate() {
        threadGuard.check();
        if (!immediate) return;
        vertexCount = immediateFirst;
        vertices.position(Math.multiplyExact(vertexCount, BYTES_PER_VERTEX));
        immediate = false;
        immediateVertices = 0;
    }

    public boolean hasCommands() { return commandCount != 0; }
    public int getCommandCount() { return commandCount; }
    public int getVertexCount() { return vertexCount; }
    public Throwable getLastError() { return lastError; }
    public long getModernFlushes() { return modernFlushes; }
    public long getLegacyFlushes() { return legacyFlushes; }
    public long getFenceBusy() { return fenceBusy; }
    public long getSubmittedVertices() { return submittedVertices; }

    /** Preflight used to keep cached font state changes atomic with recording. */
    public boolean canRecordGlyphRun(int glyphs) {
        threadGuard.check();
        if (glyphs < 0) return false;
        int verticesNeeded;
        try { verticesNeeded = Math.multiplyExact(glyphs, VERTICES_PER_QUAD); }
        catch (ArithmeticException overflow) { return false; }
        return !closed && !immediate
            && topologyCompatible(GL11.GL_TRIANGLE_STRIP)
            && room(verticesNeeded, glyphs);
    }

    public FlushResult flush(EarlyGlStateTracker.Snapshot state) {
        threadGuard.check();
        lastError = null;
        if (commandCount == 0) return FlushResult.EMPTY;
        if (immediate) throw new IllegalStateException("flush inside font primitive");
        if (!safe(state)) {
            return legacyAndClear(FlushResult.LEGACY_STATE, null);
        }
        Slot slot;
        try {
            ensureBuffers();
            slot = acquireSlot();
        } catch (Throwable error) {
            return legacyAndClear(FlushResult.FAILED_BEFORE_DRAW, error);
        }
        if (slot == null) {
            FatalErrors.rethrowIfFatal(lastError);
            fenceBusy++;
            return legacyAndClear(FlushResult.LEGACY_BUSY, null);
        }

        boolean clientPushed = false;
        boolean issued = false;
        try {
            GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
            clientPushed = true;
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, slot.bufferId);
            vertices.limit(Math.multiplyExact(vertexCount, BYTES_PER_VERTEX));
            vertices.position(0);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STREAM_DRAW);
            GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
            GL11.glVertexPointer(3, GL11.GL_FLOAT, BYTES_PER_VERTEX, 0L);
            GL11.glTexCoordPointer(2, GL11.GL_FLOAT, BYTES_PER_VERTEX, 12L);
            for (int command = 0; command < commandCount;) {
                int mode = modes[command];
                if (mode == GL11.GL_TRIANGLE_STRIP) {
                    int end = command + 1;
                    while (end < commandCount && modes[end] == mode) end++;
                    multiFirst.clear();
                    multiCount.clear();
                    for (int index = command; index < end; index++) {
                        multiFirst.put(firsts[index]);
                        multiCount.put(counts[index]);
                    }
                    multiFirst.flip();
                    multiCount.flip();
                    issued = true;
                    GL14.glMultiDrawArrays(mode, multiFirst, multiCount);
                    command = end;
                } else {
                    issued = true;
                    GL11.glDrawArrays(mode, firsts[command], counts[command]);
                    command++;
                }
            }
        } catch (Throwable error) {
            lastError = error;
        } finally {
            vertices.limit(vertices.capacity());
            vertices.position(Math.min(vertices.capacity(),
                Math.multiplyExact(vertexCount, BYTES_PER_VERTEX)));
            if (clientPushed) {
                try { GL11.glPopClientAttrib(); }
                catch (Throwable restoreError) {
                    lastError = restoreFailure(lastError, restoreError);
                }
            }
            try { GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, state.getArrayBuffer()); }
            catch (Throwable restoreError) {
                lastError = restoreFailure(lastError, restoreError);
            }
        }
        if (lastError != null) {
            if (!issued) {
                if (FatalErrors.findFatal(lastError) != null) {
                    Throwable fatal = lastError;
                    clear();
                    FatalErrors.rethrowIfFatal(fatal);
                }
                return legacyAndClear(FlushResult.FAILED_BEFORE_DRAW,
                    lastError);
            }
            // At least one draw call crossed the driver boundary.  Seal the
            // slot and consume the queued batch before propagating even a
            // fatal failure; replaying or reusing it could duplicate HUD/font
            // geometry whose submission outcome is unknown.
            quarantineSubmitted(slot);
            Throwable submittedFailure = lastError;
            submittedVertices += vertexCount;
            modernFlushes++;
            clear();
            FatalErrors.rethrowIfFatal(submittedFailure);
            return FlushResult.FAILED_AFTER_DRAW;
        }
        try {
            slot.fence = LwjglRetirementFence.tryAfterCurrentCommands(ledger);
            if (slot.fence == null) {
                slot.poisoned = true;
                slot.fence = NeverReadyFence.INSTANCE;
                lastError = new IllegalStateException("HUD Fence creation failed");
            }
        } catch (Throwable error) {
            slot.poisoned = true;
            slot.fence = NeverReadyFence.INSTANCE;
            lastError = error;
        }
        Throwable fenceFailure = lastError;
        submittedVertices += vertexCount;
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
        resourceGeneration = resources;
        contextGeneration = context;
        nextSlot = 0;
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
        } finally {
            try { directReservation.close(); }
            catch (Throwable error) { failure = appendFailure(failure, error); }
            closed = true;
        }
        if (failure != null) rethrow(failure);
    }

    private void ensureBuffers() {
        if (resourceGeneration <= 0L || contextGeneration <= 0L) {
            throw new IllegalStateException("HUD generation not prepared");
        }
        long bytes = vertices.capacity();
        for (Slot slot : slots) {
            if (slot.bufferId != 0) continue;
            CacheBudget.Reservation reservation = ledger.reserveGpu(bytes);
            if (reservation == null) {
                throw new IllegalStateException("HUD GPU budget exhausted");
            }
            int id;
            try {
                id = GL15.glGenBuffers();
            } catch (Throwable creationFailure) {
                // A throwing native allocator is outcome-uncertain. Keep the
                // reservation charged because no reliable name is available
                // for a one-shot cleanup.
                rethrow(creationFailure);
                throw new AssertionError("unreachable HUD allocation");
            }
            if (id <= 0) {
                reservation.close();
                throw new IllegalStateException("HUD glGenBuffers failed");
            }
            RenderHandle handle = registerBuffer(id, bytes, reservation);
            slot.bufferId = id;
            slot.handle = handle;
        }
    }

    /** Deletes a not-yet-published GL name if ledger publication is uncertain. */
    private RenderHandle registerBuffer(int id, long bytes,
                                        CacheBudget.Reservation reservation) {
        Throwable failure = null;
        try {
            RenderHandle handle = ledger.registerReserved(
                RenderResourceKind.BUFFER, id, bytes, resourceGeneration,
                contextGeneration, reservation);
            if (handle != null) return handle; // Ledger owns reservation now.
            failure = new IllegalStateException("HUD GPU budget exhausted");
        } catch (Throwable registrationFailure) {
            failure = registrationFailure;
        }
        boolean deleted = false;
        try {
            GL15.glDeleteBuffers(id);
            deleted = true;
        }
        catch (Throwable cleanupFailure) {
            failure = appendFailure(failure, cleanupFailure);
        }
        if (deleted) try { reservation.close(); }
        catch (Throwable cleanupFailure) {
            failure = appendFailure(failure, cleanupFailure);
        }
        rethrow(failure);
        throw new AssertionError("unreachable HUD buffer registration");
    }

    private Slot acquireSlot() {
        for (int checked = 0; checked < slots.length; checked++) {
            int index = (nextSlot + checked) % slots.length;
            Slot slot = slots[index];
            if (slot.poisoned) continue;
            ResourceLedger.RetirementFence fence = slot.fence;
            if (fence != null) {
                try {
                    if (!fence.isSignaled()) continue;
                    destroySlotFence(slot);
                } catch (Throwable error) {
                    slot.poisoned = true;
                    // This happens before the current batch draws, so surface
                    // it immediately and let the caller replay that batch on
                    // the legacy path.  Continuing with another slot hid a
                    // broken Fence and slowly exhausted the whole ring.
                    rethrow(error);
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

    private void replayLegacy() {
        for (int command = 0; command < commandCount; command++) {
            boolean begun = false;
            Throwable failure = null;
            try {
                GL11.glBegin(modes[command]);
                begun = true;
                int end = firsts[command] + counts[command];
                for (int vertex = firsts[command]; vertex < end; vertex++) {
                    int offset = Math.multiplyExact(vertex, BYTES_PER_VERTEX);
                    float x = vertices.getFloat(offset);
                    float y = vertices.getFloat(offset + 4);
                    float z = vertices.getFloat(offset + 8);
                    float u = vertices.getFloat(offset + 12);
                    float v = vertices.getFloat(offset + 16);
                    GL11.glTexCoord2f(u, v);
                    GL11.glVertex3f(x, y, z);
                }
            } catch (Throwable error) {
                failure = error;
            } finally {
                if (begun) try { GL11.glEnd(); }
                catch (Throwable endError) {
                    failure = appendFailure(failure, endError);
                }
            }
            if (failure != null) rethrow(failure);
        }
    }

    private FlushResult legacyAndClear(FlushResult success,
                                       Throwable primaryFailure) {
        if (FatalErrors.findFatal(primaryFailure) != null) {
            lastError = primaryFailure;
            clear();
            FatalErrors.rethrowIfFatal(primaryFailure);
        }
        Throwable failure = primaryFailure;
        try {
            replayLegacy();
            legacyFlushes++;
        } catch (Throwable replayFailure) {
            FatalErrors.rethrowIfFatal(replayFailure);
            failure = appendFailure(failure, replayFailure);
        } finally {
            clear();
        }
        lastError = failure;
        return failure == null ? success : FlushResult.FAILED_BEFORE_DRAW;
    }

    private void quarantineSubmitted(Slot slot) {
        slot.poisoned = true;
        slot.fence = NeverReadyFence.INSTANCE;
        // Avoid extra driver/allocation work while handling VM termination.
        if (FatalErrors.findFatal(lastError) != null) return;
        try {
            LwjglRetirementFence fence =
                LwjglRetirementFence.tryAfterCurrentCommands(ledger);
            if (fence != null) slot.fence = fence;
            else lastError = appendFailure(lastError,
                new IllegalStateException("HUD quarantine Fence creation failed"));
        } catch (Throwable fenceFailure) {
            lastError = appendFailure(lastError, fenceFailure);
        }
    }

    private void append(int mode, int first, int count, boolean mergeQuads) {
        if (mergeQuads && commandCount > 0 && modes[commandCount - 1] == mode
            && firsts[commandCount - 1] + counts[commandCount - 1] == first) {
            counts[commandCount - 1] += count;
            return;
        }
        modes[commandCount] = mode;
        firsts[commandCount] = first;
        counts[commandCount] = count;
        commandCount++;
    }

    private void put(float x, float y, float z, float u, float v) {
        vertices.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v);
        vertexCount++;
    }

    private boolean room(int verticesNeeded, int commandsNeeded) {
        return verticesNeeded >= 0 && commandsNeeded >= 0
            && vertexCount <= maximumVertices - verticesNeeded
            && commandCount <= modes.length - commandsNeeded;
    }

    private boolean topologyCompatible(int mode) {
        return commandCount == 0 || modes[0] == mode;
    }

    private void clear() {
        vertexCount = 0;
        commandCount = 0;
        immediate = false;
        immediateVertices = 0;
        vertices.clear();
    }

    public static boolean isSafeState(EarlyGlStateTracker.Snapshot state) {
        return state != null && state.hasHudState() && state.hasArrayBufferBinding()
            && state.getProgram() == 0 && state.getActiveTexture() == 0
            && state.getClientActiveTexture() == 0 && state.isTexture0Enabled()
            && state.getTexture0() > 0 && state.getViewportWidth() > 0
            && state.getViewportHeight() > 0;
    }

    private static boolean safe(EarlyGlStateTracker.Snapshot state) {
        return isSafeState(state);
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static Throwable appendFailure(Throwable first, Throwable next) {
        if (first == null) return next;
        Throwable nextFatal = FatalErrors.findFatal(next);
        if (nextFatal != null && FatalErrors.findFatal(first) == null) {
            if (first != nextFatal) nextFatal.addSuppressed(first);
            return nextFatal;
        }
        if (next != null && first != next) first.addSuppressed(next);
        return first;
    }

    static Throwable restoreFailure(Throwable first, Throwable next) {
        EarlyGlStateTracker.invalidate();
        return appendFailure(first, next);
    }

    private static void rethrow(Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("HUD resource cleanup failed", failure);
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
