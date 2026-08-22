package dev.rlcraft.ice.profiler.core;

import dev.rlcraft.ice.IceProfilerMod;
import dev.rlcraft.ice.config.IceConfig;
import dev.rlcraft.ice.profiler.FatalErrors;
import dev.rlcraft.ice.profiler.capture.HitchCluster;
import dev.rlcraft.ice.profiler.capture.HitchTrigger;
import dev.rlcraft.ice.profiler.capture.TriggerEngine;
import dev.rlcraft.ice.profiler.capture.TriggerType;
import dev.rlcraft.ice.profiler.metrics.MetricRegistry;
import dev.rlcraft.ice.profiler.metrics.TimelinePoint;
import dev.rlcraft.ice.profiler.probe.ProbeBridge;
import dev.rlcraft.ice.profiler.report.ReportComparison;
import dev.rlcraft.ice.profiler.report.ReportWriter;
import dev.rlcraft.ice.profiler.sampling.SampleListener;
import dev.rlcraft.ice.profiler.sampling.SamplingMode;
import dev.rlcraft.ice.profiler.sampling.StackSample;
import dev.rlcraft.ice.profiler.sampling.ThreadRole;
import dev.rlcraft.ice.profiler.sampling.ThreadSampler;
import dev.rlcraft.ice.profiler.session.RecordingSession;
import dev.rlcraft.ice.profiler.session.SessionManager;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class ProfilerRuntime implements SampleListener, SamplingMode, TriggerEngine.Sink {
    public static final ProfilerRuntime INSTANCE = new ProfilerRuntime();

    private final MetricRegistry metrics = new MetricRegistry();
    private final TriggerEngine triggers = new TriggerEngine(this);
    private final FixedRingBuffer<TimelinePoint> passiveTimeline = new FixedRingBuffer<TimelinePoint>(120);
    private final FixedRingBuffer<String> recentDiagnoses = new FixedRingBuffer<String>(10);
    private volatile boolean initialized;
    private volatile boolean deepMode;
    private volatile TimelinePoint latest;
    private volatile String lastCompletedDiagnosis = "";
    private volatile long completionSequence;
    private ThreadSampler sampler;
    private SessionManager sessions;
    private ReportWriter reports;
    private long lastPulseMillis;
    private int activeWorldSources;
    private long lastAutomaticExportNanos;
    private final AtomicLong collectorFailures = new AtomicLong();

    private ProfilerRuntime() {
    }

    public synchronized void initialize(File gameDirectory) {
        if (initialized) return;
        reports = new ReportWriter(gameDirectory);
        sampler = new ThreadSampler(this, this);
        sessions = new SessionManager(sampler.getStacks(), reports);
        metrics.start();
        deepMode = IceConfig.probes.deepProfiling;
        initialized = true;
        ProbeBridge.setEnabled(true);
        if (IceConfig.general.passiveMonitoring) sampler.start();
        IceProfilerMod.LOGGER.info("ICE Recorder 已初始化；报告目录 {}，内存上限 {} MiB", reports.getReportsRoot(), IceConfig.general.maxProfilerMemoryMiB);
    }

    public synchronized void activityStarted(String label) {
        if (!initialized) return;
        activeWorldSources++;
        if (!IceConfig.capture.automaticIncidentSessions) sessions.ensureAutomatic(label);
        if (IceConfig.general.passiveMonitoring || IceConfig.capture.autoCapture || sessions.isRecording()) sampler.start();
    }

    public synchronized void activityStopped() {
        if (!initialized) return;
        activeWorldSources = Math.max(0, activeWorldSources - 1);
        if (activeWorldSources == 0 && sessions.isRecording()) {
            sessions.stop("世界/服务器关闭", true);
        }
        if (!IceConfig.general.passiveMonitoring && !sessions.isRecording()) sampler.stop();
    }

    public synchronized RecordingSession startManual(String label) {
        ensureInitialized();
        RecordingSession existing = sessions.getCurrent();
        if (existing != null && !existing.isManual()) sessions.stop("切换到手动会话", true);
        RecordingSession result = sessions.start(true, label == null ? "手动录制" : label);
        sampler.start();
        return result;
    }

    public synchronized RecordingSession stopManual(boolean export) {
        ensureInitialized();
        RecordingSession result = sessions.stop("用户停止", export);
        if (!IceConfig.general.passiveMonitoring && activeWorldSources == 0) sampler.stop();
        return result;
    }

    public synchronized RecordingSession exportAndContinue() {
        ensureInitialized();
        if (!sessions.isRecording()) return null;
        boolean restart = activeWorldSources > 0;
        return sessions.rotate("手动导出", restart);
    }

    public synchronized void mark(String text) {
        ensureInitialized();
        try {
            String detail = text == null ? "用户标记" : text;
            if (!sessions.isRecording() && !IceConfig.capture.automaticIncidentSessions) sessions.start(true, "由标记启动");
            if (!sessions.isRecording()) {
                triggers.manual(detail);
                sessions.addMarker(detail);
            } else {
                sessions.addMarker(detail);
                triggers.manual(detail);
            }
        } catch (Throwable error) {
            collectorFailure("marker", error);
        }
    }

    public void recordClientFrame(long nanos) {
        try {
            metrics.recordClientFrame(nanos);
            triggers.clientFrame(nanos);
            pulse();
        } catch (Throwable error) { collectorFailure("client-frame", error); }
    }

    public void recordClientTick(long nanos) {
        try {
            metrics.recordClientTick(nanos);
            triggers.clientTick(nanos);
            pulse();
        } catch (Throwable error) { collectorFailure("client-tick", error); }
    }

    public void recordServerTick(long nanos) {
        try {
            metrics.recordServerTick(nanos);
            triggers.serverTick(nanos);
            pulse();
        } catch (Throwable error) { collectorFailure("server-tick", error); }
    }

    public void pulse() {
        if (!initialized) return;
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (now - lastPulseMillis < 1000L) return;
            lastPulseMillis = now;
            RecordingSession session = sessions.getCurrent();
            long started = session == null ? now : session.getStartedEpochMillis();
            TimelinePoint point = metrics.snapshot(started);
            latest = point;
            passiveTimeline.add(point);
            sessions.addTimeline(point);
            if (point.getJvm().getGcPauseMillisDelta() >= IceConfig.capture.gcPauseThresholdMs) {
                triggers.gcPause(point.getJvm().getGcPauseMillisDelta());
            }
            completed(sessions.pollCompleted(System.nanoTime()));
            RecordingSession active = sessions.getCurrent();
            long pulseNanos = System.nanoTime();
            if (shouldAutoExportIncident(active, pulseNanos) && automaticExportIntervalElapsed(pulseNanos)) {
                sessions.stop("自动卡顿事件捕获完成", true);
                lastAutomaticExportNanos = pulseNanos;
                return;
            }
            if (active != null && active.durationMillis() >= TimeUnit.MINUTES.toMillis(IceConfig.general.maxSessionMinutes)) {
                sessions.rotate("达到会话时长上限", true);
            }
        }
    }

    @Override
    public void onTrigger(HitchTrigger trigger) {
        if (!initialized) return;
        synchronized (this) {
            boolean explicit = trigger.getType() == TriggerType.MANUAL || trigger.getType() == TriggerType.MARKER;
            if (!sessions.isRecording() && (IceConfig.capture.autoCapture || explicit) && activeWorldSources > 0) {
                long preMillis = TimeUnit.SECONDS.toMillis(IceConfig.capture.preCaptureSeconds);
                long preNanos = TimeUnit.SECONDS.toNanos(IceConfig.capture.preCaptureSeconds);
                long incidentStartedEpoch = trigger.getEpochMillis() - preMillis;
                sessions.startAt(false, "自动卡顿事件", incidentStartedEpoch, trigger.getTimestampNanos() - preNanos);
                for (TimelinePoint point : passiveTimeline.snapshot()) {
                    if (point.getEpochMillis() >= incidentStartedEpoch) sessions.addTimeline(point.rebase(incidentStartedEpoch));
                }
            }
            if (!sessions.isRecording()) return;
            long start = trigger.getTimestampNanos() - TimeUnit.SECONDS.toNanos(IceConfig.capture.preCaptureSeconds);
            completed(sessions.trigger(trigger, sampler.recentSince(start)));
        }
    }

    @Override
    public void onSample(StackSample sample) {
        if (!initialized) return;
        completed(sessions.onSample(sample));
    }

    private void completed(HitchCluster cluster) {
        if (cluster == null) return;
        lastCompletedDiagnosis = cluster.getDiagnosis().getRootCause().getDisplayName() + " → "
            + cluster.getDiagnosis().getMod().getName() + " → " + cluster.getDiagnosis().getHotMethod();
        recentDiagnoses.add(lastCompletedDiagnosis);
        completionSequence++;
    }

    public void registerCurrentThread(ThreadRole role) {
        if (initialized) sampler.getRegistry().registerCurrent(role);
    }

    public MetricRegistry metrics() { return metrics; }
    public TriggerEngine triggers() { return triggers; }

    @Override
    public boolean isDeepSampling() {
        RecordingSession current = sessions == null ? null : sessions.getCurrent();
        return deepMode || (current != null && current.hasActiveCapture());
    }

    public void setDeepMode(boolean enabled) {
        deepMode = enabled;
        IceConfig.probes.deepProfiling = enabled;
    }

    public boolean isDeepMode() { return deepMode; }
    public long getCompletionSequence() { return completionSequence; }
    public String getLastCompletedDiagnosis() { return lastCompletedDiagnosis; }

    public synchronized ProfilerStatus status() {
        if (!initialized) return new ProfilerStatus(false, false, false, false, "", 0L, 0L, false, null,
            Collections.<String>emptyList(), 0, 0L, null, "");
        RecordingSession session = sessions.getCurrent();
        List<String> diagnoses = new ArrayList<String>(sessions.currentClusterSummaries());
        List<String> recent = recentDiagnoses.snapshot();
        for (int i = recent.size() - 1; i >= 0; i--) {
            String value = recent.get(i);
            if (!diagnoses.contains(value)) diagnoses.add(value);
        }
        return new ProfilerStatus(true, session != null, deepMode, session != null && session.isManual(), session == null ? "" : session.getId(),
            session == null ? 0L : session.durationMillis(), session == null ? 0L : session.getTriggerCount(),
            session != null && session.hasActiveCapture(), latest, diagnoses,
            sampler.getStacks().size(), sampler.getStacks().getOverflowCount(), sessions.getLastReport(), sessions.getLastExportError());
    }

    public List<File> listReports() {
        return reports == null ? Collections.<File>emptyList() : reports.listReports();
    }

    public ReportComparison compareReports(String left, String right) throws IOException {
        ensureInitialized();
        return reports.compare(left, right);
    }

    public synchronized void shutdown() {
        if (!initialized) return;
        sessions.shutdown();
        sampler.stop();
        metrics.stop();
        initialized = false;
        activeWorldSources = 0;
    }

    private void ensureInitialized() {
        if (!initialized) throw new IllegalStateException("ICE Profiler 尚未初始化");
    }

    private void collectorFailure(String collector, Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        long failures = collectorFailures.incrementAndGet();
        if (failures == 1L || (failures & (failures - 1L)) == 0L) {
            IceProfilerMod.LOGGER.warn("ICE 采集器 {} 发生错误（累计 {} 次），本轮数据已丢弃，游戏继续运行", collector, failures, error);
        }
    }

    static boolean shouldAutoExportIncident(RecordingSession session, long nowNanos) {
        if (!IceConfig.capture.automaticIncidentSessions || session == null || session.isManual()) return false;
        if (session.getTriggerCount() == 0L || session.hasActiveCapture()) return false;
        long quiet = TimeUnit.SECONDS.toNanos(IceConfig.capture.postCaptureSeconds + IceConfig.capture.automaticExportQuietSeconds);
        return nowNanos - session.getLastTriggerNanos() >= quiet;
    }

    private boolean automaticExportIntervalElapsed(long nowNanos) {
        return lastAutomaticExportNanos == 0L
            || nowNanos - lastAutomaticExportNanos >= TimeUnit.SECONDS.toNanos(IceConfig.capture.minimumAutomaticExportIntervalSeconds);
    }
}
