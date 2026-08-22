package dev.rlcraft.ice.optimizer.render.visibility;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.compat.chunk.ChunkAnimatorRenderBridge;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderListAccessor;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.memory.RetainedHeap;
import dev.rlcraft.ice.optimizer.render.backend.CapabilityReport;
import dev.rlcraft.ice.optimizer.render.backend.ModernCapability;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.RenderHandle;
import dev.rlcraft.ice.optimizer.render.resource.LwjglRetirementFence;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.opengl.ARBSync;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.GLSync;

/**
 * Delayed conservative depth history. The active depth buffer is max-reduced
 * by a verified GPU shader; only the bounded low-resolution image enters the
 * PBO ring and no ordinary frame performs a synchronous GL state query.
 */
public final class LwjglDepthHistory {
    private static final int SLOT_COUNT = 2;
    private static final int MAX_SOURCE_PIXELS = 4096 * 2160;
    private static final int MAX_REDUCED_BYTES = 320 * 180 * 4;
    private static final int MAX_FILTER_CHUNKS = 1 << 20;
    private static final float DEPTH_EPSILON = 0.00025F;

    private final RenderThreadGuard guard;
    private final ResourceLedger resources;
    private final CapabilityReport capabilities;
    private final ConservativeOcclusionHistory history;
    private final LwjglConservativeDepthReducer reducer;
    private final CacheBudget budget;
    private final StableOcclusionGate occlusionGate;
    private final VisibleChunkListTransaction filterTransaction;
    private final Slot[] slots = new Slot[SLOT_COUNT];
    private final DelayedDepthReadbackRing<Capture> readbacks =
        new DelayedDepthReadbackRing<Capture>(SLOT_COUNT);
    private final StableViewCaptureGate captureGate = new StableViewCaptureGate();
    private long sceneSerial = 1L;
    private DepthFrame latest;
    private Capture pendingCapture;
    private long captures;
    private long published;
    private long rejected;
    private long occluded;
    private long rawOccluded;
    private long confirmationDeferrals;
    private long animationBypasses;
    private long filterRollbacks;
    private long filterTransactionDeferrals;
    private long filterTransactionFailures;
    private long tested;
    private long sceneInvalidations;
    private long staleCompletions;
    private long geometryChanges;
    private long coalescedGeometryChanges;
    private long deferredPolls;
    private long oracleValidatedPublications;
    private long publicationSequence;
    private int pendingOracleValidatedPublications;
    private final long[] viewGateDecisions =
        new long[StableViewCaptureGate.Decision.values().length];
    private long lastGeometryInvalidationFrame = Long.MIN_VALUE;
    private long lastCaptureFrame = Long.MIN_VALUE;
    private long authorizedFrame = Long.MIN_VALUE;
    private long authorizedScene = Long.MIN_VALUE;
    private long authorizedSignature;
    private Throwable readbackFailure;
    private RenderChunk[] filterScratch;
    private int pendingFilterRemoved;
    private CacheBudget.Reservation filterScratchReservation =
        CacheBudget.Reservation.empty();

    public enum CaptureOutcome {
        CAPTURE_READY,
        CAPTURED,
        VIEW_UNSTABLE,
        CAPTURE_PENDING,
        HISTORY_REUSED,
        TEMPORARILY_UNAVAILABLE,
        UNSAFE_STATE,
        UNSUPPORTED_SOURCE
    }

    public LwjglDepthHistory(RenderThreadGuard guard, ResourceLedger resources,
                             CapabilityReport capabilities,
                             ConservativeOcclusionHistory history) {
        this(guard, resources, capabilities, history, null);
    }

    /** Production constructor with budgeted HZB and filter scratch ownership. */
    public LwjglDepthHistory(RenderThreadGuard guard, ResourceLedger resources,
                             CapabilityReport capabilities,
                             ConservativeOcclusionHistory history,
                             CacheBudget budget) {
        if (guard == null || resources == null || capabilities == null || history == null) {
            throw new IllegalArgumentException("depth history");
        }
        this.guard = guard;
        this.resources = resources;
        this.capabilities = capabilities;
        this.history = history;
        this.budget = budget;
        this.reducer = new LwjglConservativeDepthReducer(guard, resources);
        this.occlusionGate = new StableOcclusionGate(budget);
        this.filterTransaction = new VisibleChunkListTransaction(budget);
        for (int i = 0; i < slots.length; i++) slots[i] = new Slot();
    }

    public int poll() {
        guard.check();
        // A signaled Fence does not make its PBO safe to consume unless the
        // previous pixel-pack binding is known as well.  Keep the submitted
        // ring entry intact until the frame boundary has reauthenticated the
        // software mirror; consuming first would silently discard the only
        // copy of the completed depth image.
        if (readbacks.hasSubmitted()
            && EarlyGlStateTracker.snapshot() == null) {
            if (deferredPolls != Long.MAX_VALUE) deferredPolls++;
            return 0;
        }
        int completed = readbacks.poll(SLOT_COUNT,
            new DelayedDepthReadbackRing.Completion<Capture>() {
                @Override public void complete(int index, Capture capture) {
                    if (capture == pendingCapture) pendingCapture = null;
                    if (capture == null || capture.sceneSerial != sceneSerial) {
                        if (capture != null) staleCompletions++;
                        return;
                    }
                    try {
                        DepthFrame frame = consume(slots[index], capture);
                        if (frame != null) {
                            long sequence = nextSceneSerial(publicationSequence);
                            if (sequence <= publicationSequence) {
                                occlusionGate.invalidate();
                            }
                            publicationSequence = sequence;
                            frame.publicationSerial = sequence;
                            latest = frame;
                            history.publish(frame.key, frame.hzb);
                            published++;
                            if (frame.oracleValidated) {
                                noteOracleValidatedPublication();
                            }
                        }
                    } catch (Throwable error) {
                        readbackFailure = error;
                        throw error;
                    }
                }
            }, new DelayedDepthReadbackRing.Failure<Capture>() {
                @Override public void failed(int index, Capture capture,
                                             DelayedDepthReadbackRing.FailureKind kind,
                                             Throwable error) {
                    if (capture == pendingCapture) pendingCapture = null;
                    if (kind == DelayedDepthReadbackRing.FailureKind.FENCE) {
                        poisonSlot(slots[index], true);
                        readbackFailure = error;
                    } else {
                        poisonSlot(slots[index], false);
                    }
                }
            });
        Throwable failure = readbackFailure;
        readbackFailure = null;
        if (failure != null) {
            throw new IllegalStateException("delayed HZB readback failed", failure);
        }
        return completed;
    }

    /**
     * CPU-only gate used before any fallback GL state query.  CAPTURE_READY
     * authorizes exactly one capture with the same frame, scene and view.
     */
    public CaptureOutcome preflightCapture(FrameStamp stamp, double viewX,
                                           double viewY, double viewZ) {
        guard.check();
        clearCaptureAuthorization();
        if (stamp == null || !supported()) return CaptureOutcome.UNSUPPORTED_SOURCE;
        RenderMatrixBridge.Snapshot matrices = RenderMatrixBridge.snapshot();
        if (matrices == null || matrices.getWidth() <= 0 || matrices.getHeight() <= 0) {
            rejected++;
            return CaptureOutcome.TEMPORARILY_UNAVAILABLE;
        }
        int currentDimension = dimension();
        DepthFrame reusable = latest;
        if (reusable != null && reusable.matches(matrices, viewX, viewY, viewZ,
            stamp, currentDimension)) return CaptureOutcome.HISTORY_REUSED;
        Capture pending = pendingCapture;
        if (pending != null && pending.matchesView(stamp, matrices, viewX, viewY,
            viewZ, sceneSerial, currentDimension)) {
            return CaptureOutcome.CAPTURE_PENDING;
        }
        long signature = viewSignature(stamp, matrices, viewX, viewY, viewZ,
            currentDimension);
        if (!viewCaptureAllowed(stamp.getFrameId(), signature)) {
            return CaptureOutcome.VIEW_UNSTABLE;
        }
        long pixels = (long) matrices.getWidth() * matrices.getHeight();
        if (pixels <= 0L || pixels > MAX_SOURCE_PIXELS) {
            rejected++;
            return CaptureOutcome.UNSUPPORTED_SOURCE;
        }
        authorizedFrame = stamp.getFrameId();
        authorizedScene = sceneSerial;
        authorizedSignature = signature;
        return CaptureOutcome.CAPTURE_READY;
    }

    public void cancelPreflight() {
        guard.check();
        clearCaptureAuthorization();
    }

    public CaptureOutcome capture(FrameStamp stamp, double viewX, double viewY,
                                  double viewZ, boolean validateSource) {
        return captureInternal(stamp, viewX, viewY, viewZ, validateSource,
            false);
    }

    public CaptureOutcome capturePreflighted(FrameStamp stamp, double viewX,
                                             double viewY, double viewZ,
                                             boolean validateSource) {
        return captureInternal(stamp, viewX, viewY, viewZ, validateSource,
            true);
    }

    private CaptureOutcome captureInternal(FrameStamp stamp, double viewX,
                                           double viewY, double viewZ,
                                           boolean validateSource,
                                           boolean requirePreflight) {
        guard.check();
        if (stamp == null || !supported()) {
            clearCaptureAuthorization();
            return CaptureOutcome.UNSUPPORTED_SOURCE;
        }
        RenderMatrixBridge.Snapshot matrices = RenderMatrixBridge.snapshot();
        if (matrices == null || matrices.getWidth() <= 0
            || matrices.getHeight() <= 0) {
            clearCaptureAuthorization();
            rejected++;
            return CaptureOutcome.TEMPORARILY_UNAVAILABLE;
        }
        int currentDimension = dimension();
        DepthFrame reusable = latest;
        if (reusable != null && reusable.matches(matrices, viewX, viewY, viewZ,
            stamp, currentDimension)) {
            clearCaptureAuthorization();
            return CaptureOutcome.HISTORY_REUSED;
        }
        Capture pending = pendingCapture;
        if (pending != null && pending.matchesView(stamp, matrices, viewX, viewY,
            viewZ, sceneSerial, currentDimension)) {
            clearCaptureAuthorization();
            return CaptureOutcome.CAPTURE_PENDING;
        }
        long signature = viewSignature(stamp, matrices, viewX, viewY, viewZ,
            currentDimension);
        boolean authorized = requirePreflight
            && consumeCaptureAuthorization(stamp.getFrameId(), sceneSerial,
                signature);
        if (requirePreflight && !authorized) return CaptureOutcome.VIEW_UNSTABLE;
        if (!requirePreflight) {
            clearCaptureAuthorization();
            if (!viewCaptureAllowed(stamp.getFrameId(), signature)) {
                return CaptureOutcome.VIEW_UNSTABLE;
            }
        }
        long pixels = (long) matrices.getWidth() * matrices.getHeight();
        if (pixels <= 0L || pixels > MAX_SOURCE_PIXELS) {
            rejected++;
            return CaptureOutcome.UNSUPPORTED_SOURCE;
        }
        EarlyGlStateTracker.Snapshot state = EarlyGlStateTracker.snapshot();
        if (state == null) {
            invalidateScene();
            return CaptureOutcome.TEMPORARILY_UNAVAILABLE;
        }
        if (!EarlyGlStateTracker.hasStandardDepthRange()
            || state.getProgram() != 0
            || (state.getDepthFunction() != GL11.GL_LESS
                && state.getDepthFunction() != GL11.GL_LEQUAL)) {
            invalidateScene();
            return CaptureOutcome.UNSAFE_STATE;
        }
        int slotIndex = readbacks.tryAcquire();
        if (slotIndex < 0) return CaptureOutcome.TEMPORARILY_UNAVAILABLE;
        Slot slot = slots[slotIndex];
        boolean commandsMayBeInFlight = false;
        boolean cleanupStarted = false;
        try {
            if (!ensureCapacity(slot, MAX_REDUCED_BYTES, stamp,
                state.getPixelPackBuffer())) {
                if (!readbacks.cancel(slotIndex, false)) {
                    throw new IllegalStateException("depth readback lease disappeared");
                }
                rejected++;
                return CaptureOutcome.UNSUPPORTED_SOURCE;
            }
            // reduce() can enqueue both an off-screen draw and a PBO readback.
            // From this boundary onward an outcome-uncertain failure requires
            // retiring the buffer behind a later Fence, never immediate reuse.
            commandsMayBeInFlight = true;
            LwjglConservativeDepthReducer.Reduction reduction = reducer.reduce(
                matrices, state, slot.bufferId, stamp, validateSource);
            if (reduction == null) {
                if (!readbacks.cancel(slotIndex, false)) {
                    throw new IllegalStateException("depth readback lease disappeared");
                }
                rejected++;
                return CaptureOutcome.UNSUPPORTED_SOURCE;
            }
            Capture capture = new Capture(stamp, matrices, viewX, viewY, viewZ,
                sceneSerial, currentDimension, fov(), reduction,
                validateSource);
            Fence fence = Fence.create(resources);
            if (fence == null) {
                cleanupStarted = true;
                Throwable cleanup = abortCapture(slotIndex, slot, true, false,
                    null);
                if (cleanup != null) rethrow(cleanup);
                rejected++;
                return CaptureOutcome.UNSUPPORTED_SOURCE;
            }
            if (!readbacks.submit(slotIndex, capture, fence)) {
                cleanupStarted = true;
                Throwable cleanup = null;
                try { fence.destroy(true); }
                catch (Throwable error) { cleanup = appendFailure(cleanup, error); }
                cleanup = abortCapture(slotIndex, slot, true, true, cleanup);
                if (cleanup != null) rethrow(cleanup);
                rejected++;
                return CaptureOutcome.UNSUPPORTED_SOURCE;
            }
            pendingCapture = capture;
            lastCaptureFrame = stamp.getFrameId();
            captures++;
            return CaptureOutcome.CAPTURED;
        } catch (Throwable error) {
            if (cleanupStarted) {
                rethrow(error);
                return CaptureOutcome.UNSUPPORTED_SOURCE;
            }
            rethrow(abortCapture(slotIndex, slot, commandsMayBeInFlight,
                true, error));
            return CaptureOutcome.UNSUPPORTED_SOURCE;
        }
    }

    /** Preserves relative order and removes only provably hidden opaque chunks. */
    public int filter(Object container, BlockRenderLayer layer, FrameStamp stamp) {
        guard.check();
        if (!(container instanceof TerrainRenderListAccessor) || stamp == null
            || layer == null || layer == BlockRenderLayer.TRANSLUCENT) return 0;
        TerrainRenderListAccessor accessor = (TerrainRenderListAccessor) container;
        DepthFrame frame = latest;
        RenderMatrixBridge.Snapshot current = RenderMatrixBridge.snapshot();
        if (frame == null || current == null || !frame.matches(current,
            accessor.ice$viewEntityX(), accessor.ice$viewEntityY(),
            accessor.ice$viewEntityZ(), stamp, dimension())) return 0;
        List<RenderChunk> chunks = accessor.ice$renderChunks();
        // The injected field is an ArrayList in every certified vanilla/OF
        // container.  An exotic replacement may expose a fixed or observing
        // list; never start an in-place compaction when rollback is uncertain.
        if (chunks == null || chunks.getClass() != java.util.ArrayList.class) return 0;
        int size = chunks.size();
        if (size <= 0 || !ensureFilterCapacity(size)) return 0;
        int kept = 0;
        int removed = 0;
        int evaluated = 0;
        int rawRemoved = 0;
        int deferred = 0;
        int animated = 0;
        try {
            // Complete every potentially throwing projection/HZB test before
            // mutating the caller's list. A failed test therefore leaves the
            // untouched VboRenderList input byte-for-byte reachable.
            for (int read = 0; read < size; read++) {
                RenderChunk chunk = chunks.get(read);
                if (ChunkAnimatorRenderBridge.requiresCompatibilityDraw(chunk)) {
                    occlusionGate.visible(chunk, frame.publicationSerial);
                    animated++;
                    filterScratch[kept++] = chunk;
                    continue;
                }
                evaluated++;
                if (!frame.occluded(chunk)) {
                    occlusionGate.visible(chunk, frame.publicationSerial);
                    filterScratch[kept++] = chunk;
                    continue;
                }
                rawRemoved++;
                if (occlusionGate.confirm(chunk, frame.publicationSerial)) {
                    removed++;
                } else {
                    deferred++;
                    filterScratch[kept++] = chunk;
                }
            }
            tested = saturatedAdd(tested, evaluated);
            rawOccluded = saturatedAdd(rawOccluded, rawRemoved);
            confirmationDeferrals = saturatedAdd(confirmationDeferrals,
                deferred);
            animationBypasses = saturatedAdd(animationBypasses, animated);
            if (removed <= 0) return 0;
            if (!filterTransaction.begin(container, chunks)) {
                filterTransactionDeferrals = saturatedAdd(
                    filterTransactionDeferrals, 1L);
                return 0;
            }
            pendingFilterRemoved = removed;
            try {
                for (int write = 0; write < kept; write++) {
                    chunks.set(write, filterScratch[write]);
                }
                while (chunks.size() > kept) {
                    chunks.remove(chunks.size() - 1);
                }
            } catch (Throwable mutationFailure) {
                filterTransactionFailures = saturatedAdd(
                    filterTransactionFailures, 1L);
                try {
                    rollbackFilter(container);
                } catch (Throwable rollbackFailure) {
                    mutationFailure = appendFailure(mutationFailure,
                        rollbackFailure);
                }
                rethrow(mutationFailure);
            }
            return removed;
        } finally {
            for (int i = 0; i < kept; i++) filterScratch[i] = null;
        }
    }

    /** Publishes a filtered list only after the arena owns the draw outcome. */
    public void commitFilter(Object container) {
        guard.check();
        if (!filterTransaction.isActive()) return;
        filterTransaction.commit(container);
        occluded = saturatedAdd(occluded, pendingFilterRemoved);
        pendingFilterRemoved = 0;
    }

    /** Restores the untouched legacy list after a pre-submission decline. */
    public void rollbackFilter(Object container) {
        guard.check();
        if (!filterTransaction.isActive()) return;
        try {
            filterTransaction.rollback(container);
            pendingFilterRemoved = 0;
            filterRollbacks = saturatedAdd(filterRollbacks, 1L);
        } catch (Throwable failure) {
            filterTransactionFailures = saturatedAdd(
                filterTransactionFailures, 1L);
            throw failure;
        }
    }

    private boolean ensureFilterCapacity(int required) {
        if (required < 0 || required > MAX_FILTER_CHUNKS) return false;
        if (filterScratch != null && filterScratch.length >= required) return true;
        int capacity = 256;
        while (capacity < required && capacity < MAX_FILTER_CHUNKS) capacity <<= 1;
        if (capacity < required) return false;
        CacheBudget.Reservation replacement;
        try {
            replacement = RetainedHeap.reserve(budget,
                RetainedHeap.referenceArray(capacity),
                "HZB filter scratch");
        } catch (IllegalStateException budgetExhausted) {
            return false;
        }
        RenderChunk[] allocated;
        try {
            allocated = new RenderChunk[capacity];
        } catch (RuntimeException | Error failure) {
            replacement.close();
            throw failure;
        }
        CacheBudget.Reservation previous = filterScratchReservation;
        filterScratch = allocated;
        filterScratchReservation = replacement;
        if (previous != null) previous.close();
        return true;
    }

    public boolean hasUsableHistory(FrameStamp stamp, Object container) {
        guard.check();
        if (!(container instanceof TerrainRenderListAccessor) || stamp == null
            || latest == null) return false;
        TerrainRenderListAccessor accessor = (TerrainRenderListAccessor) container;
        return latest.matches(RenderMatrixBridge.snapshot(), accessor.ice$viewEntityX(),
            accessor.ice$viewEntityY(), accessor.ice$viewEntityZ(), stamp, dimension());
    }

    /**
     * Live candidate validation: generation/matrix identity, exact pyramid
     * max invariants, and sampled coarse-query implications against level 0.
     */
    public boolean validateHistory(FrameStamp stamp, Object container) {
        guard.check();
        if (!(container instanceof TerrainRenderListAccessor) || stamp == null
            || latest == null) return false;
        TerrainRenderListAccessor accessor = (TerrainRenderListAccessor) container;
        DepthFrame frame = latest;
        if (!frame.matches(RenderMatrixBridge.snapshot(), accessor.ice$viewEntityX(),
            accessor.ice$viewEntityY(), accessor.ice$viewEntityZ(), stamp, dimension())
            || !frame.hzb.isConservativeHierarchy()) return false;
        List<RenderChunk> chunks = accessor.ice$renderChunks();
        if (chunks == null) return false;
        int stride = Math.max(1, chunks.size() / 32);
        for (int i = 0; i < chunks.size(); i += stride) {
            RenderChunk chunk = chunks.get(i);
            if (!ChunkAnimatorRenderBridge.requiresCompatibilityDraw(chunk)
                && !frame.validateProjection(chunk)) return false;
        }
        return true;
    }

    public void invalidateScene() {
        invalidateScene(true);
    }

    private void invalidateScene(boolean resetViewCadence) {
        guard.check();
        // Never leave the serial pinned at Long.MAX_VALUE: an in-flight
        // capture made immediately before invalidation would otherwise match
        // again and republish stale depth.  The ring is bounded to two live
        // captures, so wrapping to the positive start also rejects every
        // currently reachable pre-wrap payload.
        sceneSerial = nextSceneSerial(sceneSerial);
        sceneInvalidations++;
        latest = null;
        occlusionGate.invalidate();
        pendingCapture = null;
        lastCaptureFrame = Long.MIN_VALUE;
        lastGeometryInvalidationFrame = Long.MIN_VALUE;
        clearCaptureAuthorization();
        if (resetViewCadence) captureGate.invalidate();
        history.invalidate();
    }

    /**
     * Coalesces repeated upload notifications only while no capture has been
     * submitted after the preceding invalidation in the same frame.  A change
     * after capture still advances the serial and rejects that stale result.
     */
    public void geometryChanged(long frameId) {
        guard.check();
        geometryChanges++;
        if (frameId > 0L && lastGeometryInvalidationFrame == frameId
            && lastCaptureFrame != frameId) {
            coalescedGeometryChanges++;
            return;
        }
        // Upload churn changes depth identity but says nothing about camera
        // motion.  Preserve the independent exact-view cadence while still
        // invalidating every published/pending depth generation.
        invalidateScene(false);
        lastGeometryInvalidationFrame = frameId > 0L
            ? frameId : Long.MIN_VALUE;
    }

    static long nextSceneSerial(long current) {
        return current <= 0L || current == Long.MAX_VALUE ? 1L : current + 1L;
    }

    public void reset(boolean contextValid) {
        guard.check();
        Throwable failure = null;
        try { invalidateScene(); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        for (Slot slot : slots) {
            try { closeSlot(slot, contextValid); }
            catch (Throwable error) { failure = appendFailure(failure, error); }
        }
        // Current-context slot resources were first protected by a later
        // retirement Fence in closeSlot; old per-command sync objects can now
        // be deleted without making those PBOs reusable too early.
        try { readbacks.reset(contextValid); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        pendingCapture = null;
        pendingOracleValidatedPublications = 0;
        captureGate.invalidate();
        try { reducer.reset(contextValid); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        readbackFailure = null;
        if (filterTransaction.isActive()) try {
            filterTransaction.rollbackActiveTransaction();
            filterRollbacks = saturatedAdd(filterRollbacks, 1L);
        } catch (Throwable error) {
            filterTransactionFailures = saturatedAdd(
                filterTransactionFailures, 1L);
            failure = appendFailure(failure, error);
        }
        pendingFilterRemoved = 0;
        if (failure != null) rethrow(failure);
    }

    public void close(boolean contextValid) {
        Throwable failure = null;
        try { reset(contextValid); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        filterScratch = null;
        CacheBudget.Reservation reservation = filterScratchReservation;
        filterScratchReservation = CacheBudget.Reservation.empty();
        try { if (reservation != null) reservation.close(); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        try { occlusionGate.close(); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        try { filterTransaction.close(); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        if (failure != null) rethrow(failure);
    }

    public long getCaptures() { return captures; }
    public long getPublished() { return published; }
    public long getOracleValidatedPublications() {
        return oracleValidatedPublications;
    }
    public boolean consumeOracleValidatedPublication() {
        guard.check();
        if (pendingOracleValidatedPublications <= 0) return false;
        pendingOracleValidatedPublications--;
        return true;
    }
    public boolean hasPendingReadback() {
        guard.check();
        return readbacks.hasSubmitted();
    }
    public long getDeferredPolls() { return deferredPolls; }
    public long getBusy() {
        return readbacks.getBusyPolls() + readbacks.getRejectedAcquires();
    }
    public long getRejected() {
        return rejected + readbacks.getFenceFailures()
            + readbacks.getCompletionFailures();
    }
    public long getTested() { return tested; }
    public long getOccluded() { return occluded; }
    public long getRawOccluded() { return rawOccluded; }
    public long getConfirmationDeferrals() { return confirmationDeferrals; }
    public long getAnimationBypasses() { return animationBypasses; }
    public long getFilterRollbacks() { return filterRollbacks; }
    public long getFilterTransactionDeferrals() {
        return filterTransactionDeferrals;
    }
    public long getFilterTransactionFailures() {
        return filterTransactionFailures;
    }
    public long getOcclusionGateCapacityResets() {
        return occlusionGate.getCapacityResets();
    }
    public int getOcclusionGateCapacity() {
        return occlusionGate.getCapacity();
    }
    public int getOcclusionGateBudgetCapacityReductions() {
        return occlusionGate.getBudgetCapacityReductions();
    }
    public long getSceneInvalidations() { return sceneInvalidations; }
    public long getStaleCompletions() { return staleCompletions; }
    public long getGeometryChanges() { return geometryChanges; }
    public long getCoalescedGeometryChanges() {
        return coalescedGeometryChanges;
    }
    public long getViewGateInvalidInput() {
        return viewGateCount(StableViewCaptureGate.Decision.INVALID_INPUT);
    }
    public long getViewGateFirstObservation() {
        return viewGateCount(StableViewCaptureGate.Decision.FIRST_OBSERVATION);
    }
    public long getViewGateViewChanged() {
        return viewGateCount(StableViewCaptureGate.Decision.VIEW_CHANGED);
    }
    public long getViewGateDuplicateFrame() {
        return viewGateCount(StableViewCaptureGate.Decision.DUPLICATE_FRAME);
    }
    public long getViewGateFrameGap() {
        return viewGateCount(StableViewCaptureGate.Decision.FRAME_GAP);
    }
    public long getViewGateCaptureAllowed() {
        return viewGateCount(StableViewCaptureGate.Decision.CAPTURE_ALLOWED);
    }

    private boolean viewCaptureAllowed(long frameId, long signature) {
        StableViewCaptureGate.Decision decision = captureGate.observe(frameId,
            signature);
        int index = decision.ordinal();
        viewGateDecisions[index] = saturatedIncrement(viewGateDecisions[index]);
        return decision == StableViewCaptureGate.Decision.CAPTURE_ALLOWED;
    }

    private long viewGateCount(StableViewCaptureGate.Decision decision) {
        return decision == null ? 0L : viewGateDecisions[decision.ordinal()];
    }

    void noteOracleValidatedPublication() {
        oracleValidatedPublications = saturatedIncrement(
            oracleValidatedPublications);
        if (pendingOracleValidatedPublications != Integer.MAX_VALUE) {
            pendingOracleValidatedPublications++;
        }
    }

    private static long saturatedIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    private static long saturatedAdd(long value, long delta) {
        if (delta <= 0L) return value;
        return value > Long.MAX_VALUE - delta ? Long.MAX_VALUE : value + delta;
    }

    private boolean consumeCaptureAuthorization(long frameId, long scene,
                                                long signature) {
        boolean matches = authorizedFrame == frameId && authorizedScene == scene
            && authorizedSignature == signature;
        clearCaptureAuthorization();
        return matches;
    }

    private void clearCaptureAuthorization() {
        authorizedFrame = Long.MIN_VALUE;
        authorizedScene = Long.MIN_VALUE;
        authorizedSignature = 0L;
    }

    private boolean supported() {
        return capabilities.passed(ModernCapability.BUFFER_OBJECT)
            && capabilities.passed(ModernCapability.SYNC_FENCE)
            && capabilities.passed(ModernCapability.OFFSCREEN_FRAMEBUFFER)
            && capabilities.passed(ModernCapability.CONSERVATIVE_HZB);
    }

    private boolean ensureCapacity(Slot slot, int bytes, FrameStamp stamp,
                                   int previousPackBuffer) {
        if ((slot.bufferId == 0) != (slot.bufferHandle == null)
            || slot.bufferHandle != null
                && slot.bufferHandle.getNativeId() != slot.bufferId) {
            throw new IllegalStateException("depth PBO ownership mismatch");
        }
        if (slot.bufferId != 0 && slot.capacity >= bytes) return true;
        if (slot.bufferHandle != null) {
            resources.retire(slot.bufferHandle, null);
            slot.bufferHandle = null;
            slot.bufferId = 0;
            slot.capacity = 0;
        }
        int created = 0;
        boolean result = false;
        boolean deleteCompleted = false;
        boolean allocationReturned = false;
        boolean nativeNameCreated = false;
        Throwable failure = null;
        CacheBudget.Reservation reservation = resources.reserveGpu(bytes);
        if (reservation == null) return false;
        try {
            created = GL15.glGenBuffers();
            allocationReturned = true;
            if (created <= 0) throw new IllegalStateException(
                "depth glGenBuffers failed");
            nativeNameCreated = true;
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, created);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, (long) bytes, GL15.GL_STREAM_READ);
            RenderHandle handle = resources.registerReserved(
                RenderResourceKind.BUFFER, created, bytes,
                stamp.getResourceGeneration(), stamp.getGlContextGeneration(),
                reservation);
            if (handle != null) {
                reservation = null;
                slot.bufferId = created;
                slot.bufferHandle = handle;
                slot.capacity = bytes;
                created = 0;
                nativeNameCreated = false;
                result = true;
            }
        } catch (Throwable error) {
            failure = error;
        }
        if (nativeNameCreated) {
            try {
                GL15.glDeleteBuffers(created);
                deleteCompleted = true;
            } catch (Throwable cleanup) {
                failure = appendFailure(failure, cleanup);
            }
        }
        boolean noNameCreated = allocationReturned && !nativeNameCreated;
        if (reservation != null && (noNameCreated || deleteCompleted)) try {
            reservation.close();
        } catch (Throwable cleanup) {
            failure = appendFailure(failure, cleanup);
        }
        try { GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPackBuffer); }
        catch (Throwable restoreError) {
            EarlyGlStateTracker.invalidate();
            failure = appendFailure(failure, restoreError);
        }
        if (failure != null) rethrow(failure);
        return result;
    }

    private DepthFrame consume(Slot slot, Capture capture) {
        int bytes = capture.reduction.getBytes();
        EarlyGlStateTracker.Snapshot state = EarlyGlStateTracker.snapshot();
        if (state == null) return null;
        ByteBuffer mapped = null;
        DepthFrame result = null;
        Throwable failure = null;
        try {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, slot.bufferId);
            mapped = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0L,
                bytes, GL30.GL_MAP_READ_BIT, null);
            if (mapped == null) throw new IllegalStateException("depth PBO map failed");
            mapped.order(ByteOrder.nativeOrder());
            FloatBuffer source = mapped.asFloatBuffer();
            int scale = capture.reduction.getScale();
            int lowWidth = capture.reduction.getWidth();
            int lowHeight = capture.reduction.getHeight();
            ConservativeHzb hzb = null;
            try {
                hzb = ConservativeHzb.buildStandardDepth(source, lowWidth,
                    lowHeight, budget);
                if (!capture.reduction.validatesOracle(hzb.levelZeroUnsafe())) {
                    throw new IllegalStateException(
                        "GPU depth reduction violated source oracle");
                }
                if (capture.validateSource
                    && !capture.reduction.hasOracle()) {
                    throw new IllegalStateException(
                        "GPU depth reduction source oracle missing");
                }
                long cameraHash = cameraHash(capture.viewX, capture.viewY,
                    capture.viewZ);
                HzbHistoryKey key = new HzbHistoryKey(capture.dimension,
                    lowWidth, lowHeight, capture.fov, cameraHash,
                    capture.stamp.getViewFrustumGeneration(),
                    capture.stamp.getShaderPackGeneration(),
                    capture.matrices.getMatrixHash(), true);
                result = new DepthFrame(capture, hzb, key, scale, lowWidth,
                    lowHeight, capture.validateSource);
                hzb = null;
            } finally {
                if (hzb != null) hzb.close();
            }
        } catch (Throwable error) {
            failure = error;
        } finally {
            try {
                if (mapped != null && !GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER)) {
                    throw new IllegalStateException("depth PBO contents became corrupt");
                }
            } catch (Throwable unmapError) {
                failure = appendFailure(failure, unmapError);
            }
            try {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,
                    state.getPixelPackBuffer());
            } catch (Throwable restoreError) {
                EarlyGlStateTracker.invalidate();
                failure = appendFailure(failure, restoreError);
            }
        }
        if (failure != null) rethrow(failure);
        return result;
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
        throw new IllegalStateException("depth history operation failed", failure);
    }

    private void closeSlot(Slot slot, boolean contextValid) {
        RenderHandle handle = slot.bufferHandle;
        if (contextValid && handle != null) {
            resources.retire(handle,
                LwjglRetirementFence.afterCurrentCommands(resources));
        }
        slot.bufferHandle = null;
        slot.bufferId = 0;
        slot.capacity = 0;
    }

    /** A command may already reference this PBO; retire the name, never reuse it. */
    private void poisonSlot(Slot slot, boolean commandsMayBeInFlight) {
        RenderHandle handle = slot.bufferHandle;
        if (handle != null) resources.retire(handle, commandsMayBeInFlight
            ? LwjglRetirementFence.afterCurrentCommands(resources) : null);
        slot.bufferHandle = null;
        slot.bufferId = 0;
        slot.capacity = 0;
    }

    /** Aggregates cleanup without ever losing the primary capture failure. */
    private Throwable abortCapture(int slotIndex, Slot slot,
                                   boolean commandsMayBeInFlight,
                                   boolean poisonLease, Throwable failure) {
        try { poisonSlot(slot, commandsMayBeInFlight); }
        catch (Throwable cleanup) {
            failure = appendFailure(failure, cleanup);
        }
        try {
            if (!readbacks.cancel(slotIndex, poisonLease)) {
                failure = appendFailure(failure, new IllegalStateException(
                    "depth readback lease cleanup failed"));
            }
        } catch (Throwable cleanup) {
            failure = appendFailure(failure, cleanup);
        }
        return failure;
    }

    private static int dimension() {
        Minecraft minecraft = Minecraft.getMinecraft();
        return minecraft == null || minecraft.world == null
            ? Integer.MIN_VALUE : minecraft.world.provider.getDimension();
    }

    private static float fov() {
        Minecraft minecraft = Minecraft.getMinecraft();
        return minecraft == null || minecraft.gameSettings == null
            ? 0.0F : minecraft.gameSettings.fovSetting;
    }

    private static long cameraHash(double x, double y, double z) {
        long value = Double.doubleToRawLongBits(x);
        value = value * 31L + Double.doubleToRawLongBits(y);
        return value * 31L + Double.doubleToRawLongBits(z);
    }

    private static long viewSignature(FrameStamp stamp,
                                      RenderMatrixBridge.Snapshot matrices,
                                      double viewX, double viewY, double viewZ,
                                      int dimension) {
        long value = matrices.getMatrixHash();
        value = mix(value, Double.doubleToRawLongBits(viewX));
        value = mix(value, Double.doubleToRawLongBits(viewY));
        value = mix(value, Double.doubleToRawLongBits(viewZ));
        value = mix(value, dimension);
        value = mix(value, stamp.getWorldGeneration());
        value = mix(value, stamp.getResourceGeneration());
        value = mix(value, stamp.getGlContextGeneration());
        value = mix(value, stamp.getShaderPackGeneration());
        value = mix(value, stamp.getShaderPermutationGeneration());
        return mix(value, stamp.getViewFrustumGeneration());
    }

    private static long mix(long current, long value) {
        long mixed = current ^ value;
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        return mixed;
    }

    private static final class Slot {
        private int bufferId;
        private int capacity;
        private RenderHandle bufferHandle;
    }

    private static final class Capture {
        private final FrameStamp stamp;
        private final RenderMatrixBridge.Snapshot matrices;
        private final double viewX;
        private final double viewY;
        private final double viewZ;
        private final long sceneSerial;
        private final int dimension;
        private final float fov;
        private final LwjglConservativeDepthReducer.Reduction reduction;
        private final boolean validateSource;

        private Capture(FrameStamp stamp, RenderMatrixBridge.Snapshot matrices,
                        double viewX, double viewY, double viewZ,
                        long sceneSerial, int dimension, float fov,
                        LwjglConservativeDepthReducer.Reduction reduction,
                        boolean validateSource) {
            this.stamp = stamp;
            this.matrices = matrices;
            this.viewX = viewX;
            this.viewY = viewY;
            this.viewZ = viewZ;
            this.sceneSerial = sceneSerial;
            this.dimension = dimension;
            this.fov = fov;
            this.reduction = reduction;
            this.validateSource = validateSource;
        }

        private boolean matchesView(FrameStamp currentStamp,
                                    RenderMatrixBridge.Snapshot currentMatrices,
                                    double currentX, double currentY,
                                    double currentZ, long currentScene,
                                    int currentDimension) {
            return currentStamp != null && currentMatrices != null
                && sceneSerial == currentScene && dimension == currentDimension
                && Double.doubleToRawLongBits(viewX)
                    == Double.doubleToRawLongBits(currentX)
                && Double.doubleToRawLongBits(viewY)
                    == Double.doubleToRawLongBits(currentY)
                && Double.doubleToRawLongBits(viewZ)
                    == Double.doubleToRawLongBits(currentZ)
                && matrices.matrixEquals(currentMatrices)
                && stamp.getWorldGeneration()
                    == currentStamp.getWorldGeneration()
                && stamp.getResourceGeneration()
                    == currentStamp.getResourceGeneration()
                && stamp.getGlContextGeneration()
                    == currentStamp.getGlContextGeneration()
                && stamp.getShaderPackGeneration()
                    == currentStamp.getShaderPackGeneration()
                && stamp.getShaderPermutationGeneration()
                    == currentStamp.getShaderPermutationGeneration()
                && stamp.getViewFrustumGeneration()
                    == currentStamp.getViewFrustumGeneration();
        }
    }

    private static final class DepthFrame {
        private final Capture capture;
        private final ConservativeHzb hzb;
        private final HzbHistoryKey key;
        private final int scale;
        private final int lowWidth;
        private final int lowHeight;
        private final boolean oracleValidated;
        private final Projection projectionScratch = new Projection();
        private long publicationSerial;

        private DepthFrame(Capture capture, ConservativeHzb hzb, HzbHistoryKey key,
                           int scale, int lowWidth, int lowHeight,
                           boolean oracleValidated) {
            this.capture = capture;
            this.hzb = hzb;
            this.key = key;
            this.scale = scale;
            this.lowWidth = lowWidth;
            this.lowHeight = lowHeight;
            this.oracleValidated = oracleValidated;
        }

        private boolean matches(RenderMatrixBridge.Snapshot matrices,
                                double viewX, double viewY, double viewZ,
                                FrameStamp stamp, int dimension) {
            return matrices != null && stamp != null && dimension == capture.dimension
                && Double.doubleToRawLongBits(viewX)
                    == Double.doubleToRawLongBits(capture.viewX)
                && Double.doubleToRawLongBits(viewY)
                    == Double.doubleToRawLongBits(capture.viewY)
                && Double.doubleToRawLongBits(viewZ)
                    == Double.doubleToRawLongBits(capture.viewZ)
                && capture.matrices.matrixEquals(matrices)
                && capture.stamp.getWorldGeneration() == stamp.getWorldGeneration()
                && capture.stamp.getResourceGeneration() == stamp.getResourceGeneration()
                && capture.stamp.getGlContextGeneration() == stamp.getGlContextGeneration()
                && capture.stamp.getShaderPackGeneration()
                    == stamp.getShaderPackGeneration()
                && capture.stamp.getShaderPermutationGeneration()
                    == stamp.getShaderPermutationGeneration()
                && capture.stamp.getViewFrustumGeneration()
                    == stamp.getViewFrustumGeneration();
        }

        private boolean occluded(RenderChunk chunk) {
            if (chunk == null) return false;
            BlockPos position = chunk.getPosition();
            Projection rectangle = projectionScratch;
            if (!project(position.getX(), position.getY(), position.getZ(),
                position.getX() + 16.0D, position.getY() + 16.0D,
                position.getZ() + 16.0D, rectangle)) return false;
            OcclusionResult result = hzb.test(rectangle.minX, rectangle.minY,
                rectangle.maxX, rectangle.maxY, rectangle.nearestDepth, DEPTH_EPSILON);
            return result == OcclusionResult.OCCLUDED;
        }

        private boolean validateProjection(RenderChunk chunk) {
            if (chunk == null) return true;
            BlockPos position = chunk.getPosition();
            Projection rectangle = projectionScratch;
            if (!project(position.getX(), position.getY(), position.getZ(),
                position.getX() + 16.0D, position.getY() + 16.0D,
                position.getZ() + 16.0D, rectangle)) return true;
            OcclusionResult hierarchical = hzb.test(rectangle.minX, rectangle.minY,
                rectangle.maxX, rectangle.maxY, rectangle.nearestDepth, DEPTH_EPSILON);
            if (hierarchical != OcclusionResult.OCCLUDED) return true;
            return hzb.testBaseReference(rectangle.minX, rectangle.minY,
                rectangle.maxX, rectangle.maxY, rectangle.nearestDepth,
                DEPTH_EPSILON) == OcclusionResult.OCCLUDED;
        }

        private boolean project(double minX, double minY, double minZ,
                                double maxX, double maxY, double maxZ,
                                Projection target) {
            float[] model = capture.matrices.modelViewUnsafe();
            float[] projection = capture.matrices.projectionUnsafe();
            double minScreenX = Double.POSITIVE_INFINITY;
            double minScreenY = Double.POSITIVE_INFINITY;
            double maxScreenX = Double.NEGATIVE_INFINITY;
            double maxScreenY = Double.NEGATIVE_INFINITY;
            float nearest = 1.0F;
            for (int corner = 0; corner < 8; corner++) {
                double x = ((corner & 1) == 0 ? minX : maxX) - capture.viewX;
                double y = ((corner & 2) == 0 ? minY : maxY) - capture.viewY;
                double z = ((corner & 4) == 0 ? minZ : maxZ) - capture.viewZ;
                double eyeX = model[0] * x + model[4] * y + model[8] * z + model[12];
                double eyeY = model[1] * x + model[5] * y + model[9] * z + model[13];
                double eyeZ = model[2] * x + model[6] * y + model[10] * z + model[14];
                double eyeW = model[3] * x + model[7] * y + model[11] * z + model[15];
                double clipX = projection[0] * eyeX + projection[4] * eyeY
                    + projection[8] * eyeZ + projection[12] * eyeW;
                double clipY = projection[1] * eyeX + projection[5] * eyeY
                    + projection[9] * eyeZ + projection[13] * eyeW;
                double clipZ = projection[2] * eyeX + projection[6] * eyeY
                    + projection[10] * eyeZ + projection[14] * eyeW;
                double clipW = projection[3] * eyeX + projection[7] * eyeY
                    + projection[11] * eyeZ + projection[15] * eyeW;
                if (!(clipW > 0.0D) || !Double.isFinite(clipW)) return false;
                double ndcX = clipX / clipW;
                double ndcY = clipY / clipW;
                double ndcZ = clipZ / clipW;
                if (!Double.isFinite(ndcX) || !Double.isFinite(ndcY)
                    || !Double.isFinite(ndcZ) || ndcZ < -1.0D || ndcZ > 1.0D) return false;
                double screenX = (ndcX * 0.5D + 0.5D) * capture.matrices.getWidth();
                double screenY = (ndcY * 0.5D + 0.5D) * capture.matrices.getHeight();
                minScreenX = Math.min(minScreenX, screenX);
                minScreenY = Math.min(minScreenY, screenY);
                maxScreenX = Math.max(maxScreenX, screenX);
                maxScreenY = Math.max(maxScreenY, screenY);
                nearest = Math.min(nearest, (float) (ndcZ * 0.5D + 0.5D));
            }
            int minPixelX = Math.max(0, (int) Math.floor(minScreenX) - 1);
            int minPixelY = Math.max(0, (int) Math.floor(minScreenY) - 1);
            int maxPixelX = Math.min(capture.matrices.getWidth(),
                (int) Math.ceil(maxScreenX) + 1);
            int maxPixelY = Math.min(capture.matrices.getHeight(),
                (int) Math.ceil(maxScreenY) + 1);
            if (minPixelX >= maxPixelX || minPixelY >= maxPixelY) return false;
            int lowMinX = Math.max(0, minPixelX / scale);
            int lowMinY = Math.max(0, minPixelY / scale);
            int lowMaxX = Math.min(lowWidth, (maxPixelX + scale - 1) / scale);
            int lowMaxY = Math.min(lowHeight, (maxPixelY + scale - 1) / scale);
            if (lowMinX >= lowMaxX || lowMinY >= lowMaxY) return false;
            target.set(lowMinX, lowMinY, lowMaxX, lowMaxY, nearest);
            return true;
        }
    }

    private static final class Projection {
        private int minX;
        private int minY;
        private int maxX;
        private int maxY;
        private float nearestDepth;
        private void set(int minX, int minY, int maxX, int maxY,
                         float nearestDepth) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.nearestDepth = nearestDepth;
        }
    }

    private static final class Fence implements DelayedDepthReadbackRing.Fence {
        private LwjglRetirementFence delegate;

        private Fence(LwjglRetirementFence delegate) {
            this.delegate = delegate;
        }

        private static Fence create(ResourceLedger resources) {
            LwjglRetirementFence fence =
                LwjglRetirementFence.tryAfterCurrentCommands(resources);
            return fence == null ? null : new Fence(fence);
        }

        @Override public boolean isSignaled() {
            return delegate == null || delegate.isSignaled();
        }

        @Override public void destroy(boolean contextValid) {
            LwjglRetirementFence current = delegate;
            delegate = null;
            if (current == null) return;
            if (contextValid) current.destroy();
            else current.abandon();
        }
    }
}
