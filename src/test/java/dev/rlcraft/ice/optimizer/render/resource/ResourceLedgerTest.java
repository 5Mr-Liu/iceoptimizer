package dev.rlcraft.ice.optimizer.render.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.lwjgl.opengl.ARBSync;

public final class ResourceLedgerTest {
    @Test
    public void enforcesBudgetAndRetiresOnlyAfterFenceWithoutCrossContextDelete() {
        final AtomicInteger deletes = new AtomicInteger();
        CacheBudget budget = new CacheBudget(64, 64, 100);
        ResourceLedger ledger = new ResourceLedger(RenderThreadGuard.captureCurrent(), budget,
            new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind, int nativeId) {
                    deletes.incrementAndGet();
                }
            }, 8, 3);
        ClientEpochs epochs = new ClientEpochs();
        FrameStamp stamp = new FrameStamp(1L, 1L, epochs.snapshot());
        RenderHandle first = ledger.register(RenderResourceKind.BUFFER, 7, 80L, stamp);
        assertNotNull(first);
        assertNull(ledger.register(RenderResourceKind.BUFFER, 8, 21L, stamp));
        TestFence fence = new TestFence();
        assertTrue(ledger.retire(first, fence));
        assertEquals(0, ledger.collect(stamp.getGlContextGeneration(), 1));
        fence.signaled = true;
        assertEquals(1, ledger.collect(stamp.getGlContextGeneration(), 1));
        assertEquals(1, deletes.get());
        assertEquals(0L, budget.snapshot().getGpuUsed());

        RenderHandle second = ledger.register(RenderResourceKind.TEXTURE, 9, 50L, stamp);
        assertNotNull(second);
        assertEquals(1, ledger.abandonContext(stamp.getGlContextGeneration()));
        assertEquals("context loss must not delete a name in the new context", 1, deletes.get());
        assertFalse(ledger.isLive(second));
    }

    @Test
    public void abandoningRetiredResourceNeverDeletesItsLostContextFence() {
        CacheBudget budget = new CacheBudget(64, 64, 64);
        ResourceLedger ledger = new ResourceLedger(RenderThreadGuard.captureCurrent(), budget,
            new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind, int nativeId) {
                    throw new AssertionError("lost-context name deletion");
                }
            }, 4, 2);
        ClientEpochs epochs = new ClientEpochs();
        FrameStamp stamp = new FrameStamp(1L, 1L, epochs.snapshot());
        RenderHandle handle = ledger.register(RenderResourceKind.BUFFER, 1, 64L, stamp);
        TestFence fence = new TestFence();
        assertTrue(ledger.retire(handle, fence));
        assertEquals(1, ledger.abandonContext(stamp.getGlContextGeneration()));
        assertEquals("old-context Fence must only be abandoned", 0, fence.destroyCalls);
        assertEquals(0L, budget.snapshot().getGpuUsed());
    }

    @Test
    public void rejectsAbaHandleAndStrandsNeverCompletingFenceWithinHardBudget() {
        CacheBudget budget = new CacheBudget(64, 64, 64);
        ResourceLedger ledger = new ResourceLedger(RenderThreadGuard.captureCurrent(), budget,
            new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind, int nativeId) { }
            }, 4, 2);
        ClientEpochs epochs = new ClientEpochs();
        FrameStamp stamp = new FrameStamp(1L, 1L, epochs.snapshot());
        RenderHandle handle = ledger.register(RenderResourceKind.BUFFER, 1, 64L, stamp);
        RenderHandle forged = new RenderHandle(handle.getLogicalId(), handle.getSerial() + 1L,
            1, RenderResourceKind.BUFFER, 64L, stamp.getResourceGeneration(),
            stamp.getGlContextGeneration());
        TestFence rejectedFence = new TestFence();
        assertFalse(ledger.retire(forged, rejectedFence));
        assertEquals("ledger owns and destroys a Fence rejected by ABA validation",
            1, rejectedFence.destroyCalls);
        TestFence acceptedFence = new TestFence();
        assertTrue(ledger.retire(handle, acceptedFence));
        TestFence duplicateFence = new TestFence();
        assertFalse(ledger.retire(handle, duplicateFence));
        assertEquals("duplicate retirement must not leak its newly-created Fence",
            1, duplicateFence.destroyCalls);
        ledger.collect(stamp.getGlContextGeneration(), 1);
        ledger.collect(stamp.getGlContextGeneration(), 1);
        assertEquals(1L, ledger.snapshot().getTimedOut());
        assertNull(ledger.register(RenderResourceKind.BUFFER, 2, 1L, stamp));
        ledger.destroyAll(stamp.getGlContextGeneration());
        assertEquals(1, acceptedFence.destroyCalls);
        assertEquals(0L, budget.snapshot().getGpuUsed());
    }

    @Test
    public void strandedFenceDoesNotStarveLaterReadyRetirements() {
        final AtomicInteger deletes = new AtomicInteger();
        CacheBudget budget = new CacheBudget(64, 64, 128);
        ResourceLedger ledger = new ResourceLedger(RenderThreadGuard.captureCurrent(),
            budget, new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind, int nativeId) {
                    deletes.incrementAndGet();
                }
            }, 4, 1);
        ClientEpochs epochs = new ClientEpochs();
        FrameStamp stamp = new FrameStamp(1L, 1L, epochs.snapshot());
        RenderHandle stuck = ledger.register(RenderResourceKind.BUFFER, 1, 32L, stamp);
        RenderHandle ready = ledger.register(RenderResourceKind.BUFFER, 2, 32L, stamp);
        TestFence stuckFence = new TestFence();
        TestFence readyFence = new TestFence();
        readyFence.signaled = true;
        assertTrue(ledger.retire(stuck, stuckFence));
        assertTrue(ledger.retire(ready, readyFence));
        assertEquals(0, ledger.collect(stamp.getGlContextGeneration(), 1));
        assertEquals(1, ledger.collect(stamp.getGlContextGeneration(), 1));
        assertEquals(1, deletes.get());
        assertEquals(32L, budget.snapshot().getGpuUsed());
        ledger.destroyAll(stamp.getGlContextGeneration());
        assertEquals(2, deletes.get());
        assertEquals(0L, budget.snapshot().getGpuUsed());
    }

    @Test
    public void failedNativeDeleteIsNeverRetriedAndKeepsItsHardBudgetPoisoned() {
        final AtomicInteger attempts = new AtomicInteger();
        CacheBudget budget = new CacheBudget(64, 64, 64);
        ResourceLedger ledger = new ResourceLedger(RenderThreadGuard.captureCurrent(),
            budget, new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind, int nativeId) {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("injected delete failure");
                }
            }, 2, 1);
        ClientEpochs epochs = new ClientEpochs();
        FrameStamp stamp = new FrameStamp(1L, 1L, epochs.snapshot());
        RenderHandle handle = ledger.register(RenderResourceKind.BUFFER, 1, 64L, stamp);
        assertTrue(ledger.retire(handle, null));
        try {
            ledger.collect(stamp.getGlContextGeneration(), 1);
            fail("delete failure must be reported once");
        } catch (IllegalStateException expected) {
            assertEquals("injected delete failure", expected.getMessage());
        }
        assertEquals(1, attempts.get());
        assertEquals(0, ledger.collect(stamp.getGlContextGeneration(), 1));
        assertEquals(1, attempts.get());
        assertEquals(0, ledger.snapshot().getRetired());
        assertEquals("uncertain native objects must remain charged",
            64L, budget.snapshot().getGpuUsed());
        assertNull("delete failures must not permit repeated budget bypass",
            ledger.register(RenderResourceKind.BUFFER, 2, 1L, stamp));
    }

    @Test
    public void fenceDeleteFailureStillAttemptsResourceDeleteAndIsReported() {
        final AtomicInteger deletes = new AtomicInteger();
        CacheBudget budget = new CacheBudget(64, 64, 64);
        ResourceLedger ledger = new ResourceLedger(RenderThreadGuard.captureCurrent(),
            budget, new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind, int nativeId) {
                    deletes.incrementAndGet();
                }
            }, 2, 1);
        ClientEpochs epochs = new ClientEpochs();
        FrameStamp stamp = new FrameStamp(1L, 1L, epochs.snapshot());
        RenderHandle handle = ledger.register(RenderResourceKind.BUFFER, 1, 64L,
            stamp);
        TestFence fence = new TestFence();
        fence.signaled = true;
        fence.failDestroy = true;
        assertTrue(ledger.retire(handle, fence));
        try {
            ledger.collect(stamp.getGlContextGeneration(), 1);
            fail("Fence deletion failure must be reported");
        } catch (IllegalStateException expected) {
            assertEquals("injected Fence delete failure", expected.getMessage());
        }
        assertEquals("resource deletion is independently safe after signal",
            1, deletes.get());
        assertEquals(0L, budget.snapshot().getGpuUsed());
        assertEquals(0, ledger.collect(stamp.getGlContextGeneration(), 1));
    }

    @Test
    public void uncertainNativeFenceRemainsOwnedUntilItsContextIsLost() {
        final AtomicInteger deletes = new AtomicInteger();
        long syncCharge = ResourceLedger.syncObjectCharge();
        CacheBudget budget = new CacheBudget(1L, 1L, syncCharge + 16L);
        ResourceLedger ledger = new ResourceLedger(
            RenderThreadGuard.captureCurrent(), budget,
            new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind,
                                              int nativeId) {
                    deletes.incrementAndGet();
                }
            }, 4, 1);
        FrameStamp stamp = stamp();
        RenderHandle handle = ledger.register(RenderResourceKind.BUFFER, 81,
            16L, stamp);
        ThrowingSyncDriver driver = new ThrowingSyncDriver();
        LwjglRetirementFence fence =
            LwjglRetirementFence.tryAfterCurrentCommands(budget, driver);
        assertNotNull(fence);
        assertTrue(ledger.retire(handle, fence));

        try {
            ledger.collect(stamp.getGlContextGeneration(), 1);
            fail("native Fence delete failure must escape");
        } catch (IllegalStateException expected) {
            assertEquals("injected sync delete", expected.getMessage());
        }
        assertEquals(1, deletes.get());
        assertEquals(1, driver.deleteCalls);
        assertEquals(syncCharge, budget.snapshot().getGpuUsed());
        assertEquals(1, ledger.snapshot().getRetired());

        assertEquals(0, ledger.abandonContext(
            stamp.getGlContextGeneration()));
        assertEquals(0L, budget.snapshot().getGpuUsed());
        assertEquals(0, ledger.snapshot().getRetired());
        assertEquals("uncertain native sync deletion must not be retried", 1,
            driver.deleteCalls);
    }

    @Test
    public void collectAcrossContextLossAbandonsFenceAccountingWithoutDelete() {
        long syncCharge = ResourceLedger.syncObjectCharge();
        CacheBudget budget = new CacheBudget(1L, 1L, syncCharge + 16L);
        ResourceLedger ledger = new ResourceLedger(
            RenderThreadGuard.captureCurrent(), budget,
            new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind,
                                              int nativeId) {
                    throw new AssertionError("old-context resource delete");
                }
            }, 4, 1);
        FrameStamp stamp = stamp();
        RenderHandle handle = ledger.register(RenderResourceKind.BUFFER, 82,
            16L, stamp);
        ThrowingSyncDriver driver = new ThrowingSyncDriver();
        driver.throwDelete = false;
        LwjglRetirementFence fence =
            LwjglRetirementFence.tryAfterCurrentCommands(budget, driver);
        assertTrue(ledger.retire(handle, fence));

        assertEquals(1, ledger.collect(
            stamp.getGlContextGeneration() + 1L, 1));
        assertEquals(0, driver.deleteCalls);
        assertEquals(0L, budget.snapshot().getGpuUsed());
    }

    @Test
    public void postPublicationRegistrationFailureRollsBackMapAndBudget() {
        FaultHook hook = new FaultHook();
        CacheBudget budget = new CacheBudget(64, 64, 64);
        ResourceLedger ledger = ledger(budget, hook, new AtomicInteger());
        FrameStamp stamp = stamp();
        hook.failLivePut = true;
        try {
            ledger.register(RenderResourceKind.BUFFER, 17, 32L, stamp);
            fail("post-publication registration failure must escape");
        } catch (IllegalStateException expected) {
            assertEquals("injected live put failure", expected.getMessage());
        }
        assertEquals(0, ledger.snapshot().getLive());
        assertEquals(0L, budget.snapshot().getGpuUsed());
        assertNotNull("a verified rollback must leave the ledger reusable",
            ledger.register(RenderResourceKind.BUFFER, 18, 32L, stamp));
    }

    @Test
    public void postEnqueueRetirementFailureKeepsResourceLiveAndDestroysFence() {
        FaultHook hook = new FaultHook();
        CacheBudget budget = new CacheBudget(64, 64, 64);
        ResourceLedger ledger = ledger(budget, hook, new AtomicInteger());
        FrameStamp stamp = stamp();
        RenderHandle handle = ledger.register(RenderResourceKind.BUFFER, 21,
            32L, stamp);
        TestFence fence = new TestFence();
        hook.failRetiredEnqueue = true;
        try {
            ledger.retire(handle, fence);
            fail("post-enqueue retirement failure must escape");
        } catch (IllegalStateException expected) {
            assertEquals("injected retired enqueue failure",
                expected.getMessage());
        }
        assertTrue(ledger.isLive(handle));
        assertEquals(1, fence.destroyCalls);
        assertEquals(0, ledger.snapshot().getRetired());
    }

    @Test
    public void postRemovalRetirementFailureLeavesQueueAsSoleOwner() {
        FaultHook hook = new FaultHook();
        AtomicInteger deletes = new AtomicInteger();
        CacheBudget budget = new CacheBudget(64, 64, 64);
        ResourceLedger ledger = ledger(budget, hook, deletes);
        FrameStamp stamp = stamp();
        RenderHandle handle = ledger.register(RenderResourceKind.BUFFER, 25,
            32L, stamp);
        TestFence fence = new TestFence();
        fence.signaled = true;
        hook.failLiveRemove = true;
        try {
            ledger.retire(handle, fence);
            fail("post-removal retirement failure must escape");
        } catch (IllegalStateException expected) {
            assertEquals("injected live remove failure", expected.getMessage());
        }
        assertFalse(ledger.isLive(handle));
        assertEquals(1, ledger.snapshot().getRetired());
        assertEquals(1, ledger.collect(stamp.getGlContextGeneration(), 1));
        assertEquals(1, deletes.get());
        assertEquals(0L, budget.snapshot().getGpuUsed());
    }

    @Test
    public void adoptsPreAllocationReservationWithoutChargingItTwice() {
        CacheBudget budget = new CacheBudget(64, 64, 64);
        AtomicInteger deletes = new AtomicInteger();
        ResourceLedger ledger = ledger(budget, new FaultHook(), deletes);
        FrameStamp stamp = stamp();
        CacheBudget.Reservation reservation = budget.tryReserve(
            dev.rlcraft.ice.optimizer.memory.BudgetKind.GPU, 32L);
        assertNotNull(reservation);

        RenderHandle handle = ledger.registerReserved(
            RenderResourceKind.BUFFER, 31, 32L,
            stamp.getResourceGeneration(), stamp.getGlContextGeneration(),
            reservation);
        assertNotNull(handle);
        assertEquals("adoption must not duplicate the pre-allocation charge",
            32L, budget.snapshot().getGpuUsed());
        ledger.destroyAll(stamp.getGlContextGeneration());
        assertEquals(1, deletes.get());
        assertEquals(0L, budget.snapshot().getGpuUsed());
    }

    @Test
    public void rejectedPreAllocationReservationRemainsCallerOwned() {
        CacheBudget budget = new CacheBudget(64, 64, 64);
        ResourceLedger ledger = new ResourceLedger(
            RenderThreadGuard.captureCurrent(), budget,
            new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind,
                                              int nativeId) { }
            }, 1, 1);
        FrameStamp stamp = stamp();
        assertNotNull(ledger.register(RenderResourceKind.BUFFER, 41, 16L,
            stamp));
        CacheBudget.Reservation reservation = budget.tryReserve(
            dev.rlcraft.ice.optimizer.memory.BudgetKind.GPU, 32L);
        assertNotNull(reservation);
        assertNull(ledger.registerReserved(RenderResourceKind.BUFFER, 42, 32L,
            stamp.getResourceGeneration(), stamp.getGlContextGeneration(),
            reservation));
        assertEquals(48L, budget.snapshot().getGpuUsed());
        reservation.close();
        assertEquals(16L, budget.snapshot().getGpuUsed());
        ledger.destroyAll(stamp.getGlContextGeneration());
    }

    @Test
    public void rolledBackPreAllocationPublicationKeepsCallerCharge() {
        FaultHook hook = new FaultHook();
        CacheBudget budget = new CacheBudget(64, 64, 64);
        ResourceLedger ledger = ledger(budget, hook, new AtomicInteger());
        FrameStamp stamp = stamp();
        CacheBudget.Reservation reservation = budget.tryReserve(
            dev.rlcraft.ice.optimizer.memory.BudgetKind.GPU, 32L);
        hook.failLivePut = true;
        try {
            ledger.registerReserved(RenderResourceKind.BUFFER, 51, 32L,
                stamp.getResourceGeneration(), stamp.getGlContextGeneration(),
                reservation);
            fail("reserved publication fault must escape");
        } catch (IllegalStateException expected) {
            assertEquals("injected live put failure", expected.getMessage());
        }
        assertEquals(0, ledger.snapshot().getLive());
        assertEquals("native cleanup still needs the pre-allocation charge",
            32L, budget.snapshot().getGpuUsed());
        reservation.close();
        assertEquals(0L, budget.snapshot().getGpuUsed());
    }

    @Test
    public void opaqueNativeObjectsConsumeAPreAllocationToken() {
        CacheBudget budget = new CacheBudget(64, 64, 128L * 1024L);
        AtomicInteger deletes = new AtomicInteger();
        ResourceLedger ledger = ledger(budget, new FaultHook(), deletes);
        FrameStamp stamp = stamp();
        CacheBudget.Reservation reservation = ledger.reserveNativeObject(
            RenderResourceKind.PROGRAM);
        assertNotNull(reservation);
        assertEquals(64L * 1024L, budget.snapshot().getGpuUsed());
        RenderHandle handle = ledger.registerReservedObject(
            RenderResourceKind.PROGRAM, 61,
            stamp.getResourceGeneration(), stamp.getGlContextGeneration(),
            reservation);
        assertNotNull(handle);
        assertEquals(ResourceLedger.nativeObjectCharge(
            RenderResourceKind.PROGRAM), handle.getBytes());
        ledger.destroyAll(stamp.getGlContextGeneration());
        assertEquals(1, deletes.get());
        assertEquals(0L, budget.snapshot().getGpuUsed());
    }

    @Test
    public void rejectedOpaqueObjectTokenRemainsCallerOwned() {
        CacheBudget budget = new CacheBudget(64, 64, 128L * 1024L);
        ResourceLedger ledger = new ResourceLedger(
            RenderThreadGuard.captureCurrent(), budget,
            new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind,
                                              int nativeId) { }
            }, 1, 1);
        FrameStamp stamp = stamp();
        assertNotNull(ledger.register(RenderResourceKind.BUFFER, 71, 1L,
            stamp));
        CacheBudget.Reservation reservation = ledger.reserveNativeObject(
            RenderResourceKind.VERTEX_ARRAY);
        assertNotNull(reservation);
        assertNull(ledger.registerReservedObject(
            RenderResourceKind.VERTEX_ARRAY, 72,
            stamp.getResourceGeneration(), stamp.getGlContextGeneration(),
            reservation));
        assertEquals(1L + 4L * 1024L, budget.snapshot().getGpuUsed());
        reservation.close();
        assertEquals(1L, budget.snapshot().getGpuUsed());
        ledger.destroyAll(stamp.getGlContextGeneration());
    }

    @Test
    public void destructionOrdersReferenceContainersBeforePayloads() {
        final List<RenderResourceKind> order =
            new ArrayList<RenderResourceKind>();
        CacheBudget budget = new CacheBudget(1L, 1L, 128L);
        ResourceLedger ledger = new ResourceLedger(
            RenderThreadGuard.captureCurrent(), budget,
            new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind,
                                              int nativeId) {
                    order.add(kind);
                }
            }, 8, 3);
        FrameStamp stamp = stamp();
        assertNotNull(ledger.register(RenderResourceKind.TEXTURE, 1, 20L,
            stamp));
        assertNotNull(ledger.register(RenderResourceKind.FRAMEBUFFER, 2, 10L,
            stamp));
        assertNotNull(ledger.register(RenderResourceKind.BUFFER, 3, 20L,
            stamp));
        assertNotNull(ledger.register(RenderResourceKind.VERTEX_ARRAY, 4, 10L,
            stamp));
        ledger.destroyAll(stamp.getGlContextGeneration());
        assertEquals(Arrays.asList(RenderResourceKind.FRAMEBUFFER,
            RenderResourceKind.VERTEX_ARRAY, RenderResourceKind.TEXTURE,
            RenderResourceKind.BUFFER), order);
        assertEquals(0L, budget.snapshot().getGpuUsed());
    }

    @Test
    public void uncertainContainerDeletionPoisonsPossiblePayloadBudgets() {
        CacheBudget budget = new CacheBudget(1L, 1L, 128L);
        ResourceLedger ledger = new ResourceLedger(
            RenderThreadGuard.captureCurrent(), budget,
            new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind,
                                              int nativeId) {
                    if (kind == RenderResourceKind.FRAMEBUFFER) {
                        throw new IllegalStateException("injected FBO delete");
                    }
                }
            }, 8, 3);
        FrameStamp stamp = stamp();
        assertNotNull(ledger.register(RenderResourceKind.TEXTURE, 1, 20L,
            stamp));
        assertNotNull(ledger.register(RenderResourceKind.FRAMEBUFFER, 2, 10L,
            stamp));
        try {
            ledger.destroyAll(stamp.getGlContextGeneration());
            fail("expected FBO deletion failure");
        } catch (IllegalStateException expected) {
            assertEquals("injected FBO delete", expected.getMessage());
        }
        assertEquals("both uncertain container and possible attachment stay charged",
            30L, budget.snapshot().getGpuUsed());
        assertEquals(0, ledger.snapshot().getLive());
    }

    @Test
    public void pendingFramebufferFenceDefersAttachmentRetirement() {
        final List<RenderResourceKind> order =
            new ArrayList<RenderResourceKind>();
        CacheBudget budget = new CacheBudget(1L, 1L, 128L);
        ResourceLedger ledger = new ResourceLedger(
            RenderThreadGuard.captureCurrent(), budget,
            new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind,
                                              int nativeId) {
                    order.add(kind);
                }
            }, 8, 3);
        FrameStamp stamp = stamp();
        RenderHandle framebuffer = ledger.register(
            RenderResourceKind.FRAMEBUFFER, 1, 16L, stamp);
        RenderHandle texture = ledger.register(RenderResourceKind.TEXTURE, 2,
            16L, stamp);
        TestFence fence = new TestFence();
        assertTrue(ledger.retire(framebuffer, fence));
        assertTrue(ledger.retire(texture, null));
        assertEquals(0, ledger.collect(stamp.getGlContextGeneration(), 8));
        assertTrue(order.isEmpty());
        fence.signaled = true;
        assertEquals(1, ledger.collect(stamp.getGlContextGeneration(), 8));
        assertEquals(Arrays.asList(RenderResourceKind.FRAMEBUFFER), order);
        assertEquals(1, ledger.collect(stamp.getGlContextGeneration(), 8));
        assertEquals(Arrays.asList(RenderResourceKind.FRAMEBUFFER,
            RenderResourceKind.TEXTURE), order);
        assertEquals(0L, budget.snapshot().getGpuUsed());
    }

    private static ResourceLedger ledger(CacheBudget budget, FaultHook hook,
                                         final AtomicInteger deletes) {
        return new ResourceLedger(RenderThreadGuard.captureCurrent(), budget,
            new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind,
                                              int nativeId) {
                    deletes.incrementAndGet();
                }
            }, 8, 3, hook);
    }

    private static FrameStamp stamp() {
        return new FrameStamp(1L, 1L, new ClientEpochs().snapshot());
    }

    private static final class FaultHook
        implements ResourceLedger.PublicationHook {
        private boolean failLivePut;
        private boolean failRetiredEnqueue;
        private boolean failLiveRemove;

        @Override public void afterLivePut() {
            if (!failLivePut) return;
            failLivePut = false;
            throw new IllegalStateException("injected live put failure");
        }

        @Override public void afterRetiredEnqueue() {
            if (!failRetiredEnqueue) return;
            failRetiredEnqueue = false;
            throw new IllegalStateException(
                "injected retired enqueue failure");
        }

        @Override public void afterLiveRemove() {
            if (!failLiveRemove) return;
            failLiveRemove = false;
            throw new IllegalStateException("injected live remove failure");
        }
    }

    private static final class TestFence implements ResourceLedger.RetirementFence {
        private boolean signaled;
        private boolean failDestroy;
        private int destroyCalls;
        @Override public boolean isSignaled() { return signaled; }
        @Override public void destroy() {
            destroyCalls++;
            if (failDestroy) {
                throw new IllegalStateException("injected Fence delete failure");
            }
        }
    }

    private static final class ThrowingSyncDriver
        implements LwjglRetirementFence.SyncDriver {
        private final Object sync = new Object();
        private boolean throwDelete = true;
        private int deleteCalls;
        @Override public boolean supported() { return true; }
        @Override public Object create() { return sync; }
        @Override public int wait(Object value) {
            return ARBSync.GL_ALREADY_SIGNALED;
        }
        @Override public void delete(Object value) {
            deleteCalls++;
            if (throwDelete) {
                throw new IllegalStateException("injected sync delete");
            }
        }
    }
}
