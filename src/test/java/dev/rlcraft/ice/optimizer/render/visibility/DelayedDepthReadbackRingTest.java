package dev.rlcraft.ice.optimizer.render.visibility;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class DelayedDepthReadbackRingTest {
    @Test
    public void reportsSubmittedPayloadWithoutPollingItsFence() {
        DelayedDepthReadbackRing<String> ring =
            new DelayedDepthReadbackRing<String>(1);
        TestFence fence = new TestFence();
        assertFalse(ring.hasSubmitted());
        assertTrue(ring.submit(ring.tryAcquire(), "payload", fence));
        assertTrue(ring.hasSubmitted());
        assertEquals(0, ring.poll(0, (slot, value) -> { }));
        assertTrue(ring.hasSubmitted());
        fence.signaled = true;
        assertEquals(1, ring.poll(1, (slot, value) -> { }));
        assertFalse(ring.hasSubmitted());
    }

    @Test
    public void neverWaitsAndPublishesOnlySignaledPayloads() {
        DelayedDepthReadbackRing<String> ring = new DelayedDepthReadbackRing<String>(2);
        int first = ring.tryAcquire();
        int second = ring.tryAcquire();
        TestFence firstFence = new TestFence();
        TestFence secondFence = new TestFence();
        assertTrue(ring.submit(first, "first", firstFence));
        assertTrue(ring.submit(second, "second", secondFence));
        assertEquals(-1, ring.tryAcquire());
        final List<String> completed = new ArrayList<String>();
        assertEquals(0, ring.poll(2, (slot, value) -> completed.add(value)));
        firstFence.signaled = true;
        assertEquals(1, ring.poll(2, (slot, value) -> completed.add(value)));
        assertEquals(1, completed.size());
        assertEquals("first", completed.get(0));
        assertTrue(ring.getBusyPolls() >= 2L);
    }

    @Test
    public void badFenceAndBadConsumerPoisonOnlyTheirOwnSlotsUntilReset() {
        DelayedDepthReadbackRing<String> ring = new DelayedDepthReadbackRing<String>(2);
        int failedFenceSlot = ring.tryAcquire();
        int failedConsumerSlot = ring.tryAcquire();
        TestFence failedFence = new TestFence();
        failedFence.fail = true;
        TestFence ready = new TestFence();
        ready.signaled = true;
        ring.submit(failedFenceSlot, "fence", failedFence);
        ring.submit(failedConsumerSlot, "consumer", ready);
        assertEquals(1, ring.poll(2, (slot, value) -> {
            throw new IllegalStateException("injected map failure");
        }));
        assertEquals(1L, ring.getFenceFailures());
        assertEquals(1L, ring.getCompletionFailures());
        assertEquals(2L, ring.getPoisonedSlots());
        assertEquals(-1, ring.tryAcquire());
        // A separately submitted unsignaled Fence represents context loss:
        // reset(false) must abandon it without issuing a GL delete.
        ring.reset(false);
        int contextLossSlot = ring.tryAcquire();
        TestFence contextLossFence = new TestFence();
        ring.submit(contextLossSlot, "lost-context", contextLossFence);
        ring.reset(false);
        assertTrue(ring.tryAcquire() >= 0);
        assertTrue("context-loss reset must not delete old sync objects",
            !contextLossFence.destroyedWithValidContext);
    }

    @Test
    public void reportsWhetherFenceOrCompletionFailedWithoutThrowingIntoRenderLoop() {
        DelayedDepthReadbackRing<String> ring = new DelayedDepthReadbackRing<String>(2);
        TestFence bad = new TestFence();
        bad.fail = true;
        TestFence ready = new TestFence();
        ready.signaled = true;
        ring.submit(ring.tryAcquire(), "fence", bad);
        ring.submit(ring.tryAcquire(), "completion", ready);
        final List<DelayedDepthReadbackRing.FailureKind> kinds =
            new ArrayList<DelayedDepthReadbackRing.FailureKind>();
        assertEquals(1, ring.poll(2, (slot, value) -> {
            throw new IllegalStateException(value);
        }, (slot, value, kind, error) -> kinds.add(kind)));
        assertEquals(2, kinds.size());
        assertEquals(DelayedDepthReadbackRing.FailureKind.FENCE, kinds.get(0));
        assertEquals(DelayedDepthReadbackRing.FailureKind.COMPLETION, kinds.get(1));
    }

    @Test
    public void fenceDeleteFailurePoisonsSlotAndSkipsUnsafePublication() {
        DelayedDepthReadbackRing<String> ring =
            new DelayedDepthReadbackRing<String>(1);
        TestFence fence = new TestFence();
        fence.signaled = true;
        fence.failDestroy = true;
        ring.submit(ring.tryAcquire(), "payload", fence);
        final List<DelayedDepthReadbackRing.FailureKind> failures =
            new ArrayList<DelayedDepthReadbackRing.FailureKind>();
        final List<String> completed = new ArrayList<String>();
        assertEquals(0, ring.poll(1, (slot, value) -> completed.add(value),
            (slot, value, kind, error) -> failures.add(kind)));
        assertTrue(completed.isEmpty());
        assertEquals(1, failures.size());
        assertEquals(DelayedDepthReadbackRing.FailureKind.FENCE, failures.get(0));
        assertEquals(-1, ring.tryAcquire());
    }

    @Test
    public void resetClearsEverySlotAndAggregatesFenceDeleteFailures() {
        DelayedDepthReadbackRing<String> ring =
            new DelayedDepthReadbackRing<String>(2);
        TestFence first = new TestFence();
        first.failDestroy = true;
        TestFence second = new TestFence();
        second.failDestroy = true;
        ring.submit(ring.tryAcquire(), "first", first);
        ring.submit(ring.tryAcquire(), "second", second);
        try {
            ring.reset(true);
            throw new AssertionError("expected Fence cleanup failure");
        } catch (IllegalStateException expected) {
            assertEquals(1, expected.getSuppressed().length);
        }
        assertEquals("outcome-uncertain Fence deletion keeps both slots poisoned",
            -1, ring.tryAcquire());
        first.failDestroy = false;
        second.failDestroy = false;
        ring.reset(false);
        assertTrue(ring.tryAcquire() >= 0);
        assertTrue(ring.tryAcquire() >= 0);
    }

    @Test
    public void failureObserverErrorIsAttachedToTheOriginalFailure() {
        DelayedDepthReadbackRing<String> ring =
            new DelayedDepthReadbackRing<String>(1);
        TestFence fence = new TestFence();
        fence.fail = true;
        ring.submit(ring.tryAcquire(), "payload", fence);
        try {
            ring.poll(1, (slot, value) -> { }, (slot, value, kind, error) -> {
                throw new IllegalStateException("observer injected");
            });
            throw new AssertionError("expected original Fence failure");
        } catch (IllegalStateException expected) {
            assertEquals("injected Fence failure", expected.getMessage());
            assertEquals(1, expected.getSuppressed().length);
            assertEquals("observer injected",
                expected.getSuppressed()[0].getMessage());
        }
    }

    @Test
    public void identicalFenceAndCleanupFailureCannotStrandRingState() {
        DelayedDepthReadbackRing<String> ring =
            new DelayedDepthReadbackRing<String>(1);
        final IllegalStateException injected = new IllegalStateException(
            "shared Fence failure");
        ring.submit(ring.tryAcquire(), "payload",
            new DelayedDepthReadbackRing.Fence() {
                @Override public boolean isSignaled() { throw injected; }
                @Override public void destroy(boolean contextValid) {
                    throw injected;
                }
            });
        final List<Throwable> failures = new ArrayList<Throwable>();
        assertEquals(0, ring.poll(1, (slot, value) -> { },
            (slot, value, kind, error) -> failures.add(error)));
        assertEquals(1, failures.size());
        assertTrue(failures.get(0) == injected);
        assertEquals(0, injected.getSuppressed().length);
        assertEquals(-1, ring.tryAcquire());
    }

    private static final class TestFence implements DelayedDepthReadbackRing.Fence {
        private boolean signaled;
        private boolean fail;
        private boolean failDestroy;
        private boolean destroyedWithValidContext;
        @Override public boolean isSignaled() {
            if (fail) throw new IllegalStateException("injected Fence failure");
            return signaled;
        }
        @Override public void destroy(boolean contextValid) {
            destroyedWithValidContext |= contextValid;
            if (failDestroy) {
                throw new IllegalStateException("injected Fence delete failure");
            }
        }
    }
}
