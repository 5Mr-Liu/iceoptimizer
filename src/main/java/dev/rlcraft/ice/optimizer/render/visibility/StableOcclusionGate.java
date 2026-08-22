package dev.rlcraft.ice.optimizer.render.visibility;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.memory.RetainedHeap;
import java.util.Arrays;

/**
 * Bounded identity table requiring independent depth publications before an
 * HZB candidate is allowed to alter the real visible list.
 */
final class StableOcclusionGate implements AutoCloseable {
    private static final int DEFAULT_CAPACITY = 1 << 15;
    private static final int DEFAULT_CONFIRMATIONS = 2;

    private final Object[] keys;
    private final long[] publications;
    private final int[] epochs;
    private final byte[] confirmations;
    private final int mask;
    private final int requiredConfirmations;
    private final int budgetCapacityReductions;
    private CacheBudget.Reservation reservation;
    private int epoch = 1;
    private int size;
    private long capacityResets;

    StableOcclusionGate(CacheBudget budget) {
        this(DEFAULT_CAPACITY, DEFAULT_CONFIRMATIONS, budget);
    }

    StableOcclusionGate(int requestedCapacity, int requiredConfirmations,
                        CacheBudget budget) {
        if (requestedCapacity <= 0 || requiredConfirmations <= 0
            || requiredConfirmations > Byte.MAX_VALUE) {
            throw new IllegalArgumentException("stable occlusion gate");
        }
        int capacity = 1;
        while (capacity < requestedCapacity) capacity <<= 1;
        CacheBudget.Reservation acquired = CacheBudget.Reservation.empty();
        int reductions = 0;
        while (capacity > 0) {
            long bytes = retainedBytes(capacity);
            try {
                acquired = RetainedHeap.reserve(budget, bytes,
                    "stable HZB occlusion witnesses");
                break;
            } catch (IllegalStateException budgetExhausted) {
                capacity >>= 1;
                reductions++;
            }
        }
        reservation = acquired;
        Object[] allocatedKeys = null;
        long[] allocatedPublications = null;
        int[] allocatedEpochs = null;
        byte[] allocatedConfirmations = null;
        try {
            allocatedKeys = new Object[capacity];
            allocatedPublications = new long[capacity];
            allocatedEpochs = new int[capacity];
            allocatedConfirmations = new byte[capacity];
        } catch (RuntimeException | Error failure) {
            reservation.close();
            reservation = CacheBudget.Reservation.empty();
            throw failure;
        }
        keys = allocatedKeys;
        publications = allocatedPublications;
        epochs = allocatedEpochs;
        confirmations = allocatedConfirmations;
        mask = capacity <= 0 ? 0 : capacity - 1;
        this.requiredConfirmations = requiredConfirmations;
        budgetCapacityReductions = reductions;
    }

    boolean confirm(Object key, long publication) {
        if (key == null || publication <= 0L || keys.length == 0) return false;
        if (size >= keys.length * 3 / 4) {
            invalidate();
            capacityResets++;
        }
        int slot = find(key);
        if (epochs[slot] != epoch) {
            keys[slot] = key;
            publications[slot] = publication;
            confirmations[slot] = 1;
            epochs[slot] = epoch;
            size++;
            return requiredConfirmations <= 1;
        }
        if (publications[slot] == publication) {
            return confirmations[slot] >= requiredConfirmations;
        }
        int count = isNext(publications[slot], publication)
            ? Math.min(requiredConfirmations, confirmations[slot] + 1) : 1;
        publications[slot] = publication;
        confirmations[slot] = (byte) count;
        return count >= requiredConfirmations;
    }

    void visible(Object key, long publication) {
        if (key == null || publication <= 0L || keys.length == 0) return;
        int slot = findExisting(key);
        if (slot < 0) return;
        publications[slot] = publication;
        confirmations[slot] = 0;
    }

    void invalidate() {
        size = 0;
        if (epoch == Integer.MAX_VALUE) {
            Arrays.fill(keys, null);
            Arrays.fill(epochs, 0);
            epoch = 1;
        } else {
            epoch++;
        }
    }

    long getCapacityResets() { return capacityResets; }
    int getCapacity() { return keys.length; }
    int getBudgetCapacityReductions() { return budgetCapacityReductions; }

    @Override public void close() {
        Arrays.fill(keys, null);
        Arrays.fill(epochs, 0);
        size = 0;
        CacheBudget.Reservation current = reservation;
        reservation = CacheBudget.Reservation.empty();
        if (current != null) current.close();
    }

    private int find(Object key) {
        int slot = mix(System.identityHashCode(key)) & mask;
        for (int checked = 0; checked < keys.length; checked++) {
            if (epochs[slot] != epoch || keys[slot] == key) return slot;
            slot = slot + 1 & mask;
        }
        invalidate();
        capacityResets++;
        return mix(System.identityHashCode(key)) & mask;
    }

    private int findExisting(Object key) {
        int slot = mix(System.identityHashCode(key)) & mask;
        for (int checked = 0; checked < keys.length; checked++) {
            if (epochs[slot] != epoch) return -1;
            if (keys[slot] == key) return slot;
            slot = slot + 1 & mask;
        }
        return -1;
    }

    private static boolean isNext(long previous, long current) {
        return previous > 0L && previous != Long.MAX_VALUE
            && current == previous + 1L;
    }

    private static long retainedBytes(int capacity) {
        long bytes = RetainedHeap.add(RetainedHeap.referenceArray(capacity),
            RetainedHeap.longArray(capacity));
        bytes = RetainedHeap.add(bytes, RetainedHeap.intArray(capacity));
        return RetainedHeap.add(bytes, RetainedHeap.byteArray(capacity));
    }

    private static int mix(int value) {
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        return value ^ value >>> 16;
    }
}
