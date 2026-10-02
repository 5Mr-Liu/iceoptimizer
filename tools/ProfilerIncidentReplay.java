package dev.rlcraft.ice.profiler.capture;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.rlcraft.ice.profiler.analysis.Diagnosis;
import dev.rlcraft.ice.profiler.analysis.ModResolver;
import dev.rlcraft.ice.profiler.analysis.RootCauseAnalyzer;
import dev.rlcraft.ice.profiler.sampling.StackSample;
import dev.rlcraft.ice.profiler.sampling.StackTraceRepository;
import dev.rlcraft.ice.profiler.sampling.ThreadRole;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Offline replay of strict ICECAP-v4 parsed fixtures; never opens or modifies game data. */
public final class ProfilerIncidentReplay {
    public static void main(String[] args) throws Exception {
        JsonArray sessions;
        try (Reader in = Files.newBufferedReader(Paths.get(args[0]), StandardCharsets.UTF_8)) {
            sessions = new JsonParser().parse(in).getAsJsonArray();
        }
        List<Object> output = new ArrayList<Object>();
        for (JsonElement sessionElement : sessions) {
            JsonObject session = sessionElement.getAsJsonObject();
            StackTraceRepository stacks = new StackTraceRepository(50_000, 512);
            Map<Integer, Integer> ids = new HashMap<Integer, Integer>();
            for (Map.Entry<String, JsonElement> entry : session.getAsJsonObject("stacks").entrySet()) {
                JsonArray input = entry.getValue().getAsJsonArray();
                StackTraceElement[] trace = new StackTraceElement[input.size()];
                for (int i = 0; i < trace.length; i++) {
                    JsonObject frame = input.get(i).getAsJsonObject();
                    trace[i] = new StackTraceElement(frame.get("cls").getAsString(), frame.get("method").getAsString(),
                        frame.get("file").getAsString(), frame.get("line").getAsInt());
                }
                ids.put(Integer.valueOf(entry.getKey()), stacks.intern(trace));
            }
            RootCauseAnalyzer analyzer = new RootCauseAnalyzer(stacks, new ModResolver());
            List<Object> captures = new ArrayList<Object>();
            List<Object> events = new ArrayList<Object>();
            for (JsonElement captureElement : session.getAsJsonArray("captures")) {
                JsonObject input = captureElement.getAsJsonObject();
                HitchCapture source = restore(input, ids);
                HitchClusterer clusterer = new HitchClusterer(analyzer, 16, 4, 100_000);
                clusterer.add(source);
                List<Object> diagnoses = new ArrayList<Object>();
                int kept = 0;
                for (HitchCluster cluster : clusterer.snapshot()) {
                    HitchCapture view = cluster.getRepresentatives().get(0);
                    kept += view.getSamples().size();
                    TriggerIntervals intervals = new TriggerIntervals(TriggerIntervals.groupOf(view.getTriggers().get(0)), view.getTriggers());
                    int actual = 0;
                    for (StackSample sample : view.getSamples()) if (intervals.matches(sample)) actual++;
                    Map<String, Object> row = diagnosis(cluster.getDiagnosis());
                    row.put("sequence", view.getSequence());
                    row.put("group", intervals.getGroup().name());
                    row.put("signals", view.getTriggers().size());
                    row.put("actualSamples", actual);
                    row.put("missingIntervals", intervals.missingTriggerCount(view.getSamples()));
                    row.put("retainedSamples", view.getSamples().size());
                    row.put("maxDurationMs", cluster.getMaximumDurationMs());
                    diagnoses.add(row);
                }
                if (kept != source.getSamples().size() || kept != clusterer.getDetailedSamples()) {
                    throw new AssertionError("Partition changed sample budget: " + source.getSequence());
                }
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("sourceSequence", source.getSequence());
                row.put("oldCategory", input.get("oldCategory").getAsString());
                row.put("oldMethod", input.get("oldMethod").getAsString());
                row.put("originalSamples", source.getSamples().size());
                row.put("newDiagnoses", diagnoses);
                captures.add(row);
                for (HitchTrigger trigger : source.getTriggers()) {
                    TriggerIntervals intervals = new TriggerIntervals(TriggerIntervals.groupOf(trigger), Collections.singletonList(trigger));
                    List<StackSample> selected = new ArrayList<StackSample>();
                    for (StackSample sample : source.getSamples()) if (intervals.matches(sample)) selected.add(sample);
                    HitchCapture single = raw(source.getSequence(), source.getStartedNanos(), trigger.getTimestampNanos(), source.getEndNanos(),
                        Collections.singletonList(trigger), selected);
                    Map<String, Object> event = diagnosis(analyzer.analyze(single));
                    event.put("type", trigger.getType().name());
                    event.put("epochMs", trigger.getEpochMillis());
                    event.put("durationMs", trigger.getDurationMillis());
                    event.put("actualSamples", selected.size());
                    events.add(event);
                }
            }
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("session", session.get("id").getAsString());
            result.put("captures", captures);
            result.put("events", events);
            output.add(result);
        }
        try (Writer out = Files.newBufferedWriter(Paths.get(args[1]), StandardCharsets.UTF_8)) {
            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            gson.toJson(output, out);
        }
        System.out.println("Replayed " + output.size() + " sessions; original samples preserved, partition budget exact.");
    }

    private static Map<String, Object> diagnosis(Diagnosis value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("cause", value.getRootCause().name());
        result.put("mod", value.getMod().getId());
        result.put("method", value.getHotMethod());
        result.put("confidence", value.getConfidence());
        result.put("evidence", value.getEvidence());
        return result;
    }

    private static HitchCapture restore(JsonObject input, Map<Integer, Integer> ids) throws Exception {
        List<StackSample> samples = new ArrayList<StackSample>();
        for (JsonElement element : input.getAsJsonArray("samples")) {
            JsonObject s = element.getAsJsonObject();
            samples.add(new StackSample(s.get("ns").getAsLong(), s.get("tid").getAsLong(), s.get("thread").getAsString(),
                ThreadRole.valueOf(s.get("role").getAsString()), ids.get(s.get("stackId").getAsInt()),
                s.get("cpuDelta").getAsLong(), s.get("allocDelta").getAsLong(), Thread.State.valueOf(s.get("state").getAsString())));
        }
        List<HitchTrigger> triggers = new ArrayList<HitchTrigger>();
        double offset = input.get("offsetMs").getAsDouble();
        for (JsonElement element : input.getAsJsonArray("triggers")) {
            JsonObject t = element.getAsJsonObject();
            long ns = Math.round((t.get("epochMs").getAsLong() - offset) * 1e6);
            if (triggers.isEmpty()) ns = input.get("triggerNs").getAsLong();
            triggers.add(new HitchTrigger(TriggerType.valueOf(t.get("type").getAsString()), ns,
                t.get("epochMs").getAsLong(), Math.round(t.get("durationMs").getAsDouble() * 1e6), 0L, t.get("detail").getAsString()));
        }
        HitchCapture result = raw(input.get("sequence").getAsLong(), input.get("startedNs").getAsLong(),
            input.get("triggerNs").getAsLong(), input.get("endNs").getAsLong(), triggers, samples);
        set(result, "droppedPreSamples", input.get("droppedPre").getAsLong());
        set(result, "droppedPostSamples", input.get("droppedPost").getAsLong());
        set(result, "sampleLimitReached", input.get("droppedPre").getAsLong() + input.get("droppedPost").getAsLong() > 0L);
        return result;
    }

    // Restoration is fixture-only: bypass new retention so lost old samples are never invented.
    private static HitchCapture raw(long sequence, long start, long anchor, long end,
                                    List<HitchTrigger> triggers, List<StackSample> samples) throws Exception {
        HitchCapture capture = new HitchCapture(sequence, start, anchor, end);
        for (HitchTrigger trigger : triggers) capture.addTrigger(trigger);
        set(capture, "samples", Collections.unmodifiableList(samples));
        set(capture, "sealed", true);
        int pre = 0;
        long firstPre = Long.MAX_VALUE, lastPre = Long.MIN_VALUE, firstPost = Long.MAX_VALUE, lastPost = Long.MIN_VALUE;
        for (StackSample sample : samples) {
            long ns = sample.getTimestampNanos();
            if (ns <= anchor) { pre++; firstPre = Math.min(firstPre, ns); lastPre = Math.max(lastPre, ns); }
            else { firstPost = Math.min(firstPost, ns); lastPost = Math.max(lastPost, ns); }
        }
        set(capture, "retainedPreSamples", pre); set(capture, "retainedPostSamples", samples.size() - pre);
        set(capture, "firstPreSampleNanos", pre == 0 ? 0L : firstPre); set(capture, "lastPreSampleNanos", pre == 0 ? 0L : lastPre);
        set(capture, "firstPostSampleNanos", samples.size() == pre ? 0L : firstPost); set(capture, "lastPostSampleNanos", samples.size() == pre ? 0L : lastPost);
        return capture;
    }
    private static void set(HitchCapture capture, String name, Object value) throws Exception {
        Field field = HitchCapture.class.getDeclaredField(name); field.setAccessible(true); field.set(capture, value);
    }
}
