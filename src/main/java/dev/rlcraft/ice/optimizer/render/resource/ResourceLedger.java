package dev.rlcraft.ice.optimizer.render.resource;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Render-thread-only ledger with hard GPU accounting and non-blocking Fence
 * retirement. A timed-out Fence is stranded, never force-freed while the same
 * context may still reference it; the hard budget bounds that failure mode.
 */
public final class ResourceLedger {
    interface PublicationHook {
        void afterLivePut();
        void afterRetiredEnqueue();
        void afterLiveRemove();
    }

    private static final PublicationHook NO_PUBLICATION_HOOK =
        new PublicationHook() {
            @Override public void afterLivePut() { }
            @Override public void afterRetiredEnqueue() { }
            @Override public void afterLiveRemove() { }
        };

    public interface Destroyer {
        void destroy(RenderResourceKind kind, int nativeId);
    }

    public interface RetirementFence {
        boolean isSignaled();
        void destroy();
    }

    private static final int DEFAULT_MAX_FENCE_POLLS = 4096;
    // Driver-private storage for these objects cannot be queried portably.
    // Charge a conservative minimum token before native creation so a failed
    // create/delete cannot retry forever merely because the public object has
    // no byte-sized payload of its own.
    private static final long SMALL_NATIVE_OBJECT_CHARGE = 4L * 1024L;
    private static final long PROGRAM_NATIVE_OBJECT_CHARGE = 64L * 1024L;
    private static final RenderResourceKind[] DESTRUCTION_ORDER = {
        RenderResourceKind.FRAMEBUFFER,
        RenderResourceKind.VERTEX_ARRAY,
        RenderResourceKind.PROGRAM,
        RenderResourceKind.SHADER,
        RenderResourceKind.RENDERBUFFER,
        RenderResourceKind.TEXTURE,
        RenderResourceKind.BUFFER,
        RenderResourceKind.QUERY
    };

    private final RenderThreadGuard threadGuard;
    private final CacheBudget budget;
    private final Destroyer destroyer;
    private final int maximumResources;
    private final int maximumFencePolls;
    private final PublicationHook publicationHook;
    private final Map<Long, Entry> live = new HashMap<Long, Entry>();
    private final ArrayDeque<RetiredEntry> retired = new ArrayDeque<RetiredEntry>();
    private final ArrayDeque<RetiredEntry> stranded = new ArrayDeque<RetiredEntry>();
    private final RetirementFence[] uncertainFences;
    private final long[] uncertainFenceContexts;
    private int uncertainFenceCount;
    private long nextLogicalId = 1L;
    private long nextSerial = 1L;
    private long liveBytes;
    private long created;
    private long destroyed;
    private long abandoned;
    private long rejected;
    private long timedOut;
    private boolean poisonTexturePayloads;
    private boolean poisonBufferPayloads;
    private boolean poisonShaderPayloads;

    public ResourceLedger(RenderThreadGuard threadGuard, CacheBudget budget,
                          Destroyer destroyer, int maximumResources) {
        this(threadGuard, budget, destroyer, maximumResources,
            DEFAULT_MAX_FENCE_POLLS);
    }

    ResourceLedger(RenderThreadGuard threadGuard, CacheBudget budget,
                   Destroyer destroyer, int maximumResources,
                   int maximumFencePolls) {
        this(threadGuard, budget, destroyer, maximumResources,
            maximumFencePolls, NO_PUBLICATION_HOOK);
    }

    ResourceLedger(RenderThreadGuard threadGuard, CacheBudget budget,
                   Destroyer destroyer, int maximumResources,
                   int maximumFencePolls,
                   PublicationHook publicationHook) {
        if (threadGuard == null || budget == null || destroyer == null) {
            throw new IllegalArgumentException("ledger dependencies");
        }
        if (publicationHook == null) {
            throw new IllegalArgumentException("ledger publication hook");
        }
        this.threadGuard = threadGuard;
        this.budget = budget;
        this.destroyer = destroyer;
        this.maximumResources = Math.max(1, maximumResources);
        this.maximumFencePolls = Math.max(1, maximumFencePolls);
        this.publicationHook = publicationHook;
        this.uncertainFences = new RetirementFence[this.maximumResources];
        this.uncertainFenceContexts = new long[this.maximumResources];
    }

    public RenderHandle register(RenderResourceKind kind, int nativeId, long bytes,
                                 FrameStamp stamp) {
        if (stamp == null) throw new IllegalArgumentException("invalid resource registration");
        return register(kind, nativeId, bytes, stamp.getResourceGeneration(),
            stamp.getGlContextGeneration());
    }

    /** Registers render-thread resources created outside an active frame. */
    public RenderHandle register(RenderResourceKind kind, int nativeId, long bytes,
                                 long resourceGeneration,
                                 long contextGeneration) {
        threadGuard.check();
        validateRegistration(kind, nativeId, bytes, resourceGeneration,
            contextGeneration);
        if (!hasRegistrationCapacity()) return null;
        CacheBudget.Reservation reservation = bytes == 0L
            ? CacheBudget.Reservation.empty() : budget.tryReserve(BudgetKind.GPU, bytes);
        if (reservation == null) {
            rejected++;
            return null;
        }
        try {
            return publishRegistration(kind, nativeId, bytes,
                resourceGeneration, contextGeneration, reservation, true);
        } catch (Throwable failure) {
            // Also covers failures while computing counters or constructing
            // the map entry, before publishRegistration's rollback block is
            // entered. Reservation.close() is idempotent if that block already
            // completed a verified rollback.
            try { reservation.close(); }
            catch (Throwable releaseFailure) {
                failure = appendFailure(failure, releaseFailure);
            }
            rethrow(failure);
            throw new AssertionError("unreachable resource registration");
        }
    }

    /**
     * Adopts a GPU reservation acquired before an outcome-producing native
     * allocation. Ownership transfers only when this method returns a handle;
     * a rejected or rolled-back registration leaves the reservation with the
     * caller so failed native deletion can keep the hard budget poisoned.
     */
    public RenderHandle registerReserved(RenderResourceKind kind, int nativeId,
                                         long bytes, long resourceGeneration,
                                         long contextGeneration,
                                         CacheBudget.Reservation reservation) {
        threadGuard.check();
        validateRegistration(kind, nativeId, bytes, resourceGeneration,
            contextGeneration);
        if (bytes <= 0L || !budget.owns(reservation, BudgetKind.GPU, bytes)) {
            throw new IllegalArgumentException("foreign GPU reservation");
        }
        if (!hasRegistrationCapacity()) return null;
        return publishRegistration(kind, nativeId, bytes, resourceGeneration,
            contextGeneration, reservation, false);
    }

    /** Reserves GPU bytes before entering an outcome-producing native allocator. */
    public CacheBudget.Reservation reserveGpu(long bytes) {
        threadGuard.check();
        if (bytes <= 0L) throw new IllegalArgumentException("GPU reservation bytes");
        CacheBudget.Reservation reservation = budget.tryReserve(
            BudgetKind.GPU, bytes);
        if (reservation == null) rejected++;
        return reservation;
    }

    /**
     * Reserves a conservative driver-storage token before creating a GL
     * object whose real byte size is opaque (program, VAO, FBO, or query).
     */
    public CacheBudget.Reservation reserveNativeObject(
        RenderResourceKind kind) {
        return reserveGpu(nativeObjectCharge(kind));
    }

    /** Reserves a driver-storage token before creating an opaque GL sync. */
    public CacheBudget.Reservation reserveSyncObject() {
        return reserveGpu(syncObjectCharge());
    }

    public static long syncObjectCharge() { return SMALL_NATIVE_OBJECT_CHARGE; }

    /** Adopts a pre-allocation token for an opaque-size native object. */
    public RenderHandle registerReservedObject(RenderResourceKind kind,
                                               int nativeId,
                                               long resourceGeneration,
                                               long contextGeneration,
                                               CacheBudget.Reservation reservation) {
        return registerReserved(kind, nativeId, nativeObjectCharge(kind),
            resourceGeneration, contextGeneration, reservation);
    }

    public static long nativeObjectCharge(RenderResourceKind kind) {
        if (kind == RenderResourceKind.PROGRAM
            || kind == RenderResourceKind.SHADER) {
            return PROGRAM_NATIVE_OBJECT_CHARGE;
        }
        if (kind == RenderResourceKind.VERTEX_ARRAY
            || kind == RenderResourceKind.FRAMEBUFFER
            || kind == RenderResourceKind.RENDERBUFFER
            || kind == RenderResourceKind.QUERY) {
            return SMALL_NATIVE_OBJECT_CHARGE;
        }
        throw new IllegalArgumentException(
            "native object requires exact byte accounting: " + kind);
    }

    private RenderHandle publishRegistration(RenderResourceKind kind,
                                              int nativeId, long bytes,
                                              long resourceGeneration,
                                              long contextGeneration,
                                              CacheBudget.Reservation reservation,
                                              boolean releaseOnRollback) {
        long updatedLiveBytes = checkedAdd(liveBytes, bytes);
        long logical = nextLogicalId;
        long serial = nextSerial;
        RenderHandle handle = new RenderHandle(logical, serial, nativeId, kind,
            bytes, resourceGeneration, contextGeneration);
        Long logicalKey = Long.valueOf(logical);
        Entry entry = new Entry(handle, reservation);
        try {
            Entry previous = live.put(logicalKey, entry);
            if (previous != null) {
                live.put(logicalKey, previous);
                throw new IllegalStateException(
                    "resource logical id collision");
            }
            publicationHook.afterLivePut();
        } catch (Throwable publicationFailure) {
            boolean rolledBack = false;
            try {
                Entry mapped = live.get(logicalKey);
                if (mapped == entry) {
                    Entry removed = live.remove(logicalKey);
                    if (removed != entry || live.get(logicalKey) != null) {
                        throw new IllegalStateException(
                            "resource registration rollback failed");
                    }
                } else if (mapped != null) {
                    throw new IllegalStateException(
                        "resource registration rollback found foreign entry");
                }
                rolledBack = true;
            } catch (Throwable rollbackFailure) {
                publicationFailure = appendFailure(publicationFailure,
                    rollbackFailure);
            }
            if (rolledBack && releaseOnRollback) {
                try { reservation.close(); }
                catch (Throwable releaseFailure) {
                    publicationFailure = appendFailure(publicationFailure,
                        releaseFailure);
                }
            }
            rethrow(publicationFailure);
            throw new AssertionError("unreachable registration failure");
        }
        nextLogicalId++;
        nextSerial++;
        liveBytes = updatedLiveBytes;
        created++;
        return handle;
    }

    private void validateRegistration(RenderResourceKind kind, int nativeId,
                                      long bytes, long resourceGeneration,
                                      long contextGeneration) {
        if (kind == null || nativeId <= 0 || bytes < 0L
            || resourceGeneration <= 0L || contextGeneration <= 0L) {
            throw new IllegalArgumentException("invalid resource registration");
        }
        if (nextLogicalId == Long.MAX_VALUE) {
            throw new IllegalStateException("resource logical id exhausted");
        }
        if (nextSerial == Long.MAX_VALUE) {
            throw new IllegalStateException("resource serial exhausted");
        }
    }

    private boolean hasRegistrationCapacity() {
        if ((long) live.size() + (long) retired.size() + (long) stranded.size()
            + (long) uncertainFenceCount
            < (long) maximumResources) return true;
        rejected++;
        return false;
    }

    public boolean isLive(RenderHandle handle) {
        threadGuard.check();
        return entryFor(handle) != null;
    }

    public boolean retire(RenderHandle handle, RetirementFence fence) {
        threadGuard.check();
        Entry entry = entryFor(handle);
        // Ownership of a supplied Fence transfers at this call boundary even
        // when the generation-qualified handle is stale.  Callers commonly
        // create the Fence before discovering an ABA/duplicate retirement;
        // leaving cleanup with the caller made every rejection path subtly
        // different and leaked GL sync objects in several render backends.
        if (entry == null) {
            destroyFence(fence, handle == null ? 0L
                : handle.getContextGeneration());
            return false;
        }
        RetiredEntry retiredEntry = new RetiredEntry(entry, fence);
        try {
            // Queue first so an allocation failure leaves the resource live
            // and its accounting unchanged.  The ledger is render-thread-only,
            // so the temporary presence in both containers is unobservable.
            retired.addLast(retiredEntry);
            publicationHook.afterRetiredEnqueue();
        } catch (Throwable enqueueFailure) {
            boolean queueRolledBack = false;
            try {
                boolean removed = retired.removeLastOccurrence(retiredEntry);
                if (removed || !retired.contains(retiredEntry)) {
                    queueRolledBack = true;
                }
            } catch (Throwable rollbackFailure) {
                enqueueFailure = appendFailure(enqueueFailure,
                    rollbackFailure);
            }
            if (queueRolledBack) {
                try { destroyFence(fence,
                    entry.handle.getContextGeneration()); }
                catch (Throwable cleanupFailure) {
                    enqueueFailure = appendFailure(enqueueFailure,
                        cleanupFailure);
                }
            }
            rethrow(enqueueFailure);
            throw new AssertionError("unreachable retirement enqueue failure");
        }
        Long logicalKey = Long.valueOf(handle.getLogicalId());
        try {
            Entry removed = live.remove(logicalKey);
            if (removed != entry) {
                throw new IllegalStateException(
                    "resource live removal failed during retirement");
            }
            publicationHook.afterLiveRemove();
            liveBytes -= handle.getBytes();
        } catch (Throwable removalFailure) {
            boolean removedFromLive = false;
            try {
                Entry mapped = live.get(logicalKey);
                if (mapped == null) {
                    removedFromLive = true;
                    liveBytes -= handle.getBytes();
                } else if (mapped == entry) {
                    if (!retired.removeLastOccurrence(retiredEntry)) {
                        throw new IllegalStateException(
                            "resource retirement queue rollback failed");
                    }
                    destroyFence(fence,
                        entry.handle.getContextGeneration());
                } else {
                    throw new IllegalStateException(
                        "resource retirement found foreign live entry");
                }
            } catch (Throwable recoveryFailure) {
                removalFailure = appendFailure(removalFailure,
                    recoveryFailure);
            }
            // If removal completed before the injected failure, the retired
            // queue now exclusively owns both resource and Fence.  Otherwise
            // the verified rollback leaves the resource live and the Fence
            // destroyed.  In either case the caller must observe the failure.
            if (removedFromLive && liveBytes < 0L) liveBytes = 0L;
            rethrow(removalFailure);
            throw new AssertionError("unreachable live removal failure");
        }
        return true;
    }

    /** Polls readiness only; implementations must use a zero-timeout query. */
    public int collect(long currentContextGeneration, int maximumChecks) {
        threadGuard.check();
        abandonUncertainFencesExcept(currentContextGeneration);
        int checks = 0;
        int released = 0;
        int limit = Math.max(0, maximumChecks);
        // A reference container which has not passed its Fence must keep all
        // potentially referenced payloads alive. This is intentionally global
        // and conservative because the ledger does not trust caller-supplied
        // attachment graphs.
        boolean waitForFramebuffer = hasPendingKind(
            RenderResourceKind.FRAMEBUFFER, currentContextGeneration);
        boolean waitForVertexArray = hasPendingKind(
            RenderResourceKind.VERTEX_ARRAY, currentContextGeneration);
        boolean waitForProgram = hasPendingKind(
            RenderResourceKind.PROGRAM, currentContextGeneration);
        Iterator<RetiredEntry> iterator = retired.iterator();
        while (iterator.hasNext() && checks < limit) {
            RetiredEntry value = iterator.next();
            if (value.stranded) continue;
            RenderHandle handle = value.entry.handle;
            if (blockedByPendingContainer(handle.getKind(), waitForFramebuffer,
                waitForVertexArray, waitForProgram)) continue;
            checks++;
            if (handle.getContextGeneration() != currentContextGeneration) {
                // A sync object belongs to the lost GL context just like the
                // resource name.  Calling glDeleteSync through a newly-current
                // context is not cleanup: it can target an unrelated driver
                // object or raise an error.  Dropping the Java wrapper is the
                // only valid operation after context loss.
                iterator.remove();
                released++;
                LwjglRetirementFence.abandon(value.fence);
                abandon(value.entry);
                continue;
            }
            if (value.stranded) continue;
            boolean ready;
            try {
                ready = value.fence == null || value.fence.isSignaled();
            } catch (Throwable failedFence) {
                strand(value);
                timedOut++;
                rethrow(failedFence);
                return released;
            }
            if (!ready) {
                value.polls++;
                if (value.polls >= maximumFencePolls) {
                    strand(value);
                    timedOut++;
                }
                continue;
            }
            iterator.remove();
            released++;
            Throwable failure = null;
            try { destroyFence(value.fence,
                value.entry.handle.getContextGeneration()); }
            catch (Throwable error) { failure = appendFailure(failure, error); }
            try { destroy(value.entry); }
            catch (Throwable error) { failure = appendFailure(failure, error); }
            if (failure != null) rethrow(failure);
        }
        return released;
    }

    /** Context loss invalidates names; never issue deletes against the new context. */
    public int abandonContext(long lostContextGeneration) {
        threadGuard.check();
        int count = 0;
        Iterator<Entry> liveIterator = live.values().iterator();
        while (liveIterator.hasNext()) {
            Entry entry = liveIterator.next();
            if (entry.handle.getContextGeneration() == lostContextGeneration) {
                liveIterator.remove();
                liveBytes -= entry.handle.getBytes();
                abandon(entry);
                count++;
            }
        }
        Iterator<RetiredEntry> retiredIterator = retired.iterator();
        while (retiredIterator.hasNext()) {
            RetiredEntry entry = retiredIterator.next();
            if (entry.entry.handle.getContextGeneration() == lostContextGeneration) {
                retiredIterator.remove();
                LwjglRetirementFence.abandon(entry.fence);
                abandon(entry.entry);
                count++;
            }
        }
        Iterator<RetiredEntry> strandedIterator = stranded.iterator();
        while (strandedIterator.hasNext()) {
            RetiredEntry entry = strandedIterator.next();
            if (entry.entry.handle.getContextGeneration() == lostContextGeneration) {
                strandedIterator.remove();
                LwjglRetirementFence.abandon(entry.fence);
                abandon(entry.entry);
                count++;
            }
        }
        abandonUncertainFences(lostContextGeneration);
        return count;
    }

    /** Caller must first flush modern work and guarantee this context is valid. */
    public void destroyAll(long validContextGeneration) {
        threadGuard.check();
        Throwable failure = null;
        for (RenderResourceKind kind : DESTRUCTION_ORDER) {
            failure = destroyLiveKind(kind, validContextGeneration, failure);
            failure = destroyRetiredKind(retired, kind,
                validContextGeneration, failure);
            failure = destroyRetiredKind(stranded, kind,
                validContextGeneration, failure);
        }
        abandonUncertainFencesExcept(validContextGeneration);
        liveBytes = 0L;
        if (failure != null) rethrow(failure);
    }

    public ResourceLedgerStatus snapshot() {
        threadGuard.check();
        return new ResourceLedgerStatus(live.size(),
            Math.addExact(Math.addExact(retired.size(), stranded.size()),
                uncertainFenceCount), liveBytes,
            created, destroyed, abandoned, rejected, timedOut);
    }

    private Entry entryFor(RenderHandle handle) {
        if (handle == null) return null;
        Entry entry = live.get(Long.valueOf(handle.getLogicalId()));
        return entry != null && entry.handle.equals(handle) ? entry : null;
    }

    private void destroy(Entry entry) {
        // A throwing native delete is outcome-uncertain: retrying the same GL
        // name can delete a newly-reused driver object, while releasing the
        // reservation would allow repeated failures to bypass the hard GPU
        // budget.  Therefore the failed reservation remains permanently held.
        RenderResourceKind kind = entry.handle.getKind();
        try {
            destroyer.destroy(kind, entry.handle.getNativeId());
        } catch (Throwable failure) {
            poisonDependents(kind);
            rethrow(failure);
        }
        destroyed++;
        if (!payloadReservationPoisoned(kind)) entry.reservation.close();
    }

    private void abandon(Entry entry) {
        entry.reservation.close();
        abandoned++;
    }

    private void destroyFence(RetirementFence fence,
                              long contextGeneration) {
        if (fence == null) return;
        try { fence.destroy(); }
        catch (Throwable failure) {
            try { retainUncertainFence(fence, contextGeneration); }
            catch (Throwable retentionFailure) {
                failure = appendFailure(failure, retentionFailure);
            }
            rethrow(failure);
        }
    }

    private void retainUncertainFence(RetirementFence fence,
                                      long contextGeneration) {
        if (fence == null) return;
        for (int index = 0; index < uncertainFenceCount; index++) {
            if (uncertainFences[index] == fence) return;
        }
        if (uncertainFenceCount >= uncertainFences.length) {
            throw new IllegalStateException(
                "uncertain Fence ownership capacity exhausted");
        }
        uncertainFences[uncertainFenceCount] = fence;
        uncertainFenceContexts[uncertainFenceCount] = contextGeneration;
        uncertainFenceCount++;
    }

    private void abandonUncertainFences(long contextGeneration) {
        for (int index = uncertainFenceCount - 1; index >= 0; index--) {
            if (uncertainFenceContexts[index] != contextGeneration) continue;
            LwjglRetirementFence.abandon(uncertainFences[index]);
            removeUncertainFence(index);
        }
    }

    private void abandonUncertainFencesExcept(long contextGeneration) {
        for (int index = uncertainFenceCount - 1; index >= 0; index--) {
            if (uncertainFenceContexts[index] == contextGeneration) continue;
            LwjglRetirementFence.abandon(uncertainFences[index]);
            removeUncertainFence(index);
        }
    }

    private void removeUncertainFence(int index) {
        int last = --uncertainFenceCount;
        uncertainFences[index] = uncertainFences[last];
        uncertainFenceContexts[index] = uncertainFenceContexts[last];
        uncertainFences[last] = null;
        uncertainFenceContexts[last] = 0L;
    }

    private void strand(RetiredEntry value) {
        value.stranded = true;
        // Keep it in the original queue.  This requires no allocation, avoids
        // a duplicate entry if a queue migration only partially succeeds, and
        // does not consume later poll budget because collect skips it.
    }

    private Throwable destroyLiveKind(RenderResourceKind kind,
                                      long validContextGeneration,
                                      Throwable failure) {
        Iterator<Entry> iterator = live.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.handle.getKind() != kind) continue;
            iterator.remove();
            liveBytes -= entry.handle.getBytes();
            try {
                if (entry.handle.getContextGeneration()
                    == validContextGeneration) destroy(entry);
                else abandon(entry);
            } catch (Throwable error) {
                failure = appendFailure(failure, error);
            }
        }
        return failure;
    }

    private Throwable destroyRetiredKind(ArrayDeque<RetiredEntry> queue,
                                         RenderResourceKind kind,
                                         long validContextGeneration,
                                         Throwable failure) {
        Iterator<RetiredEntry> iterator = queue.iterator();
        while (iterator.hasNext()) {
            RetiredEntry value = iterator.next();
            if (value.entry.handle.getKind() != kind) continue;
            iterator.remove();
            try {
                if (value.entry.handle.getContextGeneration()
                    == validContextGeneration) {
                    Throwable localFailure = null;
                    try { destroyFence(value.fence,
                        value.entry.handle.getContextGeneration()); }
                    catch (Throwable error) {
                        localFailure = appendFailure(localFailure, error);
                    }
                    try { destroy(value.entry); }
                    catch (Throwable error) {
                        localFailure = appendFailure(localFailure, error);
                    }
                    if (localFailure != null) rethrow(localFailure);
                } else {
                    LwjglRetirementFence.abandon(value.fence);
                    abandon(value.entry);
                }
            } catch (Throwable error) {
                failure = appendFailure(failure, error);
            }
        }
        return failure;
    }

    private boolean hasPendingKind(RenderResourceKind kind,
                                   long contextGeneration) {
        for (RetiredEntry value : retired) {
            if (value.entry.handle.getKind() == kind
                && value.entry.handle.getContextGeneration()
                    == contextGeneration) return true;
        }
        for (RetiredEntry value : stranded) {
            if (value.entry.handle.getKind() == kind
                && value.entry.handle.getContextGeneration()
                    == contextGeneration) return true;
        }
        return false;
    }

    private static boolean blockedByPendingContainer(
        RenderResourceKind kind, boolean framebuffer, boolean vertexArray,
        boolean program) {
        return framebuffer && (kind == RenderResourceKind.TEXTURE
                || kind == RenderResourceKind.RENDERBUFFER)
            || vertexArray && kind == RenderResourceKind.BUFFER
            || program && kind == RenderResourceKind.SHADER;
    }

    private void poisonDependents(RenderResourceKind kind) {
        if (kind == RenderResourceKind.FRAMEBUFFER) {
            poisonTexturePayloads = true;
        } else if (kind == RenderResourceKind.VERTEX_ARRAY) {
            poisonBufferPayloads = true;
        } else if (kind == RenderResourceKind.PROGRAM) {
            poisonShaderPayloads = true;
        }
    }

    private boolean payloadReservationPoisoned(RenderResourceKind kind) {
        return poisonTexturePayloads
                && (kind == RenderResourceKind.TEXTURE
                    || kind == RenderResourceKind.RENDERBUFFER)
            || poisonBufferPayloads && kind == RenderResourceKind.BUFFER
            || poisonShaderPayloads && kind == RenderResourceKind.SHADER;
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
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("resource destruction failed", failure);
    }

    private static long checkedAdd(long left, long right) {
        if (right > Long.MAX_VALUE - left) throw new ArithmeticException("resource bytes overflow");
        return left + right;
    }

    private static final class Entry {
        private final RenderHandle handle;
        private final CacheBudget.Reservation reservation;

        private Entry(RenderHandle handle, CacheBudget.Reservation reservation) {
            this.handle = handle;
            this.reservation = reservation;
        }
    }

    private static final class RetiredEntry {
        private final Entry entry;
        private final RetirementFence fence;
        private int polls;
        private boolean stranded;

        private RetiredEntry(Entry entry, RetirementFence fence) {
            this.entry = entry;
            this.fence = fence;
        }
    }
}
