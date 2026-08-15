package dev.rlcraft.ice.optimizer.memory;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Exact byte accounting for optimizer-owned heap, direct and GPU resources. */
public final class CacheBudget {
    private final Map<BudgetKind, AtomicLong> used = new EnumMap<BudgetKind, AtomicLong>(BudgetKind.class);
    private final Map<BudgetKind, Long> limits = new EnumMap<BudgetKind, Long>(BudgetKind.class);
    private final AtomicLong rejected = new AtomicLong();

    public CacheBudget(long heapLimit, long directLimit, long gpuLimit) {
        limits.put(BudgetKind.HEAP, Math.max(1L, heapLimit));
        limits.put(BudgetKind.DIRECT, Math.max(1L, directLimit));
        limits.put(BudgetKind.GPU, Math.max(1L, gpuLimit));
        for (BudgetKind kind : BudgetKind.values()) used.put(kind, new AtomicLong());
    }

    public Reservation tryReserve(BudgetKind kind, long bytes) {
        if (kind == null || bytes <= 0L) return Reservation.EMPTY;
        AtomicLong counter = used.get(kind);
        long limit = limits.get(kind);
        while (true) {
            long current = counter.get();
            if (bytes > limit - current) {
                rejected.incrementAndGet();
                return null;
            }
            if (counter.compareAndSet(current, current + bytes)) return new Reservation(this, kind, bytes);
        }
    }

    private void release(BudgetKind kind, long bytes) {
        AtomicLong counter = used.get(kind);
        while (true) {
            long current = counter.get();
            long next = Math.max(0L, current - bytes);
            if (counter.compareAndSet(current, next)) return;
        }
    }

    public CacheBudgetStatus snapshot() {
        return new CacheBudgetStatus(used.get(BudgetKind.HEAP).get(), limits.get(BudgetKind.HEAP),
            used.get(BudgetKind.DIRECT).get(), limits.get(BudgetKind.DIRECT),
            used.get(BudgetKind.GPU).get(), limits.get(BudgetKind.GPU), rejected.get());
    }

    public static final class Reservation implements AutoCloseable {
        private static final Reservation EMPTY = new Reservation(null, null, 0L);
        private final CacheBudget owner;
        private final BudgetKind kind;
        private final long bytes;
        private final AtomicBoolean released = new AtomicBoolean();

        private Reservation(CacheBudget owner, BudgetKind kind, long bytes) {
            this.owner = owner;
            this.kind = kind;
            this.bytes = bytes;
        }

        public long getBytes() { return bytes; }

        @Override
        public void close() {
            if (owner != null && released.compareAndSet(false, true)) owner.release(kind, bytes);
        }
    }
}
