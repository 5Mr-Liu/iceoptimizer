package dev.rlcraft.ice.optimizer.render.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.backend.RenderBackendId;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.frame.RenderPass;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class CorrelatedRenderProfilerTest {
    @Test
    public void boundsCpuNestingAndAllowsOwnerToRecoverAfterWrongThreadClose()
        throws Exception {
        ClientEpochs epochs = new ClientEpochs();
        FrameStamp stamp = new FrameStamp(1L, 1L, epochs.snapshot());
        CorrelatedRenderProfiler profiler = new CorrelatedRenderProfiler(8);
        CorrelatedRenderProfiler.CpuScope[] scopes =
            new CorrelatedRenderProfiler.CpuScope[80];
        for (int i = 0; i < scopes.length; i++) {
            scopes[i] = profiler.beginCpu(stamp, RenderPass.ENTITY_PASS_0,
                RenderBackendId.ICE_NATIVE, CpuWorkKind.WORK);
        }
        final AtomicReference<Throwable> workerFailure =
            new AtomicReference<Throwable>();
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                try { scopes[0].close(); }
                catch (Throwable error) { workerFailure.set(error); }
            }
        }, "wrong-profiler-owner");
        worker.start();
        worker.join();
        if (workerFailure.get() != null) throw new AssertionError(workerFailure.get());
        for (int i = scopes.length - 1; i >= 0; i--) scopes[i].close();
        assertTrue(profiler.snapshot().getScopeErrors() >= 17L);
    }

    @Test
    public void gpuQueryConstructionIsTransactionalAndScopesStayOnOwnerThread()
        throws Exception {
        ClientEpochs epochs = new ClientEpochs();
        FrameStamp stamp = new FrameStamp(1L, 1L, epochs.snapshot());
        CorrelatedRenderProfiler profiler = new CorrelatedRenderProfiler(8);
        FakeGpuDriver working = new FakeGpuDriver();
        profiler.attachGpuQueries(working, 4);

        FailingGpuDriver failing = new FailingGpuDriver(3);
        try {
            profiler.attachGpuQueries(failing, 4);
            throw new AssertionError("expected query allocation failure");
        } catch (IllegalStateException expected) {
            assertEquals(2, failing.deleteCalls);
        }

        final CorrelatedRenderProfiler.GpuScope scope = profiler.beginGpu(stamp,
            RenderPass.ENTITY_PASS_0, RenderBackendId.ICE_NATIVE);
        assertNotNull("old ring remains published after failed replacement", scope);
        Thread worker = new Thread(new Runnable() {
            @Override public void run() { scope.close(); }
        }, "wrong-gpu-owner");
        worker.start();
        worker.join();
        assertEquals("wrong-thread close must not issue the end timestamp",
            1, working.timestampCalls);
        scope.close();
        assertEquals(2, working.timestampCalls);
        assertTrue(profiler.snapshot().getScopeErrors() >= 1L);
    }

    @Test
    public void queryConstructionPreservesAllPartialCleanupFailures() {
        CorrelatedRenderProfiler profiler = new CorrelatedRenderProfiler(8);
        CleanupFailingGpuDriver driver = new CleanupFailingGpuDriver();
        try {
            profiler.attachGpuQueries(driver, 4);
            throw new AssertionError("expected query allocation failure");
        } catch (IllegalStateException expected) {
            assertEquals("allocation injected", expected.getMessage());
            assertEquals(2, driver.deleteCalls);
            assertEquals(2, expected.getSuppressed().length);
        }
    }

    @Test
    public void correlatesNestedCpuScopesCountersAndDelayedGpuTimestamps() {
        ClientEpochs epochs = new ClientEpochs();
        FrameStamp stamp = new FrameStamp(1L, 1L, epochs.snapshot());
        CorrelatedRenderProfiler profiler = new CorrelatedRenderProfiler(8);
        FakeGpuDriver driver = new FakeGpuDriver();
        profiler.attachGpuQueries(driver, 4);
        CorrelatedRenderProfiler.CpuScope outer = profiler.beginCpu(stamp,
            RenderPass.MAIN_SOLID, RenderBackendId.ICE_NATIVE, CpuWorkKind.WORK);
        CorrelatedRenderProfiler.CpuScope inner = profiler.beginCpu(stamp,
            RenderPass.MAIN_SOLID, RenderBackendId.ICE_NATIVE, CpuWorkKind.CACHE_LOOKUP);
        burnCpu();
        inner.close();
        outer.close();
        profiler.addCounter(stamp, RenderPass.MAIN_SOLID,
            RenderBackendId.ICE_NATIVE, RenderCounter.DRAW, 3L);
        CorrelatedRenderProfiler.GpuScope gpu = profiler.beginGpu(stamp,
            RenderPass.MAIN_SOLID, RenderBackendId.ICE_NATIVE,
            new CorrelatedRenderProfiler.GpuCompletion() {
                @Override public void completed(RenderProfileKey key, long elapsedNanos) {
                    callbackNanos.set(elapsedNanos);
                }
            });
        assertNotNull(gpu);
        gpu.close();
        assertEquals(0, profiler.pollGpu(4));
        driver.available = true;
        assertEquals(1, profiler.pollGpu(4));

        RenderProfileKey key = new RenderProfileKey(stamp, RenderPass.MAIN_SOLID,
            RenderBackendId.ICE_NATIVE);
        PassProfile profile = profiler.snapshot().getProfiles().get(key);
        assertNotNull(profile);
        assertTrue(profile.getCpuInclusiveNanos() >= profile.getCpuExclusiveNanos());
        assertEquals(Long.valueOf(3L), profile.getCounters().get(RenderCounter.DRAW));
        assertEquals(100L, profile.getGpuNanos());
        assertEquals(100L, callbackNanos.get());
    }

    @Test
    public void timestampFailuresPermanentlyPoisonUncertainSlots() {
        ClientEpochs epochs = new ClientEpochs();
        FrameStamp stamp = new FrameStamp(1L, 1L, epochs.snapshot());
        CorrelatedRenderProfiler profiler = new CorrelatedRenderProfiler(8);
        FaultGpuDriver driver = new FaultGpuDriver();
        profiler.attachGpuQueries(driver, 4);

        driver.failTimestampCall = 1;
        assertNull(profiler.beginGpu(stamp, RenderPass.MAIN_SOLID,
            RenderBackendId.ICE_NATIVE));
        CorrelatedRenderProfiler.GpuScope beginRecovered = profiler.beginGpu(stamp,
            RenderPass.MAIN_SOLID, RenderBackendId.ICE_NATIVE);
        assertNotNull(beginRecovered);
        driver.failTimestampCall = driver.timestampCalls + 1;
        beginRecovered.close();

        CorrelatedRenderProfiler.GpuScope endRecovered = profiler.beginGpu(stamp,
            RenderPass.MAIN_SOLID, RenderBackendId.ICE_NATIVE);
        assertNotNull("other healthy slots must remain usable", endRecovered);
        endRecovered.close();
        assertEquals(Arrays.asList(Integer.valueOf(1), Integer.valueOf(3),
            Integer.valueOf(4), Integer.valueOf(5), Integer.valueOf(6)),
            driver.timestampQueryIds);
        assertTrue(profiler.snapshot().getDroppedGpuQueries() >= 2L);
        assertTrue(profiler.snapshot().getScopeErrors() >= 2L);
    }

    @Test
    public void poisonedTimestampSlotsAreNeverReusedBeforeRingDestruction() {
        ClientEpochs epochs = new ClientEpochs();
        FrameStamp stamp = new FrameStamp(1L, 1L, epochs.snapshot());
        CorrelatedRenderProfiler profiler = new CorrelatedRenderProfiler(8);
        FaultGpuDriver driver = new FaultGpuDriver();
        profiler.attachGpuQueries(driver, 4);

        for (int i = 0; i < 4; i++) {
            driver.failTimestampCall = driver.timestampCalls + 1;
            assertNull(profiler.beginGpu(stamp, RenderPass.MAIN_SOLID,
                RenderBackendId.ICE_NATIVE));
        }
        assertNull("a fully poisoned ring must fail open instead of reusing IDs",
            profiler.beginGpu(stamp, RenderPass.MAIN_SOLID,
                RenderBackendId.ICE_NATIVE));
        assertEquals(Arrays.asList(Integer.valueOf(1), Integer.valueOf(3),
            Integer.valueOf(5), Integer.valueOf(7)), driver.timestampQueryIds);
    }

    @Test
    public void pollFailuresAndTimeoutsPoisonOnlyTheirOwnSlots() {
        ClientEpochs epochs = new ClientEpochs();
        FrameStamp stamp = new FrameStamp(1L, 1L, epochs.snapshot());
        CorrelatedRenderProfiler profiler = new CorrelatedRenderProfiler(8);
        FaultGpuDriver driver = new FaultGpuDriver();
        profiler.attachGpuQueries(driver, 4);

        CorrelatedRenderProfiler.GpuScope availabilityFailure = profiler.beginGpu(
            stamp, RenderPass.MAIN_SOLID, RenderBackendId.ICE_NATIVE);
        assertNotNull(availabilityFailure);
        availabilityFailure.close();
        driver.failAvailability = true;
        assertEquals(0, profiler.pollGpu(4));
        driver.failAvailability = false;
        CorrelatedRenderProfiler.GpuScope afterAvailabilityFailure =
            profiler.beginGpu(stamp, RenderPass.MAIN_SOLID,
                RenderBackendId.ICE_NATIVE);
        assertNotNull(afterAvailabilityFailure);
        afterAvailabilityFailure.close();

        for (int i = 0; i < 512; i++) profiler.pollGpu(4);
        CorrelatedRenderProfiler.GpuScope afterTimeout = profiler.beginGpu(stamp,
            RenderPass.MAIN_SOLID, RenderBackendId.ICE_NATIVE);
        assertNotNull("other slots must survive an unavailable query timeout",
            afterTimeout);
        assertEquals(Integer.valueOf(5), driver.timestampQueryIds.get(
            driver.timestampQueryIds.size() - 1));
    }

    @Test
    public void resultFailurePoisonsSlotAndCloseAggregatesEveryDeletionFailure() {
        ClientEpochs epochs = new ClientEpochs();
        FrameStamp stamp = new FrameStamp(1L, 1L, epochs.snapshot());
        CorrelatedRenderProfiler profiler = new CorrelatedRenderProfiler(8);
        FaultGpuDriver driver = new FaultGpuDriver();
        profiler.attachGpuQueries(driver, 4);
        CorrelatedRenderProfiler.GpuScope scope = profiler.beginGpu(stamp,
            RenderPass.MAIN_SOLID, RenderBackendId.ICE_NATIVE);
        assertNotNull(scope);
        scope.close();
        driver.available = true;
        driver.failResult = true;
        assertEquals(0, profiler.pollGpu(4));
        assertNotNull(profiler.beginGpu(stamp, RenderPass.MAIN_SOLID,
            RenderBackendId.ICE_NATIVE));

        driver.failDeleteCall = 1;
        driver.secondFailDeleteCall = 3;
        try {
            profiler.resetGpu(true);
            throw new AssertionError("expected injected delete failure");
        } catch (IllegalStateException expected) {
            assertEquals(8, driver.deleteCalls);
            assertEquals(1, expected.getSuppressed().length);
        }
        assertNull("failed cleanup must still detach the dead ring",
            profiler.beginGpu(stamp, RenderPass.MAIN_SOLID,
                RenderBackendId.ICE_NATIVE));
    }

    @Test
    public void gpuQueryBudgetIsPreReservedAndOnlyConfirmedDeletesReleaseIt() {
        CacheBudget budget = new CacheBudget(1L, 1L, 128L * 1024L);
        CorrelatedRenderProfiler profiler = new CorrelatedRenderProfiler(8);
        FaultGpuDriver driver = new FaultGpuDriver();
        profiler.attachGpuQueries(driver, 4, budget);
        assertEquals(8L * 4096L, budget.snapshot().getGpuUsed());
        driver.failDeleteCall = 1;
        try {
            profiler.resetGpu(true);
            throw new AssertionError("expected injected query deletion failure");
        } catch (IllegalStateException expected) {
            assertEquals("one outcome-uncertain query stays charged", 4096L,
                budget.snapshot().getGpuUsed());
        }

        CorrelatedRenderProfiler partial = new CorrelatedRenderProfiler(8);
        FailingGpuDriver allocationFailure = new FailingGpuDriver(3);
        try {
            partial.attachGpuQueries(allocationFailure, 4, budget);
            throw new AssertionError("expected query allocation failure");
        } catch (IllegalStateException expected) {
            assertEquals("throwing allocator keeps its unnamed-object token",
                8192L, budget.snapshot().getGpuUsed());
        }
    }

    private final AtomicLong callbackNanos = new AtomicLong();

    private static void burnCpu() {
        long value = 0L;
        for (int i = 0; i < 1000; i++) value += i;
        if (value == -1L) throw new AssertionError();
    }

    private static final class FakeGpuDriver implements GpuTimestampDriver {
        private final Map<Integer, Long> values = new HashMap<Integer, Long>();
        private int next = 1;
        private long clock;
        private boolean available;
        private int timestampCalls;
        @Override public int createQuery() { return next++; }
        @Override public void timestamp(int queryId) {
            timestampCalls++;
            clock += 100L;
            values.put(Integer.valueOf(queryId), Long.valueOf(clock));
        }
        @Override public boolean isAvailable(int queryId) { return available; }
        @Override public long resultNanos(int queryId) {
            return values.get(Integer.valueOf(queryId)).longValue();
        }
        @Override public void deleteQuery(int queryId) { }
    }

    private static final class FailingGpuDriver implements GpuTimestampDriver {
        private final int failAt;
        private int creates;
        private int deleteCalls;

        private FailingGpuDriver(int failAt) { this.failAt = failAt; }

        @Override public int createQuery() {
            if (++creates == failAt) throw new IllegalStateException("injected");
            return creates;
        }
        @Override public void timestamp(int queryId) { }
        @Override public boolean isAvailable(int queryId) { return false; }
        @Override public long resultNanos(int queryId) { return 0L; }
        @Override public void deleteQuery(int queryId) { deleteCalls++; }
    }

    private static final class CleanupFailingGpuDriver implements GpuTimestampDriver {
        private int creates;
        private int deleteCalls;

        @Override public int createQuery() {
            if (++creates == 3) throw new IllegalStateException("allocation injected");
            return creates;
        }
        @Override public void timestamp(int queryId) { }
        @Override public boolean isAvailable(int queryId) { return false; }
        @Override public long resultNanos(int queryId) { return 0L; }
        @Override public void deleteQuery(int queryId) {
            deleteCalls++;
            throw new IllegalStateException("cleanup injected " + deleteCalls);
        }
    }

    private static final class FaultGpuDriver implements GpuTimestampDriver {
        private final Map<Integer, Long> values = new HashMap<Integer, Long>();
        private int next = 1;
        private long clock;
        private boolean available;
        private boolean failAvailability;
        private boolean failResult;
        private int failTimestampCall;
        private int timestampCalls;
        private final List<Integer> timestampQueryIds = new ArrayList<Integer>();
        private int failDeleteCall;
        private int secondFailDeleteCall;
        private int deleteCalls;

        @Override public int createQuery() { return next++; }

        @Override public void timestamp(int queryId) {
            timestampCalls++;
            timestampQueryIds.add(Integer.valueOf(queryId));
            if (timestampCalls == failTimestampCall) {
                throw new IllegalStateException("timestamp injected");
            }
            clock += 100L;
            values.put(Integer.valueOf(queryId), Long.valueOf(clock));
        }

        @Override public boolean isAvailable(int queryId) {
            if (failAvailability) {
                throw new IllegalStateException("availability injected");
            }
            return available;
        }

        @Override public long resultNanos(int queryId) {
            if (failResult) throw new IllegalStateException("result injected");
            return values.get(Integer.valueOf(queryId)).longValue();
        }

        @Override public void deleteQuery(int queryId) {
            deleteCalls++;
            if (deleteCalls == failDeleteCall || deleteCalls == secondFailDeleteCall) {
                throw new IllegalStateException("delete injected");
            }
        }
    }
}
