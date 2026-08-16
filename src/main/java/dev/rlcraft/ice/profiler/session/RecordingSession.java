package dev.rlcraft.ice.profiler.session;

import dev.rlcraft.ice.config.IceConfig;
import dev.rlcraft.ice.profiler.analysis.ModResolver;
import dev.rlcraft.ice.profiler.analysis.RootCauseAnalyzer;
import dev.rlcraft.ice.profiler.capture.HitchCapture;
import dev.rlcraft.ice.profiler.capture.HitchCluster;
import dev.rlcraft.ice.profiler.capture.HitchClusterer;
import dev.rlcraft.ice.profiler.capture.HitchTrigger;
import dev.rlcraft.ice.profiler.core.FixedRingBuffer;
import dev.rlcraft.ice.profiler.core.ProfilerLimits;
import dev.rlcraft.ice.profiler.metrics.TimelinePoint;
import dev.rlcraft.ice.profiler.sampling.StackSample;
import dev.rlcraft.ice.profiler.sampling.StackTraceRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class RecordingSession {
    private final String id;
    private final String label;
    private final boolean manual;
    private final long startedEpochMillis;
    private final long startedNanos;
    private final FixedRingBuffer<TimelinePoint> timeline;
    private final FixedRingBuffer<SessionMarker> markers = new FixedRingBuffer<SessionMarker>(256);
    private final HitchClusterer clusterer;
    private final StackTraceRepository stacks;
    private final int perCaptureSampleLimit;
    private HitchCapture activeCapture;
    private long nextCaptureSequence = 1L;
    private long triggerCount;
    private long lastTriggerNanos;
    private long finishedEpochMillis;
    private String stopReason = "recording";

    public RecordingSession(String id, String label, boolean manual, StackTraceRepository stacks, ModResolver resolver) {
        this(id, label, manual, stacks, resolver, System.currentTimeMillis(), System.nanoTime());
    }

    public RecordingSession(String id, String label, boolean manual, StackTraceRepository stacks, ModResolver resolver,
                            long startedEpochMillis, long startedNanos) {
        this.id = id;
        this.label = label == null ? "" : label;
        this.manual = manual;
        this.startedEpochMillis = startedEpochMillis;
        this.startedNanos = startedNanos;
        this.timeline = new FixedRingBuffer<TimelinePoint>(IceConfig.general.maxTimelinePoints);
        this.stacks = stacks;
        this.perCaptureSampleLimit = ProfilerLimits.samplesPerCapture();
        this.clusterer = new HitchClusterer(
            new RootCauseAnalyzer(stacks, resolver),
            IceConfig.capture.maxClusters,
            IceConfig.capture.representativesPerCluster,
            ProfilerLimits.detailedSamples()
        );
    }

    public synchronized void addTimeline(TimelinePoint point) {
        timeline.add(point);
    }

    public synchronized void addMarker(String text) {
        long now = System.currentTimeMillis();
        markers.add(new SessionMarker(now, now - startedEpochMillis, text));
    }

    public synchronized HitchCluster trigger(HitchTrigger trigger, List<StackSample> preSamples) {
        triggerCount++;
        lastTriggerNanos = trigger.getTimestampNanos();
        long postNanos = TimeUnit.SECONDS.toNanos(IceConfig.capture.postCaptureSeconds);
        long mergeNanos = TimeUnit.SECONDS.toNanos(IceConfig.capture.mergeWindowSeconds);
        HitchCluster completed = null;
        if (activeCapture != null && trigger.getTimestampNanos() > activeCapture.getEndNanos() + mergeNanos) {
            completed = completeActive();
        }
        if (activeCapture == null) {
            long preNanos = TimeUnit.SECONDS.toNanos(IceConfig.capture.preCaptureSeconds);
            activeCapture = new HitchCapture(nextCaptureSequence++, trigger.getTimestampNanos() - preNanos,
                trigger.getTimestampNanos(), trigger.getTimestampNanos() + postNanos);
            activeCapture.addPreSamples(preSamples, perCaptureSampleLimit);
        } else {
            activeCapture.extendTo(trigger.getTimestampNanos() + postNanos);
        }
        activeCapture.addTrigger(trigger);
        return completed;
    }

    public synchronized HitchCluster onSample(StackSample sample) {
        if (activeCapture == null) return null;
        if (sample.getTimestampNanos() <= activeCapture.getEndNanos()) {
            activeCapture.addSample(sample, perCaptureSampleLimit);
            return null;
        }
        return completeActive();
    }

    public synchronized HitchCluster pollCompleted(long nowNanos) {
        return activeCapture != null && nowNanos > activeCapture.getEndNanos() ? completeActive() : null;
    }

    public synchronized void finish(String reason) {
        completeActive();
        finishedEpochMillis = System.currentTimeMillis();
        stopReason = reason == null ? "stopped" : reason;
    }

    private HitchCluster completeActive() {
        if (activeCapture == null) return null;
        HitchCapture completed = activeCapture;
        activeCapture = null;
        completed.seal();
        return clusterer.add(completed);
    }

    public String getId() { return id; }
    public String getLabel() { return label; }
    public boolean isManual() { return manual; }
    public long getStartedEpochMillis() { return startedEpochMillis; }
    public long getStartedNanos() { return startedNanos; }
    public synchronized long getFinishedEpochMillis() { return finishedEpochMillis; }
    public synchronized String getStopReason() { return stopReason; }
    public synchronized boolean hasActiveCapture() { return activeCapture != null; }
    public synchronized long getTriggerCount() { return triggerCount; }
    public synchronized long getLastTriggerNanos() { return lastTriggerNanos; }
    public synchronized List<TimelinePoint> getTimeline() { return timeline.snapshot(); }
    public synchronized List<SessionMarker> getMarkers() { return markers.snapshot(); }
    public List<HitchCluster> getClusters() { return clusterer.snapshot(); }
    public StackTraceRepository getStacks() { return stacks; }
    public synchronized long durationMillis() {
        long end = finishedEpochMillis == 0L ? System.currentTimeMillis() : finishedEpochMillis;
        return Math.max(0L, end - startedEpochMillis);
    }
    public long getTimelineOverwrites() { return timeline.overwrittenCount(); }
    public int getDetailedSampleCount() { return clusterer.getDetailedSamples(); }
    public long getDiscardedClusterEvents() { return clusterer.getDiscardedClusterEvents(); }
}
