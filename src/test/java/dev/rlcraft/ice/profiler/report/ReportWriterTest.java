package dev.rlcraft.ice.profiler.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.config.IceConfig;
import dev.rlcraft.ice.profiler.analysis.ModResolver;
import dev.rlcraft.ice.profiler.capture.HitchTrigger;
import dev.rlcraft.ice.profiler.capture.TriggerType;
import dev.rlcraft.ice.profiler.jvm.JvmSnapshot;
import dev.rlcraft.ice.profiler.metrics.ChunkChurnDimensionSnapshot;
import dev.rlcraft.ice.profiler.metrics.ChunkChurnSnapshot;
import dev.rlcraft.ice.profiler.metrics.TimelinePoint;
import dev.rlcraft.ice.profiler.metrics.WorldGauge;
import dev.rlcraft.ice.profiler.probe.ProbeMetric;
import dev.rlcraft.ice.profiler.sampling.StackSample;
import dev.rlcraft.ice.profiler.sampling.StackTraceRepository;
import dev.rlcraft.ice.profiler.sampling.ThreadRole;
import dev.rlcraft.ice.profiler.session.RecordingSession;
import dev.rlcraft.ice.profiler.stats.DistributionSnapshot;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
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
            publishRendererDiagnostics(
                "ICE modern renderer diagnostics\nterrain_draw_arena_total=80\nterrain_draw_legacy=3\n",
                "Terrain U A/L 40/2 | D A/L 80/3");
            StackTraceRepository stacks = new StackTraceRepository(1, 16);
            int stackId = stacks.intern(new StackTraceElement[] {
                new StackTraceElement("example.mod.WorldGenerator", "generateChunk", "WorldGenerator.java", 42)
            });
            RecordingSession session = new RecordingSession("test-session", "测试", true, stacks, new ModResolver());
            for (int i = 0; i < 40; i++) {
                stacks.intern(new StackTraceElement[] {
                    new StackTraceElement("overflow.Hot" + i, "sample" + i,
                        "Hot.java", i)
                });
            }
            DistributionSnapshot frames = new DistributionSnapshot(60, 16.0D, 15.0D, 22.0D, 30.0D, 100.0D);
            ChunkChurnSnapshot churn = new ChunkChurnSnapshot(Arrays.asList(
                churnDimension(-1, 10L), churnDimension(0, 1L)), 5L, 9);
            session.addTimeline(new TimelinePoint(System.currentTimeMillis(), 1000L, frames, DistributionSnapshot.EMPTY,
                new DistributionSnapshot(20, 20.0D, 20.0D, 45.0D, 80.0D, 120.0D), 60, 4, 1, 8.5D,
                2, 1, 1, 1, 3, 2, 100, 80,
                Arrays.asList(new WorldGauge(0, 300, 900, 120)), new JvmSnapshot(512L << 20, 1024L << 20, 2048L << 20, 1, 40, 0.5D),
                Collections.singletonList(new ProbeMetric(1,
                    "example,\"quoted\"", 2L, 3L, 2L)), churn));
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
            assertTrue(new File(report, "optimizer-renderer.txt").isFile());
            assertTrue(new File(report, "report.html").isFile());
            assertTrue(new File(report, "test-session.icecap").isFile());
            assertTrue(new File(report.getParentFile(), report.getName() + ".zip").isFile());
            String summary = new String(Files.readAllBytes(new File(report, "summary.txt").toPath()), StandardCharsets.UTF_8);
            assertTrue(summary.contains("世界生成"));
            assertTrue(summary.contains("WorldGenerator.generateChunk"));
            assertTrue(summary.contains("Terrain U A/L 40/2 | D A/L 80/3"));
            assertTrue(summary.contains("栈字典容量后样本：40，前缀合并：0，无法合并：40"));
            assertTrue(summary.contains("无法合并栈的有界热点摘要"));
            assertTrue(summary.contains("维度 -1（下界）：data/no-data=10/20，"
                + "reload=30/40/50，short-unload=60/70/80"));
            String properties = new String(Files.readAllBytes(
                new File(report, "session.properties").toPath()),
                StandardCharsets.UTF_8);
            assertTrue(properties.contains("dictionaryOverflow=40"));
            assertTrue(properties.contains("dictionaryPrefixMerged=0"));
            assertTrue(properties.contains("dictionaryDropped=40"));
            assertTrue(properties.contains("chunkDataBackedLoads=11"));
            assertTrue(properties.contains("chunkChurnStateEvictions=5"));
            assertTrue(properties.contains(
                "chunkChurnDimensions=-1\\:10/20/30/40/50/60/70/80;0\\:1/2/3/4/5/6/7/8"));
            String renderer = new String(Files.readAllBytes(
                new File(report, "optimizer-renderer.txt").toPath()),
                StandardCharsets.UTF_8);
            assertTrue(renderer.contains("terrain_draw_arena_total=80"));
            assertTimelineCsv(new File(report, "timeline.csv"));
            assertProbeCsv(new File(report, "probes.csv"));
            assertIcecapV4(new File(report, "test-session.icecap"));
            assertZipMatchesDirectory(report);
        } finally {
            resetRendererDiagnostics();
            IceConfig.reports.zip = oldZip;
            IceConfig.reports.html = oldHtml;
        }
    }

    @Test
    public void missingRendererDiagnosticsAndRetentionAreExplicit()
        throws Exception {
        boolean oldZip = IceConfig.reports.zip;
        boolean oldHtml = IceConfig.reports.html;
        int oldMaxSessions = IceConfig.reports.maxSessions;
        try {
            resetRendererDiagnostics();
            IceConfig.reports.zip = true;
            IceConfig.reports.html = false;
            IceConfig.reports.maxSessions = 1;
            File root = temporary.newFolder("retention-game");
            ReportWriter writer = new ReportWriter(root);
            File first = writer.export(finishedSession("20260101-000000-000"));
            File firstZip = new File(first.getParentFile(), first.getName() + ".zip");
            assertTrue(first.isDirectory());
            assertTrue(firstZip.isFile());

            File second = writer.export(finishedSession("20260102-000000-000"));
            assertFalse(first.exists());
            assertFalse(firstZip.exists());
            assertTrue(second.isDirectory());
            String diagnostics = new String(Files.readAllBytes(new File(second,
                "optimizer-renderer.txt").toPath()), StandardCharsets.UTF_8);
            assertTrue(diagnostics.contains(
                "optimizer_not_installed_or_not_yet_published=true"));
            assertEquals(1, writer.listReports().size());
        } finally {
            resetRendererDiagnostics();
            IceConfig.reports.zip = oldZip;
            IceConfig.reports.html = oldHtml;
            IceConfig.reports.maxSessions = oldMaxSessions;
        }
    }

    private static RecordingSession finishedSession(String id) {
        RecordingSession session = new RecordingSession(id, "", true,
            new StackTraceRepository(4, 4), new ModResolver());
        session.finish("test");
        return session;
    }

    private static ChunkChurnDimensionSnapshot churnDimension(int dimension,
                                                               long scale) {
        return new ChunkChurnDimensionSnapshot(dimension, scale, scale * 2L,
            scale * 3L, scale * 4L, scale * 5L, scale * 6L, scale * 7L,
            scale * 8L);
    }

    private static void assertTimelineCsv(File file) throws Exception {
        List<List<String>> records = parseCsv(new String(Files.readAllBytes(
            file.toPath()), StandardCharsets.UTF_8));
        assertEquals(2, records.size());
        assertEquals(42, records.get(0).size());
        assertEquals(records.get(0).size(), records.get(1).size());
        assertEquals("-1:10/20/30/40/50/60/70/80;0:1/2/3/4/5/6/7/8",
            records.get(1).get(41));
    }

    private static void assertProbeCsv(File file) throws Exception {
        List<List<String>> records = parseCsv(new String(Files.readAllBytes(
            file.toPath()), StandardCharsets.UTF_8));
        assertEquals(2, records.size());
        assertEquals(8, records.get(1).size());
        assertEquals("example,\"quoted\"", records.get(1).get(4));
    }

    private static List<List<String>> parseCsv(String source) {
        List<List<String>> records = new ArrayList<List<String>>();
        List<String> record = new ArrayList<String>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < source.length(); i++) {
            char value = source.charAt(i);
            if (quoted) {
                if (value == '"') {
                    if (i + 1 < source.length() && source.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else quoted = false;
                } else field.append(value);
            } else if (value == '"' && field.length() == 0) {
                quoted = true;
            } else if (value == ',') {
                record.add(field.toString());
                field.setLength(0);
            } else if (value == '\n' || value == '\r') {
                if (value == '\r' && i + 1 < source.length()
                    && source.charAt(i + 1) == '\n') i++;
                record.add(field.toString());
                field.setLength(0);
                records.add(record);
                record = new ArrayList<String>();
            } else field.append(value);
        }
        if (field.length() != 0 || !record.isEmpty()) {
            record.add(field.toString());
            records.add(record);
        }
        return records;
    }

    private static void assertIcecapV4(File file) throws Exception {
        DataInputStream input = new DataInputStream(new BufferedInputStream(
            new GZIPInputStream(new FileInputStream(file))));
        try {
            assertEquals("ICECAP", input.readUTF());
            assertEquals(4, input.readInt());
            assertEquals("test-session", input.readUTF());
            input.readLong();
            input.readLong();
            assertEquals(1, input.readInt());
            input.readLong();
            assertEquals(1000L, input.readLong());
            skipDistribution(input);
            skipDistribution(input);
            skipDistribution(input);
            assertEquals(60, input.readInt());
            assertEquals(4, input.readInt());
            assertEquals(1, input.readInt());
            assertEquals(8.5D, input.readDouble(), 0.0D);
            for (int i = 0; i < 8; i++) input.readLong();
            assertEquals(1, input.readInt());
            assertEquals(0, input.readInt());
            assertEquals(300, input.readInt());
            assertEquals(900, input.readInt());
            assertEquals(120, input.readInt());
            for (int i = 0; i < 5; i++) input.readLong();
            input.readDouble();
            assertEquals(1, input.readInt());
            assertEquals(1, input.readInt());
            assertEquals("example,\"quoted\"", input.readUTF());
            assertEquals(2L, input.readLong());
            assertEquals(3L, input.readLong());
            assertEquals(2L, input.readLong());

            assertEquals(2, input.readInt());
            assertChunkChurnDimension(input, -1, 10L);
            assertChunkChurnDimension(input, 0, 1L);
            assertEquals(5L, input.readLong());
            assertEquals(9, input.readInt());

            int clusters = input.readInt();
            assertEquals(1, clusters);
            Set<Integer> referenced = new HashSet<Integer>();
            for (int cluster = 0; cluster < clusters; cluster++) {
                input.readUTF();
                input.readUTF();
                input.readUTF();
                input.readUTF();
                input.readDouble();
                input.readLong();
                input.readDouble();
                input.readDouble();
                int representatives = input.readInt();
                assertTrue(representatives > 0);
                for (int representative = 0; representative < representatives;
                     representative++) {
                    input.readLong();
                    input.readLong();
                    input.readLong();
                    input.readLong();
                    input.readInt();
                    input.readInt();
                    input.readLong();
                    input.readLong();
                    input.readBoolean();
                    int samples = input.readInt();
                    for (int sample = 0; sample < samples; sample++) {
                        input.readLong();
                        input.readLong();
                        input.readUTF();
                        input.readByte();
                        referenced.add(Integer.valueOf(input.readInt()));
                        input.readLong();
                        input.readLong();
                        input.readByte();
                    }
                }
            }
            int stackCount = input.readInt();
            assertEquals(referenced.size(), stackCount);
            Set<Integer> serialized = new HashSet<Integer>();
            for (int stack = 0; stack < stackCount; stack++) {
                serialized.add(Integer.valueOf(input.readInt()));
                int frames = input.readUnsignedShort();
                for (int frame = 0; frame < frames; frame++) {
                    input.readUTF();
                    input.readUTF();
                    input.readUTF();
                    input.readInt();
                }
            }
            assertEquals(referenced, serialized);
            assertEquals(-1, input.read());
        } finally {
            input.close();
        }
    }

    private static void skipDistribution(DataInputStream input)
        throws Exception {
        input.readLong();
        for (int i = 0; i < 5; i++) input.readDouble();
    }

    private static void assertChunkChurnDimension(DataInputStream input,
                                                  int dimension, long scale)
        throws Exception {
        assertEquals(dimension, input.readInt());
        for (int multiplier = 1; multiplier <= 8; multiplier++) {
            assertEquals(scale * multiplier, input.readLong());
        }
    }

    private static void assertZipMatchesDirectory(File report)
        throws Exception {
        Set<String> expected = new HashSet<String>();
        File[] files = report.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    expected.add(report.getName() + "/" + file.getName());
                }
            }
        }
        Set<String> actual = new HashSet<String>();
        ZipFile zip = new ZipFile(new File(report.getParentFile(),
            report.getName() + ".zip"));
        try {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                assertFalse(entry.isDirectory());
                actual.add(entry.getName());
            }
        } finally {
            zip.close();
        }
        assertEquals(expected, actual);
    }

    private static void publishRendererDiagnostics(String report, String summary)
        throws Exception {
        Class<?> diagnostics = Class.forName(
            "dev.rlcraft.ice.optimizer.client.ModernRendererDiagnostics");
        java.lang.reflect.Method publish = diagnostics.getDeclaredMethod(
            "publish", String.class, String.class);
        publish.setAccessible(true);
        publish.invoke(null, report, summary);
    }

    private static void resetRendererDiagnostics() {
        try {
            Class<?> diagnostics = Class.forName(
                "dev.rlcraft.ice.optimizer.client.ModernRendererDiagnostics");
            java.lang.reflect.Method reset = diagnostics.getDeclaredMethod(
                "resetForTest");
            reset.setAccessible(true);
            reset.invoke(null);
        } catch (Throwable ignored) {
            // Test cleanup must not mask the assertion or report failure.
        }
    }
}
