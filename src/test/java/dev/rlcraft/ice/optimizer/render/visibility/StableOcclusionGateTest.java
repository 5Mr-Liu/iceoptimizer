package dev.rlcraft.ice.optimizer.render.visibility;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import org.junit.Test;

public final class StableOcclusionGateTest {
    @Test
    public void requiresIndependentConsecutivePublications() {
        StableOcclusionGate gate = new StableOcclusionGate(8, 2, null);
        Object chunk = new Object();
        try {
            assertFalse(gate.confirm(chunk, 10L));
            assertFalse("three layers in one publication are still one witness",
                gate.confirm(chunk, 10L));
            assertTrue(gate.confirm(chunk, 11L));
        } finally {
            gate.close();
        }
    }

    @Test
    public void gapsVisibilityAndInvalidationRestartCertification() {
        StableOcclusionGate gate = new StableOcclusionGate(8, 2, null);
        Object chunk = new Object();
        try {
            assertFalse(gate.confirm(chunk, 1L));
            assertFalse(gate.confirm(chunk, 3L));
            assertTrue(gate.confirm(chunk, 4L));
            gate.visible(chunk, 4L);
            assertFalse(gate.confirm(chunk, 5L));
            assertTrue(gate.confirm(chunk, 6L));
            gate.invalidate();
            assertFalse(gate.confirm(chunk, 7L));
        } finally {
            gate.close();
        }
    }

    @Test
    public void equalObjectsNeverShareAnIdentityWitness() {
        StableOcclusionGate gate = new StableOcclusionGate(8, 2, null);
        String first = new String("chunk");
        String second = new String("chunk");
        try {
            assertFalse(gate.confirm(first, 1L));
            assertFalse(gate.confirm(second, 2L));
            assertTrue(gate.confirm(first, 2L));
        } finally {
            gate.close();
        }
    }

    @Test
    public void boundedCapacityResetCanOnlyDelayCulling() {
        StableOcclusionGate gate = new StableOcclusionGate(4, 2, null);
        try {
            Object first = new Object();
            assertFalse(gate.confirm(first, 1L));
            assertFalse(gate.confirm(new Object(), 1L));
            assertFalse(gate.confirm(new Object(), 1L));
            assertFalse(gate.confirm(new Object(), 2L));
            assertTrue(gate.getCapacityResets() > 0L);
            assertFalse("capacity pressure must forget, never invent, evidence",
                gate.confirm(first, 2L));
        } finally {
            gate.close();
        }
    }

    @Test
    public void heapPressureShrinksOrDisablesTheGateWithoutDisablingHzb() {
        CacheBudget small = new CacheBudget(1024L, 1L, 1L);
        StableOcclusionGate reduced = new StableOcclusionGate(small);
        try {
            assertTrue(reduced.getCapacity() > 0);
            assertTrue(reduced.getCapacity() < (1 << 15));
            assertTrue(reduced.getBudgetCapacityReductions() > 0);
        } finally {
            reduced.close();
        }
        assertEquals(0L, small.snapshot().getHeapUsed());

        CacheBudget exhausted = new CacheBudget(1L, 1L, 1L);
        StableOcclusionGate disabled = new StableOcclusionGate(exhausted);
        try {
            assertEquals(0, disabled.getCapacity());
            assertFalse("no witness storage must fail open",
                disabled.confirm(new Object(), 1L));
        } finally {
            disabled.close();
        }
    }
}
