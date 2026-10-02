package dev.rlcraft.ice.profiler.session;

import static org.junit.Assert.*;

import dev.rlcraft.ice.config.IceConfig;
import dev.rlcraft.ice.profiler.analysis.ModResolver;
import dev.rlcraft.ice.profiler.capture.HitchTrigger;
import dev.rlcraft.ice.profiler.capture.TriggerType;
import dev.rlcraft.ice.profiler.sampling.RecentSampleSource;
import dev.rlcraft.ice.profiler.sampling.StackSample;
import dev.rlcraft.ice.profiler.sampling.StackTraceRepository;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class RecordingSessionLazySnapshotTest {
    @Test public void mergedTriggersDoNotReadRollingSnapshotAndNewCapturesReadOnce() {
        final int[] reads = {0};
        RecentSampleSource source = new RecentSampleSource() {
            @Override public List<StackSample> recentSince(long timestamp) {
                reads[0]++;
                return Collections.emptyList();
            }
        };
        RecordingSession session = new RecordingSession("lazy", "test", true,
            new StackTraceRepository(8, 8), new ModResolver());
        long now = System.nanoTime();
        session.triggerLazy(trigger(now), source);
        for (int i = 1; i <= 100; i++) session.triggerLazy(trigger(now + i), source);
        assertEquals(1, reads[0]);
        session.triggerLazy(trigger(now + TimeUnit.SECONDS.toNanos(
            IceConfig.capture.postCaptureSeconds + IceConfig.capture.mergeWindowSeconds + 1L)), source);
        assertEquals(2, reads[0]);
    }
    private static HitchTrigger trigger(long end) {
        return new HitchTrigger(TriggerType.SERVER_TICK, end, 1L, 100_000_000L, 1L, "test");
    }
}
