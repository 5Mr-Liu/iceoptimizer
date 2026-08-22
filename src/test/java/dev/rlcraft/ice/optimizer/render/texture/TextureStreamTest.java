package dev.rlcraft.ice.optimizer.render.texture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class TextureStreamTest {
    @Test
    public void mergesOnlyAdjacentRectanglesWithCorrectRowInterleaveAndDropsStale() {
        TextureUploadStream stream = new TextureUploadStream(16, 1024L);
        TextureUpload left = upload(1L, 0, new byte[] { 1, 2, 3, 4 }, 1L, 1L, 0L);
        TextureUpload right = upload(2L, 2, new byte[] { 5, 6, 7, 8 }, 1L, 1L, 1L);
        assertTrue(stream.offer(left));
        assertTrue(stream.offer(right));
        assertEquals(1, stream.size());
        List<TextureUpload> uploads = stream.drain(1L, 1L);
        assertEquals(1, uploads.size());
        assertArrayEquals(new byte[] { 1, 2, 5, 6, 3, 4, 7, 8 },
            uploads.get(0).copyPixels());
        assertTrue(stream.offer(upload(3L, 0, new byte[] { 1, 2, 3, 4 },
            2L, 1L, 2L)));
        assertEquals(0, stream.drain(1L, 1L).size());
        assertEquals(1L, stream.getStale());
    }

    @Test
    public void invisibleAnimationDefersPixelsButImmediateVisibilityCatchesUpLatestFrame() {
        final List<TextureUpload> emitted = new ArrayList<TextureUpload>();
        SpriteVisibilityTracker tracker = new SpriteVisibilityTracker(64,
            new SpriteVisibilityTracker.UploadSink() {
                @Override public boolean offer(TextureUpload upload) {
                    emitted.add(upload);
                    return true;
                }
            });
        TextureUpload frame = upload(1L, 0, new byte[] { 1, 2, 3, 4 },
            1L, 1L, 0L);
        assertTrue(tracker.animationAdvanced(1L, 10L, 3, frame));
        assertEquals(0, emitted.size());
        assertTrue(tracker.markVisible(1L, 10L));
        assertEquals(1, emitted.size());
        assertEquals(1L, tracker.getCaughtUp());
    }

    @Test
    public void rejectsCoordinateAndByteCountOverflowBeforePublishingPixels() {
        assertInvalidUpload(Integer.MAX_VALUE, 0, 1, 1, 1,
            new byte[] { 1 });
        assertInvalidUpload(0, Integer.MAX_VALUE, 1, 1, 1,
            new byte[] { 1 });
        assertInvalidUpload(0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE,
            Integer.MAX_VALUE, new byte[] { 1 });
    }

    private static void assertInvalidUpload(int x, int y, int width,
                                            int height, int bytesPerPixel,
                                            byte[] pixels) {
        try {
            new TextureUpload(1L, 1, 0, x, y, width, height, bytesPerPixel,
                6408, 5121, pixels, 1L, 1L, 0L);
            fail("overflowing upload must be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected: invalid geometry is rejected before cloning/publishing.
        }
    }

    private static TextureUpload upload(long sprite, int x, byte[] bytes,
                                        long resources, long atlas, long sequence) {
        return new TextureUpload(sprite, 1, 0, x, 0, 2, 2, 1,
            6408, 5121, bytes, resources, atlas, sequence);
    }
}
