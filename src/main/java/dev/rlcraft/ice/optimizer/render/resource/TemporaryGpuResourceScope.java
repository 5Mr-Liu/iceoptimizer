package dev.rlcraft.ice.optimizer.render.resource;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;

/**
 * Bounded ownership scope for short-lived GL names used by executable probes.
 * A reservation is acquired before the native allocator is entered.  It is
 * released only when allocation is known not to have produced a name, or the
 * corresponding deletion returned successfully.  An allocator or deleter
 * whose outcome is unknown deliberately poisons the shared hard budget.
 */
public final class TemporaryGpuResourceScope implements AutoCloseable {
    public interface IntAllocator {
        int allocate();
    }

    public interface IntDestroyer {
        void destroy(int nativeId);
    }

    private final CacheBudget budget;
    private final Slot[] slots;
    private int size;
    private boolean closed;

    public TemporaryGpuResourceScope(CacheBudget budget, int maximumResources) {
        if (budget == null) throw new IllegalArgumentException(
            "temporary GPU resource budget");
        if (maximumResources <= 0 || maximumResources > 256) {
            throw new IllegalArgumentException(
                "temporary GPU resource scope capacity");
        }
        this.budget = budget;
        this.slots = new Slot[maximumResources];
    }

    public Slot reserveOpaque(RenderResourceKind kind,
                              IntDestroyer destroyer) {
        return reserve(kind, ResourceLedger.nativeObjectCharge(kind), destroyer);
    }

    public Slot reserve(RenderResourceKind kind, long bytes,
                        IntDestroyer destroyer) {
        checkOpen();
        if (kind == null || bytes <= 0L || destroyer == null) {
            throw new IllegalArgumentException("temporary GPU resource");
        }
        if (size >= slots.length) {
            throw new IllegalStateException(
                "temporary GPU resource scope capacity exceeded");
        }
        CacheBudget.Reservation reservation = budget.tryReserve(
            BudgetKind.GPU, bytes);
        if (reservation == null) return null;
        try {
            Slot slot = new Slot(kind, bytes, destroyer, reservation);
            slots[size++] = slot;
            return slot;
        } catch (Throwable failure) {
            try { reservation.close(); }
            catch (Throwable cleanup) { failure = append(failure, cleanup); }
            rethrow(failure);
            throw new AssertionError("unreachable temporary reservation");
        }
    }

    public int size() { return size; }
    public boolean isClosed() { return closed; }

    /**
     * Deletes reference containers before payload objects, otherwise uses
     * reverse reservation order, and appends cleanup failures without hiding
     * the primary operation failure.
     */
    public Throwable closeAndAppend(Throwable failure) {
        if (closed) return failure;
        closed = true;
        // Container objects retain references to attachment/vertex storage.
        // Delete those containers before their payload names regardless of
        // reservation order. If a container deletion outcome is uncertain,
        // keep every possibly-referenced payload token charged even when its
        // own glDelete call returns successfully.
        failure = cleanupKind(RenderResourceKind.FRAMEBUFFER, failure);
        if (hasUncertainDeletion(RenderResourceKind.FRAMEBUFFER)) {
            poisonKind(RenderResourceKind.TEXTURE);
            poisonKind(RenderResourceKind.RENDERBUFFER);
        }
        failure = cleanupKind(RenderResourceKind.VERTEX_ARRAY, failure);
        if (hasUncertainDeletion(RenderResourceKind.VERTEX_ARRAY)) {
            poisonKind(RenderResourceKind.BUFFER);
        }
        for (int index = size - 1; index >= 0; index--) {
            Slot slot = slots[index];
            if (slot == null || slot.kind == RenderResourceKind.FRAMEBUFFER
                || slot.kind == RenderResourceKind.VERTEX_ARRAY) continue;
            failure = cleanupSlot(slot, failure);
        }
        return failure;
    }

    private Throwable cleanupKind(RenderResourceKind kind, Throwable failure) {
        for (int index = size - 1; index >= 0; index--) {
            Slot slot = slots[index];
            if (slot != null && slot.kind == kind) {
                failure = cleanupSlot(slot, failure);
            }
        }
        return failure;
    }

    private Throwable cleanupSlot(Slot slot, Throwable failure) {
        if (slot.alias == null && slot.creationReturned
            && slot.nativeId > 0 && !slot.deletionAttempted) {
            slot.deletionAttempted = true;
            try {
                slot.destroyer.destroy(slot.nativeId);
                slot.deletionCompleted = true;
            } catch (Throwable cleanup) {
                failure = append(failure, cleanup);
            }
        }
        if (slot.safeToRelease()) {
            try { slot.releaseReservation(); }
            catch (Throwable cleanup) {
                failure = append(failure, cleanup);
            }
        }
        return failure;
    }

    private boolean hasUncertainDeletion(RenderResourceKind kind) {
        for (int index = 0; index < size; index++) {
            Slot slot = slots[index];
            if (slot != null && slot.kind == kind && slot.deletionAttempted
                && !slot.deletionCompleted) return true;
        }
        return false;
    }

    private void poisonKind(RenderResourceKind kind) {
        for (int index = 0; index < size; index++) {
            Slot slot = slots[index];
            if (slot != null && slot.kind == kind) slot.forcedPoison = true;
        }
    }

    @Override
    public void close() {
        Throwable failure = closeAndAppend(null);
        if (failure != null) rethrow(failure);
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException(
            "temporary GPU resource scope is closed");
    }

    private Slot priorOwner(Slot current, int nativeId) {
        for (int index = 0; index < size; index++) {
            Slot candidate = slots[index];
            if (candidate == current) break;
            if (candidate != null && candidate.kind == current.kind
                && candidate.creationReturned
                && candidate.nativeId == nativeId && nativeId > 0) {
                return candidate.alias == null ? candidate : candidate.alias;
            }
        }
        return null;
    }

    private static Throwable append(Throwable first, Throwable next) {
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
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException(
            "temporary GPU resource cleanup failed", failure);
    }

    public final class Slot {
        private final RenderResourceKind kind;
        private final long bytes;
        private final IntDestroyer destroyer;
        private CacheBudget.Reservation reservation;
        private boolean creationAttempted;
        private boolean creationReturned;
        private int nativeId;
        private boolean deletionAttempted;
        private boolean deletionCompleted;
        private boolean forcedPoison;
        private Slot alias;

        private Slot(RenderResourceKind kind, long bytes,
                     IntDestroyer destroyer,
                     CacheBudget.Reservation reservation) {
            this.kind = kind;
            this.bytes = bytes;
            this.destroyer = destroyer;
            this.reservation = reservation;
        }

        public int allocate(IntAllocator allocator) {
            checkOpen();
            if (allocator == null) throw new IllegalArgumentException(
                "temporary native allocator");
            if (creationAttempted) throw new IllegalStateException(
                "temporary native allocation already attempted");
            creationAttempted = true;
            int allocated = allocator.allocate();
            creationReturned = true;
            nativeId = allocated;
            if (allocated > 0) {
                alias = priorOwner(this, allocated);
                if (alias != null) releaseReservation();
            }
            return allocated;
        }

        private boolean safeToRelease() {
            return !forcedPoison && (alias != null || !creationAttempted
                || creationReturned && nativeId <= 0 || deletionCompleted);
        }

        private void releaseReservation() {
            CacheBudget.Reservation owned = reservation;
            if (owned != null) {
                owned.close();
                reservation = null;
            }
        }

        public RenderResourceKind getKind() { return kind; }
        public long getReservedBytes() { return bytes; }
        public int getNativeId() { return nativeId; }
        public boolean wasAllocationAttempted() { return creationAttempted; }
        public boolean didAllocationReturn() { return creationReturned; }
        public boolean wasDeletionCompleted() { return deletionCompleted; }
        public boolean isReservationPoisoned() {
            return closed && reservation != null;
        }
    }
}
