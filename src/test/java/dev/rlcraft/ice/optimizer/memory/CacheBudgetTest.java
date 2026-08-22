package dev.rlcraft.ice.optimizer.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

public class CacheBudgetTest {
    @Test
    public void reservationsAreHardBoundedAndIdempotentlyReleased() {
        CacheBudget budget = new CacheBudget(100, 200, 300);
        CacheBudget.Reservation reservation = budget.tryReserve(BudgetKind.HEAP, 80);
        assertNotNull(reservation);
        assertNull(budget.tryReserve(BudgetKind.HEAP, 21));
        assertEquals(80L, budget.snapshot().getHeapUsed());
        reservation.close();
        reservation.close();
        assertEquals(0L, budget.snapshot().getHeapUsed());
    }

    @Test
    public void invalidReservationsCannotSilentlyBypassAccounting() {
        CacheBudget budget = new CacheBudget(100, 200, 300);
        assertNotNull(budget.tryReserve(BudgetKind.HEAP, 0L));
        try {
            budget.tryReserve(null, 10L);
            fail("expected null kind rejection");
        } catch (IllegalArgumentException expected) {
            assertEquals("budget kind", expected.getMessage());
        }
        try {
            budget.tryReserve(BudgetKind.GPU, -1L);
            fail("expected negative byte rejection");
        } catch (IllegalArgumentException expected) {
            assertEquals("negative budget bytes", expected.getMessage());
        }
        assertEquals(0L, budget.snapshot().getHeapUsed());
        assertEquals(0L, budget.snapshot().getGpuUsed());
    }
}
