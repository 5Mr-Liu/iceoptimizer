package dev.rlcraft.ice.optimizer.render.telemetry;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.backend.RenderBackendId;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.frame.RenderPass;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded CPU/GPU pass profiler with delayed, never-blocking GPU collection. */
public final class CorrelatedRenderProfiler {
    private static final int MAXIMUM_CPU_SCOPE_DEPTH = 64;
    private static final int MAXIMUM_GPU_SLOT_POLLS = 256;
    private static final long QUERY_OBJECT_CHARGE = 4L * 1024L;
    private static final int BACKEND_COUNT = RenderBackendId.values().length;
    private static final int PROFILE_COUNT = RenderPass.values().length * BACKEND_COUNT;
    private static final CpuWorkKind[] CPU_KINDS = CpuWorkKind.values();
    private static final RenderCounter[] COUNTERS = RenderCounter.values();

    public interface GpuCompletion {
        void completed(RenderProfileKey key, long elapsedNanos);
    }

    private final int maximumFrames;
    private final LinkedHashMap<RenderProfileKey, MutableProfile> profiles =
        new LinkedHashMap<RenderProfileKey, MutableProfile>();
    private final FrameBucket[] frameBuckets;
    private FrameStamp counterStamp;
    private final MutableProfile[] counterProfiles = new MutableProfile[PROFILE_COUNT];
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
        frameBuckets = new FrameBucket[this.maximumFrames];
        for (int index = 0; index < frameBuckets.length; index++) {
            frameBuckets[index] = new FrameBucket();
        }
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
        counterProfile(stamp, pass, backend).addCounter(counter, delta);
    }

    public synchronized GpuScope beginGpu(FrameStamp stamp, RenderPass pass,
                                          RenderBackendId backend) {
        return beginGpu(stamp, pass, backend, null);
    }

    public synchronized GpuScope beginGpu(FrameStamp stamp, RenderPass pass,
                                          RenderBackendId backend,
                                          GpuCompletion completion) {
        if (gpu == null) return null;
        RenderProfileKey key = new RenderProfileKey(stamp, pass, backend);
        if (!markGpuIssued(key)) return null;
        return gpu.begin(key, completion);
    }

    /** Poll a bounded number of old query slots. Unavailable results are skipped. */
    public synchronized int pollGpu(int maximumChecks) {
        return gpu == null ? 0 : gpu.poll(Math.max(0, maximumChecks));
    }

    /** Allocation-free gate used to avoid polling an idle query ring. */
    public synchronized boolean hasPendingGpuQueries() {
        return gpu != null && gpu.hasPending();
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

    /** Reuse the current view's profiles across individual model/particle draws. */
    private MutableProfile counterProfile(FrameStamp stamp, RenderPass pass,
                                           RenderBackendId backend) {
        if (stamp == null || pass == null || backend == null) {
            throw new IllegalArgumentException("profile key");
        }
        // Advance before publishing the cache: eviction also clears it, so a
        // delayed GPU/CPU completion cannot leave an expired profile cached.
        advanceFrameBuckets(stamp.getFrameId());
        if (counterStamp != stamp) {
            counterStamp = stamp;
            Arrays.fill(counterProfiles, null);
        }
        int index = pass.ordinal() * BACKEND_COUNT + backend.ordinal();
        MutableProfile profile = counterProfiles[index];
        if (profile == null) {
            profile = mutable(new RenderProfileKey(stamp, pass, backend));
            if (!isExpired(stamp.getFrameId())) counterProfiles[index] = profile;
        }
        return profile;
    }

    private MutableProfile mutable(RenderProfileKey key) {
        long frame = key.getStamp().getFrameId();
        advanceFrameBuckets(frame);
        MutableProfile value = profiles.get(key);
        if (value == null) {
            value = new MutableProfile();
            if (isExpired(frame)) {
                droppedFrames++;
                return value;
            }
            profiles.put(key, value);
            bucket(frame).profileKeys.add(key);
        }
        return value;
    }

    private boolean markGpuIssued(RenderProfileKey key) {
        long frame = key.getStamp().getFrameId();
        advanceFrameBuckets(frame);
        if (isExpired(frame)) return false;
        FrameBucket bucket = bucket(frame);
        int index = key.getPass().ordinal() * BACKEND_COUNT
            + key.getBackend().ordinal();
        if (bucket.gpuIssued[index]) return false;
        bucket.gpuIssued[index] = true;
        return true;
    }

    /**
     * Ring buckets make ordinary one-frame advancement O(entries expired)
     * instead of scanning every retained profile. A large frame jump clears
     * the fixed bucket array once, then returns to the constant-time path.
     */
    private void advanceFrameBuckets(long frame) {
        if (frame <= newestFrame) return;
        counterStamp = null;
        Arrays.fill(counterProfiles, null);
        if (newestFrame <= 0L || frame - newestFrame <= 0L
            || frame - newestFrame >= maximumFrames) {
            clearFrameBuckets();
            newestFrame = frame;
            prepareBucket(frame);
            return;
        }
        for (long value = newestFrame + 1L; value <= frame; value++) {
            prepareBucket(value);
        }
        newestFrame = frame;
    }

    private void clearFrameBuckets() {
        for (FrameBucket bucket : frameBuckets) evict(bucket);
    }

    private FrameBucket prepareBucket(long frame) {
        FrameBucket bucket = frameBuckets[bucketIndex(frame)];
        if (bucket.frameId != frame) {
            evict(bucket);
            bucket.frameId = frame;
        }
        return bucket;
    }

    private FrameBucket bucket(long frame) {
        return prepareBucket(frame);
    }

    private void evict(FrameBucket bucket) {
        for (int index = 0; index < bucket.profileKeys.size(); index++) {
            if (profiles.remove(bucket.profileKeys.get(index)) != null) {
                droppedFrames++;
            }
        }
        bucket.reset();
    }

    private boolean isExpired(long frame) {
        return newestFrame > maximumFrames
            && frame < newestFrame - maximumFrames + 1L;
    }

    private int bucketIndex(long frame) {
        long value = frame % maximumFrames;
        return (int) (value < 0L ? value + maximumFrames : value);
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
        private final int[] freeSlots;
        private final int[] activeQueue;
        private int freeCount;
        private int activeHead;
        private int activeTail;
        private int activeCount;
        private long backoffFrame = Long.MIN_VALUE;
        private long nextSerial = 1L;
        private long dropped;

        private GpuQueryRing(GpuTimestampDriver driver, int count,
                             CacheBudget budget) {
            this.driver = driver;
            this.budget = budget;
            ownerThread = Thread.currentThread();
            slots = new Slot[count];
            freeSlots = new int[count];
            activeQueue = new int[count];
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
                    slots[i] = new Slot(i, start.nativeId, end.nativeId,
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
            for (int index = count - 1; index >= 0; index--) {
                freeSlots[freeCount++] = index;
            }
        }

        private GpuScope begin(RenderProfileKey key, GpuCompletion completion) {
            if (!isOwnerThread()) {
                scopeErrors++;
                return null;
            }
            long frame = key.getStamp().getFrameId();
            if (backoffFrame == frame) return null;
            if (freeCount == 0) {
                backoffFrame = frame;
                dropped++;
                return null;
            }
            Slot slot = slots[freeSlots[--freeCount]];
            slot.key = key;
            slot.completion = completion;
            long serial = nextSerial();
            if (serial == 0L) {
                release(slot);
                dropped++;
                return null;
            }
            slot.serial = serial;
            slot.state = Slot.STARTED;
            try {
                driver.timestamp(slot.startQuery);
                enqueue(slot.index);
                return new GpuScope(this, slot, slot.serial);
            } catch (Throwable error) {
                poison(slot, true);
                FatalErrors.rethrowIfFatal(error);
                return null;
            }
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
            int scheduled = activeCount;
            while (checks < maximumChecks && scheduled-- > 0
                && activeCount > 0) {
                Slot slot = slots[dequeue()];
                if (slot.state == Slot.IDLE || slot.state == Slot.POISONED) {
                    continue;
                }
                checks++;
                if (slot.state == Slot.STARTED) {
                    if (++slot.polls >= MAXIMUM_GPU_SLOT_POLLS) {
                        poison(slot, true);
                    } else {
                        enqueue(slot.index);
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
                    } else {
                        enqueue(slot.index);
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
                release(slot);
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

        private boolean hasPending() { return activeCount > 0; }

        private void enqueue(int index) {
            activeQueue[activeTail] = index;
            activeTail = (activeTail + 1) % activeQueue.length;
            activeCount++;
        }

        private int dequeue() {
            int index = activeQueue[activeHead];
            activeHead = (activeHead + 1) % activeQueue.length;
            activeCount--;
            return index;
        }

        private void release(Slot slot) {
            slot.reset();
            freeSlots[freeCount++] = slot.index;
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
            activeHead = 0;
            activeTail = 0;
            activeCount = 0;
            freeCount = 0;
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

        private final int index;
        private final int startQuery;
        private final int endQuery;
        private final CacheBudget.Reservation startReservation;
        private final CacheBudget.Reservation endReservation;
        private RenderProfileKey key;
        private GpuCompletion completion;
        private long serial;
        private int polls;
        private int state;

        private Slot(int index, int startQuery, int endQuery,
                     CacheBudget.Reservation startReservation,
                     CacheBudget.Reservation endReservation) {
            this.index = index;
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

    private static final class FrameBucket {
        private long frameId = Long.MIN_VALUE;
        private final ArrayList<RenderProfileKey> profileKeys =
            new ArrayList<RenderProfileKey>(8);
        private final boolean[] gpuIssued = new boolean[PROFILE_COUNT];

        private void reset() {
            frameId = Long.MIN_VALUE;
            profileKeys.clear();
            java.util.Arrays.fill(gpuIssued, false);
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
        private final long[] cpuKinds = new long[CPU_KINDS.length];
        private final boolean[] cpuKindsSeen = new boolean[CPU_KINDS.length];
        private final long[] counters = new long[COUNTERS.length];

        private void addCpu(CpuWorkKind kind, long inclusive, long exclusive) {
            cpuInclusive = safeAdd(cpuInclusive, inclusive);
            cpuExclusive = safeAdd(cpuExclusive, exclusive);
            int index = kind.ordinal();
            cpuKinds[index] = safeAdd(cpuKinds[index], inclusive);
            cpuKindsSeen[index] = true;
        }

        private void addCounter(RenderCounter counter, long delta) {
            int index = counter.ordinal();
            counters[index] = safeAdd(counters[index], delta);
        }

        private PassProfile snapshot() {
            EnumMap<CpuWorkKind, Long> cpuSnapshot =
                new EnumMap<CpuWorkKind, Long>(CpuWorkKind.class);
            EnumMap<RenderCounter, Long> counterSnapshot =
                new EnumMap<RenderCounter, Long>(RenderCounter.class);
            for (CpuWorkKind kind : CPU_KINDS) {
                if (cpuKindsSeen[kind.ordinal()]) {
                    cpuSnapshot.put(kind, Long.valueOf(cpuKinds[kind.ordinal()]));
                }
            }
            for (RenderCounter counter : COUNTERS) {
                long value = counters[counter.ordinal()];
                if (value > 0L) counterSnapshot.put(counter, Long.valueOf(value));
            }
            return new PassProfile(cpuInclusive, cpuExclusive, gpuNanos,
                cpuSnapshot, counterSnapshot);
        }
    }
}
