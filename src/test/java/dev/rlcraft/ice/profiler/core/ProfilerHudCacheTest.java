package dev.rlcraft.ice.profiler.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.config.IceConfig;
import dev.rlcraft.ice.profiler.capture.HitchTrigger;
import dev.rlcraft.ice.profiler.capture.TriggerType;
import dev.rlcraft.ice.profiler.jvm.JvmSnapshot;
import dev.rlcraft.ice.profiler.metrics.TimelinePoint;
import dev.rlcraft.ice.profiler.metrics.WorldGauge;
import dev.rlcraft.ice.profiler.probe.ProbeMetric;
import dev.rlcraft.ice.profiler.sampling.StackSample;
import dev.rlcraft.ice.profiler.sampling.StackTraceRepository;
import dev.rlcraft.ice.profiler.session.RecordingSession;
import dev.rlcraft.ice.profiler.session.ReportExporter;
import dev.rlcraft.ice.profiler.session.SessionManager;
import dev.rlcraft.ice.profiler.stats.DistributionSnapshot;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class ProfilerHudCacheTest {
    @Test
    public void reusesTextUntilMetricsOrRecordingStateChange() throws Exception {
        Constructor<ProfilerRuntime> constructor = ProfilerRuntime.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        ProfilerRuntime runtime = constructor.newInstance();
        SessionManager sessions = new SessionManager(new StackTraceRepository(32, 16), new ReportExporter() {
            @Override public File export(RecordingSession session) { return null; }
        });
        try {
            set(runtime, "sessions", sessions);
            set(runtime, "initialized", Boolean.TRUE);
            assertEquals("", runtime.getHudLine());
            sessions.start(true, "test");
            set(runtime, "latest", point(8.0D));
            String recording = runtime.getHudLine();
            assertTrue(recording.contains("REC"));
            assertTrue(recording.contains("8.0ms"));
            for (int index = 0; index < 200; index++) assertSame(recording, runtime.getHudLine());
            long now = System.nanoTime();
            sessions.trigger(new HitchTrigger(TriggerType.CLIENT_FRAME, now, System.currentTimeMillis(),
                200_000_000L, 80_000_000L, "test"), Collections.<StackSample>emptyList());
            String capture = runtime.getHudLine();
            assertTrue(capture.contains("CAPTURE"));
            assertNotSame(recording, capture);
            sessions.pollCompleted(now + TimeUnit.SECONDS.toNanos(IceConfig.capture.postCaptureSeconds) + 1L);
            assertTrue(runtime.getHudLine().contains("REC"));
            set(runtime, "latest", point(16.0D));
            assertTrue(runtime.getHudLine().contains("16.0ms"));
            set(runtime, "lastCompletedDiagnosis", "test diagnosis");
            sessions.stop("test", false);
            assertTrue(runtime.getHudLine().contains("PASSIVE"));
            set(runtime, "lastCompletedDiagnosis", "");
            assertEquals("", runtime.getHudLine());
        } finally {
            sessions.shutdown();
        }
    }

    private static void set(ProfilerRuntime runtime, String name, Object value) throws Exception {
        Field field = ProfilerRuntime.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(runtime, value);
    }

    private static TimelinePoint point(double p95) {
        DistributionSnapshot frames = new DistributionSnapshot(100, p95, p95, p95, p95, p95);
        return new TimelinePoint(1L, 1L, frames, DistributionSnapshot.EMPTY, DistributionSnapshot.EMPTY,
            100, 0, 0, 0.0D, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
            Collections.<WorldGauge>emptyList(), new JvmSnapshot(0L, 0L, 0L, 0L, 0L, 0.0D),
            Collections.<ProbeMetric>emptyList());
    }
}
