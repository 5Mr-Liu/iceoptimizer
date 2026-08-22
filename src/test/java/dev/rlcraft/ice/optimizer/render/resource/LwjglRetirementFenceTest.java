package dev.rlcraft.ice.optimizer.render.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import org.junit.Test;
import org.lwjgl.opengl.ARBSync;

public class LwjglRetirementFenceTest {
    private static final long CHARGE = ResourceLedger.syncObjectCharge();

    @Test
    public void successfulFenceOwnsAndReleasesOneToken() {
        CacheBudget budget = budget();
        FakeDriver driver = new FakeDriver();
        driver.waitResult = ARBSync.GL_ALREADY_SIGNALED;
        LwjglRetirementFence fence =
            LwjglRetirementFence.tryAfterCurrentCommands(budget, driver);
        assertNotNull(fence);
        assertEquals(CHARGE, budget.snapshot().getGpuUsed());
        assertTrue(fence.isSignaled());
        fence.destroy();
        fence.destroy();
        assertEquals(1, driver.createCalls);
        assertEquals(1, driver.waitCalls);
        assertEquals(1, driver.deleteCalls);
        assertEquals(0L, budget.snapshot().getGpuUsed());
    }

    @Test
    public void knownNullCreationReleasesButThrowingCreationPoisons() {
        CacheBudget nullBudget = budget();
        FakeDriver nullDriver = new FakeDriver();
        nullDriver.nullCreate = true;
        assertNull(LwjglRetirementFence.tryAfterCurrentCommands(nullBudget,
            nullDriver));
        assertEquals(0L, nullBudget.snapshot().getGpuUsed());

        CacheBudget failedBudget = budget();
        FakeDriver failedDriver = new FakeDriver();
        failedDriver.failCreate = true;
        assertNull(LwjglRetirementFence.tryAfterCurrentCommands(failedBudget,
            failedDriver));
        assertEquals(CHARGE, failedBudget.snapshot().getGpuUsed());
        assertEquals(1, failedDriver.createCalls);
    }

    @Test
    public void throwingDeleteIsNeverRetriedAndStaysPoisonedUntilContextLoss() {
        CacheBudget budget = budget();
        FakeDriver driver = new FakeDriver();
        driver.failDelete = true;
        LwjglRetirementFence fence =
            LwjglRetirementFence.tryAfterCurrentCommands(budget, driver);
        assertNotNull(fence);
        boolean failed = false;
        try { fence.destroy(); }
        catch (IllegalStateException expected) { failed = true; }
        assertTrue(failed);
        fence.destroy();
        assertEquals(1, driver.deleteCalls);
        assertEquals(CHARGE, budget.snapshot().getGpuUsed());
        fence.abandon();
        assertEquals(0L, budget.snapshot().getGpuUsed());
    }

    @Test
    public void waitFailureRemainsOwnedUntilExplicitAbandon() {
        CacheBudget budget = budget();
        FakeDriver driver = new FakeDriver();
        driver.failWait = true;
        LwjglRetirementFence fence =
            LwjglRetirementFence.tryAfterCurrentCommands(budget, driver);
        assertNotNull(fence);
        boolean failed = false;
        try { fence.isSignaled(); }
        catch (IllegalStateException expected) { failed = true; }
        assertTrue(failed);
        assertEquals(CHARGE, budget.snapshot().getGpuUsed());
        fence.abandon();
        assertEquals(0, driver.deleteCalls);
        assertEquals(0L, budget.snapshot().getGpuUsed());
    }

    private static CacheBudget budget() {
        return new CacheBudget(1L, 1L, CHARGE);
    }

    private static final class FakeDriver
        implements LwjglRetirementFence.SyncDriver {
        private final Object handle = new Object();
        private boolean nullCreate;
        private boolean failCreate;
        private boolean failWait;
        private boolean failDelete;
        private int waitResult = ARBSync.GL_TIMEOUT_EXPIRED;
        private int createCalls;
        private int waitCalls;
        private int deleteCalls;

        @Override public boolean supported() { return true; }

        @Override public Object create() {
            createCalls++;
            if (failCreate) throw new IllegalStateException("create");
            return nullCreate ? null : handle;
        }

        @Override public int wait(Object sync) {
            waitCalls++;
            if (failWait) throw new IllegalStateException("wait");
            return waitResult;
        }

        @Override public void delete(Object sync) {
            deleteCalls++;
            if (failDelete) throw new IllegalStateException("delete");
        }
    }
}
