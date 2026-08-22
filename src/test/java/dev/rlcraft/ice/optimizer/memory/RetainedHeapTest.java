package dev.rlcraft.ice.optimizer.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class RetainedHeapTest {
    @Test
    public void arrayChargesAreAlignedAndReferencesRemainConservative() {
        assertEquals(16L, RetainedHeap.byteArray(0));
        assertEquals(24L, RetainedHeap.byteArray(1));
        assertEquals(24L, RetainedHeap.intArray(1));
        assertEquals(32L, RetainedHeap.referenceArray(2));
    }

    @Test
    public void reservationFailureIsFailClosed() {
        CacheBudget budget = new CacheBudget(31L, 1L, 1L);
        assertNotNull(RetainedHeap.reserve(budget, 24L, "test"));
        try {
            RetainedHeap.reserve(budget, 8L, "test");
            fail("expected budget rejection");
        } catch (IllegalStateException expected) {
            assertEquals("test Heap budget exhausted", expected.getMessage());
        }
    }
}
