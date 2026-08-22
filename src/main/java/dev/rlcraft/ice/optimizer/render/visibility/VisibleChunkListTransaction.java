package dev.rlcraft.ice.optimizer.render.visibility;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.memory.RetainedHeap;
import java.util.List;

/**
 * Render-thread transaction for an in-place HZB compaction.
 *
 * <p>The legacy renderer owns the original {@code ArrayList}.  HZB may only
 * publish a compacted view when the terrain arena accepts that same draw.  A
 * pre-submission decline restores every original identity and its exact
 * order; a completed or uncertain submission commits by dropping the saved
 * references.  Capacity pressure always fails open before the list changes.</p>
 */
final class VisibleChunkListTransaction implements AutoCloseable {
    private static final int MAX_CHUNKS = 1 << 20;

    private final CacheBudget budget;
    private Object[] original;
    private CacheBudget.Reservation reservation =
        CacheBudget.Reservation.empty();
    private Object owner;
    private List<Object> list;
    private int originalSize;

    VisibleChunkListTransaction(CacheBudget budget) {
        this.budget = budget;
    }

    /** Takes an exact identity snapshot without changing the caller's list. */
    @SuppressWarnings("unchecked")
    boolean begin(Object expectedOwner, List<?> expectedList) {
        if (owner != null) throw new IllegalStateException(
            "unresolved HZB list transaction");
        if (expectedOwner == null || expectedList == null) return false;
        int size = expectedList.size();
        if (size <= 0 || !ensureCapacity(size)) return false;
        for (int index = 0; index < size; index++) {
            original[index] = expectedList.get(index);
        }
        owner = expectedOwner;
        list = (List<Object>) expectedList;
        originalSize = size;
        return true;
    }

    /** Keeps the compacted list (the arena normally clears it after drawing). */
    void commit(Object expectedOwner) {
        if (owner == null) return;
        requireOwner(expectedOwner);
        clearActive();
    }

    /** Restores the exact pre-HZB identities and order without reallocating. */
    void rollback(Object expectedOwner) {
        if (owner == null) return;
        requireOwner(expectedOwner);
        rollbackActive();
    }

    /** Reset-only recovery when the owning draw cannot return to its caller. */
    void rollbackActiveTransaction() {
        if (owner != null) rollbackActive();
    }

    boolean isActive() { return owner != null; }

    private void rollbackActive() {
        List<Object> target = list;
        int savedSize = originalSize;
        try {
            int shared = Math.min(target.size(), savedSize);
            for (int index = 0; index < shared; index++) {
                target.set(index, original[index]);
            }
            for (int index = shared; index < savedSize; index++) {
                target.add(original[index]);
            }
            while (target.size() > savedSize) {
                target.remove(target.size() - 1);
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            if (failure instanceof RuntimeException) {
                throw (RuntimeException) failure;
            }
            throw new IllegalStateException(
                "failed to restore HZB visible list", failure);
        }
        clearActive();
    }

    private void requireOwner(Object expectedOwner) {
        if (owner != expectedOwner) throw new IllegalStateException(
            "HZB list transaction owner changed");
    }

    private boolean ensureCapacity(int required) {
        if (required < 0 || required > MAX_CHUNKS) return false;
        if (original != null && original.length >= required) return true;
        int capacity = 256;
        while (capacity < required && capacity < MAX_CHUNKS) capacity <<= 1;
        if (capacity < required) return false;
        CacheBudget.Reservation replacement;
        try {
            replacement = RetainedHeap.reserve(budget,
                RetainedHeap.referenceArray(capacity),
                "HZB rollback snapshot");
        } catch (IllegalStateException budgetExhausted) {
            return false;
        }
        Object[] allocated;
        try {
            allocated = new Object[capacity];
        } catch (RuntimeException | Error failure) {
            replacement.close();
            throw failure;
        }
        CacheBudget.Reservation previous = reservation;
        original = allocated;
        reservation = replacement;
        if (previous != null) previous.close();
        return true;
    }

    private void clearActive() {
        int size = originalSize;
        owner = null;
        list = null;
        originalSize = 0;
        for (int index = 0; index < size; index++) original[index] = null;
    }

    @Override public void close() {
        // The runtime resolves every transaction before leaving the draw.  If
        // shutdown follows a fatal failure, retain no foreign chunk identity.
        if (owner != null) clearActive();
        original = null;
        CacheBudget.Reservation previous = reservation;
        reservation = CacheBudget.Reservation.empty();
        if (previous != null) previous.close();
    }
}
