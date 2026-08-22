package dev.rlcraft.ice.optimizer.render.visibility;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;

public final class VisibleChunkListTransactionTest {
    @Test
    public void rollbackRestoresEveryIdentityAndItsExactOrder() {
        VisibleChunkListTransaction transaction =
            new VisibleChunkListTransaction(null);
        Object owner = new Object();
        ArrayList<Object> visible = new ArrayList<Object>(Arrays.asList(
            new Object(), new Object(), new Object(), new Object()));
        ArrayList<Object> original = new ArrayList<Object>(visible);

        assertTrue(transaction.begin(owner, visible));
        visible.set(1, visible.get(2));
        visible.remove(3);
        visible.remove(2);
        transaction.rollback(owner);

        assertFalse(transaction.isActive());
        assertEquals(original.size(), visible.size());
        for (int index = 0; index < original.size(); index++) {
            assertTrue("identity " + index,
                original.get(index) == visible.get(index));
        }
        transaction.close();
    }

    @Test
    public void commitKeepsTheCompactedOutcomeAndReleasesSnapshot() {
        VisibleChunkListTransaction transaction =
            new VisibleChunkListTransaction(null);
        Object owner = new Object();
        ArrayList<Object> visible = new ArrayList<Object>(Arrays.asList(
            new Object(), new Object(), new Object()));
        Object first = visible.get(0);
        Object last = visible.get(2);

        assertTrue(transaction.begin(owner, visible));
        visible.set(1, last);
        visible.remove(2);
        transaction.commit(owner);

        assertFalse(transaction.isActive());
        assertEquals(2, visible.size());
        assertTrue(first == visible.get(0));
        assertTrue(last == visible.get(1));
        transaction.close();
    }

    @Test
    public void ownerMismatchCannotDiscardRollbackEvidence() {
        VisibleChunkListTransaction transaction =
            new VisibleChunkListTransaction(null);
        Object owner = new Object();
        ArrayList<Object> visible = new ArrayList<Object>(Arrays.asList(
            new Object(), new Object()));
        ArrayList<Object> original = new ArrayList<Object>(visible);

        assertTrue(transaction.begin(owner, visible));
        visible.remove(1);
        try {
            transaction.rollback(new Object());
            fail("owner mismatch accepted");
        } catch (IllegalStateException expected) {
            assertTrue(transaction.isActive());
        }
        transaction.rollback(owner);
        assertEquals(original, visible);
        transaction.close();
    }

    @Test
    public void budgetPressureFailsOpenBeforeTheListChanges() {
        CacheBudget budget = new CacheBudget(1L, 1L, 1L);
        VisibleChunkListTransaction transaction =
            new VisibleChunkListTransaction(budget);
        ArrayList<Object> visible = new ArrayList<Object>(Arrays.asList(
            new Object(), new Object()));
        ArrayList<Object> original = new ArrayList<Object>(visible);

        assertFalse(transaction.begin(new Object(), visible));
        assertFalse(transaction.isActive());
        assertEquals(original, visible);
        assertTrue(budget.snapshot().getRejectedReservations() > 0L);
        transaction.close();
    }
}
