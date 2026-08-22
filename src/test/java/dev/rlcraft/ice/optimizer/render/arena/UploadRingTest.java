package dev.rlcraft.ice.optimizer.render.arena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class UploadRingTest {
    @Test
    public void boundedProbeCursorStillVisitsEverySlot() {
        UploadRing ring = new UploadRing(2, 1);
        UploadRing.Lease first = ring.tryAcquire(16L);
        assertNotNull(first);
        assertTrue(ring.submit(first, new TestFence(false, false)));
        UploadRing.Lease second = ring.tryAcquire(16L);
        assertNotNull(second);
        assertTrue(ring.cancel(second));

        assertNull("the first bounded probe sees the busy slot",
            ring.tryAcquire(16L));
        assertNotNull("a failed probe must advance to the free sibling",
            ring.tryAcquire(16L));
    }

    @Test
    public void failedFenceDestroyPoisonsOnlyThatSlot() {
        UploadRing ring = new UploadRing(2, 2);
        UploadRing.Lease first = ring.tryAcquire(16L);
        assertTrue(ring.submit(first, new TestFence(true, true)));
        UploadRing.Lease cursorAdvance = ring.tryAcquire(16L);
        assertNotNull(cursorAdvance);
        assertTrue(ring.cancel(cursorAdvance));
        UploadRing.Lease second = ring.tryAcquire(16L);
        assertNotNull(second);
        assertEquals(1L, ring.getPoisoned());
    }

    @Test
    public void resetCleansEverySlotBeforeReportingFailures() {
        UploadRing ring = new UploadRing(2, 2);
        UploadRing.Lease first = ring.tryAcquire(16L);
        UploadRing.Lease second = ring.tryAcquire(16L);
        assertTrue(ring.submit(first, new TestFence(false, true)));
        assertTrue(ring.submit(second, new TestFence(false, true)));
        try {
            ring.reset(true);
            fail("Fence cleanup failure must be reported");
        } catch (IllegalStateException expected) {
            assertEquals(1, expected.getSuppressed().length);
        }
        assertNull("same-Context uncertain Fence deletion keeps slots poisoned",
            ring.tryAcquire(16L));
        ring.reset(false);
        assertNotNull("Context loss releases poisoned slot ownership",
            ring.tryAcquire(16L));
    }

    private static final class TestFence implements UploadRing.Fence {
        private final boolean signaled;
        private final boolean failDestroy;

        private TestFence(boolean signaled, boolean failDestroy) {
            this.signaled = signaled;
            this.failDestroy = failDestroy;
        }

        @Override public boolean isSignaled() { return signaled; }

        @Override public void destroy() {
            if (failDestroy) throw new IllegalStateException("injected destroy");
        }
    }
}
