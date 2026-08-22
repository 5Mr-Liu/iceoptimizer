package dev.rlcraft.ice.optimizer.render.entity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import org.junit.Test;

public final class CertifiedDrawSitesBudgetTest {
    @Test
    public void certificationFailsClosedAndReleasesSharedHeap() {
        CacheBudget exhausted = new CacheBudget(1L, 1L, 1L);
        CertifiedDrawSites rejected = new CertifiedDrawSites(16, exhausted);
        rejected.certify("renderer", "fingerprint", 1L, 1L, true);
        assertFalse(rejected.isCertified("renderer", "fingerprint", 1L, 1L));
        assertEquals(0, rejected.entryCount());

        CacheBudget budget = new CacheBudget(4096L, 1L, 1L);
        CertifiedDrawSites sites = new CertifiedDrawSites(16, budget);
        sites.certify("renderer", "fingerprint", 1L, 1L, true);
        assertTrue(sites.isCertified("renderer", "fingerprint", 1L, 1L));
        assertEquals(1, sites.entryCount());
        assertTrue(budget.snapshot().getHeapUsed() > 0L);

        sites.invalidate();
        assertEquals(0L, budget.snapshot().getHeapUsed());
    }

    @Test
    public void wrappedFatalCertificationPublicationRollsBackBeforeEscaping() {
        CacheBudget budget = new CacheBudget(4096L, 1L, 1L);
        final OutOfMemoryError fatal = new OutOfMemoryError(
            "injected wrapped draw-site publication failure");
        CertifiedDrawSites sites = new CertifiedDrawSites(16, budget,
            new CertifiedDrawSites.PublicationHook() {
                private boolean fail = true;

                @Override public void afterEntryPut() {
                    if (!fail) return;
                    fail = false;
                    throw new IllegalStateException(
                        "wrapped draw-site publication failure", fatal);
                }
            });

        try {
            sites.certify("renderer", "fingerprint", 1L, 1L, true);
            fail("wrapped fatal draw-site publication failure must escape");
        } catch (OutOfMemoryError expected) {
            assertSame(fatal, expected);
        }

        assertEquals(0, sites.entryCount());
        assertEquals(0L, budget.snapshot().getHeapUsed());
        sites.certify("renderer", "fingerprint", 1L, 1L, true);
        assertTrue(sites.isCertified("renderer", "fingerprint", 1L, 1L));
        sites.close();
        assertEquals(0L, budget.snapshot().getHeapUsed());
    }
}
