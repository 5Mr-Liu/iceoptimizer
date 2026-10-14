package dev.rlcraft.ice.profiler.report;

import static org.junit.Assert.*;

import com.google.gson.JsonArray;
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
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MixedRoleCaptureExportTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void splitRolesRoundTripV5WithUniqueSequencesAnchorsAndOneCopyOfSamples() throws Exception {
        StackTraceRepository stacks = new StackTraceRepository(8, 8);
        int client = stacks.intern(trace("net.minecraft.client.renderer.EntityRenderer", "renderWorld"));
        int server = stacks.intern(trace("net.minecraft.world.chunk.storage.AnvilChunkLoader", "func_75820_a"));
        RecordingSession session = new RecordingSession("mixed", "test", true, stacks, new ModResolver());
        long start = System.nanoTime();
        long clientAnchor = start + 1_000_000L;
        long serverAnchor = start + 200_000_000L;
        session.trigger(new HitchTrigger(TriggerType.CLIENT_FRAME, clientAnchor, 1_001L, 200_000_000L, 1L, "client"),
            Collections.singletonList(sample(start, ThreadRole.CLIENT_MAIN, client)));
        session.onSample(sample(start + 150_000_000L, ThreadRole.SERVER_MAIN, server));
        session.trigger(new HitchTrigger(TriggerType.SERVER_TICK, serverAnchor, 1_200L, 100_000_000L, 1L, "server"), Collections.<StackSample>emptyList());
        session.pollCompleted(serverAnchor + TimeUnit.SECONDS.toNanos(IceConfig.capture.postCaptureSeconds + 1L));
        session.finish("test");
        assertEquals(2, session.getClusters().size());
        assertEquals(2, session.getDetailedSampleCount());
        File report = new ReportWriter(temporary.newFolder("game")).export(session);
        JsonArray clusters = new JsonParser().parse(new String(Files.readAllBytes(new File(report, "hitches.json").toPath()),
            StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("clusters");
        Map<Long, String> roles = new HashMap<Long, String>();
        for (int i = 0; i < clusters.size(); i++) {
            JsonObject view = clusters.get(i).getAsJsonObject().getAsJsonArray("representatives").get(0).getAsJsonObject();
            assertEquals(1, view.get("sampleCount").getAsInt());
            assertEquals(1, view.getAsJsonArray("triggers").size());
            String type = view.getAsJsonArray("triggers").get(0).getAsJsonObject().get("type").getAsString();
            assertNull(roles.put(view.get("sequence").getAsLong(), type));
        }
        Set<Integer> referenced = new HashSet<Integer>();
        Set<Long> sequences = new HashSet<Long>();
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(new File(report, "mixed.icecap"))))) {
            assertEquals("ICECAP", in.readUTF()); assertEquals(5, in.readInt()); assertEquals("mixed", in.readUTF());
            in.readLong(); in.readLong(); assertEquals(0, in.readInt()); // no timeline
            assertEquals(2, in.readInt());
            int samples = 0;
            for (int c = 0; c < 2; c++) {
                for (int f = 0; f < 4; f++) in.readUTF();
                in.readDouble(); in.readLong(); in.readDouble(); in.readDouble();
                assertEquals(1, in.readInt());
                long sequence = in.readLong(); assertTrue(sequences.add(sequence));
                in.readLong(); long anchor = in.readLong(); in.readLong();
                String type = roles.get(sequence); assertNotNull(type);
                assertEquals(type.equals("SERVER_TICK") ? serverAnchor : clientAnchor, anchor);
                assertEquals(1, in.readInt()); assertEquals(0, in.readInt());
                in.readLong(); in.readLong(); in.readBoolean();
                int count = in.readInt(); assertEquals(1, count); samples += count;
                for (int s = 0; s < count; s++) {
                    in.readLong(); in.readLong(); in.readUTF();
                    int role = in.readByte();
                    assertEquals(type.equals("SERVER_TICK") ? ThreadRole.SERVER_MAIN.ordinal() : ThreadRole.CLIENT_MAIN.ordinal(), role);
                    referenced.add(in.readInt()); in.readLong(); in.readLong(); in.readByte();
                }
            }
            assertEquals(2, samples); assertEquals(roles.keySet(), sequences);
            int count = in.readInt(); assertEquals(referenced.size(), count);
            Set<Integer> serialized = new HashSet<Integer>();
            for (int s = 0; s < count; s++) {
                serialized.add(in.readInt()); int frames = in.readUnsignedShort();
                for (int f = 0; f < frames; f++) { in.readUTF(); in.readUTF(); in.readUTF(); in.readInt(); }
            }
            assertEquals(referenced, serialized); assertEquals(-1, in.read());
        }
    }
    private static StackSample sample(long time, ThreadRole role, int stack) {
        return new StackSample(time, role.ordinal() + 1L, role.name(), role, stack, 1L, 0L, Thread.State.RUNNABLE);
    }
    private static StackTraceElement[] trace(String owner, String method) {
        return new StackTraceElement[] {new StackTraceElement(owner, method, "Test.java", 1)};
    }
}
