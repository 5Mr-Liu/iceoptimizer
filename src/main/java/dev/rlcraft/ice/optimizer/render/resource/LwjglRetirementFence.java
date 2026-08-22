package dev.rlcraft.ice.optimizer.render.resource;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import org.lwjgl.opengl.ARBSync;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.GLSync;

/** Zero-wait retirement Fence submitted after all commands using a resource. */
public final class LwjglRetirementFence implements ResourceLedger.RetirementFence {
    interface SyncDriver {
        boolean supported();
        Object create();
        int wait(Object sync);
        void delete(Object sync);
    }

    private final SyncDriver driver;
    private CacheBudget.Reservation reservation;
    private Object sync;

    private LwjglRetirementFence(SyncDriver driver, Object sync,
                                 CacheBudget.Reservation reservation) {
        this.driver = driver;
        this.sync = sync;
        this.reservation = reservation;
    }

    /**
     * Returns a real Fence, or a permanently-busy sentinel when the driver
     * cannot create one.  The latter intentionally strands the resource under
     * the ledger's hard budget instead of risking use-after-free.
     */
    public static ResourceLedger.RetirementFence afterCurrentCommands() {
        return new FailedFence(new IllegalStateException(
            "resource retirement Fence GPU budget unavailable"));
    }

    public static ResourceLedger.RetirementFence afterCurrentCommands(
        ResourceLedger ledger) {
        LwjglRetirementFence fence = tryAfterCurrentCommands(ledger);
        return fence == null ? new FailedFence(new IllegalStateException(
            "resource retirement Fence creation failed")) : fence;
    }

    /** Returns null on a known failure; uncertain native outcomes stay charged. */
    public static LwjglRetirementFence tryAfterCurrentCommands(
        ResourceLedger ledger) {
        if (ledger == null) return null;
        CacheBudget.Reservation reservation = ledger.reserveSyncObject();
        return tryAfterCurrentCommands(reservation, productionDriver());
    }

    public static LwjglRetirementFence tryAfterCurrentCommands(
        CacheBudget budget) {
        if (budget == null) return null;
        CacheBudget.Reservation reservation = budget.tryReserve(BudgetKind.GPU,
            ResourceLedger.syncObjectCharge());
        return tryAfterCurrentCommands(reservation, productionDriver());
    }

    private static LwjglRetirementFence tryAfterCurrentCommands(
        CacheBudget.Reservation reservation, SyncDriver driver) {
        if (reservation == null) return null;
        boolean allocationAttempted = false;
        boolean allocationReturned = false;
        Object value = null;
        try {
            if (driver == null || !driver.supported()) {
                reservation.close();
                return null;
            }
            allocationAttempted = true;
            value = driver.create();
            allocationReturned = true;
            if (value == null) {
                reservation.close();
                return null;
            }
            return new LwjglRetirementFence(driver, value, reservation);
        } catch (Throwable error) {
            Throwable failure = error;
            if (!allocationAttempted || allocationReturned && value == null) {
                try { reservation.close(); }
                catch (Throwable cleanup) {
                    Throwable cleanupFatal = FatalErrors.findFatal(cleanup);
                    if (cleanupFatal != null
                        && FatalErrors.findFatal(failure) == null) {
                        cleanupFatal.addSuppressed(failure);
                        failure = cleanupFatal;
                    } else if (cleanup != failure) {
                        failure.addSuppressed(cleanup);
                    }
                }
            }
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    static LwjglRetirementFence tryAfterCurrentCommands(
        CacheBudget budget, SyncDriver driver) {
        if (budget == null) return null;
        CacheBudget.Reservation reservation = budget.tryReserve(BudgetKind.GPU,
            ResourceLedger.syncObjectCharge());
        return tryAfterCurrentCommands(reservation, driver);
    }

    private static SyncDriver productionDriver() {
        try {
            ContextCapabilities capabilities = GLContext.getCapabilities();
            return capabilities == null ? null
                : new LwjglSyncDriver(capabilities);
        } catch (Throwable unavailable) {
            FatalErrors.rethrowIfFatal(unavailable);
            return null;
        }
    }

    @Override public boolean isSignaled() {
        Object value = sync;
        if (value == null) return true;
        int state = driver.wait(value);
        if (state == ARBSync.GL_WAIT_FAILED) {
            throw new IllegalStateException("resource retirement Fence failed");
        }
        return state == ARBSync.GL_ALREADY_SIGNALED
            || state == ARBSync.GL_CONDITION_SATISFIED;
    }

    @Override public void destroy() {
        Object value = sync;
        sync = null;
        if (value == null) return;
        driver.delete(value);
        CacheBudget.Reservation owned = reservation;
        reservation = null;
        if (owned != null) owned.close();
    }

    /** Releases accounting after the owning GL context is known to be gone. */
    public void abandon() {
        sync = null;
        CacheBudget.Reservation owned = reservation;
        reservation = null;
        if (owned != null) owned.close();
    }

    public static void abandon(ResourceLedger.RetirementFence fence) {
        if (fence instanceof LwjglRetirementFence) {
            ((LwjglRetirementFence) fence).abandon();
        }
    }

    private static final class LwjglSyncDriver implements SyncDriver {
        private final ContextCapabilities capabilities;

        private LwjglSyncDriver(ContextCapabilities capabilities) {
            this.capabilities = capabilities;
        }

        @Override public boolean supported() {
            return capabilities.OpenGL32 || capabilities.GL_ARB_sync;
        }

        @Override public Object create() {
            return capabilities.OpenGL32
                ? GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
                : ARBSync.glFenceSync(ARBSync.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        }

        @Override public int wait(Object sync) {
            GLSync value = (GLSync) sync;
            return capabilities.OpenGL32
                ? GL32.glClientWaitSync(value, 0, 0L)
                : ARBSync.glClientWaitSync(value, 0, 0L);
        }

        @Override public void delete(Object sync) {
            GLSync value = (GLSync) sync;
            if (capabilities.OpenGL32) GL32.glDeleteSync(value);
            else ARBSync.glDeleteSync(value);
        }
    }

    /** Safely strands the resource while making the infrastructure failure visible. */
    private static final class FailedFence
        implements ResourceLedger.RetirementFence {
        private final Throwable failure;
        private FailedFence(Throwable failure) {
            this.failure = failure == null
                ? new IllegalStateException("resource retirement Fence failed")
                : failure;
        }
        @Override public boolean isSignaled() { rethrow(failure); return false; }
        @Override public void destroy() { rethrow(failure); }
    }

    private static void rethrow(Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("resource retirement Fence failed", failure);
    }
}
