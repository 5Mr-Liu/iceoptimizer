package dev.rlcraft.ice.optimizer.render.arena;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Checked, bounded first-fit allocator with coalescing and ABA-safe frees. */
public final class GpuArenaAllocator {
    /** Covers the range, live/free map nodes and the terrain mesh envelope. */
    private static final long RANGE_HEAP_BYTES = 512L;

    public static long rangeHeapBytes() { return RANGE_HEAP_BYTES; }
    private final long pageBytes;
    private final long maximumBytes;
    private final long alignment;
    private final long allocationBoundary;
    private final long metadataHeapBytes;
    private final NavigableMap<Long, Long> free;
    private final Map<Long, ArenaRange> live;
    private final CacheBudget budget;
    private long generation;
    private long committedBytes;
    private long usedBytes;
    private long nextId = 1L;
    private long nextSerial = 1L;
    private long rejected;
    private long invalidFrees;
    private boolean poisoned;
    private boolean heapRejected;
    // Upper bound, not an allocation hint: alignment, page residency and the
    // original selection order still decide every potentially successful fit.
    private long freeSpanUpperBound = Long.MAX_VALUE;
    // A faulting Map is permitted to throw before or after completing a
    // mutation. Keep direct ownership witnesses for both publication sites so
    // reset/close can release reservations even when the range is absent from
    // the live map. ArenaRange release is idempotent when a witness is also
    // present in the live snapshot.
    private ArenaRange uncertainAllocation;
    private ArenaRange uncertainRemoval;

    public GpuArenaAllocator(long pageBytes, long maximumBytes, long alignment,
                             long generation) {
        this(pageBytes, maximumBytes, alignment, generation, null);
    }

    public GpuArenaAllocator(long pageBytes, long maximumBytes, long alignment,
                             long generation, CacheBudget budget) {
        this(pageBytes, maximumBytes, alignment, generation,
            new TreeMap<Long, Long>(), new HashMap<Long, ArenaRange>(), budget);
    }

    /** Paged buffers cannot place one mesh across two native buffer names. */
    public GpuArenaAllocator(long pageBytes, long maximumBytes, long alignment,
                             long generation, CacheBudget budget,
                             long allocationBoundary) {
        this(pageBytes, maximumBytes, alignment, generation,
            new TreeMap<Long, Long>(), new HashMap<Long, ArenaRange>(), budget,
            allocationBoundary);
    }

    /** Owners with additional per-range tables charge them for the entire
     * allocation lifetime, including delayed retirement and uncertain frees. */
    public GpuArenaAllocator(long pageBytes, long maximumBytes, long alignment,
                             long generation, CacheBudget budget,
                             long allocationBoundary, long metadataHeapBytes) {
        this(pageBytes, maximumBytes, alignment, generation,
            new TreeMap<Long, Long>(), new HashMap<Long, ArenaRange>(), budget,
            allocationBoundary, metadataHeapBytes);
    }

    GpuArenaAllocator(long pageBytes, long maximumBytes, long alignment,
                      long generation, NavigableMap<Long, Long> free,
                      Map<Long, ArenaRange> live) {
        this(pageBytes, maximumBytes, alignment, generation, free, live, null);
    }

    GpuArenaAllocator(long pageBytes, long maximumBytes, long alignment,
                      long generation, NavigableMap<Long, Long> free,
                      Map<Long, ArenaRange> live, CacheBudget budget) {
        this(pageBytes, maximumBytes, alignment, generation, free, live, budget, 0L);
    }

    private GpuArenaAllocator(long pageBytes, long maximumBytes, long alignment,
                      long generation, NavigableMap<Long, Long> free,
                      Map<Long, ArenaRange> live, CacheBudget budget,
                      long allocationBoundary) {
        this(pageBytes, maximumBytes, alignment, generation, free, live, budget,
            allocationBoundary, RANGE_HEAP_BYTES);
    }

    private GpuArenaAllocator(long pageBytes, long maximumBytes, long alignment,
                      long generation, NavigableMap<Long, Long> free,
                      Map<Long, ArenaRange> live, CacheBudget budget,
                      long allocationBoundary, long metadataHeapBytes) {
        if (pageBytes <= 0L || maximumBytes < pageBytes || generation <= 0L
            || alignment <= 0L || (alignment & (alignment - 1L)) != 0L
            || allocationBoundary < 0L
            || allocationBoundary != 0L && (allocationBoundary != pageBytes
                || allocationBoundary % alignment != 0L)
            || free == null || live == null || metadataHeapBytes < RANGE_HEAP_BYTES) {
            throw new IllegalArgumentException("arena bounds");
        }
        this.pageBytes = pageBytes;
        this.maximumBytes = maximumBytes;
        this.alignment = alignment;
        this.allocationBoundary = allocationBoundary;
        this.metadataHeapBytes = metadataHeapBytes;
        this.generation = generation;
        this.free = free;
        this.live = live;
        this.budget = budget;
    }

    public ArenaRange allocate(long bytes) {
        return allocate(bytes, null, null, Long.MAX_VALUE);
    }

    /** Prefer resident holes; a denied growth still permits that page's resident prefix. */
    public ArenaRange allocatePaged(long bytes, long[] residentCapacities, boolean[] deniedGrowth) {
        return allocatePaged(bytes, residentCapacities, deniedGrowth, Long.MAX_VALUE);
    }

    /** After real allocation pressure, replacements must fit the remaining payload space. */
    public ArenaRange allocatePaged(long bytes, long[] residentCapacities, boolean[] deniedGrowth,
                                    long maximumNewPageBytes) {
        if (allocationBoundary == 0L) throw new IllegalStateException("arena is not paged");
        if (residentCapacities == null) throw new IllegalArgumentException("resident page capacities");
        return allocate(bytes, residentCapacities, deniedGrowth, Math.max(0L, maximumNewPageBytes));
    }

    private ArenaRange allocate(long bytes, long[] residentCapacities, boolean[] deniedGrowth,
                                 long maximumNewPageBytes) {
        requireHealthy();
        heapRejected = false;
        if (bytes <= 0L || bytes > maximumBytes
            || allocationBoundary != 0L && bytes > allocationBoundary) {
            rejected++;
            return null;
        }
        ArenaRange result = allocateFromFree(bytes, residentCapacities, deniedGrowth, maximumNewPageBytes);
        if (result != null) return result;
        if (heapRejected) return null;
        if (bytes > maximumNewPageBytes) { rejected++; return null; }
        long required = roundUp(bytes, pageBytes);
        if (deniedGrowth != null) {
            long nextPage = committedBytes / pageBytes;
            while (nextPage < deniedGrowth.length && deniedGrowth[(int) nextPage]
                && (nextPage >= residentCapacities.length || residentCapacities[(int) nextPage] < bytes)) {
                required = checkedAdd(required, pageBytes);
                nextPage++;
            }
        }
        if (required > maximumBytes - committedBytes) {
            rejected++;
            return null;
        }
        long start = committedBytes;
        committedBytes = checkedAdd(committedBytes, required);
        putFree(start, required);
        result = allocateFromFree(bytes, residentCapacities, deniedGrowth, maximumNewPageBytes);
        if (result == null && heapRejected) return null;
        if (result == null) throw new IllegalStateException("new arena page was not allocatable");
        return result;
    }

    public boolean free(ArenaRange range) {
        if (range == null) return false;
        if (poisoned) {
            invalidFrees++;
            return false;
        }
        ArenaRange existing;
        try { existing = live.get(Long.valueOf(range.getId())); }
        catch (Throwable inspectionFailure) {
            poison();
            throw inspectionFailure;
        }
        if (existing == null || !existing.equals(range)
            || range.getGeneration() != generation) {
            invalidFrees++;
            return false;
        }
        if (range.getLength() > usedBytes) {
            poison();
            throw new IllegalStateException("arena used-byte accounting underflow");
        }
        long updatedUsed = usedBytes - range.getLength();
        // Publish the free interval first. If either container reports an
        // outcome-uncertain mutation, poison the allocator before any caller
        // can reuse an interval that might still be live.
        putFree(range.getOffset(), range.getLength());
        ArenaRange removed;
        uncertainRemoval = existing;
        try { removed = live.remove(Long.valueOf(range.getId())); }
        catch (Throwable publicationFailure) {
            poison();
            throw publicationFailure;
        }
        if (removed != existing) {
            poison();
            throw new IllegalStateException("arena live-range removal failed");
        }
        usedBytes = updatedUsed;
        try { existing.releaseHeapReservation(); }
        catch (Throwable releaseFailure) {
            poison();
            throw releaseFailure;
        }
        uncertainRemoval = null;
        return true;
    }

    public boolean isLive(ArenaRange range) {
        if (range == null) return false;
        ArenaRange existing;
        try { existing = live.get(Long.valueOf(range.getId())); }
        catch (Throwable inspectionFailure) {
            poison();
            throw inspectionFailure;
        }
        return existing != null && existing.equals(range) && range.getGeneration() == generation;
    }

    /** Render-thread ownership witness for the exact range returned by allocate.
     * No boxed id or live-map lookup is needed after successful publication.
     * An uncertain allocator mutation retains the checked legacy inspection. */
    public boolean ownsLiveRange(ArenaRange range) {
        if (range == null || !range.hasPublishedOwner(this)
            || range.getGeneration() != generation) return false;
        return !poisoned || isLive(range);
    }

    public void reset(long nextGeneration) {
        if (nextGeneration <= generation) throw new IllegalArgumentException("generation must advance");
        releaseAllRanges();
        committedBytes = 0L;
        freeSpanUpperBound = 0L;
        usedBytes = 0L;
        generation = nextGeneration;
        poisoned = false;
        heapRejected = false;
    }

    /** Final disposal releases every live range reservation without wrapping generation. */
    public void close() {
        releaseAllRanges();
        committedBytes = 0L;
        freeSpanUpperBound = 0L;
        usedBytes = 0L;
        poisoned = true;
        heapRejected = false;
    }

    public boolean isPoisoned() { return poisoned; }

    /** Retired ranges remain live until their Fence completes, so a fully
     * free page has no published mesh or outstanding range retirement. */
    public boolean isPageFree(long offset) {
        if (poisoned || offset < 0L || offset % pageBytes != 0L
            || offset > committedBytes - pageBytes) return false;
        Map.Entry<Long, Long> interval = free.floorEntry(Long.valueOf(offset));
        return interval != null && interval.getValue().longValue()
            >= offset - interval.getKey().longValue() + pageBytes;
    }

    public ArenaStatus snapshot() {
        return new ArenaStatus(generation, committedBytes, usedBytes, live.size(),
            free.size(), rejected, invalidFrees);
    }

    private ArenaRange allocateFromFree(long bytes, long[] residentCapacities, boolean[] deniedGrowth,
                                        long maximumNewPageBytes) {
        if (bytes > freeSpanUpperBound) return null;
        Long originalKey = null;
        long segmentStart = 0L;
        long segmentLength = 0L;
        long aligned = 0L;
        long prefix = 0L;
        long bestWaste = Long.MAX_VALUE;
        boolean bestResident = false;
        try {
            for (Map.Entry<Long, Long> entry : free.entrySet()) {
                long candidateStart = entry.getKey().longValue();
                long candidateLength = entry.getValue().longValue();
                long candidateAligned = roundUp(candidateStart, alignment);
                if (allocationBoundary != 0L
                    && bytes > allocationBoundary - candidateAligned % allocationBoundary) {
                    candidateAligned = roundUp(candidateAligned, allocationBoundary);
                }
                do {
                    long candidatePrefix = candidateAligned - candidateStart;
                    boolean resident = false;
                    if (candidatePrefix <= candidateLength
                        && bytes <= candidateLength - candidatePrefix) {
                        long usable = candidateLength - candidatePrefix;
                        if (allocationBoundary != 0L) usable = Math.min(usable,
                            allocationBoundary - candidateAligned % allocationBoundary);
                        if (residentCapacities != null) {
                            long page = candidateAligned / allocationBoundary;
                            long capacity = page < residentCapacities.length
                                ? residentCapacities[(int) page] : 0L;
                            long residentBytes = capacity - candidateAligned % allocationBoundary;
                            resident = bytes <= residentBytes;
                            if (resident || deniedGrowth != null && page < deniedGrowth.length
                                && deniedGrowth[(int) page]) usable = Math.min(usable, residentBytes);
                            if (!resident) usable = Math.min(usable,
                                maximumNewPageBytes - candidateAligned % allocationBoundary);
                        }
                        long waste = usable - bytes;
                        if (usable >= bytes && (originalKey == null
                            || resident && !bestResident
                            || resident == bestResident && waste < bestWaste)) {
                            originalKey = entry.getKey();
                            segmentStart = candidateStart;
                            segmentLength = candidateLength;
                            aligned = candidateAligned;
                            prefix = candidatePrefix;
                            bestWaste = waste;
                            bestResident = resident;
                        }
                    }
                    // A coalesced free interval can contain another resident page.
                    if (residentCapacities == null) break;
                    long pageRemainder = allocationBoundary - candidateAligned % allocationBoundary;
                    if (pageRemainder >= candidateLength - candidatePrefix) break;
                    candidateAligned = checkedAdd(candidateAligned, pageRemainder);
                } while (true);
                if (originalKey != null && (allocationBoundary == 0L
                    || bestWaste == 0L && (residentCapacities == null || bestResident))) {
                    break;
                }
            }
        } catch (Throwable inspectionFailure) {
            poison();
            throw inspectionFailure;
        }
        if (originalKey == null) {
            // Learn only after a failed fit. Successful allocations retain
            // their old scan and do not compute a maximum on the hot path.
            refreshFreeSpanUpperBound();
            return null;
        }

        long consumed = checkedAdd(prefix, bytes);
        long suffix = segmentLength - consumed;
        if (nextId == Long.MAX_VALUE) {
            throw new IllegalStateException("arena id exhausted");
        }
        if (nextSerial == Long.MAX_VALUE) {
            throw new IllegalStateException("arena serial exhausted");
        }
        long id = nextId;
        long serial = nextSerial;
        Long prefixKey = prefix > 0L ? Long.valueOf(segmentStart) : null;
        Long suffixKey = suffix > 0L
            ? Long.valueOf(checkedAdd(aligned, bytes)) : null;
        long updatedUsed = checkedAdd(usedBytes, bytes);
        CacheBudget.Reservation reservation = tryReserveRange();
        if (reservation == null) {
            heapRejected = true;
            rejected++;
            return null;
        }
        ArenaRange range = new ArenaRange(id, serial, aligned, bytes,
            generation, reservation);
        uncertainAllocation = range;
        try {
            ArenaRange previous = live.put(Long.valueOf(id), range);
            if (previous != null) throw new IllegalStateException(
                "arena logical id collision");
            Long removed = free.remove(originalKey);
            if (removed == null || removed.longValue() != segmentLength) {
                throw new IllegalStateException(
                    "selected arena free segment disappeared");
            }
            if (prefixKey != null) free.put(prefixKey, Long.valueOf(prefix));
            if (suffixKey != null) free.put(suffixKey, Long.valueOf(suffix));
            usedBytes = updatedUsed;
            nextId++;
            nextSerial++;
            range.publishedBy(this);
            uncertainAllocation = null;
            return range;
        } catch (Throwable publicationFailure) {
            // A faulting Map may throw after completing a mutation. Rollback
            // could reintroduce an interval that is also present in live.
            // Poisoning makes every future mutation fail closed while known
            // existing ranges remain queryable for safe legacy rendering.
            poison();
            throw publicationFailure;
        }
    }

    private void refreshFreeSpanUpperBound() {
        long largest = 0L;
        try {
            for (Long length : free.values()) largest = Math.max(largest, length.longValue());
        } catch (Throwable inspectionFailure) {
            poison();
            throw inspectionFailure;
        }
        freeSpanUpperBound = largest;
    }

    private void putFree(long offset, long length) {
        if (offset < 0L || length <= 0L || checkedAdd(offset, length) > committedBytes) {
            throw new IllegalArgumentException("free segment outside committed arena");
        }
        long start = offset;
        long end = checkedAdd(offset, length);
        Map.Entry<Long, Long> lower;
        Map.Entry<Long, Long> upper;
        try {
            lower = free.floorEntry(Long.valueOf(offset));
            upper = free.ceilingEntry(Long.valueOf(offset));
        } catch (Throwable inspectionFailure) {
            poison();
            throw inspectionFailure;
        }
        Long lowerKey = null;
        if (lower != null) {
            long lowerEnd = checkedAdd(lower.getKey().longValue(),
                lower.getValue().longValue());
            if (lowerEnd > offset) {
                poison();
                throw new IllegalStateException("overlapping arena free");
            }
            if (lowerEnd == offset) {
                start = lower.getKey().longValue();
                lowerKey = lower.getKey();
            }
        }
        Long upperKey = null;
        if (upper != null && upper.getKey().longValue() <= end) {
            long upperStart = upper.getKey().longValue();
            if (upperStart < end) {
                poison();
                throw new IllegalStateException("overlapping arena free");
            }
            if (upperStart == end) {
                end = checkedAdd(upperStart, upper.getValue().longValue());
                upperKey = upper.getKey();
            }
        }
        try {
            Long previous = free.put(Long.valueOf(start),
                Long.valueOf(end - start));
            if (lowerKey == null ? previous != null
                : previous == null
                    || previous.longValue() != lower.getValue().longValue()) {
                throw new IllegalStateException(
                    "arena free publication replaced an unexpected segment");
            }
            if (upperKey != null) {
                Long removed = free.remove(upperKey);
                if (removed == null || removed.longValue()
                    != upper.getValue().longValue()) {
                    throw new IllegalStateException(
                        "adjacent arena free segment disappeared");
                }
            }
            freeSpanUpperBound = Math.max(freeSpanUpperBound, end - start);
        } catch (Throwable publicationFailure) {
            poison();
            throw publicationFailure;
        }
    }

    private void requireHealthy() {
        if (poisoned) throw new IllegalStateException("arena allocator poisoned");
    }

    private void poison() { poisoned = true; }

    private CacheBudget.Reservation tryReserveRange() {
        if (budget == null) return CacheBudget.Reservation.empty();
        return budget.tryReserve(BudgetKind.HEAP, metadataHeapBytes);
    }

    private void releaseAllRanges() {
        ArenaRange[] owned;
        try {
            owned = live.values().toArray(new ArenaRange[live.size()]);
        } catch (Throwable inspectionFailure) {
            poison();
            Throwable failure = inspectionFailure;
            failure = releaseUncertainRanges(failure);
            rethrow(failure);
            return;
        }
        Throwable failure = null;
        try { free.clear(); }
        catch (Throwable clearFailure) {
            failure = appendFailure(failure, clearFailure);
        }
        try { live.clear(); }
        catch (Throwable clearFailure) {
            failure = appendFailure(failure, clearFailure);
        }
        if (failure != null) poison();
        for (ArenaRange range : owned) try {
            range.releaseHeapReservation();
        } catch (Throwable releaseFailure) {
            failure = appendFailure(failure, releaseFailure);
        }
        failure = releaseUncertainRanges(failure);
        if (failure != null) {
            poison();
            rethrow(failure);
        }
    }

    private Throwable releaseUncertainRanges(Throwable failure) {
        ArenaRange allocation = uncertainAllocation;
        ArenaRange removal = uncertainRemoval;
        uncertainAllocation = null;
        uncertainRemoval = null;
        if (allocation != null) try {
            allocation.releaseHeapReservation();
        } catch (Throwable releaseFailure) {
            failure = appendFailure(failure, releaseFailure);
        }
        if (removal != null) try {
            removal.releaseHeapReservation();
        } catch (Throwable releaseFailure) {
            failure = appendFailure(failure, releaseFailure);
        }
        return failure;
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
        throw new IllegalStateException("arena allocator disposal failed", failure);
    }

    private static long roundUp(long value, long unit) {
        if (value < 0L || unit <= 0L) throw new IllegalArgumentException("rounding");
        long remainder = value % unit;
        return remainder == 0L ? value : checkedAdd(value, unit - remainder);
    }

    private static long checkedAdd(long left, long right) {
        if (left < 0L || right < 0L || right > Long.MAX_VALUE - left) {
            throw new ArithmeticException("arena offset overflow");
        }
        return left + right;
    }
}
