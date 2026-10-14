package dev.rlcraft.ice.optimizer.render.entity;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyMatrixStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyModelBatchBarrier;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.arena.GpuArenaAllocator;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.RenderHandle;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

/**
 * Render-thread VBO cache for exact ModelRenderer display-list payloads.  It
 * never changes matrices, textures, programs or fixed-function state; one VBO
 * draw is emitted at the original call-list instruction.
 */
public final class LwjglModelMeshCache {
    private static final int MAX_ENTRIES = 8192;
    private static final int MIN_STAGING_BYTES = 4096;
    private static final int MAX_STAGING_BYTES = 16 * 1024 * 1024;
    private static final ModelArrayLayout MODEL_ARRAY_LAYOUT =
        ModelArrayLayout.capture(DefaultVertexFormats.POSITION_TEX_NORMAL);

    interface PublicationHook {
        void afterEntryPut();
    }

    private static final PublicationHook NO_PUBLICATION_HOOK =
        new PublicationHook() {
            @Override public void afterEntryPut() { }
        };

    private final RenderThreadGuard threadGuard;
    private final ResourceLedger resources;
    private final CacheBudget budget;
    private final PublicationHook publicationHook;
    private final ModelMeshStorage storage;
    private final LwjglModelPoseBatch poseBatch;
    private boolean poseBatching = true;
    private final Runnable poseBarrier = new Runnable() {
        @Override public void run() { flushPoseBatch(); }
    };
    private final Runnable poseMatrixSync = new Runnable() {
        @Override public void run() { poseBatch.synchronizeMatrix(); }
    };
    private final LinkedHashMap<Integer, Entry> entries =
        new LinkedHashMap<Integer, Entry>(128, 0.75F, true);
    private ByteBuffer staging;
    private CacheBudget.Reservation stagingReservation;
    private boolean stateBatchActive;
    private int stateBatchPreviousBuffer;
    private int stateBatchPreviousVertexArray;
    private int stateBatchVertexArray;
    private boolean stateBatchClientStatePushed;
    private int stateBatchBuffer;
    private long stateBatchBegins;
    private long stateBatchDraws;
    private long stateBatchRestores;
    private long stateBatchRejections;
    private long stateBatchBufferBinds;
    private long stateBatchPointerSets;
    private boolean admissionBlockedByCapacity;
    private long admissionProgress;
    private long observedStorageReleaseSerial;
    private long observedModelStorageSerial;
    private int waitingStagingBytes;
    private long waitingGeometryBytes;

    public LwjglModelMeshCache(RenderThreadGuard threadGuard, ResourceLedger resources) {
        this(threadGuard, resources, null, NO_PUBLICATION_HOOK);
    }

    public LwjglModelMeshCache(RenderThreadGuard threadGuard,
                               ResourceLedger resources,
                               CacheBudget budget) {
        this(threadGuard, resources, budget, NO_PUBLICATION_HOOK);
    }

    LwjglModelMeshCache(RenderThreadGuard threadGuard, ResourceLedger resources,
                        PublicationHook publicationHook) {
        this(threadGuard, resources, null, publicationHook);
    }

    LwjglModelMeshCache(RenderThreadGuard threadGuard, ResourceLedger resources,
                        CacheBudget budget, PublicationHook publicationHook) {
        if (threadGuard == null || resources == null) {
            throw new IllegalArgumentException("model mesh cache dependencies");
        }
        if (publicationHook == null) {
            throw new IllegalArgumentException("model mesh publication hook");
        }
        this.threadGuard = threadGuard;
        this.resources = resources;
        this.budget = budget;
        this.publicationHook = publicationHook;
        this.storage = new ModelMeshStorage(resources, budget, MODEL_ARRAY_LAYOUT.stride);
        this.poseBatch = new LwjglModelPoseBatch(resources, budget);
    }

    public boolean accept(ModelMeshPayload payload, FrameStamp stamp) {
        threadGuard.check();
        admissionBlockedByCapacity = false;
        if (payload == null || stamp == null
            || payload.getResourceGeneration() != stamp.getResourceGeneration()
            || payload.getContextGeneration() != stamp.getGlContextGeneration()
            || payload.getDrawMode() != GL11.GL_QUADS
            || !payload.matchesFormat(DefaultVertexFormats.POSITION_TEX_NORMAL)) {
            return false;
        }
        Integer cacheKey = Integer.valueOf(payload.getDisplayList());
        Entry current = entries.get(cacheKey);
        if (current != null && resources.isLive(current.handle)
            && current.matches(payload, stamp)) return true;

        int previous = EarlyGlStateTracker.arrayBufferBinding();
        if (previous == Integer.MIN_VALUE) return false;
        int vertexBytes = payload.getByteLength();
        if (!ensureStaging(vertexBytes)) {
            admissionBlockedByCapacity = waitingStagingBytes != 0;
            return false;
        }
        long requiredHeap = vertexBytes + GpuArenaAllocator.rangeHeapBytes();
        // Range metadata and its immutable source coexist. Test their actual
        // combined footprint before creating a native page that cannot be used.
        if (budget.availableBytes(BudgetKind.HEAP) < requiredHeap) {
            waitForGeometrySpace(requiredHeap);
            return false;
        }
        CacheBudget.Reservation geometryReservation = budget.tryReserve(BudgetKind.HEAP, vertexBytes);
        if (geometryReservation == null) {
            waitForGeometrySpace(requiredHeap);
            return false;
        }
        ModelMeshStorage.Allocation allocation = null;
        boolean accepted = false;
        Throwable failure = null;
        Entry replacement = null;
        boolean publicationComplete = false;
        try {
            allocation = storage.allocate(vertexBytes, stamp);
            admissionBlockedByCapacity = storage.lastAllocationBlockedByCapacity();
            if (allocation == null && budget.availableBytes(BudgetKind.HEAP) < GpuArenaAllocator.rangeHeapBytes())
                waitForGeometrySpace(requiredHeap);
            if (allocation != null) {
                staging.clear();
                staging.limit(vertexBytes);
                payload.copyVerticesTo(staging);
                staging.flip();
                storage.upload(allocation, staging);
                prepareVertexArray(allocation, previous);
                replacement = new Entry(payload, allocation, geometryReservation);
                geometryReservation = null;
                // The capability-tested upload path is ready without a second GPU readback.
                replacement.validated = true;
                publishReplacement(entries, cacheKey, current, replacement,
                    publicationHook);
                publicationComplete = true;
                allocation = null;
                if (current != null) {
                    try { retire(current); }
                    finally { current.releaseGeometry(); }
                }
                evictOverflow();
                accepted = true;
            }
        } catch (Throwable error) {
            failure = error;
        } finally {
            boolean mapMayOwnRange = false;
            if (!publicationComplete && replacement != null && allocation != null) {
                try {
                    Entry mapped = entries.get(cacheKey);
                    if (mapped == replacement) {
                        if (current == null) {
                            Entry removed = entries.remove(cacheKey);
                            if (removed != replacement) {
                                throw new IllegalStateException(
                                    "model VBO publication rollback failed");
                            }
                        } else {
                            entries.put(cacheKey, current);
                            if (entries.get(cacheKey) != current) {
                                throw new IllegalStateException(
                                    "model VBO replacement rollback failed");
                            }
                        }
                    }
                } catch (Throwable error) {
                    mapMayOwnRange = true;
                    failure = appendFailure(failure, error);
                }
            }
            if (mapMayOwnRange) allocation = null;
            if (!publicationComplete && !mapMayOwnRange && replacement != null) replacement.releaseGeometry();
            if (geometryReservation != null) geometryReservation.close();
            try { if (storage.lastAllocationChangedBinding()) bindArrayBuffer(previous); }
            catch (Throwable error) {
                EarlyGlStateTracker.invalidate();
                failure = appendFailure(failure, error);
            }
            if (allocation != null) try { storage.cancel(allocation); }
            catch (Throwable error) { failure = appendFailure(failure, error); }
        }
        if (failure != null) rethrow(failure);
        return accepted;
    }

    /** Actual reusable ranges/deleted GPU storage, never a frame deadline. */
    public long admissionProgress() {
        threadGuard.check();
        long modelSerial = storage.admissionProgress();
        long releaseSerial = resources.storageReleaseSerial();
        boolean progressed = modelSerial != observedModelStorageSerial
            || releaseSerial != observedStorageReleaseSerial;
        observedModelStorageSerial = modelSerial;
        observedStorageReleaseSerial = releaseSerial;
        if (waitingStagingBytes != 0 && budget != null
            && budget.availableBytes(BudgetKind.DIRECT) >= waitingStagingBytes) {
            waitingStagingBytes = 0;
            progressed = true;
        }
        if (waitingGeometryBytes != 0 && budget != null
            && budget.availableBytes(BudgetKind.HEAP) >= waitingGeometryBytes) {
            waitingGeometryBytes = 0;
            progressed = true;
        }
        if (progressed) admissionProgress++;
        return admissionProgress;
    }

    public boolean lastAdmissionBlockedByCapacity() { return admissionBlockedByCapacity; }

    private void waitForGeometrySpace(long required) {
        waitingGeometryBytes = waitingGeometryBytes == 0 ? required : Math.min(waitingGeometryBytes, required);
        admissionBlockedByCapacity = true;
    }

    public Entry find(int displayList, FrameStamp stamp) {
        threadGuard.check();
        Entry entry = entries.get(Integer.valueOf(displayList));
        if (entry == null || stamp == null || !entry.handle.belongsTo(
            stamp.getResourceGeneration(), stamp.getGlContextGeneration())
            || !resources.isLive(entry.handle)) return null;
        return entry;
    }

    /** Synchronous only during the bounded OUTPUT_VALIDATE state. */
    public boolean validate(Entry entry) {
        threadGuard.check();
        if (entry == null || !entry.allocation.isLive()
            || !resources.isLive(entry.handle)) return false;
        int previous = EarlyGlStateTracker.arrayBufferBinding();
        if (previous == Integer.MIN_VALUE) return false;
        Throwable failure = null;
        try {
            bindArrayBuffer(entry.handle.getNativeId());
            if (!ensureStaging(entry.byteLength)) return false;
            staging.clear();
            staging.limit(entry.byteLength);
            GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER,
                entry.allocation.range.getOffset(), staging);
            staging.position(0);
            staging.limit(entry.byteLength);
            entry.validated = Arrays.equals(entry.digest,
                ModelMeshPayload.sha256(staging));
        } catch (Throwable error) {
            entry.validated = false;
            failure = error;
        } finally {
            try { bindArrayBuffer(previous); }
            catch (Throwable restoreError) {
                EarlyGlStateTracker.invalidate();
                failure = appendFailure(failure, restoreError);
            }
        }
        if (failure != null) rethrow(failure);
        return entry.validated;
    }

    /**
     * Returns false only if no GL draw was issued, so the caller may execute
     * the original display list without risking a duplicate partial draw.
     */
    public boolean draw(Entry entry) {
        return draw(entry, null);
    }

    public boolean draw(Entry entry, DrawPacket packet) {
        threadGuard.check();
        if (entry == null || !entry.validated || !resources.isLive(entry.handle)) return false;
        EarlyGlStateTracker.Snapshot state = EarlyGlStateTracker.snapshot();
        if (state == null || !state.hasArrayBufferBinding()
            || !state.hasDrawState() || state.getProgram() != 0
            || state.getClientActiveTexture() != 0
            || packet == null || packet.getMeshHandle() != entry.getLogicalHandle()
            || !entry.handle.belongsTo(packet.getGeneration().getResourceGeneration(),
                packet.getGeneration().getGlContextGeneration())
            || !matches(packet.getState(), state)
            || !packet.matchesMaterial(state)
            || !EarlyMatrixStateTracker.matchesModelView(packet.rawPartMatrices())) return false;
        return emitImmediate(entry, state.getArrayBuffer());
    }

    /**
     * Allocation-free production path for a draw emitted at the exact
     * ModelRenderer call-list instruction.  No packet or matrix copy is
     * needed because no command is deferred or reordered.
     */
    public boolean drawCurrent(Entry entry, FrameStamp stamp) {
        threadGuard.check();
        if (entry == null || stamp == null || !entry.validated
            || !resources.isLive(entry.handle)
            || !entry.handle.belongsTo(stamp.getResourceGeneration(),
                stamp.getGlContextGeneration())) return false;
        int previous = EarlyGlStateTracker
            .fixedFunctionModelArrayBufferBinding();
        return previous != Integer.MIN_VALUE && emitImmediate(entry, previous);
    }

    /** Admission for a preflighted, callback-free ModelRenderer tree. */
    public Entry findStateBatchEntry(int displayList, FrameStamp stamp) {
        Entry entry = find(displayList, stamp);
        return entry != null && entry.validated ? entry : null;
    }

    public boolean beginStateBatch() {
        threadGuard.check();
        if (stateBatchActive) return false;
        int previous = EarlyGlStateTracker.fixedFunctionModelArrayBufferBinding();
        if (previous == Integer.MIN_VALUE) return false;
        stateBatchPreviousBuffer = previous;
        stateBatchPreviousVertexArray = GLContext.getCapabilities().OpenGL30
            ? currentVertexArray() : Integer.MIN_VALUE;
        stateBatchVertexArray = stateBatchPreviousVertexArray;
        stateBatchClientStatePushed = false;
        stateBatchBuffer = 0;
        stateBatchActive = true;
        stateBatchBegins++;
        return true;
    }

    /** Preflight supplies the immutable draw order used by the merged mesh. */
    public boolean beginStateBatch(Entry[] parts, int count) {
        if (!beginStateBatch()) return false;
        try {
            if (poseBatching
                && MODEL_ARRAY_LAYOUT.stride == LwjglModelPoseBatch.SOURCE_STRIDE) {
                if (poseBatch.begin(parts, count)) EarlyModelBatchBarrier.enter(poseBarrier, poseMatrixSync);
            }
            // The palette owns its merged vertex array. The ordinary layout is
            // selected only by a real immediate draw, including a later state
            // boundary; binding the first part here is otherwise discarded.
        } catch (Throwable failure) {
            try { endStateBatch(); }
            catch (Throwable cleanup) { failure = appendFailure(failure, cleanup); }
            rethrow(failure);
        }
        return true;
    }

    public void seedPoseState() {
        threadGuard.check();
        if (poseBatching) poseBatch.seedStateFromDriver();
    }

    public void configurePoseBatching(boolean enabled) {
        if (!enabled && stateBatchActive) flushPoseBatch();
        poseBatching = enabled;
    }

    public void flushPoseBatch() {
        if (stateBatchActive && poseBatch.active()) {
            try { poseBatch.flush(this); }
            finally {
                try { poseBatch.release(); }
                finally {
                    poseBatch.invalidateState();
                    EarlyModelBatchBarrier.synchronize();
                }
            }
            // A palette flush temporarily replaces all array pointers.
            stateBatchBuffer = 0;
        }
    }

    public long getPoseBatchDraws() { return poseBatch.draws; }
    public long getPoseBatchParts() { return poseBatch.parts; }
    public long getPoseBatchFallbacks() { return poseBatch.fallbacks; }
    public String getPoseBatchDetail() { return poseBatch.detail; }

    /**
     * Requires an entry admitted by findStateBatchEntry in this synchronous,
     * callback-free scope. Resource lifetime, thread and fixed-function state
     * cannot change within it. Loss of the CPU matrix mirror only ends the
     * deferred pose palette; ordinary arrays still use the driver matrices.
     */
    public boolean drawInStateBatch(Entry entry) {
        if (!stateBatchActive) return false;
        if (poseBatch.active()) {
            boolean accepted = poseBatch.enqueue(this);
            if (accepted) {
                stateBatchDraws++;
                return true;
            }
            // Matrix invalidation flushes and materializes deferred transforms
            // before discarding the mirror. Finish that palette, then keep the
            // same array scope for this and the remaining ordinary draws.
            flushPoseBatch();
            EarlyModelBatchBarrier.leave();
            poseBatch.end();
        }
        drawStateBatchImmediate(entry);
        stateBatchDraws++;
        return true;
    }

    void drawStateBatchImmediate(Entry entry) {
        prepareStateBatchArrays(entry);
        // An exception after this point must escape; legacy replay could draw twice.
        GL11.glDrawArrays(entry.drawMode, entry.allocation.firstVertex, entry.vertexCount);
    }

    private void prepareStateBatchArrays(Entry entry) {
        RenderHandle vertexArray = entry.allocation.vertexArray();
        if (vertexArray != null) {
            selectStateBatchVertexArray(vertexArray.getNativeId());
            return;
        }
        // The compatibility client stack restores VAO 0. Nonzero VAOs must
        // remain immutable; their pointer formats are not stacked completely.
        selectStateBatchVertexArray(stateBatchPreviousVertexArray == Integer.MIN_VALUE
            ? Integer.MIN_VALUE : 0);
        if (!stateBatchClientStatePushed) {
            GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
            stateBatchClientStatePushed = true;
            isolateModelArrays();
        }
        int buffer = entry.handle.getNativeId();
        if (stateBatchBuffer != buffer) {
            bindArrayBuffer(buffer);
            ModelArrayLayout layout = MODEL_ARRAY_LAYOUT;
            GL11.glVertexPointer(layout.positionCount, layout.positionType,
                layout.stride, layout.positionOffset);
            GL11.glTexCoordPointer(layout.textureCount, layout.textureType,
                layout.stride, layout.textureOffset);
            GL11.glNormalPointer(layout.normalType, layout.stride, layout.normalOffset);
            stateBatchBuffer = buffer;
            stateBatchBufferBinds++;
            stateBatchPointerSets++;
        }
    }

    private void selectStateBatchVertexArray(int vertexArray) {
        if (vertexArray != stateBatchVertexArray && vertexArray != Integer.MIN_VALUE) {
            GL30.glBindVertexArray(vertexArray);
            EarlyGlStateTracker.bindVertexArray(vertexArray);
            stateBatchVertexArray = vertexArray;
            stateBatchBuffer = 0;
        }
    }

    public void endStateBatch() {
        threadGuard.check();
        if (!stateBatchActive) return;
        try {
            flushPoseBatch();
        } finally {
            EarlyModelBatchBarrier.leave();
            poseBatch.end();
            stateBatchActive = false;
            Throwable failure = null;
            try {
                if (stateBatchClientStatePushed) {
                    selectStateBatchVertexArray(stateBatchPreviousVertexArray == Integer.MIN_VALUE
                        ? Integer.MIN_VALUE : 0);
                    GL11.glPopClientAttrib();
                    restoreArrayBufferBinding(stateBatchPreviousBuffer, true);
                } else if (EarlyGlStateTracker.arrayBufferBinding() != stateBatchPreviousBuffer) {
                    bindArrayBuffer(stateBatchPreviousBuffer);
                }
                selectStateBatchVertexArray(stateBatchPreviousVertexArray);
                stateBatchRestores++;
            } catch (Throwable error) {
                EarlyGlStateTracker.invalidate();
                failure = error;
            }
            stateBatchClientStatePushed = false;
            if (failure != null) rethrow(failure);
        }
    }

    public void rejectStateBatch() { stateBatchRejections++; }
    public long getStateBatchBegins() { return stateBatchBegins; }
    public long getStateBatchDraws() { return stateBatchDraws; }
    public long getStateBatchRestores() { return stateBatchRestores; }
    public long getStateBatchRejections() { return stateBatchRejections; }
    public long getStateBatchBufferBinds() { return stateBatchBufferBinds; }
    public long getStateBatchPointerSets() { return stateBatchPointerSets; }
    public int getSharedPageCount() { return storage.pageCount(); }

    private boolean emitImmediate(Entry entry, int previous) {
        RenderHandle vertexArray = entry.allocation.vertexArray();
        if (vertexArray != null) {
            int previousVertexArray = currentVertexArray();
            return emitVertexArray(entry, vertexArray.getNativeId(), previousVertexArray);
        }
        return emitClientArrays(entry, previous);
    }

    static int currentVertexArray() {
        int value = EarlyGlStateTracker.vertexArrayBinding();
        if (value == Integer.MIN_VALUE) {
            // One driver read after a compatibility invalidation, not per part.
            value = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            EarlyGlStateTracker.bindVertexArray(value);
        }
        return value;
    }

    private boolean emitVertexArray(Entry entry, int vertexArray, int previousVertexArray) {
        boolean issued = false;
        Throwable failure = null;
        boolean cleanupFailed = false;
        try {
            GL30.glBindVertexArray(vertexArray);
            issued = true;
            GL11.glDrawArrays(entry.drawMode, entry.allocation.firstVertex, entry.vertexCount);
        } catch (Throwable error) {
            failure = error;
        } finally {
            try {
                GL30.glBindVertexArray(previousVertexArray);
                EarlyGlStateTracker.bindVertexArray(previousVertexArray);
            } catch (Throwable error) {
                EarlyGlStateTracker.invalidate();
                cleanupFailed = true;
                failure = appendFailure(failure, error);
            }
        }
        if (failure != null) {
            FatalErrors.rethrowIfFatal(failure);
            if (issued || cleanupFailed) rethrow(failure);
            return false;
        }
        return true;
    }

    private boolean emitClientArrays(Entry entry, int previous) {
        // ModelRenderer's uploader mutates client-array state while the list
        // is compiled; callList itself preserves the caller's client state.
        // One push/pop therefore reproduces the legacy execution contract.
        // Do not also run the uploader-style disable sequence on success: pop
        // is the sole normal-path restore and the extra calls are redundant.
        boolean issued = false;
        boolean clientStatePushed = false;
        boolean clientStateRestored = false;
        Throwable failure = null;
        boolean cleanupFailed = false;
        int previousVertexArray = GLContext.getCapabilities().OpenGL30
            ? currentVertexArray() : Integer.MIN_VALUE;
        try {
            if (previousVertexArray > 0) GL30.glBindVertexArray(0);
            GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
            clientStatePushed = true;
            isolateModelArrays();
            bindArrayBuffer(entry.handle.getNativeId());
            prepareArrays();
            issued = true;
            GL11.glDrawArrays(entry.drawMode, entry.allocation.firstVertex, entry.vertexCount);
        } catch (Throwable error) {
            failure = error;
            cleanupFailed = error instanceof ClientArrayCleanupException;
        } finally {
            if (clientStatePushed) {
                try {
                    GL11.glPopClientAttrib();
                    clientStateRestored = true;
                }
                catch (Throwable error) {
                    EarlyGlStateTracker.invalidate();
                    cleanupFailed = true;
                    failure = appendFailure(failure, error);
                }
            }
            try { restoreArrayBufferBinding(previous, clientStateRestored); }
            catch (Throwable error) {
                EarlyGlStateTracker.invalidate();
                cleanupFailed = true;
                failure = appendFailure(failure, error);
            }
            if (previousVertexArray > 0) try {
                GL30.glBindVertexArray(previousVertexArray);
                EarlyGlStateTracker.bindVertexArray(previousVertexArray);
            } catch (Throwable error) {
                EarlyGlStateTracker.invalidate();
                cleanupFailed = true;
                failure = appendFailure(failure, error);
            }
        }
        if (failure != null) {
            FatalErrors.rethrowIfFatal(failure);
            if (issued || cleanupFailed) rethrow(failure);
            return false;
        }
        return true;
    }

    /** One immutable layout per shared VBO page, built only on the upload path. */
    private void prepareVertexArray(ModelMeshStorage.Allocation allocation, int previousBuffer) {
        if (allocation.vertexArray() != null || !GLContext.getCapabilities().OpenGL30) return;
        CacheBudget.Reservation reservation = resources.reserveNativeObject(
            RenderResourceKind.VERTEX_ARRAY);
        if (reservation == null) return;
        int previousVertexArray = EarlyGlStateTracker.vertexArrayBinding();
        int previousClientTexture = 0;
        int id = 0;
        boolean generated = false;
        boolean bindingsCaptured = false;
        RenderHandle handle = null;
        Throwable failure = null;
        try {
            if (previousVertexArray == Integer.MIN_VALUE) {
                previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            }
            previousClientTexture = GL11.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE);
            bindingsCaptured = true;
            id = GL30.glGenVertexArrays();
            generated = true;
            if (id <= 0) throw new IllegalStateException("model vertex array allocation failed");
            handle = resources.registerReservedObject(RenderResourceKind.VERTEX_ARRAY,
                id, allocation.handle().getResourceGeneration(),
                allocation.handle().getContextGeneration(), reservation);
            if (handle == null) throw new IllegalStateException("model vertex array registration failed");
            reservation = null;
            id = 0;
            GL30.glBindVertexArray(handle.getNativeId());
            GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
            bindArrayBuffer(allocation.handle().getNativeId());
            prepareArrays();
            allocation.vertexArray(handle);
            handle = null;
        } catch (Throwable error) {
            failure = error;
        } finally {
            if (bindingsCaptured) try {
                GL30.glBindVertexArray(previousVertexArray);
                EarlyGlStateTracker.bindVertexArray(previousVertexArray);
                GL13.glClientActiveTexture(previousClientTexture);
                EarlyGlStateTracker.clientActiveTexture(previousClientTexture);
                bindArrayBuffer(previousBuffer);
            } catch (Throwable error) {
                EarlyGlStateTracker.invalidate();
                failure = appendFailure(failure, error);
            }
            if (handle != null) try { resources.retire(handle, null); }
            catch (Throwable error) { failure = appendFailure(failure, error); }
            if (id > 0) try {
                GL30.glDeleteVertexArrays(id);
                id = 0;
            } catch (Throwable error) { failure = appendFailure(failure, error); }
            if (reservation != null && generated && id == 0) reservation.close();
            // Before glGenVertexArrays, no native name could have been created.
            if (reservation != null && !bindingsCaptured) reservation.close();
        }
        if (failure != null) rethrow(failure);
    }

    /** The client vertex-array stack also restores ARRAY_BUFFER_BINDING. */
    static void restoreArrayBufferBinding(int previous, boolean clientStateRestored) {
        if (clientStateRestored) {
            EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, previous);
        } else {
            bindArrayBuffer(previous);
        }
    }

    public void certifyCapturedEntry(Entry entry) {
        threadGuard.check();
        if (entry != null && entry.allocation.isLive()
            && resources.isLive(entry.handle)) entry.validated = true;
    }

    public void invalidate(int displayList) {
        threadGuard.check();
        Integer key = Integer.valueOf(displayList);
        Entry current = entries.get(key);
        if (current == null) return;
        // Keep the cache reference until the ledger accepts ownership.  A
        // retirement publication failure may leave the handle live.
        retire(current);
        Entry removed = entries.remove(key);
        if (removed != current) {
            throw new IllegalStateException(
                "model mesh invalidation removed an unexpected entry");
        }
    }

    public void reset(boolean contextValid) {
        threadGuard.check();
        Throwable failure = null;
        if (contextValid) {
            try { endStateBatch(); }
            catch (Throwable error) { failure = error; }
        } else {
            stateBatchActive = false;
            EarlyModelBatchBarrier.leave();
        }
        try { poseBatch.reset(contextValid); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        for (Entry entry : entries.values()) {
            entry.validated = false;
            entry.releaseGeometry();
        }
        entries.clear();
        waitingGeometryBytes = 0;
        try { storage.reset(contextValid); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        if (failure != null) rethrow(failure);
    }

    public void close(boolean contextValid) {
        threadGuard.check();
        Throwable failure = null;
        try { reset(contextValid); }
        catch (Throwable error) { failure = error; }
        staging = null;
        CacheBudget.Reservation reservation = stagingReservation;
        stagingReservation = null;
        if (reservation != null) try { reservation.close(); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        if (failure != null) rethrow(failure);
    }

    public int size() { threadGuard.check(); return entries.size(); }

    boolean ensureStaging(int required) {
        if (required <= 0 || required > MAX_STAGING_BYTES || budget == null) {
            return false;
        }
        if (staging != null && staging.capacity() >= required) return true;
        int target = MIN_STAGING_BYTES;
        while (target < required && target < MAX_STAGING_BYTES) target <<= 1;
        target = Math.max(required, Math.min(MAX_STAGING_BYTES, target));
        CacheBudget.Reservation reservation = budget.tryReserve(
            BudgetKind.DIRECT, target);
        if (reservation == null) {
            waitingStagingBytes = waitingStagingBytes == 0 ? target
                : Math.min(waitingStagingBytes, target);
            return false;
        }
        ByteBuffer replacement;
        try {
            replacement = BufferUtils.createByteBuffer(target)
                .order(ByteOrder.nativeOrder());
        } catch (Throwable error) {
            reservation.close();
            throw error;
        }
        CacheBudget.Reservation previous = stagingReservation;
        staging = replacement;
        stagingReservation = reservation;
        if (previous != null) previous.close();
        return true;
    }

    private void evictOverflow() {
        Iterator<Map.Entry<Integer, Entry>> iterator = entries.entrySet().iterator();
        while (entries.size() > MAX_ENTRIES && iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            retire(entry);
            iterator.remove();
        }
    }

    private void retire(Entry entry) {
        flushPoseBatch();
        storage.retire(entry.allocation);
        entry.validated = false;
        entry.releaseGeometry();
    }

    /**
     * Publishes one replacement transactionally.  Kept package-visible so a
     * CPU-only fault-injection test can exercise the exact map transaction
     * without requiring a live OpenGL context.
     */
    static <K, V> void publishReplacement(Map<K, V> target, K key, V current,
                                          V replacement,
                                          PublicationHook hook) {
        if (target == null || replacement == null || hook == null) {
            throw new IllegalArgumentException("model mesh publication");
        }
        try {
            target.put(key, replacement);
            hook.afterEntryPut();
        } catch (Throwable publicationFailure) {
            try {
                V mapped = target.get(key);
                if (mapped == replacement) {
                    if (current == null) {
                        V removed = target.remove(key);
                        if (removed != replacement || target.get(key) != null) {
                            throw new IllegalStateException(
                                "model VBO publication rollback failed");
                        }
                    } else {
                        target.put(key, current);
                        if (target.get(key) != current) {
                            throw new IllegalStateException(
                                "model VBO replacement rollback failed");
                        }
                    }
                } else if (mapped != current) {
                    throw new IllegalStateException(
                        "model VBO rollback found foreign entry");
                }
            } catch (Throwable rollbackFailure) {
                publicationFailure = appendFailure(publicationFailure,
                    rollbackFailure);
            }
            rethrow(publicationFailure);
        }
    }

    private static Throwable appendFailure(Throwable first, Throwable next) {
        if (first == null) return next;
        Throwable nextFatal = FatalErrors.findFatal(next);
        if (nextFatal != null && FatalErrors.findFatal(first) == null) {
            if (nextFatal != first) nextFatal.addSuppressed(first);
            return nextFatal;
        }
        if (first != next) first.addSuppressed(next);
        return first;
    }

    private static void rethrow(Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("model mesh cache operation failed", failure);
    }

    private static int prepareArrays() {
        final int vertex = 1;
        final int normal = 1 << 1;
        final int texture = 1 << 2;
        int enabled = 0;
        ModelArrayLayout layout = MODEL_ARRAY_LAYOUT;
        try {
            GL11.glVertexPointer(layout.positionCount, layout.positionType,
                layout.stride, layout.positionOffset);
            enabled |= vertex;
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glTexCoordPointer(layout.textureCount, layout.textureType,
                layout.stride, layout.textureOffset);
            enabled |= texture;
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glNormalPointer(layout.normalType, layout.stride,
                layout.normalOffset);
            enabled |= normal;
            GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
            return enabled;
        } catch (RuntimeException | Error error) {
            try { releaseArrays(enabled); }
            catch (Throwable cleanupFailure) {
                FatalErrors.rethrowIfFatal(cleanupFailure);
                if (cleanupFailure != error) error.addSuppressed(cleanupFailure);
                EarlyGlStateTracker.invalidate();
                throw new ClientArrayCleanupException(error);
            }
            throw error;
        }
    }

    /** A compiled POSITION_TEX_NORMAL list never consumes caller-only arrays. */
    private static void isolateModelArrays() {
        GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
        GL11.glDisableClientState(GL11.GL_EDGE_FLAG_ARRAY);
        GL11.glDisableClientState(org.lwjgl.opengl.GL14.GL_SECONDARY_COLOR_ARRAY);
        GL11.glDisableClientState(org.lwjgl.opengl.GL14.GL_FOG_COORDINATE_ARRAY);
        int units = GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS);
        for (int unit = 1; unit < units; unit++) {
            GL13.glClientActiveTexture(GL13.GL_TEXTURE0 + unit);
            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        }
        GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
    }

    private static void bindArrayBuffer(int nativeId) {
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, nativeId);
        EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, nativeId);
    }

    private static void releaseArrays(int enabled) {
        if ((enabled & (1 << 2)) != 0) {
            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        }
        if ((enabled & (1 << 1)) != 0) {
            GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
        }
        if ((enabled & 1) != 0) GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
    }

    private static boolean matches(RenderStateKey expected,
                                   EarlyGlStateTracker.Snapshot actual) {
        return expected != null && expected.getProgram() == actual.getProgram()
            && expected.getTexture() == actual.getTexture0()
            && expected.getLightmapTexture() == actual.getTexture1()
            && expected.isBlend() == actual.isBlend()
            && expected.getBlendSource() == actual.getBlendSourceRgb()
            && expected.getBlendDestination() == actual.getBlendDestinationRgb()
            && expected.getBlendSourceAlpha() == actual.getBlendSourceAlpha()
            && expected.getBlendDestinationAlpha() == actual.getBlendDestinationAlpha()
            && expected.isDepthTest() == actual.isDepthTest()
            && expected.isDepthMask() == actual.isDepthMask()
            && expected.isCull() == actual.isCull()
            && expected.getColorMask() == actual.getColorMask();
    }

    /** Marks a pre-draw partial enable whose rollback could not be certified. */
    private static final class ClientArrayCleanupException
        extends RuntimeException {
        private ClientArrayCleanupException(Throwable cause) {
            super("model client-array cleanup failed", cause);
        }
    }

    private static final class ModelArrayLayout {
        private final int stride;
        private final int positionCount;
        private final int positionType;
        private final long positionOffset;
        private final int textureCount;
        private final int textureType;
        private final long textureOffset;
        private final int normalType;
        private final long normalOffset;

        private ModelArrayLayout(int stride, int positionCount,
                                 int positionType, long positionOffset,
                                 int textureCount, int textureType,
                                 long textureOffset, int normalType,
                                 long normalOffset) {
            this.stride = stride;
            this.positionCount = positionCount;
            this.positionType = positionType;
            this.positionOffset = positionOffset;
            this.textureCount = textureCount;
            this.textureType = textureType;
            this.textureOffset = textureOffset;
            this.normalType = normalType;
            this.normalOffset = normalOffset;
        }

        private static ModelArrayLayout capture(VertexFormat format) {
            int positionCount = 0;
            int positionType = 0;
            long positionOffset = -1L;
            int textureCount = 0;
            int textureType = 0;
            long textureOffset = -1L;
            int normalType = 0;
            long normalOffset = -1L;
            List<VertexFormatElement> elements = format.getElements();
            for (int i = 0; i < elements.size(); i++) {
                VertexFormatElement element = elements.get(i);
                switch (element.getUsage()) {
                    case POSITION:
                        if (positionOffset >= 0L) {
                            throw new IllegalStateException(
                                "duplicate model position element");
                        }
                        positionCount = element.getElementCount();
                        positionType = element.getType().getGlConstant();
                        positionOffset = format.getOffset(i);
                        break;
                    case UV:
                        if (element.getIndex() != 0 || textureOffset >= 0L) {
                            throw new IllegalStateException(
                                "unsupported model UV element");
                        }
                        textureCount = element.getElementCount();
                        textureType = element.getType().getGlConstant();
                        textureOffset = format.getOffset(i);
                        break;
                    case NORMAL:
                        if (element.getElementCount() != 3
                            || normalOffset >= 0L) {
                            throw new IllegalStateException(
                                "unsupported model normal element");
                        }
                        normalType = element.getType().getGlConstant();
                        normalOffset = format.getOffset(i);
                        break;
                    case PADDING:
                        break;
                    default:
                        throw new IllegalStateException(
                            "unsupported model element " + element.getUsage());
                }
            }
            if (positionOffset < 0L || textureOffset < 0L
                || normalOffset < 0L) {
                throw new IllegalStateException("incomplete model array layout");
            }
            return new ModelArrayLayout(format.getSize(), positionCount,
                positionType, positionOffset, textureCount, textureType,
                textureOffset, normalType, normalOffset);
        }
    }

    public static final class Entry {
        private final int displayList;
        private final RenderHandle handle;
        private final ModelMeshStorage.Allocation allocation;
        private final VertexFormat format;
        private final int drawMode;
        private final int vertexCount;
        private final int byteLength;
        private final byte[] digest;
        private ModelMeshPayload geometry;
        private CacheBudget.Reservation geometryReservation;
        private boolean validated;

        private Entry(ModelMeshPayload payload, ModelMeshStorage.Allocation allocation,
                      CacheBudget.Reservation geometryReservation) {
            this.displayList = payload.getDisplayList();
            this.allocation = allocation;
            this.handle = allocation.handle();
            this.format = payload.getFormat();
            this.drawMode = payload.getDrawMode();
            this.vertexCount = payload.getVertexCount();
            this.byteLength = payload.getByteLength();
            this.digest = payload.getDigest();
            this.geometry = payload;
            this.geometryReservation = geometryReservation;
        }

        void copyVerticesTo(ByteBuffer target) { geometry.copyVerticesTo(target); }

        private void releaseGeometry() {
            geometry = null;
            CacheBudget.Reservation owned = geometryReservation;
            geometryReservation = null;
            if (owned != null) owned.close();
        }

        private boolean matches(ModelMeshPayload payload, FrameStamp stamp) {
            return displayList == payload.getDisplayList()
                && handle.belongsTo(stamp.getResourceGeneration(),
                    stamp.getGlContextGeneration())
                && drawMode == payload.getDrawMode()
                && vertexCount == payload.getVertexCount()
                && byteLength == payload.getByteLength()
                && payload.matchesFormat(format)
                && Arrays.equals(digest, payload.getDigest());
        }

        public long getLogicalHandle() { return allocation.id; }
        public int getNativeBuffer() { return handle.getNativeId(); }
        public int getFirstVertex() { return allocation.firstVertex; }
        public boolean isValidated() { return validated; }
        int vertexCount() { return vertexCount; }
        RenderHandle resourceHandle() { return handle; }
    }
}



