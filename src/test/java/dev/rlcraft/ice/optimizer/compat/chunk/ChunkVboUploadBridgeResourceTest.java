package dev.rlcraft.ice.optimizer.compat.chunk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import org.junit.Test;

public final class ChunkVboUploadBridgeResourceTest {
    @Test
    public void successfulDeleteReleasesObjectAndStoreAccounting() {
        CacheBudget budget = new CacheBudget(1L, 1L, 2L * 1024L * 1024L);
        TestDriver driver = new TestDriver();
        ChunkVboUploadBridge.UploadSlot slot =
            new ChunkVboUploadBridge.UploadSlot(budget, driver);

        assertTrue(slot.ensureCapacity(300 * 1024));
        assertTrue(budget.snapshot().getGpuUsed() > 0L);
        slot.destroy();

        assertTrue(slot.isReleased());
        assertEquals(1, driver.deleteCalls);
        assertEquals(0L, budget.snapshot().getGpuUsed());
    }

    @Test
    public void uncertainDeleteRemainsChargedAndCannotBeRetried() {
        CacheBudget budget = new CacheBudget(1L, 1L, 2L * 1024L * 1024L);
        TestDriver driver = new TestDriver();
        driver.deleteFailure = new IllegalStateException("uncertain VBO delete");
        ChunkVboUploadBridge.UploadSlot slot =
            new ChunkVboUploadBridge.UploadSlot(budget, driver);
        assertTrue(slot.ensureCapacity(300 * 1024));

        try {
            slot.destroy();
            throw new AssertionError("expected delete failure");
        } catch (IllegalStateException expected) {
            assertEquals("uncertain VBO delete", expected.getMessage());
        }
        assertFalse(slot.isReleased());
        assertEquals(1, driver.deleteCalls);
        assertTrue(budget.snapshot().getGpuUsed() > 0L);

        driver.deleteFailure = null;
        slot.destroy();
        assertEquals("uncertain VBO deletion must not be replayed", 1,
            driver.deleteCalls);
        assertTrue(budget.snapshot().getGpuUsed() > 0L);

        slot.abandon();
        assertEquals(0L, budget.snapshot().getGpuUsed());
    }

    @Test
    public void fatalDeletePoisonsOwnershipBeforeEscaping() {
        CacheBudget budget = new CacheBudget(1L, 1L, 64L * 1024L);
        TestDriver driver = new TestDriver();
        ThreadDeath fatal = new ThreadDeath();
        driver.deleteFailure = fatal;
        ChunkVboUploadBridge.UploadSlot slot =
            new ChunkVboUploadBridge.UploadSlot(budget, driver);

        try {
            slot.destroy();
            throw new AssertionError("expected ThreadDeath");
        } catch (ThreadDeath actual) {
            assertSame(fatal, actual);
        }
        assertEquals(1, driver.deleteCalls);
        slot.abandon();
        assertEquals(0L, budget.snapshot().getGpuUsed());
    }

    private static final class TestDriver
        implements ChunkVboUploadBridge.BufferDriver {
        private int deleteCalls;
        private Throwable deleteFailure;

        @Override public int create() { return 23; }
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
