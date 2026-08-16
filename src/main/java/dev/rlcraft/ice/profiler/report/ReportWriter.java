package dev.rlcraft.ice.profiler.report;

import dev.rlcraft.ice.IceProfilerMod;
import dev.rlcraft.ice.config.IceConfig;
import dev.rlcraft.ice.profiler.analysis.Diagnosis;
import dev.rlcraft.ice.profiler.capture.HitchCapture;
import dev.rlcraft.ice.profiler.capture.HitchCluster;
import dev.rlcraft.ice.profiler.capture.HitchTrigger;
import dev.rlcraft.ice.profiler.jvm.JvmSnapshot;
import dev.rlcraft.ice.profiler.metrics.TimelinePoint;
import dev.rlcraft.ice.profiler.metrics.WorldGauge;
import dev.rlcraft.ice.profiler.sampling.StackSample;
import dev.rlcraft.ice.profiler.sampling.StackSampleFilter;
import dev.rlcraft.ice.profiler.probe.ProbeMetric;
import dev.rlcraft.ice.profiler.session.RecordingSession;
import dev.rlcraft.ice.profiler.session.ReportExporter;
import dev.rlcraft.ice.profiler.session.SessionMarker;
import dev.rlcraft.ice.profiler.stats.DistributionSnapshot;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ReportWriter implements ReportExporter {
    private static final int CAPTURE_VERSION = 3;
    private final File reportsRoot;

    public ReportWriter(File gameDirectory) {
        this.reportsRoot = new File(new File(gameDirectory, "ice-profiler"), "sessions");
    }

    @Override
    public synchronized File export(RecordingSession session) throws IOException {
        ensureDirectory(reportsRoot);
        File finalDirectory = uniqueDirectory(session.getId());
        File temporary = new File(reportsRoot, "." + finalDirectory.getName() + ".writing");
        if (temporary.exists()) deleteRecursivelySafe(temporary);
        ensureDirectory(temporary);
        try {
            writeSummary(session, new File(temporary, "summary.txt"));
            writeMetadata(session, new File(temporary, "session.properties"));
            if (IceConfig.reports.timelineCsv) writeTimeline(session, new File(temporary, "timeline.csv"));
            writeProbes(session, new File(temporary, "probes.csv"));
            writeHitchesJson(session, new File(temporary, "hitches.json"), false);
            writeFoldedStacks(session, new File(temporary, "stacks.folded"));
            writeBinaryCapture(session, new File(temporary, session.getId() + ".icecap"));
            if (IceConfig.reports.detailedJson && directorySize(temporary) < softLimitBytes()) {
                File detailed = new File(temporary, "hitches-detailed.json");
                writeHitchesJson(session, detailed, true);
                if (directorySize(temporary) > softLimitBytes()) Files.deleteIfExists(detailed.toPath());
            }
            if (IceConfig.reports.html) writeHtml(session, new File(temporary, "report.html"));
            Files.move(temporary.toPath(), finalDirectory.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), finalDirectory.toPath());
        } catch (IOException error) {
            deleteRecursivelySafe(temporary);
            throw error;
        }
        if (IceConfig.reports.zip) writeZip(finalDirectory);
        enforceRetention();
        return finalDirectory;
    }

    public synchronized List<File> listReports() {
        if (!reportsRoot.isDirectory()) return Collections.emptyList();
        File[] files = reportsRoot.listFiles();
        if (files == null) return Collections.emptyList();
        List<File> result = new ArrayList<File>();
        for (File file : files) if (file.isDirectory() && !file.getName().startsWith(".")) result.add(file);
        Collections.sort(result, new Comparator<File>() {
            @Override public int compare(File left, File right) { return Long.compare(right.lastModified(), left.lastModified()); }
        });
        return result;
    }

    public synchronized ReportComparison compare(String leftId, String rightId) throws IOException {
        File left = findReport(leftId);
        File right = findReport(rightId);
        Properties a = loadProperties(new File(left, "session.properties"));
        Properties b = loadProperties(new File(right, "session.properties"));
        List<String> lines = new ArrayList<String>();
        lines.add(compareMetric("平均客户端帧 P95", a, b, "frameP95Average", "ms", true));
        lines.add(compareMetric("最大客户端帧", a, b, "frameMaximum", "ms", true));
        lines.add(compareMetric("平均服务端 Tick P95", a, b, "serverP95Average", "ms", true));
        lines.add(compareMetric("最大服务端 Tick", a, b, "serverMaximum", "ms", true));
        lines.add(compareMetric("平均堆占用", a, b, "heapAverageMiB", "MiB", true));
        lines.add(compareMetric("卡顿触发次数", a, b, "triggers", "次", true));
        lines.add("主要根因：" + a.getProperty("topCause", "无") + " → " + b.getProperty("topCause", "无"));
        return new ReportComparison(left.getName(), right.getName(), lines);
    }

    public File getReportsRoot() { return reportsRoot; }

    private void writeSummary(RecordingSession session, File file) throws IOException {
        PrintWriter writer = utf8Writer(file);
        try {
            writer.println("ICE Profiler 性能诊断摘要");
            writer.println("========================");
            writer.println("会话：" + session.getId() + (session.getLabel().isEmpty() ? "" : "（" + session.getLabel() + "）"));
            writer.println("开始：" + formatDate(session.getStartedEpochMillis()));
            writer.println("时长：" + formatDuration(session.durationMillis()));
            writer.println("结束原因：" + session.getStopReason());
            writer.println("时间线点：" + session.getTimeline().size() + "，覆盖旧点：" + session.getTimelineOverwrites());
            writer.println("卡顿触发：" + session.getTriggerCount() + "，原因聚类：" + session.getClusters().size());
            writer.println("详细样本：" + session.getDetailedSampleCount() + "，因聚类上限仅计数的事件：" + session.getDiscardedClusterEvents());
            writer.println();
            writer.println("结论（类别 → 模组 → 类/方法 → 证据 → 置信度）");
            writer.println("-----------------------------------------------");
            if (session.getClusters().isEmpty()) {
                writer.println("本会话没有完成的卡顿捕获。若实际有卡顿，可按 F8 标记，并用 F9 开启深度录制后复现。");
            }
            int index = 1;
            for (HitchCluster cluster : session.getClusters()) {
                Diagnosis diagnosis = cluster.getDiagnosis();
                writer.println(index++ + ". " + diagnosis.getRootCause().getDisplayName() + " → "
                    + diagnosis.getMod().getName() + " [" + diagnosis.getMod().getId() + "] → " + diagnosis.getHotMethod());
                writer.println("   次数 " + cluster.getOccurrences() + "，平均 " + fmt(cluster.getAverageDurationMs())
                    + " ms，最大 " + fmt(cluster.getMaximumDurationMs()) + " ms，置信度 " + confidence(diagnosis.getConfidence()));
                for (String evidence : diagnosis.getEvidence()) writer.println("   证据：" + evidence);
                for (String recommendation : diagnosis.getRecommendations()) writer.println("   建议：" + recommendation);
                writer.println();
            }
            List<ProbeMetric> exact = aggregateProbes(session);
            if (!exact.isEmpty()) {
                writer.println("精确探针热点（仅在安装 hooks JAR 且开启深度模式时存在）");
                writer.println("----------------------------------------------------");
                for (int i = 0; i < Math.min(15, exact.size()); i++) {
                    ProbeMetric metric = exact.get(i);
                    writer.println((i + 1) + ". " + metric.getProbeName() + " → " + metric.getSubjectClass()
                        + "：" + metric.getCalls() + " 次，总计 " + fmt(metric.getTotalMillis()) + " ms，单次最大 " + fmt(metric.getMaximumMillis()) + " ms");
                }
                writer.println();
            }
            writer.println("说明：ICE Profiler 只观察和记录，不跳过 Tick、不修改区块生成、实体、渲染或网络结果。");
        } finally {
            writer.close();
        }
    }

    private void writeMetadata(RecordingSession session, File file) throws IOException {
        MetricSummary summary = summarize(session);
        Properties properties = new Properties();
        properties.setProperty("id", session.getId());
        properties.setProperty("started", Long.toString(session.getStartedEpochMillis()));
        properties.setProperty("durationMillis", Long.toString(session.durationMillis()));
        properties.setProperty("triggers", Long.toString(session.getTriggerCount()));
        properties.setProperty("frameP95Average", fmtRaw(summary.frameP95Average));
        properties.setProperty("frameMaximum", fmtRaw(summary.frameMaximum));
        properties.setProperty("serverP95Average", fmtRaw(summary.serverP95Average));
        properties.setProperty("serverMaximum", fmtRaw(summary.serverMaximum));
        properties.setProperty("heapAverageMiB", fmtRaw(summary.heapAverageMiB));
        properties.setProperty("topCause", session.getClusters().isEmpty() ? "无" : session.getClusters().get(0).getDiagnosis().getRootCause().getDisplayName());
        java.io.Writer output = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
        try { properties.store(output, "ICE Profiler session comparison data"); }
        finally { output.close(); }
    }

    private void writeTimeline(RecordingSession session, File file) throws IOException {
        PrintWriter writer = utf8Writer(file);
        try {
            writer.println("epoch_ms,elapsed_ms,fps,frame_avg_ms,frame_p95_ms,frame_p99_ms,frame_max_ms,gpu_frame_ms,client_tick_p95_ms,server_tick_avg_ms,server_tick_p95_ms,server_tick_p99_ms,server_tick_max_ms,heap_used_mib,heap_committed_mib,gc_count,gc_pause_ms,cpu_load,loaded_chunks,entities,tile_entities,chunk_loads,chunk_unloads,chunk_data_loads,chunk_data_saves,render_queue,upload_queue,in_packets,out_packets,in_bytes,out_bytes");
            for (TimelinePoint point : session.getTimeline()) {
                JvmSnapshot jvm = point.getJvm();
                writer.printf(Locale.ROOT,
                    "%d,%d,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.3f,%.3f,%d,%d,%.5f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                    point.getEpochMillis(), point.getElapsedMillis(), point.getFramesPerSecond(),
                    point.getClientFrames().getAverageMs(), point.getClientFrames().getP95Ms(), point.getClientFrames().getP99Ms(), point.getClientFrames().getMaximumMs(),
                    point.getGpuFrameMillis(), point.getClientTicks().getP95Ms(), point.getServerTicks().getAverageMs(), point.getServerTicks().getP95Ms(), point.getServerTicks().getP99Ms(), point.getServerTicks().getMaximumMs(),
                    jvm.getHeapUsedBytes() / 1048576.0D, jvm.getHeapCommittedBytes() / 1048576.0D, jvm.getGcCountDelta(), jvm.getGcPauseMillisDelta(), jvm.getProcessCpuLoad(),
                    point.getLoadedChunks(), point.getEntities(), point.getTileEntities(), point.getChunkLoads(), point.getChunkUnloads(), point.getChunkDataLoads(), point.getChunkDataSaves(),
                    point.getRenderQueueSize(), point.getChunkUploadQueueSize(), point.getInboundPackets(), point.getOutboundPackets(), point.getInboundBytes(), point.getOutboundBytes());
            }
        } finally { writer.close(); }
    }

    private void writeProbes(RecordingSession session, File file) throws IOException {
        PrintWriter writer = utf8Writer(file);
        try {
            writer.println("epoch_ms,elapsed_ms,probe_id,probe_name,subject,calls,total_ms,maximum_ms");
            for (TimelinePoint point : session.getTimeline()) {
                for (ProbeMetric metric : point.getProbes()) {
                    writer.printf(Locale.ROOT, "%d,%d,%d,%s,%s,%d,%.6f,%.6f%n", point.getEpochMillis(), point.getElapsedMillis(),
                        metric.getProbeId(), csv(metric.getProbeName()), csv(metric.getSubjectClass()), metric.getCalls(), metric.getTotalMillis(), metric.getMaximumMillis());
                }
            }
        } finally { writer.close(); }
    }

    private void writeHitchesJson(RecordingSession session, File file, boolean detailed) throws IOException {
        PrintWriter out = utf8Writer(file);
        try {
            out.println("{");
            out.println("  \"session\": \"" + json(session.getId()) + "\",");
            out.println("  \"clusters\": [");
            List<HitchCluster> clusters = session.getClusters();
            for (int i = 0; i < clusters.size(); i++) {
                HitchCluster cluster = clusters.get(i);
                Diagnosis diagnosis = cluster.getDiagnosis();
                out.println("    {");
                out.println("      \"category\": \"" + json(diagnosis.getRootCause().getDisplayName()) + "\",");
                out.println("      \"modId\": \"" + json(diagnosis.getMod().getId()) + "\",");
                out.println("      \"modName\": \"" + json(diagnosis.getMod().getName()) + "\",");
                out.println("      \"method\": \"" + json(diagnosis.getHotMethod()) + "\",");
                out.println("      \"confidence\": " + fmtRaw(diagnosis.getConfidence()) + ",");
                out.println("      \"occurrences\": " + cluster.getOccurrences() + ",");
                out.println("      \"averageMs\": " + fmtRaw(cluster.getAverageDurationMs()) + ",");
                out.println("      \"maximumMs\": " + fmtRaw(cluster.getMaximumDurationMs()) + ",");
                writeStringArray(out, "evidence", diagnosis.getEvidence(), 6, true);
                writeStringArray(out, "recommendations", diagnosis.getRecommendations(), 6, true);
                out.println("      \"representatives\": [");
                List<HitchCapture> reps = cluster.getRepresentatives();
                for (int r = 0; r < reps.size(); r++) {
                    HitchCapture capture = reps.get(r);
                    out.println("        {");
                    out.println("          \"sequence\": " + capture.getSequence() + ",");
                    out.println("          \"durationMs\": " + fmtRaw(capture.getPrimaryDurationNanos() / 1_000_000.0D) + ",");
                    out.println("          \"sampleCount\": " + capture.getSamples().size() + ",");
                    out.println("          \"preSampleCount\": " + capture.getPreSampleCount() + ",");
                    out.println("          \"postSampleCount\": " + capture.getPostSampleCount() + ",");
                    out.println("          \"droppedPreSamples\": " + capture.getDroppedPreSampleCount() + ",");
                    out.println("          \"droppedPostSamples\": " + capture.getDroppedPostSampleCount() + ",");
                    out.println("          \"capturedPreMs\": " + fmtRaw(capture.getCapturedPreMillis()) + ",");
                    out.println("          \"capturedPostMs\": " + fmtRaw(capture.getCapturedPostMillis()) + ",");
                    out.println("          \"sampleLimitReached\": " + capture.isSampleLimitReached() + ",");
                    out.println("          \"triggers\": [");
                    for (int t = 0; t < capture.getTriggers().size(); t++) {
                        HitchTrigger trigger = capture.getTriggers().get(t);
                        out.print("            {\"type\":\"" + trigger.getType().name() + "\",\"epochMs\":" + trigger.getEpochMillis()
                            + ",\"durationMs\":" + fmtRaw(trigger.getDurationMillis()) + ",\"detail\":\"" + json(trigger.getDetail()) + "\"}");
                        out.println(t + 1 == capture.getTriggers().size() ? "" : ",");
                    }
                    out.println("          ]" + (detailed ? "," : ""));
                    if (detailed) {
                        out.println("          \"samples\": [");
                        List<StackSample> samples = capture.getSamples();
                        for (int s = 0; s < samples.size(); s++) {
                            StackSample sample = samples.get(s);
                            out.print("            {\"nanos\":" + sample.getTimestampNanos() + ",\"thread\":\"" + json(sample.getThreadName())
                                + "\",\"role\":\"" + sample.getRole().name() + "\",\"stackId\":" + sample.getStackTraceId()
                                + ",\"state\":\"" + sample.getState().name() + "\",\"cpuNanos\":" + sample.getCpuNanosDelta()
                                + ",\"allocatedBytes\":" + sample.getAllocatedBytesDelta() + "}");
                            out.println(s + 1 == samples.size() ? "" : ",");
                        }
                        out.println("          ]");
                    }
                    out.print("        }");
                    out.println(r + 1 == reps.size() ? "" : ",");
                }
                out.println("      ]");
                out.print("    }");
                out.println(i + 1 == clusters.size() ? "" : ",");
            }
            out.println("  ],");
            out.println("  \"markers\": [");
            List<SessionMarker> markers = session.getMarkers();
            for (int i = 0; i < markers.size(); i++) {
                SessionMarker marker = markers.get(i);
                out.print("    {\"epochMs\":" + marker.getEpochMillis() + ",\"elapsedMs\":" + marker.getElapsedMillis() + ",\"text\":\"" + json(marker.getText()) + "\"}");
                out.println(i + 1 == markers.size() ? "" : ",");
            }
            out.println("  ]");
            out.println("}");
        } finally { out.close(); }
    }

    private void writeFoldedStacks(RecordingSession session, File file) throws IOException {
        Map<Long, Long> folded = new HashMap<Long, Long>();
        for (HitchCluster cluster : session.getClusters()) {
            for (HitchCapture capture : cluster.getRepresentatives()) {
                for (StackSample sample : capture.getSamples()) {
                    if (StackSampleFilter.isIdleWorkerWait(sample, session.getStacks().get(sample.getStackTraceId()))) continue;
                    long encoded = ((long) sample.getRole().ordinal() << 32) | (sample.getStackTraceId() & 0xffffffffL);
                    Long key = Long.valueOf(encoded);
                    Long old = folded.get(key);
                    folded.put(key, Long.valueOf(old == null ? 1L : old.longValue() + 1L));
                }
            }
        }
        List<Map.Entry<Long, Long>> entries = new ArrayList<Map.Entry<Long, Long>>(folded.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<Long, Long>>() {
            @Override public int compare(Map.Entry<Long, Long> left, Map.Entry<Long, Long> right) {
                return Long.compare(right.getValue().longValue(), left.getValue().longValue());
            }
        });
        PrintWriter writer = utf8Writer(file);
        try {
            long maximumBytes = Math.max(1024L * 1024L, Math.min(8L * 1024L * 1024L, softLimitBytes() / 3L));
            long written = 0L;
            dev.rlcraft.ice.profiler.sampling.ThreadRole[] roles = dev.rlcraft.ice.profiler.sampling.ThreadRole.values();
            for (Map.Entry<Long, Long> entry : entries) {
                long encoded = entry.getKey().longValue();
                int roleOrdinal = (int) (encoded >>> 32);
                int stackId = (int) encoded;
                StackTraceElement[] trace = session.getStacks().get(stackId);
                StringBuilder line = new StringBuilder(256);
                line.append(roleOrdinal >= 0 && roleOrdinal < roles.length ? roles[roleOrdinal].name() : "OTHER");
                for (int i = trace.length - 1; i >= 0; i--) line.append(';').append(trace[i].getClassName()).append('.').append(trace[i].getMethodName());
                line.append(' ').append(entry.getValue());
                String outputLine = line.toString();
                long bytes = outputLine.getBytes(StandardCharsets.UTF_8).length + 1L;
                if (written + bytes > maximumBytes) break;
                writer.println(outputLine);
                written += bytes;
            }
        }
        finally { writer.close(); }
    }

    private void writeBinaryCapture(RecordingSession session, File file) throws IOException {
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(file))));
        try {
            out.writeUTF("ICECAP");
            out.writeInt(CAPTURE_VERSION);
            out.writeUTF(session.getId());
            out.writeLong(session.getStartedEpochMillis());
            out.writeLong(session.durationMillis());
            List<TimelinePoint> timeline = session.getTimeline();
            out.writeInt(timeline.size());
            for (TimelinePoint point : timeline) writeTimelinePoint(out, point);
            List<HitchCluster> clusters = session.getClusters();
            out.writeInt(clusters.size());
            Set<Integer> referencedStacks = new HashSet<Integer>();
            for (HitchCluster cluster : clusters) {
                Diagnosis diagnosis = cluster.getDiagnosis();
                out.writeUTF(diagnosis.getRootCause().name());
                out.writeUTF(diagnosis.getMod().getId());
                out.writeUTF(diagnosis.getMod().getName());
                out.writeUTF(diagnosis.getHotMethod());
                out.writeDouble(diagnosis.getConfidence());
                out.writeLong(cluster.getOccurrences());
                out.writeDouble(cluster.getAverageDurationMs());
                out.writeDouble(cluster.getMaximumDurationMs());
                out.writeInt(cluster.getRepresentatives().size());
                for (HitchCapture capture : cluster.getRepresentatives()) {
                    out.writeLong(capture.getSequence());
                    out.writeLong(capture.getStartedNanos());
                    out.writeLong(capture.getTriggerNanos());
                    out.writeLong(capture.getEndNanos());
                    out.writeInt(capture.getPreSampleCount());
                    out.writeInt(capture.getPostSampleCount());
                    out.writeLong(capture.getDroppedPreSampleCount());
                    out.writeLong(capture.getDroppedPostSampleCount());
                    out.writeBoolean(capture.isSampleLimitReached());
                    out.writeInt(capture.getSamples().size());
                    for (StackSample sample : capture.getSamples()) {
                        out.writeLong(sample.getTimestampNanos());
                        out.writeLong(sample.getThreadId());
                        out.writeUTF(sample.getThreadName());
                        out.writeByte(sample.getRole().ordinal());
                        out.writeInt(sample.getStackTraceId());
                        out.writeLong(sample.getCpuNanosDelta());
                        out.writeLong(sample.getAllocatedBytesDelta());
                        out.writeByte(sample.getState().ordinal());
                        referencedStacks.add(Integer.valueOf(sample.getStackTraceId()));
                    }
                }
            }
            out.writeInt(referencedStacks.size());
            for (Integer id : referencedStacks) {
                out.writeInt(id.intValue());
                StackTraceElement[] trace = session.getStacks().get(id.intValue());
                out.writeShort(trace.length);
                for (StackTraceElement frame : trace) {
                    out.writeUTF(frame.getClassName());
                    out.writeUTF(frame.getMethodName());
                    out.writeUTF(frame.getFileName() == null ? "" : frame.getFileName());
                    out.writeInt(frame.getLineNumber());
                }
            }
        } finally { out.close(); }
    }

    private void writeHtml(RecordingSession session, File file) throws IOException {
        PrintWriter out = utf8Writer(file);
        try {
            out.println("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
            out.println("<title>ICE Profiler " + html(session.getId()) + "</title><style>body{font-family:system-ui,-apple-system,'Microsoft YaHei',sans-serif;background:#0f1419;color:#d9e2ec;margin:0}main{max-width:1200px;margin:auto;padding:24px}.card{background:#18222d;border:1px solid #2b3b4c;border-radius:10px;padding:16px;margin:14px 0}h1,h2{color:#8bd5ff}table{width:100%;border-collapse:collapse}th,td{padding:9px;border-bottom:1px solid #304152;text-align:left;vertical-align:top}.confidence{color:#a6e3a1}.warn{color:#f9e2af}canvas{width:100%;height:300px;background:#111a22;border-radius:6px}code{word-break:break-all;color:#cba6f7}.muted{color:#93a4b5}</style></head><body><main>");
            out.println("<h1>ICE Profiler 性能诊断</h1><div class=\"card\">会话 <code>" + html(session.getId()) + "</code> · 时长 " + formatDuration(session.durationMillis()) + " · 触发 " + session.getTriggerCount() + " 次 · 聚类 " + session.getClusters().size() + " 类</div>");
            out.println("<div class=\"card\"><h2>每秒时间线</h2><canvas id=\"chart\" width=\"1100\" height=\"300\"></canvas><div class=\"muted\">蓝：客户端帧 P95　橙：服务端 Tick P95　绿：堆内存 MiB（按图内尺度归一化）</div></div>");
            out.println("<div class=\"card\"><h2>根因聚类</h2><table><thead><tr><th>类别 → 模组</th><th>类/方法</th><th>次数 / 最大</th><th>证据与建议</th><th>置信度</th></tr></thead><tbody>");
            for (HitchCluster cluster : session.getClusters()) {
                Diagnosis d = cluster.getDiagnosis();
                out.print("<tr><td>" + html(d.getRootCause().getDisplayName()) + "<br><span class=\"muted\">" + html(d.getMod().getName()) + " [" + html(d.getMod().getId()) + "]</span></td>");
                out.print("<td><code>" + html(d.getHotMethod()) + "</code></td><td>" + cluster.getOccurrences() + " / " + fmt(cluster.getMaximumDurationMs()) + " ms</td><td>");
                for (String evidence : d.getEvidence()) out.print("• " + html(evidence) + "<br>");
                for (String recommendation : d.getRecommendations()) out.print("<span class=\"warn\">建议：" + html(recommendation) + "</span><br>");
                out.println("</td><td class=\"confidence\">" + confidence(d.getConfidence()) + "</td></tr>");
            }
            out.println("</tbody></table></div><div class=\"card muted\">该报告由只读诊断采集生成；采集器不会改变 Minecraft/RLCraft 的游戏内容或执行顺序。</div>");
            out.print("<script>const D=[");
            List<TimelinePoint> timeline = session.getTimeline();
            for (int i = 0; i < timeline.size(); i++) {
                TimelinePoint p = timeline.get(i);
                if (i > 0) out.print(',');
                out.print("[" + p.getElapsedMillis() + "," + fmtRaw(p.getClientFrames().getP95Ms()) + "," + fmtRaw(p.getServerTicks().getP95Ms()) + "," + fmtRaw(p.getJvm().getHeapUsedBytes() / 1048576.0D) + "]");
            }
            out.println("];const c=document.getElementById('chart'),x=c.getContext('2d'),P=28,w=c.width-P*2,h=c.height-P*2;let max=1;D.forEach(v=>max=Math.max(max,v[1],v[2],v[3]));x.strokeStyle='#334455';x.beginPath();x.moveTo(P,P);x.lineTo(P,P+h);x.lineTo(P+w,P+h);x.stroke();function line(k,col){if(!D.length)return;x.strokeStyle=col;x.lineWidth=2;x.beginPath();D.forEach((v,i)=>{let px=P+(D.length===1?0:i*w/(D.length-1)),py=P+h-v[k]*h/max;i?x.lineTo(px,py):x.moveTo(px,py)});x.stroke()}line(1,'#74c7ec');line(2,'#fab387');line(3,'#a6e3a1');x.fillStyle='#aab7c4';x.fillText('0',6,P+h);x.fillText(max.toFixed(1),2,P+8);</script></main></body></html>");
        } finally { out.close(); }
    }

    private static void writeTimelinePoint(DataOutputStream out, TimelinePoint point) throws IOException {
        out.writeLong(point.getEpochMillis());
        out.writeLong(point.getElapsedMillis());
        writeDistribution(out, point.getClientFrames());
        writeDistribution(out, point.getClientTicks());
        writeDistribution(out, point.getServerTicks());
        out.writeInt(point.getFramesPerSecond());
        out.writeInt(point.getRenderQueueSize());
        out.writeInt(point.getChunkUploadQueueSize());
        out.writeDouble(point.getGpuFrameMillis());
        out.writeLong(point.getChunkLoads()); out.writeLong(point.getChunkUnloads());
        out.writeLong(point.getChunkDataLoads()); out.writeLong(point.getChunkDataSaves());
        out.writeLong(point.getInboundPackets()); out.writeLong(point.getOutboundPackets());
        out.writeLong(point.getInboundBytes()); out.writeLong(point.getOutboundBytes());
        out.writeInt(point.getWorlds().size());
        for (WorldGauge world : point.getWorlds()) {
            out.writeInt(world.getDimension()); out.writeInt(world.getLoadedChunks()); out.writeInt(world.getEntities()); out.writeInt(world.getTileEntities());
        }
        JvmSnapshot jvm = point.getJvm();
        out.writeLong(jvm.getHeapUsedBytes()); out.writeLong(jvm.getHeapCommittedBytes()); out.writeLong(jvm.getHeapMaxBytes());
        out.writeLong(jvm.getGcCountDelta()); out.writeLong(jvm.getGcPauseMillisDelta()); out.writeDouble(jvm.getProcessCpuLoad());
        out.writeInt(point.getProbes().size());
        for (ProbeMetric metric : point.getProbes()) {
            out.writeInt(metric.getProbeId()); out.writeUTF(metric.getSubjectClass()); out.writeLong(metric.getCalls());
            out.writeLong(metric.getTotalNanos()); out.writeLong(metric.getMaximumNanos());
        }
    }

    private static void writeDistribution(DataOutputStream out, DistributionSnapshot value) throws IOException {
        out.writeLong(value.getCount()); out.writeDouble(value.getAverageMs()); out.writeDouble(value.getP50Ms());
        out.writeDouble(value.getP95Ms()); out.writeDouble(value.getP99Ms()); out.writeDouble(value.getMaximumMs());
    }

    private void writeZip(File directory) throws IOException {
        File zipFile = new File(reportsRoot, directory.getName() + ".zip");
        ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)));
        try {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (!file.isFile()) continue;
                    zip.putNextEntry(new ZipEntry(directory.getName() + "/" + file.getName()));
                    InputStream input = new BufferedInputStream(new FileInputStream(file));
                    try {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = input.read(buffer)) >= 0) zip.write(buffer, 0, read);
                    } finally { input.close(); }
                    zip.closeEntry();
                }
            }
        } finally { zip.close(); }
    }

    private void enforceRetention() {
        List<File> reports = listReports();
        for (int i = IceConfig.reports.maxSessions; i < reports.size(); i++) {
            File old = reports.get(i);
            try {
                deleteRecursivelySafe(old);
                File zip = new File(reportsRoot, old.getName() + ".zip");
                if (zip.isFile() && isInsideReports(zip)) Files.deleteIfExists(zip.toPath());
            } catch (IOException error) {
                IceProfilerMod.LOGGER.warn("无法清理旧 ICE Recorder 会话 {}", old, error);
            }
        }
    }

    private File findReport(String id) throws IOException {
        if (id == null || id.contains("..") || id.contains("/") || id.contains("\\")) throw new IOException("非法会话 ID");
        File exact = new File(reportsRoot, id);
        if (exact.isDirectory() && isInsideReports(exact)) return exact;
        List<File> candidates = listReports();
        for (File file : candidates) if (file.getName().startsWith(id)) return file;
        throw new IOException("找不到会话：" + id);
    }

    private File uniqueDirectory(String base) {
        File result = new File(reportsRoot, base);
        int suffix = 2;
        while (result.exists()) result = new File(reportsRoot, base + '-' + suffix++);
        return result;
    }

    private static MetricSummary summarize(RecordingSession session) {
        MetricSummary result = new MetricSummary();
        int framePoints = 0;
        int serverPoints = 0;
        int heapPoints = 0;
        for (TimelinePoint point : session.getTimeline()) {
            if (point.getClientFrames().getCount() > 0) {
                result.frameP95Average += point.getClientFrames().getP95Ms();
                result.frameMaximum = Math.max(result.frameMaximum, point.getClientFrames().getMaximumMs());
                framePoints++;
            }
            if (point.getServerTicks().getCount() > 0) {
                result.serverP95Average += point.getServerTicks().getP95Ms();
                result.serverMaximum = Math.max(result.serverMaximum, point.getServerTicks().getMaximumMs());
                serverPoints++;
            }
            result.heapAverageMiB += point.getJvm().getHeapUsedBytes() / 1048576.0D;
            heapPoints++;
        }
        if (framePoints > 0) result.frameP95Average /= framePoints;
        if (serverPoints > 0) result.serverP95Average /= serverPoints;
        if (heapPoints > 0) result.heapAverageMiB /= heapPoints;
        return result;
    }

    private static List<ProbeMetric> aggregateProbes(RecordingSession session) {
        Map<String, long[]> totals = new LinkedHashMap<String, long[]>();
        Map<String, ProbeMetric> identities = new HashMap<String, ProbeMetric>();
        for (TimelinePoint point : session.getTimeline()) {
            for (ProbeMetric metric : point.getProbes()) {
                String key = metric.getProbeId() + "|" + metric.getSubjectClass();
                long[] value = totals.get(key);
                if (value == null) { value = new long[3]; totals.put(key, value); identities.put(key, metric); }
                value[0] += metric.getCalls();
                value[1] += metric.getTotalNanos();
                value[2] = Math.max(value[2], metric.getMaximumNanos());
            }
        }
        List<ProbeMetric> result = new ArrayList<ProbeMetric>();
        for (Map.Entry<String, long[]> entry : totals.entrySet()) {
            ProbeMetric identity = identities.get(entry.getKey());
            long[] value = entry.getValue();
            result.add(new ProbeMetric(identity.getProbeId(), identity.getSubjectClass(), value[0], value[1], value[2]));
        }
        Collections.sort(result, new Comparator<ProbeMetric>() {
            @Override public int compare(ProbeMetric left, ProbeMetric right) { return Long.compare(right.getTotalNanos(), left.getTotalNanos()); }
        });
        return result;
    }

    private static String compareMetric(String name, Properties a, Properties b, String key, String unit, boolean lowerIsBetter) {
        double left = parse(a.getProperty(key));
        double right = parse(b.getProperty(key));
        double delta = right - left;
        String verdict = Math.abs(delta) < 0.0001D ? "无变化" : ((delta < 0.0D) == lowerIsBetter ? "改善" : "恶化");
        return name + "：" + fmt(left) + " → " + fmt(right) + " " + unit + "（" + (delta >= 0 ? "+" : "") + fmt(delta) + "，" + verdict + "）";
    }

    private static Properties loadProperties(File file) throws IOException {
        Properties value = new Properties();
        InputStream input = new FileInputStream(file);
        try { value.load(new java.io.InputStreamReader(input, StandardCharsets.UTF_8)); }
        finally { input.close(); }
        return value;
    }

    private static double parse(String value) {
        try { return Double.parseDouble(value); } catch (Exception ignored) { return 0.0D; }
    }

    private long softLimitBytes() { return IceConfig.reports.maxSessionMiB * 1024L * 1024L; }
    private static long directorySize(File directory) {
        long total = 0L;
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) total += file.isFile() ? file.length() : directorySize(file);
        return total;
    }

    private static void writeStringArray(PrintWriter out, String name, List<String> values, int spaces, boolean comma) {
        String indent = repeat(' ', spaces);
        out.println(indent + "\"" + name + "\": [");
        for (int i = 0; i < values.size(); i++) {
            out.print(indent + "  \"" + json(values.get(i)) + "\"");
            out.println(i + 1 == values.size() ? "" : ",");
        }
        out.println(indent + "]" + (comma ? "," : ""));
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static PrintWriter utf8Writer(File file) throws IOException {
        return new PrintWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)));
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建目录：" + directory);
    }

    private boolean isInsideReports(File file) throws IOException {
        String root = reportsRoot.getCanonicalPath() + File.separator;
        String target = file.getCanonicalPath();
        return target.startsWith(root);
    }

    private void deleteRecursivelySafe(File target) throws IOException {
        if (!isInsideReports(target)) throw new IOException("拒绝删除报告目录以外的路径：" + target);
        File[] children = target.listFiles();
        if (children != null) for (File child : children) deleteRecursivelySafe(child);
        Files.deleteIfExists(target.toPath());
    }

    private static String formatDate(long epochMillis) {
        DateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(epochMillis));
    }

    private static String formatDuration(long millis) {
        long seconds = millis / 1000L;
        return (seconds / 60L) + " 分 " + (seconds % 60L) + " 秒";
    }

    private static String confidence(double value) {
        return Math.round(value * 100.0D) + "%";
    }

    private static String fmt(double value) { return String.format(Locale.ROOT, "%.2f", value); }
    private static String fmtRaw(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "0";
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static String json(String value) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': result.append("\\\\"); break;
                case '"': result.append("\\\""); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (c < 32) result.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else result.append(c);
            }
        }
        return result.toString();
    }

    private static String csv(String value) {
        if (value == null) return "";
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) return value;
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String html(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static final class MetricSummary {
        private double frameP95Average;
        private double frameMaximum;
        private double serverP95Average;
        private double serverMaximum;
        private double heapAverageMiB;
    }
}
