package dev.rlcraft.ice.optimizer.render.arena;

import dev.rlcraft.ice.optimizer.FatalErrors;

/** Bounded non-blocking staging-slot state machine with serial-qualified leases. */
public final class UploadRing {
    public interface Fence {
        boolean isSignaled();
        void destroy();
    }

    private final Slot[] slots;
    private final int maximumProbes;
    private int cursor;
    private long nextSerial = 1L;
    private long busy;
    private long rejected;
    private long poisoned;

    public UploadRing(int slotCount, int maximumProbes) {
        int count = Math.max(1, slotCount);
        slots = new Slot[count];
        for (int i = 0; i < count; i++) slots[i] = new Slot();
        this.maximumProbes = Math.max(1, Math.min(count, maximumProbes));
    }

    public Lease tryAcquire(long requiredBytes) {
        if (requiredBytes <= 0L) {
            rejected++;
            return null;
        }
        for (int checked = 0; checked < maximumProbes; checked++) {
            int index = (cursor + checked) % slots.length;
            Slot slot = slots[index];
            if (slot.poisoned) continue;
            if (slot.fence != null) {
                boolean ready;
                try { ready = slot.fence.isSignaled(); }
                catch (Throwable error) {
                    slot.poisoned = true;
                    poisoned++;
                    FatalErrors.rethrowIfFatal(error);
                    continue;
                }
                if (!ready) {
                    busy++;
                    continue;
                }
                try { destroyFence(slot); }
                catch (Throwable error) {
                    slot.poisoned = true;
                    poisoned++;
                    FatalErrors.rethrowIfFatal(error);
                    continue;
                }
            }
            if (slot.leased) continue;
            if (nextSerial == Long.MAX_VALUE) throw new IllegalStateException("upload lease exhausted");
            slot.leased = true;
            slot.serial = nextSerial++;
            slot.requiredBytes = requiredBytes;
            cursor = (index + 1) % slots.length;
            return new Lease(index, slot.serial, requiredBytes);
        }
        cursor = (cursor + maximumProbes) % slots.length;
        rejected++;
        return null;
    }

    public boolean submit(Lease lease, Fence fence) {
        Slot slot = slotFor(lease);
        if (slot == null || fence == null) return false;
        slot.leased = false;
        slot.fence = fence;
        return true;
    }

    public boolean cancel(Lease lease) {
        Slot slot = slotFor(lease);
        if (slot == null) return false;
        slot.leased = false;
        slot.requiredBytes = 0L;
        return true;
    }

    public boolean poison(Lease lease) {
        Slot slot = slotFor(lease);
        if (slot == null) return false;
        slot.leased = false;
        slot.poisoned = true;
        poisoned++;
        return true;
    }

    public void reset(boolean contextValid) {
        Throwable failure = null;
        for (Slot slot : slots) {
            if (contextValid) try { destroyFence(slot); }
            catch (Throwable error) { failure = append(failure, error); }
            else {
                slot.fence = null;
                slot.uncertainFence = null;
            }
            slot.leased = false;
            slot.poisoned = contextValid && slot.uncertainFence != null;
            slot.requiredBytes = 0L;
            slot.serial = 0L;
        }
        cursor = 0;
        if (failure != null) rethrow(failure);
    }

    public int getSlotCount() { return slots.length; }
    public long getBusy() { return busy; }
    public long getRejected() { return rejected; }
    public long getPoisoned() { return poisoned; }

    private Slot slotFor(Lease lease) {
        if (lease == null || lease.slot < 0 || lease.slot >= slots.length) return null;
        Slot slot = slots[lease.slot];
        return slot.leased && slot.serial == lease.serial ? slot : null;
    }

    private static void destroyFence(Slot slot) {
        Fence fence = slot.fence;
        slot.fence = null;
        if (fence == null) return;
        try { fence.destroy(); }
        catch (Throwable error) {
            slot.uncertainFence = fence;
            throw error;
        }
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
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("upload ring cleanup failed", failure);
    }

    public static final class Lease {
        private final int slot;
        private final long serial;
        private final long requiredBytes;

        private Lease(int slot, long serial, long requiredBytes) {
            this.slot = slot;
            this.serial = serial;
            this.requiredBytes = requiredBytes;
        }

        public int getSlot() { return slot; }
        public long getSerial() { return serial; }
        public long getRequiredBytes() { return requiredBytes; }
    }

    private static final class Slot {
        private long serial;
        private long requiredBytes;
        private boolean leased;
        private boolean poisoned;
        private Fence fence;
        private Fence uncertainFence;
    }
}
