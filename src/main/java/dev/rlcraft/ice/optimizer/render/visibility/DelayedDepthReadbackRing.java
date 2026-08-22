package dev.rlcraft.ice.optimizer.render.visibility;

import dev.rlcraft.ice.optimizer.FatalErrors;

/**
 * Bounded, render-thread-owned state machine for delayed depth readbacks.
 * Polling is always zero-timeout; a bad Fence or completion permanently
 * poisons only its slot until the enclosing GL generation is reset.
 */
public final class DelayedDepthReadbackRing<T> {
    public interface Fence {
        boolean isSignaled();
        void destroy(boolean contextValid);
    }

    public interface Completion<T> {
        void complete(int slot, T payload);
    }

    public enum FailureKind { FENCE, COMPLETION }

    public interface Failure<T> {
        void failed(int slot, T payload, FailureKind kind, Throwable error);
    }

    private final Slot<T>[] slots;
    private int cursor;
    private long busyPolls;
    private long rejectedAcquires;
    private long fenceFailures;
    private long completionFailures;
    private long poisonedSlots;

    @SuppressWarnings("unchecked")
    public DelayedDepthReadbackRing(int slotCount) {
        int count = Math.max(1, slotCount);
        slots = (Slot<T>[]) new Slot<?>[count];
        for (int i = 0; i < count; i++) slots[i] = new Slot<T>();
    }

    /** Returns a leased slot index or -1 without waiting. */
    public int tryAcquire() {
        for (int checked = 0; checked < slots.length; checked++) {
            int index = (cursor + checked) % slots.length;
            Slot<T> slot = slots[index];
            if (!slot.poisoned && !slot.leased && slot.fence == null) {
                slot.leased = true;
                cursor = (index + 1) % slots.length;
                return index;
            }
        }
        rejectedAcquires++;
        return -1;
    }

    public boolean submit(int index, T payload, Fence fence) {
        Slot<T> slot = slot(index);
        if (slot == null || !slot.leased || payload == null || fence == null) return false;
        slot.leased = false;
        slot.payload = payload;
        slot.fence = fence;
        return true;
    }

    public boolean cancel(int index, boolean poison) {
        Slot<T> slot = slot(index);
        if (slot == null || !slot.leased) return false;
        slot.leased = false;
        if (poison) poison(slot);
        return true;
    }

    /** Returns whether at least one submitted Fence still owns a payload. */
    public boolean hasSubmitted() {
        for (Slot<T> slot : slots) {
            if (slot.fence != null) return true;
        }
        return false;
    }

    /** Polls at most {@code maximumChecks} submitted Fences. */
    public int poll(int maximumChecks, Completion<T> completion) {
        return poll(maximumChecks, completion, null);
    }

    public int poll(int maximumChecks, Completion<T> completion,
                    Failure<T> failure) {
        if (completion == null) throw new IllegalArgumentException("completion");
        int checks = 0;
        int completed = 0;
        int maximum = Math.max(0, maximumChecks);
        for (int index = 0; index < slots.length && checks < maximum; index++) {
            Slot<T> slot = slots[index];
            if (slot.fence == null) continue;
            checks++;
            boolean signaled;
            try {
                signaled = slot.fence.isSignaled();
            } catch (Throwable error) {
                T payload = slot.payload;
                Fence failed = slot.fence;
                slot.fence = null;
                try { destroy(failed, true); }
                catch (Throwable cleanupFailure) {
                    slot.uncertainFence = failed;
                    error = append(error, cleanupFailure);
                }
                slot.payload = null;
                poison(slot);
                fenceFailures++;
                notifyFailure(failure, index, payload, FailureKind.FENCE, error);
                FatalErrors.rethrowIfFatal(error);
                continue;
            }
            if (!signaled) {
                busyPolls++;
                continue;
            }
            Fence fence = slot.fence;
            T payload = slot.payload;
            slot.fence = null;
            slot.payload = null;
            try {
                destroy(fence, true);
            } catch (Throwable error) {
                slot.uncertainFence = fence;
                poison(slot);
                fenceFailures++;
                notifyFailure(failure, index, payload, FailureKind.FENCE, error);
                FatalErrors.rethrowIfFatal(error);
                continue;
            }
            try {
                completion.complete(index, payload);
            } catch (Throwable error) {
                poison(slot);
                completionFailures++;
                notifyFailure(failure, index, payload, FailureKind.COMPLETION, error);
                FatalErrors.rethrowIfFatal(error);
            }
            completed++;
        }
        return completed;
    }

    public void reset(boolean contextValid) {
        Throwable failure = null;
        for (Slot<T> slot : slots) {
            Fence fence = slot.fence;
            slot.fence = null;
            slot.payload = null;
            slot.leased = false;
            slot.poisoned = false;
            if (fence != null) try { destroy(fence, contextValid); }
            catch (Throwable error) {
                if (contextValid) slot.uncertainFence = fence;
                failure = append(failure, error);
            }
            Fence uncertain = slot.uncertainFence;
            if (!contextValid) {
                slot.uncertainFence = null;
                if (uncertain != null) try { destroy(uncertain, false); }
                catch (Throwable error) { failure = append(failure, error); }
            }
            if (contextValid && uncertain != null) slot.poisoned = true;
        }
        cursor = 0;
        if (failure != null) rethrow(failure);
    }

    public int getSlotCount() { return slots.length; }
    public long getBusyPolls() { return busyPolls; }
    public long getRejectedAcquires() { return rejectedAcquires; }
    public long getFenceFailures() { return fenceFailures; }
    public long getCompletionFailures() { return completionFailures; }
    public long getPoisonedSlots() { return poisonedSlots; }

    private Slot<T> slot(int index) {
        return index < 0 || index >= slots.length ? null : slots[index];
    }

    private void poison(Slot<T> slot) {
        if (!slot.poisoned) {
            slot.poisoned = true;
            poisonedSlots++;
        }
    }

    private static void destroy(Fence fence, boolean contextValid) {
        fence.destroy(contextValid);
    }

    private static <T> void notifyFailure(Failure<T> failure, int slot, T payload,
                                          FailureKind kind, Throwable error) {
        if (failure == null) return;
        try { failure.failed(slot, payload, kind, error); }
        catch (Throwable callbackFailure) {
            error = append(error, callbackFailure);
            rethrow(error);
        }
    }

    private static Throwable append(Throwable first, Throwable next) {
        if (first == null) return next;
        Throwable nextFatal = FatalErrors.findFatal(next);
        if (nextFatal != null && FatalErrors.findFatal(first) == null) {
            if (nextFatal != first) nextFatal.addSuppressed(first);
            return nextFatal;
        }
        if (first != next) first.addSuppressed(next);
        return first;
    }

    private static void rethrow(Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        if (error instanceof RuntimeException) throw (RuntimeException) error;
        if (error instanceof Error) throw (Error) error;
        throw new IllegalStateException("depth readback cleanup failed", error);
    }

    private static final class Slot<T> {
        private T payload;
        private Fence fence;
        private Fence uncertainFence;
        private boolean leased;
        private boolean poisoned;
    }
}
