package dev.rlcraft.ice.optimizer.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class MonotonicTokenCounterTest {
    @Test
    public void reservesStrictlyIncreasingPositiveTokens() {
        AtomicLong counter = new AtomicLong(1L);
        assertEquals(1L, MonotonicTokenCounter.next(counter, "bridge token"));
        assertEquals(2L, MonotonicTokenCounter.next(counter, "bridge token"));
        assertEquals(3L, counter.get());
    }

    @Test
    public void exhaustionIsStickyAndNeverWraps() {
        AtomicLong counter = new AtomicLong(Long.MAX_VALUE - 1L);
        assertEquals(Long.MAX_VALUE - 1L,
            MonotonicTokenCounter.next(counter, "bridge token"));
        assertExhausted(counter);
        assertExhausted(counter);
        assertEquals(Long.MAX_VALUE, counter.get());
    }

    @Test
    public void bridgeSentinelFailsClosedWithoutReusingAToken() {
        AtomicLong counter = new AtomicLong(Long.MAX_VALUE);
        assertEquals(0L, MonotonicTokenCounter.nextOrZero(counter,
            "bridge token"));
        assertEquals(0L, MonotonicTokenCounter.nextOrZero(counter,
            "bridge token"));
        assertEquals(Long.MAX_VALUE, counter.get());
    }

    @Test
    public void concurrentReservationsAreUnique() throws Exception {
        final int threads = 8;
        final int reservationsPerThread = 256;
        final AtomicLong counter = new AtomicLong(1L);
        final Set<Long> tokens = Collections.synchronizedSet(
            new HashSet<Long>());
        final CountDownLatch ready = new CountDownLatch(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        for (int thread = 0; thread < threads; thread++) {
            Thread worker = new Thread(new Runnable() {
                @Override public void run() {
                    ready.countDown();
                    try {
                        start.await();
                        for (int i = 0; i < reservationsPerThread; i++) {
                            tokens.add(MonotonicTokenCounter.next(counter,
                                "bridge token"));
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }
            }, "ice-token-test-" + thread);
            worker.start();
        }
        assertTrue(ready.await(5L, TimeUnit.SECONDS));
        start.countDown();
        assertTrue(done.await(5L, TimeUnit.SECONDS));
        assertEquals(threads * reservationsPerThread, tokens.size());
        assertEquals(1L + threads * reservationsPerThread, counter.get());
    }

    private static void assertExhausted(AtomicLong counter) {
        try {
            MonotonicTokenCounter.next(counter, "bridge token");
            throw new AssertionError("expected exhaustion");
        } catch (IllegalStateException expected) {
            assertEquals("bridge token exhausted", expected.getMessage());
        }
    }
}
