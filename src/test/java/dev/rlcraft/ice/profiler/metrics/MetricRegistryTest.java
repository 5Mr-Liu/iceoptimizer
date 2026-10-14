package dev.rlcraft.ice.profiler.metrics;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class MetricRegistryTest {
    @Test public void delayedPulseUsesElapsedTimeAndRetainsRawFrames() {
        MetricRegistry metrics = new MetricRegistry();
        metrics.snapshot(1000L, 1000L, 1000000000L);
        for (int i = 0; i < 120; i++) metrics.recordClientFrame(16666667L);
        TimelinePoint point = metrics.snapshot(1000L, 3000L, 3000000000L);
        assertEquals(60, point.getFramesPerSecond());
        assertEquals(120, point.getFrameCount());
        assertEquals(2000000000L, point.getSampleIntervalNanos());
        assertEquals(-1, point.getOptimizerRuntimeState());
        TimelinePoint rebased = point.rebase(2000L);
        assertEquals(1000L, rebased.getElapsedMillis());
        assertEquals(point.getFrameCount(), rebased.getFrameCount());
        assertEquals(point.getSampleIntervalNanos(), rebased.getSampleIntervalNanos());
    }

    @Test public void onOffOnInsideOneIntervalCannotBeMistakenForEnabledBaseline() {
        MetricRegistry metrics = new MetricRegistry();
        metrics.optimizerPauseChanged(false);
        assertEquals(1, metrics.snapshot(0L).getOptimizerRuntimeState());
        metrics.optimizerPauseChanged(true);
        metrics.optimizerPauseChanged(false);
        TimelinePoint changed = metrics.snapshot(0L);
        assertEquals(2, changed.getOptimizerRuntimeState());
        assertEquals(2, changed.rebase(0L).getOptimizerRuntimeState());
        assertEquals(1, metrics.snapshot(0L).getOptimizerRuntimeState());
        metrics.optimizerPauseChanged(true);
        assertEquals(2, metrics.snapshot(0L).getOptimizerRuntimeState());
        assertEquals(0, metrics.snapshot(0L).getOptimizerRuntimeState());
    }
}
