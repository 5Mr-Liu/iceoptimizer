package dev.rlcraft.ice.optimizer.compat.xaero;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import java.lang.reflect.Field;
import org.junit.Test;

public class XaeroGpuTimerBridgeResourceTest {
    @Test
    public void queryPairChargesBothOpaqueObjectsAndCleansOnce() {
        CacheBudget budget = budget();
        FakeDriver driver = new FakeDriver();
        XaeroGpuTimerBridge.QueryPair pair =
            XaeroGpuTimerBridge.QueryPair.create(budget, driver);
        assertNotNull(pair);
        assertEquals(XaeroGpuTimerBridge.QUERY_PAIR_GPU_BYTES,
            budget.snapshot().getGpuUsed());
        pair.destroy();
        pair.destroy();
        assertEquals(2, driver.createCalls);
        assertEquals(2, driver.deleteCalls);
        assertEquals(0L, budget.snapshot().getGpuUsed());
    }

    @Test
    public void knownNoNameReleasesButThrowingCreatePoisons() {
        CacheBudget nullBudget = budget();
        FakeDriver nullDriver = new FakeDriver();
        nullDriver.zeroCreateCall = 1;
        boolean knownFailure = false;
        try {
            XaeroGpuTimerBridge.QueryPair.create(nullBudget, nullDriver);
        } catch (IllegalStateException expected) {
            knownFailure = true;
        }
        assertTrue(knownFailure);
        assertEquals(0L, nullBudget.snapshot().getGpuUsed());

        CacheBudget failedBudget = budget();
        FakeDriver failedDriver = new FakeDriver();
        failedDriver.failCreateCall = 1;
        boolean uncertainFailure = false;
        try {
            XaeroGpuTimerBridge.QueryPair.create(failedBudget, failedDriver);
        } catch (IllegalStateException expected) {
            uncertainFailure = true;
        }
        assertTrue(uncertainFailure);
        assertEquals(XaeroGpuTimerBridge.QUERY_PAIR_GPU_BYTES,
            failedBudget.snapshot().getGpuUsed());
    }

    @Test
    public void throwingDeleteIsNotRetriedAndAbandonReleasesAfterContextLoss() {
        CacheBudget budget = budget();
        FakeDriver driver = new FakeDriver();
        driver.failDeleteId = 2;
        XaeroGpuTimerBridge.QueryPair pair =
            XaeroGpuTimerBridge.QueryPair.create(budget, driver);
        assertNotNull(pair);
        boolean failed = false;
        try { pair.destroy(); }
        catch (IllegalStateException expected) { failed = true; }
        assertTrue(failed);
        assertEquals(2, driver.deleteCalls);
        assertEquals(XaeroGpuTimerBridge.QUERY_PAIR_GPU_BYTES,
            budget.snapshot().getGpuUsed());
        pair.destroy();
        assertEquals(2, driver.deleteCalls);
        pair.abandon();
        assertEquals(0L, budget.snapshot().getGpuUsed());
    }

    @Test
    public void insufficientPairBudgetFailsBeforeNativeAllocation() {
        CacheBudget budget = new CacheBudget(1L, 1L,
            XaeroGpuTimerBridge.QUERY_PAIR_GPU_BYTES - 1L);
        FakeDriver driver = new FakeDriver();
        assertNull(XaeroGpuTimerBridge.QueryPair.create(budget, driver));
        assertEquals(0, driver.createCalls);
    }

    @Test
    public void contextLossImmediatelyDropsStaticQueriesBenchmarkAndBudget()
        throws Exception {
        CacheBudget budget = budget();
        XaeroGpuTimerBridge.QueryPair pair =
            XaeroGpuTimerBridge.QueryPair.create(budget, new FakeDriver());
        assertNotNull(pair);
        Field queriesField = XaeroGpuTimerBridge.class
            .getDeclaredField("QUERIES");
        queriesField.setAccessible(true);
        XaeroGpuTimerBridge.QueryPair[] queries =
            (XaeroGpuTimerBridge.QueryPair[]) queriesField.get(null);
        Field generation = XaeroGpuTimerBridge.class
            .getDeclaredField("knownContextGeneration");
        generation.setAccessible(true);
        Field benchmark = XaeroGpuTimerBridge.class
            .getDeclaredField("knownBenchmark");
        benchmark.setAccessible(true);
        queries[0] = pair;
        generation.setLong(null, 77L);
        benchmark.set(null, new Object());
        try {
            XaeroGpuTimerBridge.contextLost(77L);

            assertNull(queries[0]);
            assertNull(benchmark.get(null));
            assertEquals(0L, budget.snapshot().getGpuUsed());
        } finally {
            generation.setLong(null, Long.MIN_VALUE);
            queries[0] = null;
            pair.abandon();
        }
    }

    @Test
    public void laterFatalDeleteFailureOutranksEarlierOrdinaryCleanupFailure() {
        CacheBudget budget = budget();
        final OutOfMemoryError fatal = new OutOfMemoryError(
            "injected query cleanup fatal");
        XaeroGpuTimerBridge.QueryPair pair =
            XaeroGpuTimerBridge.QueryPair.create(budget,
                new XaeroGpuTimerBridge.QueryDriver() {
                    private int next = 1;
                    @Override public int create() { return next++; }
                    @Override public void delete(int queryId) {
                        if (queryId == 2) {
                            throw new IllegalStateException(
                                "first ordinary delete failure");
                        }
                        throw fatal;
                    }
                    @Override public void issue(int queryId) { }
                    @Override public boolean available(int queryId) {
                        return false;
                    }
                    @Override public long result(int queryId) { return 0L; }
                });
        assertNotNull(pair);

        try {
            pair.destroy();
            fail("fatal cleanup failure must escape");
        } catch (OutOfMemoryError expected) {
            assertSame(fatal, expected);
        }

        assertEquals(XaeroGpuTimerBridge.QUERY_PAIR_GPU_BYTES,
            budget.snapshot().getGpuUsed());
        pair.abandon();
        assertEquals(0L, budget.snapshot().getGpuUsed());
    }

    private static CacheBudget budget() {
        return new CacheBudget(1L, 1L,
            XaeroGpuTimerBridge.QUERY_PAIR_GPU_BYTES);
    }

    private static final class FakeDriver
        implements XaeroGpuTimerBridge.QueryDriver {
        private int nextId = 1;
        private int createCalls;
        private int deleteCalls;
        private int zeroCreateCall;
        private int failCreateCall;
        private int failDeleteId;

        @Override public int create() {
            createCalls++;
            if (createCalls == failCreateCall) {
                throw new IllegalStateException("create");
            }
            if (createCalls == zeroCreateCall) return 0;
            return nextId++;
        }

        @Override public void delete(int queryId) {
            deleteCalls++;
            if (queryId == failDeleteId) {
                throw new IllegalStateException("delete");
            }
        }

        @Override public void issue(int queryId) { }
        @Override public boolean available(int queryId) { return false; }
        @Override public long result(int queryId) { return 0L; }
    }
}
