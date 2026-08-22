package dev.rlcraft.ice.optimizer.render.resource;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;

/**
 * One pre-budgeted temporary shader-stage name. Native create/delete outcomes
 * are attempted once; an uncertain outcome permanently poisons its token.
 */
public final class TemporaryShaderStage implements AutoCloseable {
    private final TemporaryGpuResourceScope.IntDestroyer destroyer;
    private CacheBudget.Reservation reservation;
    private boolean creationAttempted;
    private boolean creationReturned;
    private int nativeId;
    private boolean attached;
    private boolean deletionAttempted;
    private boolean deletionCompleted;
    private boolean closed;

    private TemporaryShaderStage(CacheBudget.Reservation reservation,
                                 TemporaryGpuResourceScope.IntDestroyer destroyer) {
        if (reservation == null || destroyer == null) {
            throw new IllegalArgumentException("temporary shader stage");
        }
        this.reservation = reservation;
        this.destroyer = destroyer;
    }

    public static TemporaryShaderStage reserve(
        ResourceLedger ledger,
        TemporaryGpuResourceScope.IntDestroyer destroyer) {
        if (ledger == null || destroyer == null) {
            throw new IllegalArgumentException("temporary shader stage source");
        }
        CacheBudget.Reservation reservation = ledger.reserveNativeObject(
            RenderResourceKind.SHADER);
        return reservation == null ? null
            : new TemporaryShaderStage(reservation, destroyer);
    }

    public static TemporaryShaderStage reserve(
        CacheBudget budget,
        TemporaryGpuResourceScope.IntDestroyer destroyer) {
        if (budget == null || destroyer == null) {
            throw new IllegalArgumentException("temporary shader stage source");
        }
        CacheBudget.Reservation reservation = budget.tryReserve(BudgetKind.GPU,
            ResourceLedger.nativeObjectCharge(RenderResourceKind.SHADER));
        return reservation == null ? null
            : new TemporaryShaderStage(reservation, destroyer);
    }

    public int create(TemporaryGpuResourceScope.IntAllocator allocator) {
        checkOpen();
        if (allocator == null) throw new IllegalArgumentException(
            "temporary shader allocator");
        if (creationAttempted) throw new IllegalStateException(
            "temporary shader allocation already attempted");
        creationAttempted = true;
        int allocated = allocator.allocate();
        creationReturned = true;
        nativeId = allocated;
        return allocated;
    }

    /** Call only after a successful glAttachShader. */
    public void markAttached() {
        checkOpen();
        if (!creationReturned || nativeId <= 0 || deletionAttempted) {
            throw new IllegalStateException("invalid shader attachment");
        }
        attached = true;
    }

    /** Call only after a successful detach or deletion of the owning program. */
    public void markDetached() {
        attached = false;
        if (closed && safeToRelease()) releaseReservation();
    }

    public int getNativeId() { return nativeId; }
    public boolean isReservationPoisoned() {
        return closed && reservation != null;
    }

    public Throwable closeAndAppend(Throwable failure) {
        if (closed) return failure;
        closed = true;
        if (creationReturned && nativeId > 0 && !deletionAttempted) {
            deletionAttempted = true;
            try {
                destroyer.destroy(nativeId);
                deletionCompleted = true;
            } catch (Throwable cleanup) {
                failure = append(failure, cleanup);
            }
        }
        if (safeToRelease()) {
            try { releaseReservation(); }
            catch (Throwable cleanup) { failure = append(failure, cleanup); }
        }
        return failure;
    }

    @Override public void close() {
        Throwable failure = closeAndAppend(null);
        if (failure != null) rethrow(failure);
    }

    private boolean safeToRelease() {
        return !creationAttempted
            || creationReturned && nativeId <= 0
            || deletionCompleted && !attached;
    }

    private void releaseReservation() {
        CacheBudget.Reservation owned = reservation;
        reservation = null;
        if (owned != null) owned.close();
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException(
            "temporary shader stage is closed");
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
        throw new IllegalStateException("temporary shader cleanup failed",
            failure);
    }
}
