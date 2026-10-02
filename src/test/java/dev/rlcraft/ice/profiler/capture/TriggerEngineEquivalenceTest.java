package dev.rlcraft.ice.profiler.capture;

import static org.junit.Assert.assertEquals;

import dev.rlcraft.ice.config.IceConfig;
import dev.rlcraft.ice.profiler.stats.AdaptiveThreshold;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class TriggerEngineEquivalenceTest {
    @Test
    public void preservesTriggersAndPreviousWindowAcrossFpsChangesAndDisabledCapture() {
        boolean original = IceConfig.capture.autoCapture;
        try {
            final List<HitchTrigger> actual = new ArrayList<HitchTrigger>();
            TriggerEngine engine = new TriggerEngine(new TriggerEngine.Sink() {
                @Override public void onTrigger(HitchTrigger trigger) { actual.add(trigger); }
            });
            Reference[] reference = { new Reference(600), new Reference(400), new Reference(400) };
            Random random = new Random(10202109L);
            for (int index = 0; index < 12000; index++) {
                IceConfig.capture.autoCapture = index < 5000 || index >= 7000;
                int family = index % 3;
                long millis = index < 3000 ? 8L + random.nextInt(12)
                    : index < 8000 ? 95L + random.nextInt(12) : 12L + random.nextInt(15);
                if (index % 47 == 0) millis += 200L + random.nextInt(1500);
                long nanos = TimeUnit.MILLISECONDS.toNanos(millis);
                long absolute = TimeUnit.MILLISECONDS.toNanos(family == 2
                    ? IceConfig.capture.serverTickThresholdMs : IceConfig.capture.frameThresholdMs);
                long threshold = reference[family].threshold(absolute);
                reference[family].record(nanos);
                int before = actual.size();
                if (family == 0) engine.clientFrame(nanos);
                else if (family == 1) engine.clientTick(nanos);
                else engine.serverTick(nanos);
                boolean expected = IceConfig.capture.autoCapture && nanos >= threshold;
                assertEquals("observation " + index, before + (expected ? 1 : 0), actual.size());
                if (expected) {
                    HitchTrigger trigger = actual.get(before);
                    assertEquals(threshold, trigger.getThresholdNanos());
                    assertEquals(nanos, trigger.getDurationNanos());
                    assertEquals(family == 0 ? TriggerType.CLIENT_FRAME
                        : family == 1 ? TriggerType.CLIENT_TICK : TriggerType.SERVER_TICK, trigger.getType());
                }
            }
        } finally {
            IceConfig.capture.autoCapture = original;
        }
    }

    @Test
    public void reusedScratchUsesOnlyLiveValuesBeforeAndAfterRingWrap() {
        AdaptiveThreshold actual = new AdaptiveThreshold(37);
        Reference expected = new Reference(37);
        Random random = new Random(37L);
        for (int index = 0; index < 2000; index++) {
            long value = random.nextInt(100000);
            assertEquals(expected.threshold(500), actual.thresholdNanos(500));
            assertEquals(expected.threshold(40000), actual.thresholdNanos(40000));
            actual.record(value);
            expected.record(value);
        }
    }

    private static final class Reference {
        private final int capacity;
        private final List<Long> window = new ArrayList<Long>();
        Reference(int capacity) { this.capacity = capacity; }
        void record(long nanos) {
            if (window.size() == capacity) window.remove(0);
            window.add(Math.max(0L, nanos));
        }
        long threshold(long absolute) {
            if (window.size() < 20) return absolute;
            long[] values = new long[window.size()];
            for (int index = 0; index < values.length; index++) values[index] = window.get(index);
            Arrays.sort(values);
            long median = values[values.length / 2];
            for (int index = 0; index < values.length; index++) values[index] = Math.abs(values[index] - median);
            Arrays.sort(values);
            long mad = values[values.length / 2];
            long ratio = median > Long.MAX_VALUE / 2L ? Long.MAX_VALUE : median * 2L;
            return Math.max(absolute, Math.max(median + 6L * Math.max(1L, mad), ratio));
        }
    }
}
