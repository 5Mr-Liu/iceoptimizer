package dev.rlcraft.ice.optimizer.render.telemetry;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.backend.RenderBackendId;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.frame.RenderPass;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded CPU/GPU pass profiler with delayed, never-blocking GPU collection. */
public final class CorrelatedRenderProfiler {
    private static final int MAXIMUM_CPU_SCOPE_DEPTH = 64;
    private static final int MAXIMUM_GPU_SLOT_POLLS = 256;
    private static final long QUERY_OBJECT_CHARGE = 4L * 1024L;

    public interface GpuCompletion {
        void completed(RenderProfileKey key, long elapsedNanos);
    }

    private final int maximumFrames;
    private final LinkedHashMap<RenderProfileKey, MutableProfile> profiles =
        new LinkedHashMap<RenderProfileKey, MutableProfile>();
    private final ThreadLocal<CpuScopeStack> cpuScopes =
        new ThreadLocal<CpuScopeStack>() {
            @Override protected CpuScopeStack initialValue() {
                return new CpuScopeStack(MAXIMUM_CPU_SCOPE_DEPTH);
            }
        };
    private GpuQueryRing gpu;
    private long newestFrame;
    private long droppedFrames;
    private long scopeErrors;

    public CorrelatedRenderProfiler(int maximumFrames) {
        this.maximumFrames = Math.max(4, maximumFrames);
    }

    public synchronized void attachGpuQueries(GpuTimestampDriver driver, int slots) {
        attachGpuQueries(driver, slots, null);
    }

    /** Production overload with shared pre-allocation hard accounting. */
    public synchronized void attachGpuQueries(GpuTimestampDriver driver,
                                              int slots,
                                              CacheBudget budget) {
        // Construct first.  If any glGenQueries-equivalent call fails, the
        // candidate ring cleans up its partial allocation and the working old
        // ring remains published.
        GpuQueryRing replacement = driver == null ? null
            : new GpuQueryRing(driver, Math.max(4, slots), budget);
        GpuQueryRing previous = gpu;
        gpu = replacement;
        if (previous != null) previous.close(true);
    }

    public CpuScope beginCpu(FrameStamp stamp, RenderPass pass,
                             RenderBackendId backend, CpuWorkKind kind) {
        RenderProfileKey key = new RenderProfileKey(stamp, pass, backend);
        CpuScopeStack stack = cpuScopes.get();
        CpuScope scope = new CpuScope(this, key,
            kind == null ? CpuWorkKind.WORK : kind, System.nanoTime(),
            Thread.currentThread(), stack, stack.hasRoom());
        if (scope.accepted) stack.push(scope);
        else synchronized (this) { scopeErrors++; }
        return scope;
    }

    public synchronized void addCounter(FrameStamp stamp, RenderPass pass,
                                        RenderBackendId backend, RenderCounter counter,
                                        long delta) {
        if (counter == null || delta <= 0L) return;
        mutable(new RenderProfileKey(stamp, pass, backend)).addCounter(counter, delta);
    }

    public synchronized GpuScope beginGpu(FrameStamp stamp, RenderPass pass,
                                          RenderBackendId backend) {
        return beginGpu(stamp, pass, backend, null);
    }

    public synchronized GpuScope beginGpu(FrameStamp stamp, RenderPass pass,
                                          RenderBackendId backend,
                                          GpuCompletion completion) {
        return gpu == null ? null : gpu.begin(
            new RenderProfileKey(stamp, pass, backend), completion);
    }

    /** Poll a bounded number of old query slots. Unavailable results are skipped. */
    public synchronized int pollGpu(int maximumChecks) {
        return gpu == null ? 0 : gpu.poll(Math.max(0, maximumChecks));
    }

    public synchronized void resetGpu(boolean contextValid) {
        GpuQueryRing previous = gpu;
        gpu = null;
        if (previous != null) previous.close(contextValid);
    }

    public synchronized RenderProfilerSnapshot snapshot() {
        LinkedHashMap<RenderProfileKey, PassProfile> result =
            new LinkedHashMap<RenderProfileKey, PassProfile>();
        for (Map.Entry<RenderProfileKey, MutableProfile> entry : profiles.entrySet()) {
            result.put(entry.getKey(), entry.getValue().snapshot());
        }
        return new RenderProfilerSnapshot(result, droppedFrames,
            gpu == null ? 0L : gpu.dropped, scopeErrors);
    }

    private void closeCpu(CpuScope scope) {
        CpuScopeStack stack = scope.ownerStack;
        if (stack.peek() != scope) {
            synchronized (this) { scopeErrors++; }
            stack.remove(scope);
            return;
        }
        stack.pop();
        long inclusive = Math.max(0L, System.nanoTime() - scope.started);
        long exclusive = Math.max(0L, inclusive - scope.childNanos);
        CpuScope parent = stack.peek();
        if (parent != null) parent.childNanos = safeAdd(parent.childNanos, inclusive);
        synchronized (this) {
            mutable(scope.key).addCpu(scope.kind, inclusive, exclusive);
        }
    }

    private MutableProfile mutable(RenderProfileKey key) {
        long frame = key.getStamp().getFrameId();
        if (frame > newestFrame) {
            newestFrame = frame;
            trimFrames();
        }
        MutableProfile value = profiles.get(key);
        if (value == null) {
            value = new MutableProfile();
            profiles.put(key, value);
        }
        return value;
    }

    private void trimFrames() {
        long minimum = newestFrame - maximumFrames + 1L;
        Iterator<Map.Entry<RenderProfileKey, MutableProfile>> iterator =
            profiles.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getKey().getStamp().getFrameId() < minimum) {
                iterator.remove();
                droppedFrames++;
            }
        }
    }

    private static long safeAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    public static final class CpuScope implements AutoCloseable {
        private final CorrelatedRenderProfiler ownerProfiler;
        private final RenderProfileKey key;
        private final CpuWorkKind kind;
        private final long started;
        private final Thread owner;
        private final CpuScopeStack ownerStack;
        private final boolean accepted;
        private long childNanos;
        private boolean closed;

        private CpuScope(CorrelatedRenderProfiler ownerProfiler, RenderProfileKey key,
                         CpuWorkKind kind, long started, Thread owner,
                         CpuScopeStack ownerStack, boolean accepted) {
            this.ownerProfiler = ownerProfiler;
            this.key = key;
            this.kind = kind;
            this.started = started;
            this.owner = owner;
            this.ownerStack = ownerStack;
            this.accepted = accepted;
        }

        @Override public synchronized void close() {
            if (closed) return;
            if (owner != Thread.currentThread()) {
                synchronized (ownerProfiler) { ownerProfiler.scopeErrors++; }
                return;
            }
            closed = true;
            if (accepted) ownerProfiler.closeCpu(this);
        }
    }

    public final class GpuScope implements AutoCloseable {
        private final GpuQueryRing owner;
        private final Slot slot;
        private final long serial;
        private boolean closed;

        private GpuScope(GpuQueryRing owner, Slot slot, long serial) {
            this.owner = owner;
            this.slot = slot;
            this.serial = serial;
        }

        @Override public void close() {
            synchronized (CorrelatedRenderProfiler.this) {
                if (closed) return;
                if (owner != null && !owner.isOwnerThread()) {
                    scopeErrors++;
                    return;
                }
                closed = true;
                if (owner != null) owner.end(slot, serial);
            }
        }
    }

    private final class GpuQueryRing {
        private final GpuTimestampDriver driver;
        private final CacheBudget budget;
        private final Slot[] slots;
        private final Thread ownerThread;
        private int cursor;
        private int pollCursor;
        private long nextSerial = 1L;
        private long dropped;

        private GpuQueryRing(GpuTimestampDriver driver, int count,
                             CacheBudget budget) {
            this.driver = driver;
            this.budget = budget;
            ownerThread = Thread.currentThread();
            slots = new Slot[count];
            int[] created = new int[Math.multiplyExact(count, 2)];
            CacheBudget.Reservation[] reservations =
                new CacheBudget.Reservation[created.length];
            int createdCount = 0;
            HashSet<Integer> unique = new HashSet<Integer>(created.length * 2);
            try {
                for (int i = 0; i < count; i++) {
                    CreatedQuery start = createCheckedQuery(unique);
                    created[createdCount] = start.nativeId;
                    reservations[createdCount++] = start.reservation;
                    CreatedQuery end = createCheckedQuery(unique);
                    created[createdCount] = end.nativeId;
                    reservations[createdCount++] = end.reservation;
                    slots[i] = new Slot(start.nativeId, end.nativeId,
                        start.reservation, end.reservation);
                }
            } catch (Throwable allocationFailure) {
                for (int i = 0; i < createdCount; i++) {
                    boolean deleted = false;
                    try {
                        driver.deleteQuery(created[i]);
                        deleted = true;
                    }
                    catch (Throwable cleanupFailure) {
                        allocationFailure = aggregate(allocationFailure,
                            cleanupFailure);
                    }
                    if (deleted && reservations[i] != null) try {
                        reservations[i].close();
                    } catch (Throwable cleanupFailure) {
                        allocationFailure = aggregate(allocationFailure,
                            cleanupFailure);
                    }
                }
                rethrow(allocationFailure);
            }
        }

        private GpuScope begin(RenderProfileKey key, GpuCompletion completion) {
            if (!isOwnerThread()) {
                scopeErrors++;
                return null;
            }
            for (int checked = 0; checked < slots.length; checked++) {
                int index = (cursor + checked) % slots.length;
                Slot slot = slots[index];
                if (slot.state == Slot.IDLE) {
                    slot.key = key;
                    slot.completion = completion;
                    long serial = nextSerial();
                    if (serial == 0L) {
                        slot.reset();
                        dropped++;
                        return null;
                    }
                    slot.serial = serial;
                    slot.state = Slot.STARTED;
                    cursor = (index + 1) % slots.length;
                    try {
                        driver.timestamp(slot.startQuery);
                        return new GpuScope(this, slot, slot.serial);
                    } catch (Throwable error) {
                        poison(slot, true);
                        FatalErrors.rethrowIfFatal(error);
                        return null;
                    }
                }
            }
            dropped++;
            return null;
        }

        private void end(Slot slot, long serial) {
            if (!isOwnerThread()) {
                scopeErrors++;
                return;
            }
            if (slot == null || slot.serial != serial
                || slot.state != Slot.STARTED) return;
            try {
                driver.timestamp(slot.endQuery);
                slot.state = Slot.ENDED;
                slot.polls = 0;
            } catch (Throwable error) {
                poison(slot, true);
                FatalErrors.rethrowIfFatal(error);
            }
        }

        private int poll(int maximumChecks) {
            if (!isOwnerThread()) {
                scopeErrors++;
                return 0;
            }
            int checks = 0;
            int completed = 0;
            int scanned = 0;
            while (checks < maximumChecks && scanned < slots.length) {
                int index = pollCursor;
                Slot slot = slots[index];
                pollCursor = (pollCursor + 1) % slots.length;
                scanned++;
                if (slot.state == Slot.IDLE || slot.state == Slot.POISONED) continue;
                checks++;
                if (slot.state == Slot.STARTED) {
                    if (++slot.polls >= MAXIMUM_GPU_SLOT_POLLS) {
                        poison(slot, true);
                    }
                    continue;
                }
                boolean available;
                try {
                    available = driver.isAvailable(slot.endQuery);
                } catch (Throwable error) {
                    poison(slot, true);
                    FatalErrors.rethrowIfFatal(error);
                    continue;
                }
                if (!available) {
                    if (++slot.polls >= MAXIMUM_GPU_SLOT_POLLS) {
                        poison(slot, true);
                    }
                    continue;
                }
                long start;
                long end;
                try {
                    start = driver.resultNanos(slot.startQuery);
                    end = driver.resultNanos(slot.endQuery);
                } catch (Throwable error) {
                    poison(slot, true);
                    FatalErrors.rethrowIfFatal(error);
                    continue;
                }
                long elapsed = Math.max(0L, end - start);
                MutableProfile profile = mutable(slot.key);
                profile.gpuNanos = safeAdd(profile.gpuNanos, elapsed);
                GpuCompletion completion = slot.completion;
                RenderProfileKey key = slot.key;
                slot.reset();
                if (completion != null) {
                    try { completion.completed(key, elapsed); }
                    catch (Throwable error) {
                        scopeErrors++;
                        FatalErrors.rethrowIfFatal(error);
                    }
                }
                completed++;
            }
            return completed;
        }

        private void close(boolean contextValid) {
            if (contextValid && !isOwnerThread()) {
                throw new IllegalStateException("GPU query deletion from non-owner thread");
            }
            Throwable firstFailure = null;
            for (Slot slot : slots) {
                firstFailure = releaseQuery(slot.startQuery,
                    slot.startReservation, contextValid, firstFailure);
                firstFailure = releaseQuery(slot.endQuery,
                    slot.endReservation, contextValid, firstFailure);
            }
            for (Slot slot : slots) slot.reset();
            if (firstFailure != null) rethrow(firstFailure);
        }

        private Throwable releaseQuery(int nativeId,
                                       CacheBudget.Reservation reservation,
                                       boolean contextValid,
                                       Throwable failure) {
            boolean safeToRelease = !contextValid;
            if (contextValid) try {
                driver.deleteQuery(nativeId);
                safeToRelease = true;
            } catch (Throwable error) {
                failure = aggregate(failure, error);
            }
            if (safeToRelease && reservation != null) try {
                reservation.close();
            } catch (Throwable error) {
                failure = aggregate(failure, error);
            }
            return failure;
        }

        private void poison(Slot slot, boolean error) {
            if (slot == null || slot.state == Slot.IDLE
                || slot.state == Slot.POISONED) return;
            slot.poison();
            dropped++;
            if (error) scopeErrors++;
        }

        private Throwable aggregate(Throwable first, Throwable next) {
            if (first == null) return next;
            Throwable nextFatal = FatalErrors.findFatal(next);
            if (nextFatal != null && FatalErrors.findFatal(first) == null) {
                addSuppressed(nextFatal, first);
                return nextFatal;
            }
            addSuppressed(first, next);
            return first;
        }

        private void addSuppressed(Throwable primary, Throwable suppressed) {
            if (primary == null || suppressed == null || primary == suppressed) return;
            primary.addSuppressed(suppressed);
        }

        private CreatedQuery createCheckedQuery(HashSet<Integer> unique) {
            CacheBudget.Reservation reservation = budget == null ? null
                : budget.tryReserve(BudgetKind.GPU, QUERY_OBJECT_CHARGE);
            if (budget != null && reservation == null) {
                throw new IllegalStateException("GPU query object budget exhausted");
            }
            int query;
            try {
                query = driver.createQuery();
            } catch (Throwable allocationFailure) {
                // A throwing allocator provides no reliable name.  Leave its
                // shared token charged to bound initialization retries.
                throw allocationFailure;
            }
            if (query <= 0) {
                if (reservation != null) reservation.close();
                throw new IllegalStateException("invalid GPU query id");
            }
            boolean uniqueId;
            try { uniqueId = unique.add(Integer.valueOf(query)); }
            catch (Throwable publicationFailure) {
                boolean deleted = false;
                try {
                    driver.deleteQuery(query);
                    deleted = true;
                } catch (Throwable cleanupFailure) {
                    publicationFailure = aggregate(publicationFailure,
                        cleanupFailure);
                }
                if (deleted && reservation != null) try {
                    reservation.close();
                } catch (Throwable cleanupFailure) {
                    publicationFailure = aggregate(publicationFailure,
                        cleanupFailure);
                }
                rethrow(publicationFailure);
                return null;
            }
            if (!uniqueId) {
                // Never delete a duplicate live ID: it may be the already
                // published query. Retain the token because driver outcome is
                // inconsistent and therefore cannot be proven allocation-free.
                throw new IllegalStateException("duplicate GPU query id");
            }
            return new CreatedQuery(query, reservation);
        }

        private long nextSerial() {
            if (nextSerial <= 0L || nextSerial == Long.MAX_VALUE) {
                for (Slot slot : slots) {
                    if (slot.state == Slot.STARTED || slot.state == Slot.ENDED) {
                        return 0L;
                    }
                }
                nextSerial = 1L;
            }
            return nextSerial++;
        }

        private boolean isOwnerThread() { return Thread.currentThread() == ownerThread; }

        private void rethrow(Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            if (error instanceof RuntimeException) {
                throw (RuntimeException) error;
            }
            if (error instanceof Error) throw (Error) error;
            throw new IllegalStateException("GPU query operation failed", error);
        }
    }

    private static final class CpuScopeStack {
        private final CpuScope[] values;
        private int size;

        private CpuScopeStack(int capacity) { values = new CpuScope[capacity]; }
        private boolean hasRoom() { return size < values.length; }
        private void push(CpuScope value) { values[size++] = value; }
        private CpuScope peek() { return size == 0 ? null : values[size - 1]; }
        private void pop() { values[--size] = null; }

        private void remove(CpuScope value) {
            for (int i = size - 1; i >= 0; i--) {
                if (values[i] != value) continue;
                int moved = size - i - 1;
                if (moved > 0) System.arraycopy(values, i + 1, values, i, moved);
                values[--size] = null;
                return;
            }
        }
    }

    private static final class Slot {
        private static final int IDLE = 0;
        private static final int STARTED = 1;
        private static final int ENDED = 2;
        private static final int POISONED = 3;

        private final int startQuery;
        private final int endQuery;
        private final CacheBudget.Reservation startReservation;
        private final CacheBudget.Reservation endReservation;
        private RenderProfileKey key;
        private GpuCompletion completion;
        private long serial;
        private int polls;
        private int state;

        private Slot(int startQuery, int endQuery,
                     CacheBudget.Reservation startReservation,
                     CacheBudget.Reservation endReservation) {
            this.startQuery = startQuery;
            this.endQuery = endQuery;
            this.startReservation = startReservation;
            this.endReservation = endReservation;
        }

        private void reset() {
            key = null;
            completion = null;
            serial = 0L;
            polls = 0;
            state = IDLE;
        }

        private void poison() {
            key = null;
            completion = null;
            serial = 0L;
            polls = 0;
            state = POISONED;
        }
    }

    private static final class CreatedQuery {
        private final int nativeId;
        private final CacheBudget.Reservation reservation;

        private CreatedQuery(int nativeId,
                             CacheBudget.Reservation reservation) {
            this.nativeId = nativeId;
            this.reservation = reservation;
        }
    }

    private static final class MutableProfile {
        private long cpuInclusive;
        private long cpuExclusive;
        private long gpuNanos;
        private final java.util.EnumMap<CpuWorkKind, Long> cpuKinds =
            new java.util.EnumMap<CpuWorkKind, Long>(CpuWorkKind.class);
        private final java.util.EnumMap<RenderCounter, Long> counters =
            new java.util.EnumMap<RenderCounter, Long>(RenderCounter.class);

        private void addCpu(CpuWorkKind kind, long inclusive, long exclusive) {
            cpuInclusive = safeAdd(cpuInclusive, inclusive);
            cpuExclusive = safeAdd(cpuExclusive, exclusive);
            Long current = cpuKinds.get(kind);
            cpuKinds.put(kind, Long.valueOf(safeAdd(current == null ? 0L
                : current.longValue(), inclusive)));
        }

        private void addCounter(RenderCounter counter, long delta) {
            Long current = counters.get(counter);
            counters.put(counter, Long.valueOf(safeAdd(current == null ? 0L
                : current.longValue(), delta)));
        }

        private PassProfile snapshot() {
            return new PassProfile(cpuInclusive, cpuExclusive, gpuNanos,
                cpuKinds, counters);
        }
    }
}
