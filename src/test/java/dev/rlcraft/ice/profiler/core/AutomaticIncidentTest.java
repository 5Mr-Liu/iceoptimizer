package dev.rlcraft.ice.profiler.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.config.IceConfig;
import dev.rlcraft.ice.profiler.analysis.ModResolver;
import dev.rlcraft.ice.profiler.capture.HitchTrigger;
import dev.rlcraft.ice.profiler.capture.TriggerType;
import dev.rlcraft.ice.profiler.sampling.StackTraceRepository;
import dev.rlcraft.ice.profiler.session.RecordingSession;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class AutomaticIncidentTest {
    @Test
    public void exportsOnlyAfterPostWindowAndQuietPeriod() {
        int oldPost = IceConfig.capture.postCaptureSeconds;
        int oldQuiet = IceConfig.capture.automaticExportQuietSeconds;
        boolean oldAutomatic = IceConfig.capture.automaticIncidentSessions;
        try {
            IceConfig.capture.postCaptureSeconds = 1;
            IceConfig.capture.automaticExportQuietSeconds = 2;
            IceConfig.capture.automaticIncidentSessions = true;
            long triggerNanos = System.nanoTime();
            RecordingSession session = new RecordingSession("incident", "test", false,
                new StackTraceRepository(16, 8), new ModResolver(), System.currentTimeMillis() - 15000L,
                triggerNanos - TimeUnit.SECONDS.toNanos(15));
            session.trigger(new HitchTrigger(TriggerType.CLIENT_FRAME, triggerNanos, System.currentTimeMillis(),
                TimeUnit.MILLISECONDS.toNanos(120), TimeUnit.MILLISECONDS.toNanos(80), "test"), Collections.emptyList());
            assertFalse(ProfilerRuntime.shouldAutoExportIncident(session, triggerNanos + TimeUnit.SECONDS.toNanos(2)));
            session.pollCompleted(triggerNanos + TimeUnit.SECONDS.toNanos(2));
            assertFalse(ProfilerRuntime.shouldAutoExportIncident(session, triggerNanos + TimeUnit.MILLISECONDS.toNanos(2999)));
            assertTrue(ProfilerRuntime.shouldAutoExportIncident(session, triggerNanos + TimeUnit.SECONDS.toNanos(3)));
        } finally {
            IceConfig.capture.postCaptureSeconds = oldPost;
            IceConfig.capture.automaticExportQuietSeconds = oldQuiet;
            IceConfig.capture.automaticIncidentSessions = oldAutomatic;
        }
    }
}
