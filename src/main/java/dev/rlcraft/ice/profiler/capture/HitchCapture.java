package dev.rlcraft.ice.profiler.capture;

import dev.rlcraft.ice.profiler.analysis.Diagnosis;
import dev.rlcraft.ice.profiler.sampling.StackSample;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class HitchCapture {
    private final long sequence;
    private final long startedNanos;
    private final long triggerNanos;
    private long endNanos;
    private final List<HitchTrigger> triggers = new ArrayList<HitchTrigger>();
    private RoleAwareSampleBuffer preSamples;
    private RoleAwareSampleBuffer postSamples;
    private int maximumSamples;
    private int preSampleBudget;
    private List<StackSample> samples = Collections.emptyList();
    private int retainedPreSamples;
    private int retainedPostSamples;
    private long droppedPreSamples;
    private long droppedPostSamples;
    private long firstPreSampleNanos;
    private long lastPreSampleNanos;
    private long firstPostSampleNanos;
    private long lastPostSampleNanos;
    private boolean sealed;
    private boolean sampleLimitReached;
    private Diagnosis diagnosis;

    public HitchCapture(long sequence, long startedNanos, long endNanos) {
        this(sequence, startedNanos, startedNanos, endNanos);
    }

    public HitchCapture(long sequence, long startedNanos, long triggerNanos, long endNanos) {
        this.sequence = sequence;
        this.startedNanos = startedNanos;
        this.triggerNanos = triggerNanos;
        this.endNanos = endNanos;
    }

    public void addTrigger(HitchTrigger trigger) { triggers.add(trigger); }
    public void extendTo(long timestampNanos) { endNanos = Math.max(endNanos, timestampNanos); }
    public void addPreSamples(List<StackSample> candidates, int maximumSamples) {
        ensureBuffers(maximumSamples);
        if (candidates == null) return;
        for (StackSample sample : candidates) {
            if (sample != null && sample.getTimestampNanos() >= startedNanos
                && sample.getTimestampNanos() <= triggerNanos) {
                preSamples.add(sample);
            }
        }
    }
    public void addSample(StackSample sample, int maximumSamples) {
        ensureBuffers(maximumSamples);
        postSamples.add(sample);
    }
    public void setDiagnosis(Diagnosis diagnosis) { this.diagnosis = diagnosis; }

    public void seal() {
        if (sealed) return;
        sealed = true;
        if (maximumSamples <= 0) {
            samples = Collections.emptyList();
            return;
        }
        RoleAwareSampleBuffer.Selection pre = preSamples.select(preSampleBudget);
        int postBudget = Math.max(0, maximumSamples - pre.getSamples().size());
        RoleAwareSampleBuffer.Selection post = postSamples.select(postBudget);
        retainedPreSamples = pre.getSamples().size();
        retainedPostSamples = post.getSamples().size();
        droppedPreSamples = pre.getDropped();
        droppedPostSamples = post.getDropped();
        sampleLimitReached = droppedPreSamples > 0L || droppedPostSamples > 0L;

        List<StackSample> combined = new ArrayList<StackSample>(retainedPreSamples + retainedPostSamples);
        combined.addAll(pre.getSamples());
        combined.addAll(post.getSamples());
        Collections.sort(combined, new Comparator<StackSample>() {
            @Override public int compare(StackSample left, StackSample right) {
                int timestamp = Long.compare(left.getTimestampNanos(), right.getTimestampNanos());
                if (timestamp != 0) return timestamp;
                return Long.compare(left.getThreadId(), right.getThreadId());
            }
        });
        samples = Collections.unmodifiableList(combined);
        firstPreSampleNanos = firstTimestamp(pre.getSamples());
        lastPreSampleNanos = lastTimestamp(pre.getSamples());
        firstPostSampleNanos = firstTimestamp(post.getSamples());
        lastPostSampleNanos = lastTimestamp(post.getSamples());
    }

    public long getSequence() { return sequence; }
    public long getStartedNanos() { return startedNanos; }
    public long getTriggerNanos() { return triggerNanos; }
    public long getEndNanos() { return endNanos; }
    public List<HitchTrigger> getTriggers() { return Collections.unmodifiableList(triggers); }
    public List<StackSample> getSamples() { seal(); return samples; }
    public boolean isSampleLimitReached() { seal(); return sampleLimitReached; }
    public int getPreSampleCount() { seal(); return retainedPreSamples; }
    public int getPostSampleCount() { seal(); return retainedPostSamples; }
    public long getDroppedPreSampleCount() { seal(); return droppedPreSamples; }
    public long getDroppedPostSampleCount() { seal(); return droppedPostSamples; }
    public long getDroppedSampleCount() { seal(); return droppedPreSamples + droppedPostSamples; }
    public long getFirstPreSampleNanos() { seal(); return firstPreSampleNanos; }
    public long getLastPreSampleNanos() { seal(); return lastPreSampleNanos; }
    public long getFirstPostSampleNanos() { seal(); return firstPostSampleNanos; }
    public long getLastPostSampleNanos() { seal(); return lastPostSampleNanos; }
    public double getCapturedPreMillis() {
        seal();
        return retainedPreSamples == 0 ? 0.0D : Math.max(0L, triggerNanos - firstPreSampleNanos) / 1_000_000.0D;
    }
    public double getCapturedPostMillis() {
        seal();
        return retainedPostSamples == 0 ? 0.0D : Math.max(0L, lastPostSampleNanos - triggerNanos) / 1_000_000.0D;
    }
    public Diagnosis getDiagnosis() { return diagnosis; }

    public long getPrimaryDurationNanos() {
        long maximum = 0L;
        for (HitchTrigger trigger : triggers) maximum = Math.max(maximum, trigger.getDurationNanos());
        return maximum;
    }

    private void ensureBuffers(int requestedMaximum) {
        if (sealed) throw new IllegalStateException("捕获已完成，不能继续添加样本");
        if (maximumSamples > 0) return;
        maximumSamples = Math.max(1, requestedMaximum);
        preSampleBudget = Math.max(1, (int) ((long) maximumSamples * 40L / 100L));
        if (preSampleBudget >= maximumSamples && maximumSamples > 1) preSampleBudget = maximumSamples - 1;
        preSamples = new RoleAwareSampleBuffer(preSampleBudget);
        postSamples = new RoleAwareSampleBuffer(maximumSamples);
    }

    private static long firstTimestamp(List<StackSample> values) {
        if (values.isEmpty()) return 0L;
        long result = values.get(0).getTimestampNanos();
        for (StackSample sample : values) result = Math.min(result, sample.getTimestampNanos());
        return result;
    }

    private static long lastTimestamp(List<StackSample> values) {
        if (values.isEmpty()) return 0L;
        long result = values.get(0).getTimestampNanos();
        for (StackSample sample : values) result = Math.max(result, sample.getTimestampNanos());
        return result;
    }
}
