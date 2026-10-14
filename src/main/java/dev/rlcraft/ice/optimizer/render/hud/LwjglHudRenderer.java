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
import org.lwjgl.opengl.ARBBufferStorage;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.GLSync;

/**
 * Render-thread-only bounded HUD stream.  It stores primitive vertex and run
 * metadata, appends to three persistent pages without waiting, and replays queued
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
    private static final int MINIMUM_PAGE_BYTES = 256 * 1024;
    private final RenderThreadGuard threadGuard;
    private final ResourceLedger ledger;
    private final CacheBudget budget;
    private final CacheBudget.Reservation directReservation;
    private final ByteBuffer vertices;
    private final java.nio.FloatBuffer vertexFloats;
    private final int[] modes;
    private final int[] firsts;
    private final int[] counts;
    private final IntBuffer multiFirst;
    private final IntBuffer multiCount;
    private final IntBuffer stripIndices;
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
    private RenderHandle glyphIndexBuffer;
    private boolean indexAllocationAttempted;
    private boolean glyphIndicesReady;
    private long directFlushes;
    private float[] deferredGlyphs;
    private int deferredFloatOffset;
    private float deferredX, deferredY;
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
        this.budget = budget;
        int quads = Math.max(64, Math.min(65536, maximumQuads));
        maximumVertices = Math.multiplyExact(quads, VERTICES_PER_QUAD);
        int vertexBytes = Math.multiplyExact(maximumVertices, BYTES_PER_VERTEX);
        int metadataBytes = Math.multiplyExact(quads, 44);
        long directBytes = Math.addExact((long) vertexBytes, metadataBytes);
        directReservation = budget.tryReserve(BudgetKind.DIRECT, directBytes);
        if (directReservation == null) {
            throw new IllegalStateException("HUD direct-memory budget exhausted");
        }
        try {
            vertices = BufferUtils.createByteBuffer(vertexBytes)
                .order(ByteOrder.nativeOrder());
            vertexFloats = vertices.asFloatBuffer();
            modes = new int[quads];
            firsts = new int[quads];
            counts = new int[quads];
            multiFirst = BufferUtils.createIntBuffer(quads);
            multiCount = BufferUtils.createIntBuffer(quads);
            stripIndices = BufferUtils.createIntBuffer(quads * 6);
            for (int i = 0; i < quads; i++) {
                // Original strip triangles (0,1,2), (2,1,3): preserve winding,
                // interpolation and the default last provoking vertex.
                int first = i * VERTICES_PER_QUAD;
                stripIndices.put(first).put(first + 1).put(first + 2)
                    .put(first + 2).put(first + 1).put(first + 3);
            }
            stripIndices.flip();
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
        if (closed || immediate || !room(4, 1)) return false;
        materializeDeferredGlyphs();
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
        return recordGlyphRun(xyzuv, glyphs, translateX, translateY, false);
    }

    /** GlyphLayout owns immutable arrays validated once at construction. */
    boolean recordValidatedGlyphRun(float[] xyzuv, int glyphs, float translateX,
                                    float translateY) {
        threadGuard.check();
        if (!closed && !immediate && commandCount == 0 && glyphs > 0 && glyphs <= 8
            && xyzuv != null && xyzuv.length == glyphs * 20
            && finite(translateX) && finite(translateY) && room(glyphs * 4, glyphs)) {
            deferredGlyphs = xyzuv;
            deferredFloatOffset = 0;
            deferredX = translateX;
            deferredY = translateY;
            vertexCount = glyphs * 4;
            for (int glyph = 0; glyph < glyphs; glyph++)
                append(GL11.GL_TRIANGLE_STRIP, glyph * 4, 4, false);
            return true;
        }
        return recordGlyphRun(xyzuv, glyphs, translateX, translateY, true);
    }

    boolean recordValidatedGlyphRange(float[] xyzuv, int firstGlyph, int glyphs,
                                      float translateX, float translateY) {
        threadGuard.check();
        if (xyzuv == null || firstGlyph < 0 || glyphs < 0
            || firstGlyph > xyzuv.length / 20
            || glyphs > xyzuv.length / 20 - firstGlyph) return false;
        if (!closed && !immediate && commandCount == 0 && glyphs > 0 && glyphs <= 8
            && finite(translateX) && finite(translateY) && room(glyphs * 4, glyphs)) {
            // GlyphLayout owns this immutable array. A short string commonly
            // ends at a color/texture boundary; avoid a staging copy entirely.
            deferredGlyphs = xyzuv;
            deferredFloatOffset = firstGlyph * 20;
            deferredX = translateX;
            deferredY = translateY;
            vertexCount = glyphs * 4;
            for (int glyph = 0; glyph < glyphs; glyph++)
                append(GL11.GL_TRIANGLE_STRIP, glyph * 4, 4, false);
            return true;
        }
        return recordGlyphRange(xyzuv, firstGlyph, glyphs, translateX, translateY, true);
    }

    private void materializeDeferredGlyphs() {
        if (deferredGlyphs == null) return;
        float[] geometry = deferredGlyphs;
        int first = deferredFloatOffset / 20;
        int glyphs = vertexCount / 4;
        deferredGlyphs = null;
        vertexCount = commandCount = 0;
        boolean recorded = first == 0 && geometry.length == glyphs * 20
            ? recordGlyphRun(geometry, glyphs, deferredX, deferredY, true)
            : recordGlyphRange(geometry, first, glyphs, deferredX, deferredY, true);
        if (!recorded)
            throw new IllegalStateException("immutable HUD glyph materialization failed");
    }

    private boolean recordGlyphRun(float[] xyzuv, int glyphs, float translateX,
                                   float translateY, boolean validated) {
        // Retain the established whole-array fast path. Routing ordinary ASCII
        // strings through the general slice path regressed native font probes.
        threadGuard.check();
        if (closed || immediate || xyzuv == null || glyphs < 0 || !finite(translateX)
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
        if (!validated) {
            for (float value : xyzuv) if (!finite(value)) return false;
        }
        materializeDeferredGlyphs();
        int firstVertex = vertexCount;
        int firstCommand = commandCount;
        try {
            vertexFloats.position(firstVertex * FLOATS_PER_VERTEX);
            vertexFloats.put(xyzuv);
            if (translateX != 0F || translateY != 0F) {
                for (int vertex = firstVertex; vertex < firstVertex + verticesNeeded; vertex++) {
                    int offset = vertex * FLOATS_PER_VERTEX;
                    vertexFloats.put(offset, vertexFloats.get(offset) + translateX);
                    vertexFloats.put(offset + 1, vertexFloats.get(offset + 1) + translateY);
                }
            }
            vertexCount += verticesNeeded;
            vertices.position(vertexCount * BYTES_PER_VERTEX);
            for (int glyph = 0; glyph < glyphs; glyph++)
                append(GL11.GL_TRIANGLE_STRIP, firstVertex + glyph * 4, 4, false);
        } catch (Throwable failure) {
            vertexCount = firstVertex;
            commandCount = firstCommand;
            vertices.position(Math.multiplyExact(vertexCount, BYTES_PER_VERTEX));
            throw failure;
        }
        return true;
    }

    private boolean recordGlyphRange(float[] xyzuv, int firstGlyph, int glyphs,
                                     float translateX, float translateY, boolean validated) {
        threadGuard.check();
        if (closed || immediate || xyzuv == null || glyphs < 0 || !finite(translateX)
            || !finite(translateY)) return false;
        int floats;
        int verticesNeeded;
        try {
            verticesNeeded = Math.multiplyExact(glyphs, VERTICES_PER_QUAD);
            floats = Math.multiplyExact(verticesNeeded, FLOATS_PER_VERTEX);
        } catch (ArithmeticException overflow) {
            return false;
        }
        if (firstGlyph < 0 || firstGlyph > xyzuv.length / 20
            || glyphs > xyzuv.length / 20 - firstGlyph || !room(verticesNeeded, glyphs)) return false;
        int sourceOffset = firstGlyph * 20;
        if (!validated) {
            for (int index = sourceOffset; index < sourceOffset + floats; index++)
                if (!finite(xyzuv[index])) return false;
        }
        materializeDeferredGlyphs();
        int firstVertex = vertexCount;
        int firstCommand = commandCount;
        try {
            vertexFloats.position(firstVertex * FLOATS_PER_VERTEX);
            vertexFloats.put(xyzuv, sourceOffset, floats);
            if (translateX != 0F || translateY != 0F) {
                for (int vertex = firstVertex; vertex < firstVertex + verticesNeeded; vertex++) {
                    int offset = vertex * FLOATS_PER_VERTEX;
                    vertexFloats.put(offset, vertexFloats.get(offset) + translateX);
                    vertexFloats.put(offset + 1, vertexFloats.get(offset + 1) + translateY);
                }
            }
            vertexCount += verticesNeeded;
            vertices.position(vertexCount * BYTES_PER_VERTEX);
            for (int glyph = 0; glyph < glyphs; glyph++) {
                append(GL11.GL_TRIANGLE_STRIP, firstVertex + glyph * 4, 4, false);
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
            || !room(4, 1)) return false;
        materializeDeferredGlyphs();
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
    public long getDirectFlushes() { return directFlushes; }
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
            && room(verticesNeeded, glyphs);
    }

    public FlushResult flush(EarlyGlStateTracker.Snapshot state) {
        return flush(safe(state), state == null ? 0 : state.getArrayBuffer());
    }

    /** The stream changes only VAO/VBO bindings, so no full snapshot is needed. */
    public FlushResult flushTracked() {
        return flush(EarlyGlStateTracker.isHudStreamStateSafe(),
            EarlyGlStateTracker.arrayBufferBinding());
    }

    private FlushResult flush(boolean stateSafe, int previousArrayBuffer) {
        threadGuard.check();
        lastError = null;
        if (commandCount == 0) return FlushResult.EMPTY;
        if (immediate) throw new IllegalStateException("flush inside font primitive");
        int previousVertexArray = EarlyGlStateTracker.vertexArrayBinding();
        if (!stateSafe || previousVertexArray == Integer.MIN_VALUE) {
            return legacyAndClear(FlushResult.LEGACY_STATE, null);
        }
        // Tiny batches have no upload to amortize. Keep exact immediate
        // primitives, while retaining cached layout/geometry above this layer.
        if (vertexCount <= 32) return flushDirect();
        Slot slot;
        try {
            slot = acquireSlot();
            if (slot != null && !ensureVertexArray(slot)) slot = null;
        } catch (Throwable error) {
            return legacyAndClear(FlushResult.FAILED_BEFORE_DRAW, error);
        }
        if (slot == null) {
            FatalErrors.rethrowIfFatal(lastError);
            fenceBusy++;
            return legacyAndClear(FlushResult.LEGACY_BUSY, null);
        }

        boolean issued = false;
        boolean arrayChanged = false;
        boolean enableStatePushed = false;
        try {
            GL30.glBindVertexArray(slot.vertexArrayId);
            if (!slot.layoutReady) {
                ensureGlyphIndices();
                if (glyphIndicesReady)
                    GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, glyphIndexBuffer.getNativeId());
            }
            if (slot.mapped == null || !slot.layoutReady) {
                arrayChanged = true;
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, slot.bufferId);
            }
            vertices.limit(Math.multiplyExact(vertexCount, BYTES_PER_VERTEX));
            vertices.position(0);
            int bytes = vertices.remaining();
            if (!slot.storageAllocated || slot.writeOffset > slot.handle.getBytes() - bytes) {
                try {
                    // Mutable fallback or first allocation only. Persistent pages
                    // wrap in acquireSlot after a nonblocking completion check.
                    if (slot.handle.getBytes() >= 16384 && GLContext.getCapabilities().GL_ARB_buffer_storage) {
                        int flags = GL30.GL_MAP_WRITE_BIT | ARBBufferStorage.GL_MAP_PERSISTENT_BIT | ARBBufferStorage.GL_MAP_COHERENT_BIT;
                        ARBBufferStorage.glBufferStorage(GL15.GL_ARRAY_BUFFER, slot.handle.getBytes(), flags);
                        slot.mapped = GL30.glMapBufferRange(GL15.GL_ARRAY_BUFFER, 0L, slot.handle.getBytes(), flags, null);
                        if (slot.mapped == null) {
                            slot.poisoned = true;
                            throw new IllegalStateException("HUD persistent map failed");
                        }
                    } else GL15.glBufferData(GL15.GL_ARRAY_BUFFER, slot.handle.getBytes(), GL15.GL_STREAM_DRAW);
                    slot.writeOffset = 0;
                    slot.storageAllocated = true;
                } catch (Throwable error) {
                    // Immutable storage may exist even if mapping threw. Do
                    // not retry glBufferStorage on that name or reuse it.
                    slot.poisoned = true;
                    throw error;
                }
            }
            int firstVertex = slot.writeOffset / BYTES_PER_VERTEX;
            if (slot.mapped != null) {
                slot.mapped.position(slot.writeOffset); slot.mapped.put(vertices);
            } else GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, slot.writeOffset, vertices);
            slot.writeOffset += bytes;
            if (!slot.layoutReady) {
                // A fresh VAO has all other arrays disabled. The caller's
                // lightmap, color, normal and generic arrays remain untouched.
                GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
                GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
                GL11.glVertexPointer(3, GL11.GL_FLOAT, BYTES_PER_VERTEX, 0L);
                GL11.glTexCoordPointer(2, GL11.GL_FLOAT, BYTES_PER_VERTEX, 12L);
                slot.layoutReady = true;
            }
            // Primitive restart is global state, not part of a VAO. Respect a
            // foreign restart index that might match one of our glyph indices.
            boolean indexedGlyphs = glyphIndicesReady;
            GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
            enableStatePushed = true;
            GL11.glDisable(GL31.GL_PRIMITIVE_RESTART);
            for (int command = 0; command < commandCount;) {
                int mode = modes[command];
                if (mode == GL11.GL_TRIANGLE_STRIP) {
                    int end = command + 1;
                    while (end < commandCount && modes[end] == mode) end++;
                    if (indexedGlyphs) {
                        issued = true;
                        GL32.glDrawElementsBaseVertex(GL11.GL_TRIANGLES, (end - command) * 6,
                            GL11.GL_UNSIGNED_INT, 0L, firstVertex + firsts[command]);
                    } else {
                        // A tiny GPU budget still permits the existing stream.
                        multiFirst.clear(); multiCount.clear();
                        for (int index = command; index < end; index++) {
                            multiFirst.put(firstVertex + firsts[index]);
                            multiCount.put(counts[index]);
                        }
                        multiFirst.flip(); multiCount.flip();
                        issued = true;
                        GL14.glMultiDrawArrays(mode, multiFirst, multiCount);
                    }
                    command = end;
                } else {
                    issued = true;
                    GL11.glDrawArrays(mode, firstVertex + firsts[command], counts[command]);
                    command++;
                }
            }
        } catch (Throwable error) {
            lastError = error;
        } finally {
            if (enableStatePushed) try { GL11.glPopClientAttrib(); }
            catch (Throwable restoreError) {
                lastError = restoreFailure(lastError, restoreError);
            }
            vertices.limit(vertices.capacity());
            vertices.position(Math.min(vertices.capacity(),
                Math.multiplyExact(vertexCount, BYTES_PER_VERTEX)));
            try { GL30.glBindVertexArray(previousVertexArray); }
            catch (Throwable restoreError) {
                lastError = restoreFailure(lastError, restoreError);
            }
            try { if (arrayChanged) GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArrayBuffer); }
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
        // Each submission owns an append-only range. Persistent storage is
        // fenced once when a page fills, never once per glyph or flush.
        submittedVertices += vertexCount;
        modernFlushes++;
        clear();
        return FlushResult.MODERN;
    }

    private FlushResult flushDirect() {
        int submitted = vertexCount;
        try { replayLegacy(); }
        catch (Throwable error) { lastError = error; }
        finally { clear(); }
        submittedVertices += submitted;
        modernFlushes++;
        directFlushes++;
        // A failed immediate batch may have drawn a prefix. Consume it exactly
        // once, including VM failures; never let the caller replay translucency.
        FatalErrors.rethrowIfFatal(lastError);
        return lastError == null ? FlushResult.MODERN : FlushResult.FAILED_AFTER_DRAW;
    }

    /** Called inside our private VAO, before any draw or vertex-array mutation. */
    private void ensureGlyphIndices() {
        if (indexAllocationAttempted) return;
        indexAllocationAttempted = true;
        long bytes = (long) stripIndices.capacity() * 4;
        CacheBudget.Reservation reservation = ledger.reserveGpu(bytes);
        if (reservation == null) return;
        int id;
        try { id = GL15.glGenBuffers(); }
        catch (Throwable error) { throw error; } // uncertain allocation keeps its charge
        if (id <= 0) {
            reservation.close();
            throw new IllegalStateException("HUD index buffer creation failed");
        }
        glyphIndexBuffer = registerBuffer(id, bytes, reservation);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, id);
        stripIndices.clear();
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, stripIndices, GL15.GL_STATIC_DRAW);
        glyphIndicesReady = true;
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
                    ledger.retire(slot.handle, fence == null
                        ? LwjglRetirementFence.afterCurrentCommands(ledger) : fence);
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
            }
            try {
                if (slot.vertexArrayHandle != null && validContext) {
                    ledger.retire(slot.vertexArrayHandle,
                        LwjglRetirementFence.afterCurrentCommands(ledger));
                }
            } catch (Throwable error) {
                failure = appendFailure(failure, error);
            } finally { slot.clearReferences(validContext); }
        }
        if (glyphIndexBuffer != null && validContext) try {
            ledger.retire(glyphIndexBuffer, LwjglRetirementFence.afterCurrentCommands(ledger));
        } catch (Throwable error) { failure = appendFailure(failure, error); }
        glyphIndexBuffer = null;
        indexAllocationAttempted = false;
        glyphIndicesReady = false;
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

    private boolean createSlot(Slot slot, int bytes) {
        if (resourceGeneration <= 0L || contextGeneration <= 0L) {
            throw new IllegalStateException("HUD generation not prepared");
        }
        long available = budget.availableBytes(BudgetKind.GPU)
            - (slot.vertexArrayHandle == null
                ? ResourceLedger.nativeObjectCharge(RenderResourceKind.VERTEX_ARRAY) : 0L);
        bytes = (int) Math.max(bytes, Math.min(MINIMUM_PAGE_BYTES, available));
        CacheBudget.Reservation reservation = ledger.reserveGpu(bytes);
        if (reservation == null) return false;
        int id;
        try {
            id = GL15.glGenBuffers();
        } catch (Throwable creationFailure) {
            // A throwing allocator has no reliable name for cleanup.
            rethrow(creationFailure);
            throw new AssertionError("unreachable HUD allocation");
        }
        if (id <= 0) {
            reservation.close();
            throw new IllegalStateException("HUD glGenBuffers failed");
        }
        RenderHandle handle = registerBuffer(id, bytes, reservation);
        RenderHandle previous = slot.handle;
        slot.bufferId = id;
        slot.handle = handle;
        slot.layoutReady = false;
        slot.storageAllocated = false;
        slot.mapped = null;
        slot.writeOffset = 0;
        // HUD restores client arrays after each draw. Fence only this actual
        // retirement so in-flight storage remains charged until it completes.
        if (previous != null) {
            ResourceLedger.RetirementFence fence = slot.fence;
            slot.fence = null;
            ledger.retire(previous, fence == null
                ? LwjglRetirementFence.afterCurrentCommands(ledger) : fence);
        }
        return true;
    }

    private boolean ensureVertexArray(Slot slot) {
        if (slot.vertexArrayHandle != null) return true;
        CacheBudget.Reservation reservation = ledger.reserveNativeObject(
            RenderResourceKind.VERTEX_ARRAY);
        if (reservation == null) return false;
        int id = 0;
        boolean allocationReturned = false;
        try {
            id = GL30.glGenVertexArrays();
            allocationReturned = true;
            if (id <= 0) throw new IllegalStateException("HUD VAO creation failed");
            RenderHandle handle = ledger.registerReservedObject(
                RenderResourceKind.VERTEX_ARRAY, id, resourceGeneration,
                contextGeneration, reservation);
            if (handle == null) throw new IllegalStateException("HUD VAO budget exhausted");
            slot.vertexArrayId = id;
            slot.vertexArrayHandle = handle;
            return true;
        } catch (Throwable failure) {
            boolean deleted = false;
            if (id > 0) try { GL30.glDeleteVertexArrays(id); deleted = true; }
            catch (Throwable cleanup) { failure = appendFailure(failure, cleanup); }
            if (deleted || allocationReturned && id <= 0) try { reservation.close(); }
            catch (Throwable cleanup) { failure = appendFailure(failure, cleanup); }
            rethrow(failure);
            return false;
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
            int requiredBytes = vertexCount * BYTES_PER_VERTEX;
            if ((slot.bufferId == 0 || slot.handle != null && slot.handle.getBytes() < requiredBytes)
                && !createSlot(slot, requiredBytes)) continue;
            if (slot.mapped != null && (slot.fence != null
                || slot.writeOffset > slot.handle.getBytes() - requiredBytes)) {
                // Sealing forbids further appends, even if a later smaller
                // batch would fit behind the fence. Otherwise that fence
                // would not cover those writes when the page wraps.
                if (slot.fence == null) slot.fence = LwjglRetirementFence.afterCurrentCommands(ledger);
                boolean ready;
                try { ready = slot.fence.isSignaled(); }
                catch (Throwable error) { slot.poisoned = true; throw error; }
                if (!ready) continue;
                destroySlotFence(slot);
                slot.writeOffset = 0;
            }
            nextSlot = index;
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
                    int index = vertex * FLOATS_PER_VERTEX;
                    int source = deferredFloatOffset + index;
                    float x = deferredGlyphs == null ? vertices.getFloat(offset) : deferredGlyphs[source] + deferredX;
                    float y = deferredGlyphs == null ? vertices.getFloat(offset + 4) : deferredGlyphs[source + 1] + deferredY;
                    float z = deferredGlyphs == null ? vertices.getFloat(offset + 8) : deferredGlyphs[source + 2];
                    float u = deferredGlyphs == null ? vertices.getFloat(offset + 12) : deferredGlyphs[source + 3];
                    float v = deferredGlyphs == null ? vertices.getFloat(offset + 16) : deferredGlyphs[source + 4];
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

    private void clear() {
        deferredGlyphs = null;
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
        private int vertexArrayId;
        private RenderHandle vertexArrayHandle;
        private boolean layoutReady;
        private boolean storageAllocated;
        private ByteBuffer mapped;
        private int writeOffset;
        private ResourceLedger.RetirementFence fence;
        private ResourceLedger.RetirementFence uncertainFence;
        private boolean poisoned;

        private void clearReferences(boolean preserveUncertain) {
            bufferId = 0;
            handle = null;
            vertexArrayId = 0;
            vertexArrayHandle = null;
            layoutReady = false;
            storageAllocated = false;
            mapped = null;
            writeOffset = 0;
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



