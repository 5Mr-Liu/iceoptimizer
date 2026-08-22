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
