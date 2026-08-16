package dev.rlcraft.ice.profiler.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class StatisticsTest {
    @Test
    public void calculatesBoundedDistribution() {
        TimingAccumulator values = new TimingAccumulator(100);
        for (int i = 1; i <= 100; i++) values.record(TimeUnit.MILLISECONDS.toNanos(i));
        DistributionSnapshot snapshot = values.drain();
        assertEquals(100L, snapshot.getCount());
        assertEquals(50.5D, snapshot.getAverageMs(), 0.001D);
        assertEquals(95.0D, snapshot.getP95Ms(), 0.001D);
        assertEquals(100.0D, snapshot.getMaximumMs(), 0.001D);
        assertEquals(0L, values.drain().getCount());
    }

    @Test
    public void stableLowFpsIsNotClassifiedAsEveryFrameHitch() {
        AdaptiveThreshold threshold = new AdaptiveThreshold(60);
        for (int i = 0; i < 40; i++) threshold.record(TimeUnit.MILLISECONDS.toNanos(100));
        long result = threshold.thresholdNanos(TimeUnit.MILLISECONDS.toNanos(80));
        assertTrue(result >= TimeUnit.MILLISECONDS.toNanos(199));
        assertTrue(TimeUnit.MILLISECONDS.toNanos(250) >= result);
    }
}
