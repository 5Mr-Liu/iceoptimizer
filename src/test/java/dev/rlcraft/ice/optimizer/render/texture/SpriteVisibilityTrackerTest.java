package dev.rlcraft.ice.optimizer.render.texture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import java.util.BitSet;
import org.junit.Test;

public final class SpriteVisibilityTrackerTest {
    @Test
    public void retainedPixelsAndEntriesUseAndReleaseSharedHeapBudget() {
        CacheBudget budget = new CacheBudget(512L, 1L, 1L);
        RecordingSink sink = new RecordingSink();
        SpriteVisibilityTracker tracker = new SpriteVisibilityTracker(64,
            4096L, sink, budget);
        Object sprite = new Object();
        completeFrame(tracker, 1L, 1L, 1L);

        assertTrue(tracker.register(sprite, 1, 1L, 1L));
        assertEquals(256L, budget.snapshot().getHeapUsed());
        assertTrue(tracker.deferIfInvisible(sprite, 1, 7,
            new int[][] {{4}}, 1, 1, 0, 0, false, false,
            1L, 1L, 1L));
        assertTrue(budget.snapshot().getHeapUsed() > 256L);
        assertTrue(tracker.markVisible(sprite, 1, 2L, 1L, 1L));
        assertEquals(256L, budget.snapshot().getHeapUsed());

        tracker.close();
        assertEquals(0L, budget.snapshot().getHeapUsed());
    }

    @Test
    public void deferredCopyFailsClosedWhenSharedHeapBudgetIsExhausted() {
        CacheBudget budget = new CacheBudget(300L, 1L, 1L);
        RecordingSink sink = new RecordingSink();
        SpriteVisibilityTracker tracker = new SpriteVisibilityTracker(64,
            4096L, sink, budget);
        Object sprite = new Object();
        completeFrame(tracker, 1L, 1L, 1L);

        assertTrue(tracker.register(sprite, 1, 1L, 1L));
        assertFalse(tracker.deferIfInvisible(sprite, 1, 7,
            new int[][] {{4}}, 1, 1, 0, 0, false, false,
            1L, 1L, 1L));
        assertFalse(tracker.hasPending(sprite));
        assertEquals(256L, budget.snapshot().getHeapUsed());
        tracker.invalidate();
        assertEquals(0L, budget.snapshot().getHeapUsed());
    }

    @Test(expected = OutOfMemoryError.class)
    public void fatalCatchUpFailureIsNeverConvertedIntoLegacyFallback() {
        SpriteVisibilityTracker tracker = new SpriteVisibilityTracker(64,
            4096L, new SpriteVisibilityTracker.CatchUpSink() {
                @Override public boolean upload(
                    SpriteVisibilityTracker.DeferredUpload upload) {
                    throw new OutOfMemoryError("injected");
                }
            });
        Object sprite = new Object();
        completeFrame(tracker, 1L, 1L, 1L);
        assertTrue(tracker.register(sprite, 1, 1L, 1L));
        assertTrue(tracker.deferIfInvisible(sprite, 1, 7,
            new int[][] {{4}}, 1, 1, 0, 0, false, false,
            1L, 1L, 1L));
        tracker.markVisible(sprite, 1, 2L, 1L, 1L);
    }

    @Test
    public void deterministicGateCoversDeepCopyAndSynchronousCatchUp() {
        assertTrue(SpriteVisibilityTracker.selfTest());
    }

    @Test
    public void onlyACompletePreviousVisibilityFrameCanSuppressAnUpload() {
        RecordingSink sink = new RecordingSink();
        SpriteVisibilityTracker tracker = tracker(sink);
        Object sprite = new Object();
        tracker.beginFrame(1L, 2L, 3L);
        tracker.observeTerrainSource(true);
        tracker.observeBufferSource(true);
        tracker.endFrame(1L, true);
        assertTrue(tracker.register(sprite, 7, 2L, 3L));

        int[][] pixels = {{17}};
        assertTrue(tracker.deferIfInvisible(sprite, 7, 11, pixels,
            1, 1, 0, 0, false, false, 1L, 2L, 3L));
        pixels[0][0] = 99;
        tracker.beginFrame(2L, 2L, 3L);
        assertTrue(tracker.markVisible(sprite, 7, 2L, 2L, 3L));
        tracker.endFrame(2L, true);

        assertEquals(17, sink.pixel);
        assertEquals(4L, tracker.getDeferredBytes());
        assertEquals(4L, tracker.getCaughtUpBytes());
        assertEquals(0L, tracker.getPendingBytes());
    }

    @Test
    public void missingEndAndUnknownSourceKeepTheNextUploadOnTheOldPath() {
        RecordingSink sink = new RecordingSink();
        SpriteVisibilityTracker tracker = tracker(sink);
        Object sprite = new Object();
        assertTrue(tracker.register(sprite, 1, 1L, 1L));
        tracker.observeTerrainSource(true);
        tracker.observeBufferSource(true);
        tracker.beginFrame(1L, 1L, 1L);
        tracker.beginFrame(2L, 1L, 1L);
        tracker.endFrame(2L, true);
        assertFalse(tracker.deferIfInvisible(sprite, 1, 3,
            new int[][] {{1}}, 1, 1, 0, 0, false, false,
            2L, 1L, 1L));

        tracker.beginFrame(3L, 1L, 1L);
        tracker.observeBufferSource(false);
        tracker.endFrame(3L, true);
        assertFalse(tracker.deferIfInvisible(sprite, 1, 3,
            new int[][] {{2}}, 1, 1, 0, 0, false, false,
            3L, 1L, 1L));
        assertTrue(tracker.getUnknownFrames() > 0L);
    }

    @Test
    public void aNotYetBoundAtlasRetainsPendingPixelsForTheNextRealDraw() {
        RecordingSink sink = new RecordingSink();
        sink.bound = false;
        SpriteVisibilityTracker tracker = tracker(sink);
        Object sprite = new Object();
        completeFrame(tracker, 1L, 1L, 1L);
        assertTrue(tracker.register(sprite, 4, 1L, 1L));
        assertTrue(tracker.deferIfInvisible(sprite, 4, 8,
            new int[][] {{5}}, 1, 1, 0, 0, false, false,
            1L, 1L, 1L));

        assertFalse(tracker.markVisible(sprite, 4, 2L, 1L, 1L));
        assertTrue(tracker.hasPending(sprite));
        sink.bound = true;
        assertTrue(tracker.markVisible(sprite, 4, 2L, 1L, 1L));
        assertFalse(tracker.hasPending(sprite));
    }

    @Test
    public void anImmediateNewerUploadDiscardsTheOlderDeferredFrame() {
        RecordingSink sink = new RecordingSink();
        SpriteVisibilityTracker tracker = tracker(sink);
        Object sprite = new Object();
        completeFrame(tracker, 1L, 1L, 1L);
        assertTrue(tracker.register(sprite, 2, 1L, 1L));
        assertTrue(tracker.deferIfInvisible(sprite, 2, 9,
            new int[][] {{6}}, 1, 1, 0, 0, false, false,
            1L, 1L, 1L));
        tracker.immediateUpload(sprite, 2, 1L, 1L);

        assertEquals(0L, tracker.getPendingBytes());
        assertEquals(1L, tracker.getImmediateUploads());
        assertTrue(tracker.markVisible(sprite, 2, 2L, 1L, 1L));
        assertEquals(0, sink.uploads);
    }

    @Test
    public void idleEvictionUsesTheIteratorAndNeverDropsPendingEntries() {
        RecordingSink sink = new RecordingSink();
        SpriteVisibilityTracker tracker = tracker(sink);
        Object first = null;
        for (int index = 0; index < 64; index++) {
            Object sprite = new Object();
            if (index == 0) first = sprite;
            assertTrue(tracker.register(sprite, index, 1L, 1L));
        }
        assertTrue(tracker.register(new Object(), 64, 1L, 1L));
        assertEquals(64, tracker.getTrackedSprites());
        assertEquals(1L, tracker.getEvicted());

        // The first idle entry was evicted, so a visibility mark cannot create
        // or accidentally upload a frame for it without an explicit index.
        assertFalse(tracker.hasPending(first));
    }

    @Test
    public void bitSetCatchUpPreservesIndexMappingAndGeneration() {
        RecordingSink sink = new RecordingSink();
        SpriteVisibilityTracker tracker = tracker(sink);
        Object sprite = new Object();
        completeFrame(tracker, 4L, 2L, 3L);
        assertTrue(tracker.register(sprite, 31, 2L, 3L));
        assertTrue(tracker.deferIfInvisible(sprite, 31, 14,
            new int[][] {{8}}, 1, 1, 0, 0, false, false,
            4L, 2L, 3L));
        BitSet visible = new BitSet();
        visible.set(31);
        assertEquals(1, tracker.markVisible(visible, 5L, 2L, 3L));
        assertEquals(8, sink.pixel);

        tracker.invalidate(3L, 4L);
        assertEquals(0, tracker.getTrackedSprites());
        assertEquals(0L, tracker.getPendingBytes());
    }

    @Test
    public void collidingIndexMoveCannotOrphanEitherSpriteMapping() {
        RecordingSink sink = new RecordingSink();
        SpriteVisibilityTracker tracker = tracker(sink);
        Object first = new Object();
        Object second = new Object();
        completeFrame(tracker, 1L, 1L, 1L);
        assertTrue(tracker.register(first, 1, 1L, 1L));
        assertTrue(tracker.register(second, 2, 1L, 1L));

        assertFalse(tracker.register(first, 2, 1L, 1L));
        assertEquals(2, tracker.getTrackedSprites());
        assertEquals(1L, tracker.getRejected());
        assertTrue(tracker.deferIfInvisible(first, 1, 7,
            new int[][] {{11}}, 1, 1, 0, 0, false, false,
            1L, 1L, 1L));
        assertTrue(tracker.deferIfInvisible(second, 2, 7,
            new int[][] {{22}}, 1, 1, 0, 0, false, false,
            1L, 1L, 1L));

        BitSet visible = new BitSet();
        visible.set(1);
        assertEquals(1, tracker.markVisible(visible, 2L, 1L, 1L));
        assertEquals(11, sink.pixel);
        visible.clear();
        visible.set(2);
        assertEquals(1, tracker.markVisible(visible, 2L, 1L, 1L));
        assertEquals(22, sink.pixel);
        assertEquals(0L, tracker.getPendingBytes());
    }

    @Test
    public void postPublicationIndexMoveFailureRemovesTheNewReverseMapping() {
        RecordingSink sink = new RecordingSink();
        FailingPublicationHook hook = new FailingPublicationHook();
        SpriteVisibilityTracker tracker = new SpriteVisibilityTracker(64,
            4096L, sink, hook);
        Object sprite = new Object();
        completeFrame(tracker, 1L, 1L, 1L);
        assertTrue(tracker.register(sprite, 3, 1L, 1L));

        hook.failNext();
        try {
            tracker.register(sprite, 9, 1L, 1L);
            fail("post-publication failure must escape after rollback");
        } catch (IllegalStateException expected) {
            assertEquals("injected index publication failure",
                expected.getMessage());
        }

        assertTrue(tracker.deferIfInvisible(sprite, 3, 7,
            new int[][] {{41}}, 1, 1, 0, 0, false, false,
            1L, 1L, 1L));
        BitSet visible = new BitSet();
        visible.set(9);
        assertEquals("the failed new index must not retain a reverse mapping",
            0, tracker.markVisible(visible, 2L, 1L, 1L));
        visible.clear();
        visible.set(3);
        assertEquals(1, tracker.markVisible(visible, 2L, 1L, 1L));
        assertEquals(41, sink.pixel);
    }

    @Test
    public void wrappedFatalPublicationFailureRollsBackBeforeEscaping() {
        CacheBudget budget = new CacheBudget(512L, 1L, 1L);
        RecordingSink sink = new RecordingSink();
        final OutOfMemoryError fatal = new OutOfMemoryError(
            "injected wrapped publication failure");
        SpriteVisibilityTracker tracker = new SpriteVisibilityTracker(64,
            4096L, sink, new SpriteVisibilityTracker.PublicationHook() {
                private boolean fail = true;

                @Override public void afterIndexPut() {
                    if (!fail) return;
                    fail = false;
                    throw new IllegalStateException(
                        "wrapped publication failure", fatal);
                }
            }, budget);
        Object sprite = new Object();

        try {
            tracker.register(sprite, 12, 1L, 1L);
            fail("wrapped fatal publication failure must escape");
        } catch (OutOfMemoryError expected) {
            assertSame(fatal, expected);
        }

        assertEquals(0, tracker.getTrackedSprites());
        assertEquals(0L, budget.snapshot().getHeapUsed());
        assertTrue("the rolled-back identity and reverse maps must be reusable",
            tracker.register(sprite, 12, 1L, 1L));
        assertEquals(1, tracker.getTrackedSprites());
        assertEquals(256L, budget.snapshot().getHeapUsed());
        tracker.close();
        assertEquals(0L, budget.snapshot().getHeapUsed());
    }

    private static SpriteVisibilityTracker tracker(RecordingSink sink) {
        return new SpriteVisibilityTracker(64, 4096L, sink);
    }

    private static void completeFrame(SpriteVisibilityTracker tracker,
                                      long frame, long resources, long atlas) {
        tracker.beginFrame(frame, resources, atlas);
        tracker.observeTerrainSource(true);
        tracker.observeBufferSource(true);
        tracker.endFrame(frame, true);
    }

    private static final class RecordingSink
        implements SpriteVisibilityTracker.CatchUpSink {
        private boolean bound = true;
        private int uploads;
        private int pixel;

        @Override public boolean upload(
            SpriteVisibilityTracker.DeferredUpload upload) {
            if (!bound) return false;
            uploads++;
            pixel = upload.copyPixels()[0][0];
            return true;
        }
    }

    private static final class FailingPublicationHook
        implements SpriteVisibilityTracker.PublicationHook {
        private boolean fail;

        private void failNext() { fail = true; }

        @Override public void afterIndexPut() {
            if (!fail) return;
            fail = false;
            throw new IllegalStateException(
                "injected index publication failure");
        }
    }
}
