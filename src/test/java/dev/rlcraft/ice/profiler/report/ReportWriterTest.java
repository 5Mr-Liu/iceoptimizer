package dev.rlcraft.ice.profiler.report;

import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.config.IceConfig;
import dev.rlcraft.ice.profiler.analysis.ModResolver;
import dev.rlcraft.ice.profiler.capture.HitchTrigger;
import dev.rlcraft.ice.profiler.capture.TriggerType;
import dev.rlcraft.ice.profiler.jvm.JvmSnapshot;
import dev.rlcraft.ice.profiler.metrics.TimelinePoint;
import dev.rlcraft.ice.profiler.metrics.WorldGauge;
import dev.rlcraft.ice.profiler.sampling.StackSample;
import dev.rlcraft.ice.profiler.sampling.StackTraceRepository;
import dev.rlcraft.ice.profiler.sampling.ThreadRole;
import dev.rlcraft.ice.profiler.session.RecordingSession;
import dev.rlcraft.ice.profiler.stats.DistributionSnapshot;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ReportWriterTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void writesCompleteBoundedReportBundle() throws Exception {
        boolean oldZip = IceConfig.reports.zip;
        boolean oldHtml = IceConfig.reports.html;
        try {
            IceConfig.reports.zip = true;
            IceConfig.reports.html = true;
            StackTraceRepository stacks = new StackTraceRepository(32, 16);
            int stackId = stacks.intern(new StackTraceElement[] {
                new StackTraceElement("example.mod.WorldGenerator", "generateChunk", "WorldGenerator.java", 42)
            });
            RecordingSession session = new RecordingSession("test-session", "测试", true, stacks, new ModResolver());
            DistributionSnapshot frames = new DistributionSnapshot(60, 16.0D, 15.0D, 22.0D, 30.0D, 100.0D);
            session.addTimeline(new TimelinePoint(System.currentTimeMillis(), 1000L, frames, DistributionSnapshot.EMPTY,
                new DistributionSnapshot(20, 20.0D, 20.0D, 45.0D, 80.0D, 120.0D), 60, 4, 1, 8.5D,
                2, 1, 1, 1, 3, 2, 100, 80,
                Arrays.asList(new WorldGauge(0, 300, 900, 120)), new JvmSnapshot(512L << 20, 1024L << 20, 2048L << 20, 1, 40, 0.5D),
                Collections.emptyList()));
            long now = System.nanoTime();
            StackSample sample = new StackSample(now, 1L, "Server thread", ThreadRole.SERVER_MAIN, stackId, 10L, 20L, Thread.State.RUNNABLE);
            session.trigger(new HitchTrigger(TriggerType.SERVER_TICK, now, System.currentTimeMillis(), TimeUnit.MILLISECONDS.toNanos(120), TimeUnit.MILLISECONDS.toNanos(75), "test"), Collections.singletonList(sample));
            session.pollCompleted(now + TimeUnit.SECONDS.toNanos(IceConfig.capture.postCaptureSeconds + 1L));
            session.finish("test");

            File root = temporary.newFolder("game");
            ReportWriter writer = new ReportWriter(root);
            File report = writer.export(session);
            assertTrue(new File(report, "summary.txt").isFile());
            assertTrue(new File(report, "timeline.csv").isFile());
            assertTrue(new File(report, "hitches.json").isFile());
            assertTrue(new File(report, "stacks.folded").isFile());
            assertTrue(new File(report, "probes.csv").isFile());
            assertTrue(new File(report, "report.html").isFile());
            assertTrue(new File(report, "test-session.icecap").isFile());
            assertTrue(new File(report.getParentFile(), report.getName() + ".zip").isFile());
            String summary = new String(Files.readAllBytes(new File(report, "summary.txt").toPath()), StandardCharsets.UTF_8);
            assertTrue(summary.contains("世界生成"));
            assertTrue(summary.contains("WorldGenerator.generateChunk"));
        } finally {
            IceConfig.reports.zip = oldZip;
            IceConfig.reports.html = oldHtml;
        }
    }
}
