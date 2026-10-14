package dev.rlcraft.ice.optimizer.render.arena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.TreeMap;
import org.junit.Test;

public final class GpuArenaAllocatorTest {
    @Test public void impossibleFitsSkipRepeatedScansButCoalescedSpaceRemainsUsable() {
        final int[] scans = { 0 };
        TreeMap<Long, Long> free = new TreeMap<Long, Long>() {
            @Override public java.util.Set<java.util.Map.Entry<Long, Long>> entrySet() {
                scans[0]++;
                return super.entrySet();
            }
        };
        GpuArenaAllocator arena = new GpuArenaAllocator(128L, 128L, 4L, 1L,
            free, new HashMap<Long, ArenaRange>());
        ArenaRange[] ranges = new ArenaRange[8];
        for (int i = 0; i < ranges.length; i++) ranges[i] = arena.allocate(16L);
        for (int i = 0; i < ranges.length; i += 2) assertTrue(arena.free(ranges[i]));
        assertNull(arena.allocate(32L));
        int afterFailedScan = scans[0];
        assertNull(arena.allocate(64L));
        assertEquals(afterFailedScan, scans[0]);
        assertTrue(arena.free(ranges[1]));
        ArenaRange coalesced = arena.allocate(48L);
        assertNotNull(coalesced);
        assertEquals(0L, coalesced.getOffset());
        arena.reset(2L);
        assertNotNull(arena.allocate(128L));
    }

    @Test public void emptySpanBoundDoesNotSuppressFreshPages() {
        GpuArenaAllocator arena = new GpuArenaAllocator(128L, 256L, 4L, 1L);
        assertNotNull(arena.allocate(128L));
        ArenaRange next = arena.allocate(128L);
        assertNotNull(next);
        assertEquals(128L, next.getOffset());
    }

    @Test public void earlyFirstFitDoesNotHideAnUnvisitedLargerHole() {
        GpuArenaAllocator arena = new GpuArenaAllocator(512L, 512L, 4L, 1L);
        ArenaRange small = arena.allocate(16L);
        arena.allocate(16L);
        ArenaRange large = arena.allocate(128L);
        arena.allocate(352L);
        arena.free(small);
        arena.free(large);
        assertEquals(0L, arena.allocate(8L).getOffset());
        assertEquals(32L, arena.allocate(128L).getOffset());
    }

    @Test public void drawOwnershipRejectsFreedReusedForeignAndResetRanges() {
        GpuArenaAllocator arena = new GpuArenaAllocator(64L, 64L, 4L, 1L);
        GpuArenaAllocator foreign = new GpuArenaAllocator(64L, 64L, 4L, 1L);
        ArenaRange first = arena.allocate(32L);
        assertTrue(arena.ownsLiveRange(first));
        assertFalse(foreign.ownsLiveRange(first));
        assertFalse(arena.ownsLiveRange(foreign.allocate(32L)));
        assertTrue(arena.free(first));
        ArenaRange replacement = arena.allocate(32L);
        assertEquals(first.getOffset(), replacement.getOffset());
        assertFalse(arena.ownsLiveRange(first));
        assertTrue(arena.ownsLiveRange(replacement));
        arena.reset(2L);
        assertFalse(arena.ownsLiveRange(replacement));
        ArenaRange current = arena.allocate(32L);
        assertTrue(arena.ownsLiveRange(current));
        arena.close();
        assertFalse(arena.ownsLiveRange(current));
    }

    @Test public void equivalentFreeReleasesThePublishedRangeAndItsBudget() {
        CacheBudget budget = new CacheBudget(1024L, 1L, 1L);
        GpuArenaAllocator arena = new GpuArenaAllocator(64L, 64L, 4L, 1L, budget);
        ArenaRange actual = arena.allocate(32L);
        ArenaRange equivalent = new ArenaRange(actual.getId(), actual.getSerial(),
            actual.getOffset(), actual.getLength(), actual.getGeneration());
        assertFalse(arena.ownsLiveRange(equivalent));
        assertTrue(arena.free(equivalent));
        assertFalse(arena.ownsLiveRange(actual));
        assertEquals(0L, budget.snapshot().getHeapUsed());
    }

    @Test public void failedNativePageIsSkippedInsideCoalescedCommittedFreeSpace() {
        GpuArenaAllocator arena = new GpuArenaAllocator(128L, 256L, 4L, 1L, null, 128L);
        ArenaRange first = arena.allocate(128L), second = arena.allocate(128L);
        arena.free(first); arena.free(second);
        assertEquals(256L, arena.snapshot().getCommittedBytes());
        ArenaRange retry = arena.allocatePaged(112L, new long[] {0L, 128L}, new boolean[] {true, false});
        assertNotNull(retry);
        assertEquals(128L, retry.getOffset());
        assertEquals(256L, arena.snapshot().getCommittedBytes());
        assertTrue(arena.free(retry));
        assertNull(arena.allocatePaged(112L, new long[] {0L, 0L}, new boolean[] {true, true}));
        assertEquals(0L, arena.snapshot().getUsedBytes());
        assertEquals(0L, arena.allocate(112L).getOffset());
    }

    @Test public void retryUsesAnotherCommittedHoleAndRetainsBestFitWithinAllowedPages() {
        GpuArenaAllocator arena = new GpuArenaAllocator(128L, 384L, 4L, 1L, null, 128L);
        ArenaRange a = arena.allocate(128L);
        ArenaRange b = arena.allocate(32L);
        arena.allocate(96L);
        ArenaRange c = arena.allocate(64L);
        arena.allocate(64L);
        arena.free(a); arena.free(b); arena.free(c);
        ArenaRange retry = arena.allocatePaged(24L, new long[] {0L, 128L, 128L},
            new boolean[] {true, false, false});
        assertEquals(128L, retry.getOffset());
        assertEquals(384L, arena.snapshot().getCommittedBytes());
        assertEquals(256L,
            arena.allocatePaged(64L, new long[] {0L, 0L, 128L},
                new boolean[] {true, true, false}).getOffset());
    }

    @Test public void pageExclusionCanCommitFreshStorageWithoutReusingFailedLocation() {
        GpuArenaAllocator arena = new GpuArenaAllocator(128L, 384L, 4L, 1L, null, 128L);
        ArenaRange first = arena.allocate(112L);
        arena.free(first);
        assertEquals(128L,
            arena.allocatePaged(112L, new long[] {0L, 0L, 0L},
                new boolean[] {true, false, false}).getOffset());
        GpuArenaAllocator excludedFuture = new GpuArenaAllocator(128L, 256L, 4L, 1L, null, 128L);
        assertEquals(128L,
            excludedFuture.allocatePaged(16L, new long[] {0L, 0L},
                new boolean[] {true, false}).getOffset());
    }

    @Test public void residentHoleWinsOverSmallerLogicalTailEvenWithinTheSamePage() {
        GpuArenaAllocator arena = new GpuArenaAllocator(128L, 128L, 4L, 1L, null, 128L);
        ArenaRange residentHole = arena.allocate(40L);
        arena.allocate(64L);
        arena.free(residentHole);
        long[] capacities = {104L};
        ArenaRange chosen = arena.allocatePaged(4L, capacities, null);
        assertEquals(0L, chosen.getOffset());
        arena.free(chosen);
        assertEquals(0L, arena.allocatePaged(4L, capacities, new boolean[] {true}).getOffset());
    }

    @Test public void coalescedIntervalSearchesLaterResidentPageBeforeEarlierGrowth() {
        GpuArenaAllocator arena = new GpuArenaAllocator(128L, 256L, 4L, 1L, null, 128L);
        ArenaRange a = arena.allocate(128L), b = arena.allocate(128L);
        arena.free(a); arena.free(b);
        assertEquals(128L, arena.allocatePaged(16L, new long[] {0L, 32L}, null).getOffset());
    }

    @Test public void pressureRetryUsesResidentSpaceOrAffordableNewExtentWithoutRepeatedGrowth() {
        GpuArenaAllocator arena = new GpuArenaAllocator(128L, 256L, 4L, 1L, null, 128L);
        arena.allocate(104L);
        assertEquals(128L,
            arena.allocatePaged(4L, new long[] {104L, 0L}, null, 32L).getOffset());
        assertNull(arena.allocatePaged(40L, new long[] {104L, 32L}, null, 0L));
        assertEquals(132L,
            arena.allocatePaged(4L, new long[] {104L, 32L}, null, 0L).getOffset());
        assertEquals(256L, arena.snapshot().getCommittedBytes());
    }

    @Test public void pagedAllocationsNeverStraddleNativeBuffersEvenAfterCoalescing() {
        GpuArenaAllocator arena = new GpuArenaAllocator(128L, 384L, 4L, 1L, null, 128L);
        ArenaRange a = arena.allocate(96L), b = arena.allocate(96L);
        assertFalse(arena.isPageFree(0L));
        assertEquals(0L, a.getOffset());
        assertEquals(128L, b.getOffset());
        assertTrue(arena.free(a));
        assertTrue(arena.free(b));
        assertTrue(arena.isPageFree(0L));
        assertTrue(arena.isPageFree(128L));
        assertFalse(arena.isPageFree(256L));
        // Coalesced free space includes a page boundary, yet each allocation
        // remains fully contained in one physical buffer.
        ArenaRange c = arena.allocate(112L), d = arena.allocate(112L);
        assertEquals(0L, c.getOffset());
        assertEquals(128L, d.getOffset());
        assertNull(arena.allocate(132L));
        assertTrue(arena.free(c));
        assertTrue(arena.free(d));
        assertNotNull(arena.allocate(128L));
        assertNotNull(arena.allocate(128L));
        assertNotNull(arena.allocate(128L));
        assertNull(arena.allocate(4L));
    }

    @Test public void pagedBestFitPreservesLargeHoleForLargerMesh() {
        GpuArenaAllocator arena = new GpuArenaAllocator(128L, 128L, 4L, 1L, null, 128L);
        ArenaRange large = arena.allocate(48L);
        arena.allocate(16L);
        ArenaRange small = arena.allocate(24L);
        arena.allocate(40L);
        arena.free(large); arena.free(small);
        assertEquals(small.getOffset(), arena.allocate(20L).getOffset());
        assertEquals(large.getOffset(), arena.allocate(48L).getOffset());
    }

    @Test public void pagedAllocatorGrowsBeyondOld128MiBLimitOnlyOnDemand() {
        long page = 16L * 1024L * 1024L;
        GpuArenaAllocator arena = new GpuArenaAllocator(page, page * 12L, 4L, 1L, null, page);
        assertEquals(0L, arena.snapshot().getCommittedBytes());
        for (int i = 0; i < 9; i++) assertEquals(page * i, arena.allocate(page).getOffset());
        assertEquals(page * 9L, arena.snapshot().getCommittedBytes());
        assertEquals(page * 9L, arena.snapshot().getUsedBytes());
    }

    @Test
    public void liveRangeMetadataUsesAndReleasesSharedHeapBudget() {
        CacheBudget budget = new CacheBudget(512L, 1L, 1L);
        GpuArenaAllocator arena = new GpuArenaAllocator(64L, 128L, 4L,
            1L, budget);
        ArenaRange first = arena.allocate(16L);
        assertNotNull(first);
        assertEquals(512L, budget.snapshot().getHeapUsed());
        assertNull(arena.allocate(16L));

        assertTrue(arena.free(first));
        assertEquals(0L, budget.snapshot().getHeapUsed());
        assertNotNull(arena.allocate(16L));
        arena.reset(2L);
        assertEquals(0L, budget.snapshot().getHeapUsed());
    }

    @Test public void ownerMetadataChargeIsBoundedAndReleasedWithItsRange() {
        long bytes = GpuArenaAllocator.rangeHeapBytes() + 128L;
        CacheBudget budget = new CacheBudget(bytes, 1L, 1L);
        GpuArenaAllocator arena = new GpuArenaAllocator(64L, 128L, 4L, 1L, budget, 64L, bytes);
        ArenaRange first = arena.allocate(16L);
        assertNotNull(first);
        assertEquals(bytes, budget.snapshot().getHeapUsed());
        assertNull(arena.allocate(16L));
        assertTrue(arena.free(first));
        assertEquals(0L, budget.snapshot().getHeapUsed());
        assertNotNull(arena.allocate(16L));
        arena.close();
        assertEquals(0L, budget.snapshot().getHeapUsed());
    }

    @Test
    public void finalCloseReleasesEveryLiveRangeReservation() {
        CacheBudget budget = new CacheBudget(1024L, 1L, 1L);
        GpuArenaAllocator arena = new GpuArenaAllocator(64L, 128L, 4L,
            1L, budget);
        assertNotNull(arena.allocate(16L));
        assertNotNull(arena.allocate(16L));
        assertEquals(1024L, budget.snapshot().getHeapUsed());

        arena.close();

        assertEquals(0L, budget.snapshot().getHeapUsed());
        assertTrue(arena.isPoisoned());
        assertPoisonedAllocationRejected(arena);
    }

    @Test
    public void alignsCoalescesAndRejectsAbaFreeWithinHardMaximum() {
        GpuArenaAllocator arena = new GpuArenaAllocator(64L, 128L, 16L, 1L);
        ArenaRange first = arena.allocate(17L);
        ArenaRange second = arena.allocate(16L);
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(0L, first.getOffset() & 15L);
        assertEquals(0L, second.getOffset() & 15L);
        ArenaRange forged = new ArenaRange(first.getId(), first.getSerial() + 1L,
            first.getOffset(), first.getLength(), first.getGeneration());
        assertFalse(arena.free(forged));
        assertTrue(arena.free(first));
        assertTrue(arena.free(second));
        assertEquals(1, arena.snapshot().getFreeSegments());
        ArenaRange whole = arena.allocate(64L);
        assertNotNull(whole);
        assertEquals(0L, whole.getOffset());
        assertNull(arena.allocate(65L));
    }

    @Test
    public void generationResetInvalidatesOldRanges() {
        GpuArenaAllocator arena = new GpuArenaAllocator(64L, 64L, 4L, 1L);
        ArenaRange old = arena.allocate(8L);
        arena.reset(2L);
        assertFalse(arena.isLive(old));
        assertFalse(arena.free(old));
        assertEquals(2L, arena.snapshot().getGeneration());
    }

    @Test
    public void uploadRingNeverWaitsForBusyFenceAndRejectsStaleLease() {
        UploadRing ring = new UploadRing(2, 2);
        UploadRing.Lease first = ring.tryAcquire(8L);
        assertNotNull(first);
        TestFence fence = new TestFence();
        assertTrue(ring.submit(first, fence));
        UploadRing.Lease second = ring.tryAcquire(8L);
        assertNotNull(second);
        assertNull(ring.tryAcquire(8L));
        assertTrue(ring.cancel(second));
        assertFalse(ring.cancel(second));
        fence.ready = true;
        assertNotNull(ring.tryAcquire(8L));
    }

    @Test
    public void identifierExhaustionDoesNotConsumeTheSelectedFreeSegment()
        throws Exception {
        GpuArenaAllocator arena = new GpuArenaAllocator(64L, 64L, 4L, 1L);
        ArenaRange first = arena.allocate(8L);
        assertTrue(arena.free(first));
        ArenaStatus before = arena.snapshot();
        Field nextId = GpuArenaAllocator.class.getDeclaredField("nextId");
        nextId.setAccessible(true);
        nextId.setLong(arena, Long.MAX_VALUE);
        try {
            arena.allocate(8L);
            fail("exhausted identifier must fail before allocator mutation");
        } catch (IllegalStateException expected) {
            assertEquals("arena id exhausted", expected.getMessage());
        }
        ArenaStatus after = arena.snapshot();
        assertEquals(before.getUsedBytes(), after.getUsedBytes());
        assertEquals(before.getFreeSegments(), after.getFreeSegments());
        assertEquals(before.getAllocations(), after.getAllocations());
    }

    @Test
    public void postPublicationFreeMapFailurePoisonsBeforeRangeReuse() {
        FaultingTreeMap free = new FaultingTreeMap();
        GpuArenaAllocator arena = new GpuArenaAllocator(64L, 64L, 4L, 1L,
            free, new HashMap<Long, ArenaRange>());
        ArenaRange range = arena.allocate(8L);
        free.failNextPut();
        try {
            arena.free(range);
            fail("post-publication free-map failure must escape");
        } catch (IllegalStateException expected) {
            assertEquals("injected free put failure", expected.getMessage());
        }
        assertTrue(arena.isPoisoned());
        assertTrue("the live mapping is retained when free publication is uncertain",
            arena.isLive(range));
        assertTrue(arena.ownsLiveRange(range));
        assertPoisonedAllocationRejected(arena);
    }

    @Test
    public void postPublicationLiveMapFailurePoisonsUntilGenerationReset() {
        FaultingLiveMap live = new FaultingLiveMap();
        CacheBudget budget = new CacheBudget(1024L, 1L, 1L);
        GpuArenaAllocator arena = new GpuArenaAllocator(64L, 64L, 4L, 1L,
            new TreeMap<Long, Long>(), live, budget);
        live.failNextPut();
        try {
            arena.allocate(8L);
            fail("post-publication live-map failure must escape");
        } catch (IllegalStateException expected) {
            assertEquals("injected live put failure", expected.getMessage());
        }
        assertTrue(arena.isPoisoned());
        assertEquals(512L, budget.snapshot().getHeapUsed());
        assertPoisonedAllocationRejected(arena);
        arena.reset(2L);
        assertEquals(0L, budget.snapshot().getHeapUsed());
        assertFalse(arena.isPoisoned());
        assertNotNull(arena.allocate(8L));
        arena.close();
        assertEquals(0L, budget.snapshot().getHeapUsed());
    }

    @Test
    public void preMutationLiveMapFailureReservationIsReleasedOnReset() {
        FaultingLiveMap live = new FaultingLiveMap();
        CacheBudget budget = new CacheBudget(512L, 1L, 1L);
        GpuArenaAllocator arena = new GpuArenaAllocator(64L, 64L, 4L, 1L,
            new TreeMap<Long, Long>(), live, budget);
        live.failNextPutBeforeMutation();
        try {
            arena.allocate(8L);
            fail("outcome-uncertain live-map failure must escape");
        } catch (IllegalStateException expected) {
            assertEquals("injected live pre-put failure", expected.getMessage());
        }
        assertTrue(arena.isPoisoned());
        assertEquals(512L, budget.snapshot().getHeapUsed());

        arena.reset(2L);

        assertFalse(arena.isPoisoned());
        assertEquals(0L, budget.snapshot().getHeapUsed());
    }

    @Test
    public void postRemovalLiveMapFailureReservationIsReleasedOnClose() {
        FaultingLiveMap live = new FaultingLiveMap();
        CacheBudget budget = new CacheBudget(512L, 1L, 1L);
        GpuArenaAllocator arena = new GpuArenaAllocator(64L, 64L, 4L, 1L,
            new TreeMap<Long, Long>(), live, budget);
        ArenaRange range = arena.allocate(8L);
        assertNotNull(range);
        live.failNextRemove();
        try {
            arena.free(range);
            fail("post-removal live-map failure must escape");
        } catch (IllegalStateException expected) {
            assertEquals("injected live remove failure", expected.getMessage());
        }
        assertTrue(arena.isPoisoned());
        assertFalse("uncertain removal must inspect the actual live map", arena.ownsLiveRange(range));
        assertEquals(512L, budget.snapshot().getHeapUsed());

        arena.close();

        assertEquals(0L, budget.snapshot().getHeapUsed());
    }

    @Test
    public void coalescingRemovalFailureCannotExposeOverlappingRange() {
        FaultingTreeMap free = new FaultingTreeMap();
        GpuArenaAllocator arena = new GpuArenaAllocator(64L, 64L, 4L, 1L,
            free, new HashMap<Long, ArenaRange>());
        ArenaRange first = arena.allocate(8L);
        ArenaRange second = arena.allocate(8L);
        assertTrue(arena.free(first));
        free.failNextRemove();
        try {
            arena.free(second);
            fail("post-publication coalescing failure must escape");
        } catch (IllegalStateException expected) {
            assertEquals("injected free remove failure", expected.getMessage());
        }
        assertTrue(arena.isPoisoned());
        assertTrue(arena.isLive(second));
        assertPoisonedAllocationRejected(arena);
    }

    private static void assertPoisonedAllocationRejected(
        GpuArenaAllocator arena) {
        try {
            arena.allocate(4L);
            fail("poisoned allocator must never reuse uncertain free ranges");
        } catch (IllegalStateException expected) {
            assertEquals("arena allocator poisoned", expected.getMessage());
        }
    }

    private static final class TestFence implements UploadRing.Fence {
        private boolean ready;
        @Override public boolean isSignaled() { return ready; }
        @Override public void destroy() { }
    }

    private static final class FaultingTreeMap extends TreeMap<Long, Long> {
        private boolean failPut;
        private boolean failRemove;

        private void failNextPut() { failPut = true; }
        private void failNextRemove() { failRemove = true; }

        @Override public Long put(Long key, Long value) {
            Long previous = super.put(key, value);
            if (failPut) {
                failPut = false;
                throw new IllegalStateException("injected free put failure");
            }
            return previous;
        }

        @Override public Long remove(Object key) {
            Long previous = super.remove(key);
            if (failRemove) {
                failRemove = false;
                throw new IllegalStateException("injected free remove failure");
            }
            return previous;
        }
    }

    private static final class FaultingLiveMap
        extends HashMap<Long, ArenaRange> {
        private boolean failPut;
        private boolean failPutBeforeMutation;
        private boolean failRemove;
        private void failNextPut() { failPut = true; }
        private void failNextPutBeforeMutation() {
            failPutBeforeMutation = true;
        }
        private void failNextRemove() { failRemove = true; }

        @Override public ArenaRange put(Long key, ArenaRange value) {
            if (failPutBeforeMutation) {
                failPutBeforeMutation = false;
                throw new IllegalStateException(
                    "injected live pre-put failure");
            }
            ArenaRange previous = super.put(key, value);
            if (failPut) {
                failPut = false;
                throw new IllegalStateException("injected live put failure");
            }
            return previous;
        }

        @Override public ArenaRange remove(Object key) {
            ArenaRange previous = super.remove(key);
            if (failRemove) {
                failRemove = false;
                throw new IllegalStateException(
                    "injected live remove failure");
            }
            return previous;
        }
    }
}
