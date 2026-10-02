package dev.rlcraft.ice.profiler.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.config.IceConfig;
import dev.rlcraft.ice.profiler.analysis.ModResolver;
import dev.rlcraft.ice.profiler.capture.HitchTrigger;
import dev.rlcraft.ice.profiler.capture.TriggerType;
import dev.rlcraft.ice.profiler.sampling.StackSample;
import dev.rlcraft.ice.profiler.sampling.StackTraceRepository;
import dev.rlcraft.ice.profiler.sampling.ThreadRole;
import dev.rlcraft.ice.profiler.session.RecordingSession;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class PrefixStackExportTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void signedPrefixIdsResolveToTruncatedRecordsInV4AndFoldedExport()
        throws Exception {
        StackTraceRepository stacks = new StackTraceRepository(1, 16);
        int exact = stacks.intern(trace("old.WorldSelection", "loadWorld"));
        RecordingSession session = new RecordingSession("prefix-session", "",
            true, stacks, new ModResolver());
        int prefix = stacks.intern(trace("current.HeadRenderer", "render"));
        assertTrue(prefix < 0);
        long now = System.nanoTime();
        session.trigger(new HitchTrigger(TriggerType.CLIENT_FRAME, now,
            System.currentTimeMillis(), TimeUnit.MILLISECONDS.toNanos(120),
            TimeUnit.MILLISECONDS.toNanos(75), "test"), Arrays.asList(
                sample(now - 1L, prefix), sample(now - 2L, prefix),
                sample(now - 3L, exact)));
        session.pollCompleted(now + TimeUnit.SECONDS.toNanos(
            IceConfig.capture.postCaptureSeconds + 1L));
        session.finish("test");
        File report = new ReportWriter(temporary.newFolder("game"))
            .export(session);
        String folded = text(new File(report, "stacks.folded"));
        boolean prefixLineFound = false;
        for (String line : folded.split("\\r?\\n")) {
            if (!line.contains("ice.profiler.truncatedCaller")) continue;
            prefixLineFound = true;
            assertTrue(line.startsWith("CLIENT_MAIN;"));
            assertFalse(line.contains("old.WorldSelection"));
            assertFalse(line.contains("current.HeadRenderer"));
        }
        assertTrue(prefixLineFound);
        assertTrue(folded.contains("old.WorldSelection.loadWorld"));
        assertTrue(text(new File(report, "summary.txt"))
            .contains("顶部 3 个方法"));

        DataInputStream input = new DataInputStream(new BufferedInputStream(
            new GZIPInputStream(new FileInputStream(
                new File(report, "prefix-session.icecap")))));
        try {
            assertEquals("ICECAP", input.readUTF());
            assertEquals(4, input.readInt());
            assertEquals("prefix-session", input.readUTF());
            input.readLong();
            input.readLong();
            assertEquals(0, input.readInt()); // no timeline records
            Set<Integer> referenced = readSamples(input);
            assertEquals(new HashSet<Integer>(Arrays.asList(exact, prefix)),
                referenced);
            int recordCount = input.readInt();
            assertEquals(referenced.size(), recordCount);
            Set<Integer> records = new HashSet<Integer>();
            for (int record = 0; record < recordCount; record++) {
                int id = input.readInt();
                records.add(id);
                int length = input.readUnsignedShort();
                assertEquals(id == prefix ? 4 : 5, length);
                for (int frame = 0; frame < length; frame++) {
                    String owner = input.readUTF();
                    String method = input.readUTF();
                    String file = input.readUTF();
                    int line = input.readInt();
                    if (id == prefix) {
                        assertFalse(owner.equals("old.WorldSelection"));
                        assertFalse(owner.equals("current.HeadRenderer"));
                        assertEquals("", file);
                        assertEquals(-1, line);
                        if (frame == 3) {
                            assertEquals("ice.profiler", owner);
                            assertEquals("truncatedCaller", method);
                        }
                    }
                }
            }
            assertEquals(referenced, records);
            assertEquals(-1, input.read());
        } finally {
            input.close();
        }
        assertEquals(2, stacks.size());
    }

    private static Set<Integer> readSamples(DataInputStream input)
        throws Exception {
        Set<Integer> result = new HashSet<Integer>();
        int clusters = input.readInt();
        assertTrue(clusters > 0);
        for (int cluster = 0; cluster < clusters; cluster++) {
            for (int field = 0; field < 4; field++) input.readUTF();
            input.readDouble();
            input.readLong();
            input.readDouble();
            input.readDouble();
            int representatives = input.readInt();
            for (int representative = 0; representative < representatives;
                 representative++) {
                for (int field = 0; field < 4; field++) input.readLong();
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
                    result.add(input.readInt());
                    input.readLong();
                    input.readLong();
                    input.readByte();
                }
            }
        }
        return result;
    }

    private static StackSample sample(long timestamp, int stack) {
        return new StackSample(timestamp, 1L, "Client thread",
            ThreadRole.CLIENT_MAIN, stack, 1L, 0L, Thread.State.RUNNABLE);
    }

    private static StackTraceElement[] trace(String caller, String method) {
        return new StackTraceElement[] {
            frame("java.net.SocketInputStream", "socketRead0"),
            frame("java.net.SocketInputStream", "socketRead"),
            frame("java.net.SocketInputStream", "read"),
            frame("com.mojang.authlib.HttpAuthenticationService", "performGetRequest"),
            frame(caller, method)
        };
    }

    private static StackTraceElement frame(String owner, String method) {
        return new StackTraceElement(owner, method, "Source.java", 42);
    }

    private static String text(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()),
            StandardCharsets.UTF_8);
    }
}
