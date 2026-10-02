package dev.rlcraft.ice.profiler.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.config.IceConfig;
import dev.rlcraft.ice.profiler.capture.HitchTrigger;
import dev.rlcraft.ice.profiler.capture.TriggerType;
import dev.rlcraft.ice.profiler.sampling.StackSample;
import dev.rlcraft.ice.profiler.sampling.StackTraceRepository;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class SessionSummaryCacheTest {
    @Test
    public void publishesCompletedDiagnosisAndOccurrenceChangesWithoutChangingOldSnapshots() {
        SessionManager manager = new SessionManager(new StackTraceRepository(32, 16), new ReportExporter() {
            @Override public File export(RecordingSession session) { return null; }
        });
        try {
            manager.start(true, "test");
            long now = System.nanoTime();
            long post = TimeUnit.SECONDS.toNanos(IceConfig.capture.postCaptureSeconds);
            List<String> empty = manager.currentClusterSummaries();
            manager.trigger(trigger(now), Collections.<StackSample>emptyList());
            assertSame(empty, manager.currentClusterSummaries());
            manager.pollCompleted(now + post + 1L);
            List<String> first = manager.currentClusterSummaries();
            assertEquals(1, first.size());
            assertTrue(first.get(0).contains("（1 次）"));
            for (int index = 0; index < 200; index++) assertSame(first, manager.currentClusterSummaries());
            manager.trigger(trigger(now + post + 2L), Collections.<StackSample>emptyList());
            manager.pollCompleted(now + 2L * post + 3L);
            List<String> second = manager.currentClusterSummaries();
            assertNotSame(first, second);
            assertTrue(second.get(0).contains("（2 次）"));
            assertTrue(first.get(0).contains("（1 次）"));
            manager.stop("test", false);
            assertTrue(manager.currentClusterSummaries().isEmpty());
            manager.start(true, "next");
            assertTrue(manager.currentClusterSummaries().isEmpty());
            manager.stop("test", false);
        } finally {
            manager.shutdown();
        }
    }

    private static HitchTrigger trigger(long timestamp) {
        return new HitchTrigger(TriggerType.CLIENT_FRAME, timestamp, System.currentTimeMillis(),
            TimeUnit.MILLISECONDS.toNanos(200), TimeUnit.MILLISECONDS.toNanos(80), "test");
    }
}
