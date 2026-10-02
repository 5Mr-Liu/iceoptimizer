package dev.rlcraft.ice.optimizer.render.hud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import java.util.List;
import org.junit.Test;

public final class HudStreamTest {
    @Test
    public void fontLayoutCopiesOnlyUsedGlyphsAndOwnsItsData() {
        float[] geometry = new float[60];
        int[] pages = {3, 4, -1};
        geometry[0] = 1.5F;
        geometry[40] = Float.NaN;
        FontLayoutCache.GlyphLayout layout = new FontLayoutCache.GlyphLayout(
            geometry, pages, 2, 8.0F);
        geometry[0] = Float.NaN;
        pages[0] = -1;
        layout.getGlyphGeometry()[0] = -10.0F;
        layout.getTexturePages()[0] = -1;
        assertEquals(2, layout.glyphCount());
        assertEquals(40, layout.getGlyphGeometry().length);
        assertEquals(1.5F, layout.getGlyphGeometry()[0], 0.0F);
        assertArrayEquals(new int[] {3, 4}, layout.getTexturePages());
        assertEquals(168L, layout.retainedBytes());
        assertEquals(8.0F, layout.getAdvance(), 0.0F);
        FontLayoutCache.GlyphLayout empty = new FontLayoutCache.GlyphLayout(
            new float[0], new int[0], 0, 0.0F);
        assertEquals(0, empty.glyphCount());
    }

    @Test
    public void fontLayoutStillRejectsInvalidUsedDataAndExactSizeMismatch() {
        try {
            new FontLayoutCache.GlyphLayout(new float[40], new int[] {1});
            fail("exact constructor accepted a size mismatch");
        } catch (IllegalArgumentException expected) { }
        try {
            new FontLayoutCache.GlyphLayout(new float[20], new int[] {1}, 2, 1.0F);
            fail("accepted a prefix outside the input");
        } catch (IllegalArgumentException expected) { }
        try {
            float[] invalid = new float[20];
            invalid[19] = Float.NaN;
            new FontLayoutCache.GlyphLayout(invalid, new int[] {1}, 1, 1.0F);
            fail("accepted a non-finite cached vertex");
        } catch (IllegalArgumentException expected) { }
        try {
            new FontLayoutCache.GlyphLayout(new float[20], new int[] {0}, 1, 1.0F);
            fail("accepted an invalid texture page");
        } catch (IllegalArgumentException expected) { }
    }

    @Test
    public void shadowMainAndEventScopesRemainDistinctRuns() {
        HudVertexStream stream = new HudVertexStream(64);
        HudState shadow = state(1L, true);
        HudState main = state(1L, false);
        stream.record(shadow, quad(0L));
        stream.record(main, quad(1L));
        stream.record(state(2L, false), quad(2L));
        List<HudBatch> batches = stream.barrier();
        assertEquals(3, batches.size());
        assertEquals(0L, batches.get(0).getQuads().get(0).getSequence());
        assertEquals(1L, batches.get(1).getQuads().get(0).getSequence());
        assertEquals(2L, batches.get(2).getQuads().get(0).getSequence());
    }

    @Test
    public void fontCacheUsesFullGenerationKeyAndBoundedLru() {
        FontLayoutCache cache = new FontLayoutCache(16, 256);
        FontLayoutCache.LayoutFactory factory = new FontLayoutCache.LayoutFactory() {
            @Override public FontLayoutCache.GlyphLayout create() {
                return new FontLayoutCache.GlyphLayout(new float[20],
                    new int[] { 1 }, 4.0F);
            }
        };
        FontLayoutCache.GlyphLayout first = cache.getOrCreate("abc", 0, false, 1L, factory);
        assertSame(first, cache.getOrCreate("abc", 0, false, 1L, factory));
        assertNotSame(first, cache.getOrCreate("abc", 0, false, 2L, factory));
        Object secondFont = new Object();
        assertNotSame(first, cache.getOrCreate(secondFont, "abc", 0, false,
            1L, factory));
        assertEquals(4.0F, first.getAdvance(), 0.0F);
    }

    @Test
    public void fontCacheAccountsPayloadAndHasAnIndependentResettableFuse() {
        CacheBudget budget = new CacheBudget(1024L, 1L, 1L);
        FontLayoutCache cache = new FontLayoutCache(16, 256, budget);
        Object font = new Object();
        FontLayoutCache.GlyphLayout layout = new FontLayoutCache.GlyphLayout(
            new float[20], new int[] {3}, 4.0F);
        assertTrue(cache.put(font, "a", 0, false, 1L, layout));
        assertEquals(84L, budget.snapshot().getHeapUsed());
        cache.disable(new IllegalStateException("test"));
        assertFalse(cache.isTrusted());
        assertEquals(0L, budget.snapshot().getHeapUsed());
        assertFalse(cache.put(font, "a", 0, false, 1L, layout));
        cache.invalidate();
        assertTrue(cache.isTrusted());
        assertTrue(cache.put(font, "a", 0, false, 1L, layout));
        cache.invalidate();
        assertEquals(0L, budget.snapshot().getHeapUsed());
    }

    @Test
    public void wrappedFatalFontPublicationRollsBackBeforeBudgetRelease() {
        CacheBudget budget = new CacheBudget(1024L, 1L, 1L);
        final OutOfMemoryError fatal = new OutOfMemoryError(
            "injected wrapped font publication failure");
        FontLayoutCache cache = new FontLayoutCache(16, 256, budget,
            new FontLayoutCache.PublicationHook() {
                private boolean fail = true;

                @Override public void afterEntryPut() {
                    if (!fail) return;
                    fail = false;
                    throw new IllegalStateException(
                        "wrapped font publication failure", fatal);
                }
            });
        Object font = new Object();
        FontLayoutCache.GlyphLayout layout = new FontLayoutCache.GlyphLayout(
            new float[20], new int[] {5}, 3.0F);

        try {
            cache.put(font, "fault", 0, false, 1L, layout);
            fail("wrapped fatal font publication failure must escape");
        } catch (OutOfMemoryError expected) {
            assertSame(fatal, expected);
        }

        assertEquals(0L, budget.snapshot().getHeapUsed());
        assertNull(cache.get(font, "fault", 0, false, 1L));
        assertTrue(cache.put(font, "fault", 0, false, 1L, layout));
        assertEquals(84L, budget.snapshot().getHeapUsed());
        cache.invalidate();
        assertEquals(0L, budget.snapshot().getHeapUsed());
    }

    private static HudState state(long scope, boolean shadow) {
        return new HudState(0, 0, 1, 0, 770, 771, 0, 0, scope, shadow);
    }

    private static HudQuad quad(long sequence) {
        return new HudQuad(new float[16], -1, sequence);
    }
}
