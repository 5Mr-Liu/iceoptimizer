package dev.rlcraft.ice.profiler.report;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.rlcraft.ice.config.IceConfig;
import dev.rlcraft.ice.profiler.analysis.ModResolver;
import dev.rlcraft.ice.profiler.capture.HitchTrigger;
import dev.rlcraft.ice.profiler.capture.TriggerType;
import dev.rlcraft.ice.profiler.sampling.StackSample;
import dev.rlcraft.ice.profiler.sampling.StackTraceRepository;
import dev.rlcraft.ice.profiler.sampling.ThreadRole;
import dev.rlcraft.ice.profiler.session.RecordingSession;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class HitchClusterDiagnosisExportTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void sameHotspotUsesLargestPeakDiagnosisInSummaryAndJson() throws Exception {
        StackTraceRepository stacks = new StackTraceRepository(8, 8);
        int save = stacks.intern(new StackTraceElement[] {
            new StackTraceElement("net.minecraft.world.chunk.storage.AnvilChunkLoader", "func_75820_a", "AnvilChunkLoader.java", 325)
        });
        RecordingSession session = new RecordingSession("cluster-evidence", "test", true, stacks, new ModResolver());
        long start = System.nanoTime();
        long post = TimeUnit.SECONDS.toNanos(IceConfig.capture.postCaptureSeconds + 1L);
        session.trigger(trigger(start, 100_000_000L), Collections.singletonList(sample(start - 1L, save)));
        session.pollCompleted(start + post);
        assertFalse(session.getClusters().get(0).getDiagnosis().getEvidence().toString().contains("不能解释该峰值"));

        long largest = start + TimeUnit.SECONDS.toNanos(20L);
        session.trigger(trigger(largest, 800_000_000L), Collections.<StackSample>emptyList());
        long smaller = largest + 1_000_000_000L;
        session.onSample(sample(smaller - 1L, save));
        session.trigger(trigger(smaller, 100_000_000L), Collections.<StackSample>emptyList());
        session.pollCompleted(smaller + post);
        assertEquals(1, session.getClusters().size());
        assertEquals(2L, session.getClusters().get(0).getOccurrences());
        assertEquals(800.0D, session.getClusters().get(0).getMaximumDurationMs(), 0.0D);
        assertTrue(session.getClusters().get(0).getDiagnosis().getEvidence().toString().contains("不能解释该峰值"));

        // A later complete but smaller event must not replace the largest event's warning.
        long last = start + TimeUnit.SECONDS.toNanos(40L);
        session.trigger(trigger(last, 150_000_000L), Collections.singletonList(sample(last - 1L, save)));
        session.pollCompleted(last + post);
        session.finish("test");
        assertEquals(3, session.getDetailedSampleCount());
        File report = new ReportWriter(temporary.newFolder("game")).export(session);
        String summary = text(new File(report, "summary.txt"));
        assertTrue(summary.contains("当前归因来自其他触发区间"));
        assertTrue(summary.contains("不能解释该峰值"));
        JsonObject cluster = new JsonParser().parse(text(new File(report, "hitches.json")))
            .getAsJsonObject().getAsJsonArray("clusters").get(0).getAsJsonObject();
        assertEquals(800.0D, cluster.get("maximumMs").getAsDouble(), 0.0D);
        assertTrue(cluster.get("evidence").toString().contains("不能解释该峰值"));
        assertEquals(3L, cluster.get("occurrences").getAsLong());
    }

    private static HitchTrigger trigger(long end, long duration) {
        return new HitchTrigger(TriggerType.SERVER_TICK, end, System.currentTimeMillis(), duration, 1L, "test");
    }
    private static StackSample sample(long time, int stack) {
        return new StackSample(time, 1L, "Server thread", ThreadRole.SERVER_MAIN, stack, 1L, 0L, Thread.State.RUNNABLE);
    }
    private static String text(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
