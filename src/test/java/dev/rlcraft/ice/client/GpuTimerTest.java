package dev.rlcraft.ice.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import dev.rlcraft.ice.config.IceConfig;
import org.junit.Test;

public final class GpuTimerTest {
    @Test
    public void closeDeletesEveryPublishedQueryExactlyOnce() {
        boolean previous = IceConfig.client.gpuTimerQueries;
        IceConfig.client.gpuTimerQueries = true;
        try {
            TestDriver driver = new TestDriver();
            GpuTimer timer = new GpuTimer(driver);
            timer.begin();
            timer.end();

            assertEquals(3, driver.createCalls);
            timer.close();
            timer.close();

            assertEquals(3, driver.deleteCalls);
        } finally {
            IceConfig.client.gpuTimerQueries = previous;
        }
    }

    @Test
    public void contextChangeAbandonsOldNamesAndAllocatesFreshQueries() {
        boolean previous = IceConfig.client.gpuTimerQueries;
        IceConfig.client.gpuTimerQueries = true;
        try {
            TestDriver driver = new TestDriver();
            GpuTimer timer = new GpuTimer(driver);
            timer.begin();
            timer.end();

            driver.context = new Object();
            timer.begin();
            timer.end();

            assertEquals(6, driver.createCalls);
            assertEquals("old-Context Query IDs must not be deleted in the new Context",
                0, driver.deleteCalls);
            timer.close();
            assertEquals(3, driver.deleteCalls);
        } finally {
            IceConfig.client.gpuTimerQueries = previous;
        }
    }

    @Test
    public void fatalDeleteClearsOwnershipBeforeEscapeAndIsNotRetried() {
        boolean previous = IceConfig.client.gpuTimerQueries;
        IceConfig.client.gpuTimerQueries = true;
        try {
            TestDriver driver = new TestDriver();
            GpuTimer timer = new GpuTimer(driver);
            timer.begin();
            timer.end();
            ThreadDeath fatal = new ThreadDeath();
            driver.deleteFailure = fatal;

            try {
                timer.close();
                throw new AssertionError("expected ThreadDeath");
            } catch (ThreadDeath actual) {
                assertSame(fatal, actual);
            }
            assertEquals(1, driver.deleteCalls);

            driver.deleteFailure = null;
            timer.close();
            assertEquals("uncertain Query deletion must not be replayed", 1,
                driver.deleteCalls);
        } finally {
            IceConfig.client.gpuTimerQueries = previous;
        }
    }

    @Test
    public void wrappedFatalInitializationPoisonIsClearedOnlyByContextLoss() {
        boolean previous = IceConfig.client.gpuTimerQueries;
        IceConfig.client.gpuTimerQueries = true;
        try {
            TestDriver driver = new TestDriver();
            GpuTimer timer = new GpuTimer(driver);
            OutOfMemoryError fatal = new OutOfMemoryError("query allocation");
            driver.createFailure = new Exception(fatal);

            try {
                timer.begin();
                throw new AssertionError("expected OutOfMemoryError");
            } catch (OutOfMemoryError actual) {
                assertSame(fatal, actual);
            }
            assertEquals(1, driver.createCalls);

            driver.createFailure = null;
            timer.begin();
            assertEquals("same-Context fatal path must stay disabled", 1,
                driver.createCalls);

            driver.context = new Object();
            timer.begin();
            timer.end();
            assertEquals(4, driver.createCalls);
            timer.close();
        } finally {
            IceConfig.client.gpuTimerQueries = previous;
        }
    }

    private static final class TestDriver implements GpuTimer.QueryDriver {
        private Object context = new Object();
        private int nextId = 1;
        private int createCalls;
        private int deleteCalls;
        private Throwable createFailure;
        private Throwable deleteFailure;

        @Override public Object currentContext() { return context; }

        @Override public int create() throws Exception {
            createCalls++;
            if (createFailure instanceof Exception) {
                throw (Exception) createFailure;
            }
            if (createFailure instanceof Error) throw (Error) createFailure;
            return nextId++;
        }

        @Override public void begin(int queryId) { }
        @Override public void end() { }
        @Override public boolean isAvailable(int queryId) { return false; }
        @Override public long resultNanos(int queryId) { return 0L; }

        @Override public void delete(int queryId) {
            deleteCalls++;
            if (deleteFailure instanceof RuntimeException) {
                throw (RuntimeException) deleteFailure;
            }
            if (deleteFailure instanceof Error) throw (Error) deleteFailure;
        }
    }
}
