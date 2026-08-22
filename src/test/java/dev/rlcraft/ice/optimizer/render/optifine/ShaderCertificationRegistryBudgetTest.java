package dev.rlcraft.ice.optimizer.render.optifine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import org.junit.Test;

public final class ShaderCertificationRegistryBudgetTest {
    @Test
    public void entriesAndLogsAreBoundedByAndReleasedToSharedHeap() {
        CacheBudget budget = new CacheBudget(5000L, 1L, 1L);
        ShaderCertificationRegistry registry =
            new ShaderCertificationRegistry(16, budget);
        ShaderPermutationKey first = new ShaderPermutationKey("pack", "one",
            "base", 1L, 1L, 1L);
        registry.recordCompile(first, true, "compiler log");
        registry.recordStateValidation(first, true);
        registry.recordImageValidation(first, true);
        assertTrue(registry.isCertified(first));
        assertTrue(budget.snapshot().getHeapUsed() >= 4096L);

        ShaderPermutationKey second = new ShaderPermutationKey("pack", "two",
            "base", 1L, 1L, 2L);
        registry.recordCompile(second, true, "compiler log");
        assertFalse(registry.compilePassed(second));
        assertTrue(registry.isSaturated());

        registry.close();
        assertEquals(0L, budget.snapshot().getHeapUsed());
    }

    @Test
    public void wrappedFatalEntryPublicationRollsBackBeforeEscaping() {
        CacheBudget budget = new CacheBudget(8192L, 1L, 1L);
        final OutOfMemoryError fatal = new OutOfMemoryError(
            "injected wrapped shader certification failure");
        ShaderCertificationRegistry registry =
            new ShaderCertificationRegistry(16, budget,
                new ShaderCertificationRegistry.PublicationHook() {
                    private boolean fail = true;

                    @Override public void afterEntryPut() {
                        if (!fail) return;
                        fail = false;
                        throw new IllegalStateException(
                            "wrapped shader certification failure", fatal);
                    }
                });
        ShaderPermutationKey key = new ShaderPermutationKey("pack", "fault",
            "base", 1L, 1L, 1L);

        try {
            registry.recordStateValidation(key, true);
            fail("wrapped fatal shader certification failure must escape");
        } catch (OutOfMemoryError expected) {
            assertSame(fatal, expected);
        }

        assertEquals(0, registry.entryCount());
        assertEquals(0L, budget.snapshot().getHeapUsed());
        registry.recordStateValidation(key, true);
        assertTrue(registry.statePassed(key));
        registry.close();
        assertEquals(0L, budget.snapshot().getHeapUsed());
    }
}
