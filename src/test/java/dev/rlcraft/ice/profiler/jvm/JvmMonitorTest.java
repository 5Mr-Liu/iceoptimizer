package dev.rlcraft.ice.profiler.jvm;

import static org.junit.Assert.assertEquals;

import java.util.ArrayDeque;
import java.util.Queue;
import org.junit.Test;

public class JvmMonitorTest {
    @Test
    public void consumesGcDeltasFromCachedCumulativeReadings() {
        final Queue<JvmMonitor.Reading> readings = new ArrayDeque<JvmMonitor.Reading>();
        readings.add(new JvmMonitor.Reading(100L, 200L, 300L, 10L, 50L, 0.25D));
        readings.add(new JvmMonitor.Reading(120L, 200L, 300L, 13L, 92L, 0.50D));
        JvmMonitor monitor = new JvmMonitor(new JvmMonitor.Source() {
            @Override public JvmMonitor.Reading read() { return readings.remove(); }
        }, 1000L);

        monitor.collectNow();
        JvmSnapshot first = monitor.snapshot();
        assertEquals(0L, first.getGcCountDelta());
        assertEquals(0L, first.getGcPauseMillisDelta());
        assertEquals(100L, first.getHeapUsedBytes());

        monitor.collectNow();
        JvmSnapshot second = monitor.snapshot();
        assertEquals(3L, second.getGcCountDelta());
        assertEquals(42L, second.getGcPauseMillisDelta());
        assertEquals(0.50D, second.getProcessCpuLoad(), 0.0001D);

        JvmSnapshot repeated = monitor.snapshot();
        assertEquals(0L, repeated.getGcCountDelta());
        assertEquals(0L, repeated.getGcPauseMillisDelta());
    }
}
