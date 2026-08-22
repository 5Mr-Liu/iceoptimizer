package dev.rlcraft.ice.optimizer.render.legacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Callable;
import org.junit.Test;

public final class LegacyGlIslandTest {
    @Test
    public void nestedLegacyCallsFlushAndRestoreOnceThenInvalidateMirror() {
        final AtomicInteger flushes = new AtomicInteger();
        final AtomicInteger restores = new AtomicInteger();
        GlStateMirror mirror = new GlStateMirror(2);
        mirror.useProgram(3);
        final LegacyGlIsland island = new LegacyGlIsland(
            RenderThreadGuard.captureCurrent(), mirror,
            new LegacyGlIsland.ModernBatchFlusher() {
                @Override public void flush() { flushes.incrementAndGet(); }
            }, new LegacyGlIsland.LegacyStateRestorer() {
                @Override public void restoreLegacyCallSiteState() { restores.incrementAndGet(); }
            });
        island.run(new Runnable() {
            @Override public void run() {
                island.run(new Runnable() {
                    @Override public void run() { }
                });
            }
        });
        assertEquals(1, flushes.get());
        assertEquals(1, restores.get());
        assertEquals(1L, island.getEntries());
        assertFalse(mirror.isKnown());
    }

    @Test(expected = IllegalArgumentException.class)
    public void preservesLegacyExceptionPropagation() {
        LegacyGlIsland island = new LegacyGlIsland(RenderThreadGuard.captureCurrent(),
            new GlStateMirror(1), new LegacyGlIsland.ModernBatchFlusher() {
                @Override public void flush() { }
            }, new LegacyGlIsland.LegacyStateRestorer() {
                @Override public void restoreLegacyCallSiteState() { }
            });
        island.run(new Runnable() {
            @Override public void run() { throw new IllegalArgumentException("original"); }
        });
    }

    @Test
    public void internalRestoreFailureNeverSuppressesOriginalLegacyCallback() {
        final AtomicInteger calls = new AtomicInteger();
        LegacyGlIsland island = new LegacyGlIsland(RenderThreadGuard.captureCurrent(),
            new GlStateMirror(1), new LegacyGlIsland.ModernBatchFlusher() {
                @Override public void flush() { throw new IllegalStateException("flush"); }
            }, new LegacyGlIsland.LegacyStateRestorer() {
                @Override public void restoreLegacyCallSiteState() {
                    throw new IllegalStateException("restore");
                }
            });
        island.run(new Runnable() {
            @Override public void run() { calls.incrementAndGet(); }
        });
        assertEquals(1, calls.get());
        assertEquals(2L, island.getRestorationFailures());
    }

    @Test
    public void checkedWrapperCannotHideFatalCallbackFailure() throws Exception {
        LegacyGlIsland island = new LegacyGlIsland(
            RenderThreadGuard.captureCurrent(), new GlStateMirror(1),
            new LegacyGlIsland.ModernBatchFlusher() {
                @Override public void flush() { }
            }, new LegacyGlIsland.LegacyStateRestorer() {
                @Override public void restoreLegacyCallSiteState() { }
            });
        final OutOfMemoryError fatal = new OutOfMemoryError(
            "wrapped legacy callback fatal");
        try {
            island.call(new Callable<Void>() {
                @Override public Void call() throws Exception {
                    throw new Exception("checked wrapper", fatal);
                }
            });
            throw new AssertionError("wrapped fatal was swallowed");
        } catch (OutOfMemoryError expected) {
            assertSame(fatal, expected);
        }
        assertFalse(island.isInside());
        assertEquals(1L, island.getFailures());
    }

    @Test
    public void splitPhaseWrapperRestoresOnceAndRecordsOriginalFailure() {
        final AtomicInteger flushes = new AtomicInteger();
        final AtomicInteger restores = new AtomicInteger();
        GlStateMirror mirror = new GlStateMirror(1);
        mirror.useProgram(9);
        LegacyGlIsland island = new LegacyGlIsland(RenderThreadGuard.captureCurrent(),
            mirror, new LegacyGlIsland.ModernBatchFlusher() {
                @Override public void flush() { flushes.incrementAndGet(); }
            }, new LegacyGlIsland.LegacyStateRestorer() {
                @Override public void restoreLegacyCallSiteState() {
                    restores.incrementAndGet();
                }
            });
        long outer = island.enter();
        long inner = island.enter();
        assertTrue(island.isInside());
        island.exit(inner, null);
        IllegalArgumentException original = new IllegalArgumentException("original");
        island.exit(outer, original);
        assertFalse(island.isInside());
        assertFalse(mirror.isKnown());
        assertEquals(1, flushes.get());
        assertEquals(1, restores.get());
        assertEquals(1L, island.getFailures());
    }

    @Test
    public void splitPhaseTokenMismatchFailsClosedWithoutThrowing() {
        GlStateMirror mirror = new GlStateMirror(1);
        LegacyGlIsland island = new LegacyGlIsland(RenderThreadGuard.captureCurrent(),
            mirror, new LegacyGlIsland.ModernBatchFlusher() {
                @Override public void flush() { }
            }, new LegacyGlIsland.LegacyStateRestorer() {
                @Override public void restoreLegacyCallSiteState() { }
            });
        long token = island.enter();
        island.exit(token + 1L, null);
        assertFalse(island.isInside());
        assertTrue(island.getRestorationFailures() >= 1L);
        island.exit(token, null);
        assertFalse(island.isInside());
    }

    @Test
    public void tokenExhaustionNeverWrapsIntoAnOldCallbackToken() {
        assertEquals(Long.MAX_VALUE,
            LegacyGlIsland.checkedNextToken(Long.MAX_VALUE - 1L));
        try {
            LegacyGlIsland.checkedNextToken(Long.MAX_VALUE);
            throw new AssertionError("expected token exhaustion");
        } catch (IllegalStateException expected) {
            assertEquals("Legacy GL island token exhausted",
                expected.getMessage());
        }
    }
}
