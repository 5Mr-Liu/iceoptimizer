package dev.rlcraft.ice.profiler.metrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class ChunkChurnTrackerTest {
    @Test
    public void correlatesIdentityAndCumulativeChurnWindows() {
        ChunkChurnTracker tracker = new ChunkChurnTracker(8);
        Object first = new Object();
        tracker.dataLoaded(0, 3, 4, first, 10L);
        tracker.loaded(0, 3, 4, first, 20L);
        tracker.unloaded(0, 3, 4,
            20L + TimeUnit.MILLISECONDS.toNanos(500L));
        Object second = new Object();
        tracker.dataLoaded(0, 3, 4, second,
            20L + TimeUnit.MILLISECONDS.toNanos(800L));
        tracker.loaded(0, 3, 4, second,
            20L + TimeUnit.MILLISECONDS.toNanos(900L));

        ChunkChurnSnapshot snapshot = tracker.drain();
        assertEquals(2L, snapshot.getDataBackedLoads());
        assertEquals(0L, snapshot.getLoadsWithoutDataEvent());
        assertEquals(1L, snapshot.getReloadWithinOneSecond());
        assertEquals(1L, snapshot.getReloadWithinFiveSeconds());
        assertEquals(1L, snapshot.getReloadWithinThirtySeconds());
        assertEquals(1L, snapshot.getShortUnloadWithinOneSecond());
        assertEquals(1L, snapshot.getShortUnloadWithinFiveSeconds());
        assertEquals(1L, snapshot.getShortUnloadWithinThirtySeconds());
    }

    @Test
    public void identityMismatchAndLruEvictionStayBounded() {
        ChunkChurnTracker tracker = new ChunkChurnTracker(2);
        tracker.dataLoaded(0, 0, 0, new Object(), 1L);
        tracker.loaded(0, 0, 0, new Object(), 2L);
        tracker.loaded(0, 1, 0, null, 3L);
        tracker.loaded(0, 2, 0, null, 4L);

        ChunkChurnSnapshot snapshot = tracker.drain();
        assertEquals(0L, snapshot.getDataBackedLoads());
        assertEquals(3L, snapshot.getLoadsWithoutDataEvent());
        assertEquals(1L, snapshot.getStateEvictions());
        assertEquals(2, snapshot.getTrackedEntries());
    }

    @Test
    public void dimensionBucketsAndSnapshotTotalsSaturate() {
        ChunkChurnTracker tracker = new ChunkChurnTracker(128);
        for (int dimension = 0; dimension < 80; dimension++) {
            tracker.loaded(dimension, dimension, 0, null, dimension + 1L);
        }
        ChunkChurnSnapshot bounded = tracker.drain();
        assertEquals(64, bounded.getDimensions().size());
        assertTrue(hasDimension(bounded, ChunkChurnTracker.OVERFLOW_DIMENSION));

        ChunkChurnDimensionSnapshot maximum = dimension(0, Long.MAX_VALUE);
        ChunkChurnDimensionSnapshot one = dimension(1, 1L);
        ChunkChurnSnapshot saturated = new ChunkChurnSnapshot(
            Arrays.asList(maximum, one), Long.MAX_VALUE, 0);
        assertEquals(Long.MAX_VALUE, saturated.getDataBackedLoads());
        assertEquals(Long.MAX_VALUE, saturated.getReloadWithinThirtySeconds());
    }

    private static boolean hasDimension(ChunkChurnSnapshot snapshot,
                                        int expected) {
        for (ChunkChurnDimensionSnapshot dimension : snapshot.getDimensions()) {
            if (dimension.getDimension() == expected) return true;
        }
        return false;
    }

    private static ChunkChurnDimensionSnapshot dimension(int id, long value) {
        return new ChunkChurnDimensionSnapshot(id, value, value, value, value,
            value, value, value, value);
    }
}
