package dev.rlcraft.ice.optimizer.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

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
}
