package dev.rlcraft.ice.optimizer.compat.foamfix;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import org.junit.Test;

public final class FoamFixUploadBridgeTest {
    @Test
    public void acceptsCoreAndArbPboSynchronizationIndependently() {
        assertTrue(FoamFixUploadBridge.supportsPboForTest(true, true, false, true, false));
        assertTrue(FoamFixUploadBridge.supportsPboForTest(true, false, true, false, true));
        assertFalse(FoamFixUploadBridge.supportsPboForTest(true, true, false, false, false));
        assertFalse(FoamFixUploadBridge.supportsPboForTest(false, true, true, true, true));
    }

    @Test
    public void onlyLargeTrueBatchesAreEligibleForPbo() {
        assertEquals(256 * 1024, FoamFixUploadBridge.minimumBatchPboBytesForTest());
        assertFalse(FoamFixUploadBridge.tryUploadLevel(0, new int[] { 1 },
            1, 1, 0, 0, false, false, false));
    }

    @Test
    public void validContextDestroyReleasesBufferAndAllGpuAccounting() {
        CacheBudget budget = new CacheBudget(1L, 1L, 2L * 1024L * 1024L);
        TestPboDriver driver = new TestPboDriver();
        FoamFixUploadBridge.PboSlot slot =
            new FoamFixUploadBridge.PboSlot(budget, driver);

        assertTrue(slot.ensureCapacity(300 * 1024));
        assertTrue(budget.snapshot().getGpuUsed() > 0L);
        slot.destroy();

        assertTrue(slot.isReleased());
        assertEquals(1, driver.deleteCalls);
        assertEquals(0L, budget.snapshot().getGpuUsed());
    }

    @Test
    public void uncertainDeleteIsNeverRetriedAndAccountingWaitsForContextLoss() {
        CacheBudget budget = new CacheBudget(1L, 1L, 2L * 1024L * 1024L);
        TestPboDriver driver = new TestPboDriver();
        driver.deleteFailure = new IllegalStateException("uncertain delete");
        FoamFixUploadBridge.PboSlot slot =
            new FoamFixUploadBridge.PboSlot(budget, driver);
        assertTrue(slot.ensureCapacity(300 * 1024));

        try {
            slot.destroy();
            throw new AssertionError("expected delete failure");
        } catch (IllegalStateException expected) {
            assertEquals("uncertain delete", expected.getMessage());
        }
        assertFalse(slot.isReleased());
        assertEquals(1, driver.deleteCalls);
        assertTrue(budget.snapshot().getGpuUsed() > 0L);

        driver.deleteFailure = null;
        slot.destroy();
        assertEquals("uncertain native deletion must not be replayed", 1,
            driver.deleteCalls);
        assertTrue(budget.snapshot().getGpuUsed() > 0L);

        slot.abandon();
        assertEquals(0L, budget.snapshot().getGpuUsed());
    }

    @Test
    public void fatalDeleteEscapesAfterOwnershipBecomesNonRetryable() {
        CacheBudget budget = new CacheBudget(1L, 1L, 64L * 1024L);
        TestPboDriver driver = new TestPboDriver();
        ThreadDeath fatal = new ThreadDeath();
        driver.deleteFailure = fatal;
        FoamFixUploadBridge.PboSlot slot =
            new FoamFixUploadBridge.PboSlot(budget, driver);

        try {
            slot.destroy();
            throw new AssertionError("expected ThreadDeath");
        } catch (ThreadDeath actual) {
            assertTrue(actual == fatal);
        }
        assertEquals(1, driver.deleteCalls);
        slot.abandon();
        assertEquals(0L, budget.snapshot().getGpuUsed());
    }

    private static final class TestPboDriver
        implements FoamFixUploadBridge.PboDriver {
        private int deleteCalls;
        private Throwable deleteFailure;

        @Override public int create() { return 17; }
        @Override public void allocate(int bytes) { }
        @Override public void delete(int bufferId) {
            deleteCalls++;
            if (deleteFailure instanceof RuntimeException) {
                throw (RuntimeException) deleteFailure;
            }
            if (deleteFailure instanceof Error) throw (Error) deleteFailure;
        }
    }
}
