package dev.rlcraft.ice.optimizer.render.entity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public final class LwjglModelMeshCacheTest {
    @Test
    public void postPutFailureRemovesANewPublication() {
        Map<Integer, Object> entries = new LinkedHashMap<Integer, Object>();
        Object replacement = new Object();
        IllegalStateException injected = new IllegalStateException(
            "injected model publication failure");
        try {
            LwjglModelMeshCache.publishReplacement(entries,
                Integer.valueOf(7), null, replacement, failing(injected));
            fail("post-put failure must escape");
        } catch (IllegalStateException expected) {
            assertSame(injected, expected);
        }
        assertFalse(entries.containsKey(Integer.valueOf(7)));
    }

    @Test
    public void postPutFailureRestoresThePreviousEntry() {
        Map<Integer, Object> entries = new LinkedHashMap<Integer, Object>();
        Integer key = Integer.valueOf(11);
        Object current = new Object();
        Object replacement = new Object();
        entries.put(key, current);
        IllegalStateException injected = new IllegalStateException(
            "injected model replacement failure");
        try {
            LwjglModelMeshCache.publishReplacement(entries, key, current,
                replacement, failing(injected));
            fail("post-put replacement failure must escape");
        } catch (IllegalStateException expected) {
            assertSame(injected, expected);
        }
        assertEquals(1, entries.size());
        assertSame(current, entries.get(key));
    }

    @Test
    public void reusableDirectStagingIsBudgetedAndReleasedOnClose() {
        CacheBudget budget = new CacheBudget(1L, 8192L, 1L);
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger ledger = new ResourceLedger(guard, budget,
            new ResourceLedger.Destroyer() {
                @Override public void destroy(
                    dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind kind,
                    int nativeId) { }
            }, 8);
        LwjglModelMeshCache cache = new LwjglModelMeshCache(guard, ledger,
            budget);

        assertTrue(cache.ensureStaging(1024));
        assertEquals(4096L, budget.snapshot().getDirectUsed());
        assertTrue(cache.ensureStaging(2048));
        assertEquals(4096L, budget.snapshot().getDirectUsed());
        cache.close(false);
        assertEquals(0L, budget.snapshot().getDirectUsed());
    }

    private static LwjglModelMeshCache.PublicationHook failing(
        final RuntimeException failure) {
        return new LwjglModelMeshCache.PublicationHook() {
            @Override public void afterEntryPut() { throw failure; }
        };
    }
}
