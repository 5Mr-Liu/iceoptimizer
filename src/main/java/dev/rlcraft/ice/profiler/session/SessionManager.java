package dev.rlcraft.ice.profiler.session;

import dev.rlcraft.ice.IceProfilerMod;
import dev.rlcraft.ice.config.IceConfig;
import dev.rlcraft.ice.profiler.FatalErrors;
import dev.rlcraft.ice.profiler.analysis.ModResolver;
import dev.rlcraft.ice.profiler.capture.HitchCluster;
import dev.rlcraft.ice.profiler.capture.HitchTrigger;
import dev.rlcraft.ice.profiler.metrics.TimelinePoint;
import dev.rlcraft.ice.profiler.sampling.StackSample;
import dev.rlcraft.ice.profiler.sampling.StackTraceRepository;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class SessionManager {
    private final StackTraceRepository stacks;
    private final ModResolver modResolver = new ModResolver();
    private final ReportExporter exporter;
    private final ExecutorService reportExecutor;
    private RecordingSession current;
    private volatile File lastReport;
    private volatile String lastExportError = "";

    public SessionManager(StackTraceRepository stacks, ReportExporter exporter) {
        this.stacks = stacks;
        this.exporter = exporter;
        this.reportExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "ICE Report Writer");
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
                return thread;
            }
        });
    }

    public synchronized RecordingSession start(boolean manual, String label) {
        return startAt(manual, label, System.currentTimeMillis(), System.nanoTime());
    }

    public synchronized RecordingSession startAt(boolean manual, String label, long startedEpochMillis, long startedNanos) {
        if (current != null) return current;
        current = new RecordingSession(createId(), label, manual, stacks, modResolver, startedEpochMillis, startedNanos);
        IceProfilerMod.LOGGER.info("ICE Recorder 开始会话 {} ({})", current.getId(), label);
        return current;
    }

    public synchronized RecordingSession ensureAutomatic(String label) {
        if (current == null && IceConfig.general.autoStartSession) return start(false, label);
        return current;
    }

    public synchronized RecordingSession getCurrent() { return current; }
    public synchronized boolean isRecording() { return current != null; }

    public synchronized RecordingSession stop(String reason, boolean export) {
        if (current == null) return null;
        final RecordingSession stopped = current;
        current = null;
        stopped.finish(reason);
        IceProfilerMod.LOGGER.info("ICE Recorder 结束会话 {}，原因：{}", stopped.getId(), reason);
        if (export) submitExport(stopped);
        return stopped;
    }

    public synchronized RecordingSession rotate(String reason, boolean restart) {
        RecordingSession old = stop(reason, true);
        if (restart) start(false, "自动续段");
        return old;
    }

    public synchronized HitchCluster trigger(HitchTrigger trigger, List<StackSample> preSamples) {
        RecordingSession session = current;
        return session == null ? null : session.trigger(trigger, preSamples);
    }

    public synchronized HitchCluster onSample(StackSample sample) {
        return current == null ? null : current.onSample(sample);
    }

    public synchronized HitchCluster pollCompleted(long nowNanos) {
        return current == null ? null : current.pollCompleted(nowNanos);
    }

    public synchronized void addTimeline(TimelinePoint point) {
        if (current != null) current.addTimeline(point);
    }

    public synchronized void addMarker(String text) {
        if (current != null) current.addMarker(text);
    }

    public void submitExport(final RecordingSession session) {
        reportExecutor.submit(new Runnable() {
            @Override public void run() {
                try {
                    lastReport = exporter.export(session);
                    lastExportError = "";
                    IceProfilerMod.LOGGER.info("ICE Recorder 报告已写入 {}", lastReport);
                } catch (Throwable error) {
                    FatalErrors.rethrowIfFatal(error);
                    lastExportError = error.toString();
                    IceProfilerMod.LOGGER.error("ICE Recorder 无法导出会话 " + session.getId(), error);
                }
            }
        });
    }

    public synchronized List<String> currentClusterSummaries() {
        List<String> result = new ArrayList<String>();
        if (current == null) return result;
        for (HitchCluster cluster : current.getClusters()) {
            result.add(cluster.getDiagnosis().getRootCause().getDisplayName() + " → "
                + cluster.getDiagnosis().getMod().getName() + " → " + cluster.getDiagnosis().getHotMethod()
                + "（" + cluster.getOccurrences() + " 次）");
        }
        return result;
    }

    public File getLastReport() { return lastReport; }
    public String getLastExportError() { return lastExportError; }

    public synchronized void shutdown() {
        if (current != null) stop("游戏关闭", true);
        reportExecutor.shutdown();
        try {
            if (!reportExecutor.awaitTermination(30L, TimeUnit.SECONDS)) {
                IceProfilerMod.LOGGER.warn("ICE 报告线程在 30 秒内未结束；剩余导出将在进程退出时中止");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String createId() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date());
    }
}
