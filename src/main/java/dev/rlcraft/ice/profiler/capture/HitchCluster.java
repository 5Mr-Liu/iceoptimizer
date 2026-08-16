package dev.rlcraft.ice.profiler.capture;

import dev.rlcraft.ice.profiler.analysis.Diagnosis;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class HitchCluster {
    private final String key;
    private final Diagnosis diagnosis;
    private final int representativeLimit;
    private final List<HitchCapture> representatives = new ArrayList<HitchCapture>();
    private final Map<TriggerType, Integer> triggerCounts = new EnumMap<TriggerType, Integer>(TriggerType.class);
    private long occurrences;
    private long firstEpochMillis;
    private long lastEpochMillis;
    private long totalDurationNanos;
    private long maximumDurationNanos;

    HitchCluster(String key, Diagnosis diagnosis, int representativeLimit) {
        this.key = key;
        this.diagnosis = diagnosis;
        this.representativeLimit = representativeLimit;
    }

    int add(HitchCapture capture, int remainingDetailedSamples) {
        occurrences++;
        long duration = capture.getPrimaryDurationNanos();
        totalDurationNanos += duration;
        maximumDurationNanos = Math.max(maximumDurationNanos, duration);
        for (HitchTrigger trigger : capture.getTriggers()) {
            if (firstEpochMillis == 0L || trigger.getEpochMillis() < firstEpochMillis) firstEpochMillis = trigger.getEpochMillis();
            lastEpochMillis = Math.max(lastEpochMillis, trigger.getEpochMillis());
            Integer old = triggerCounts.get(trigger.getType());
            triggerCounts.put(trigger.getType(), Integer.valueOf(old == null ? 1 : old.intValue() + 1));
        }
        int removedSamples = 0;
        if (representatives.size() < representativeLimit) {
            if (capture.getSamples().size() > remainingDetailedSamples) return 0;
            representatives.add(capture);
        } else {
            HitchCapture smallest = Collections.min(representatives, new Comparator<HitchCapture>() {
                @Override public int compare(HitchCapture left, HitchCapture right) {
                    return Long.compare(left.getPrimaryDurationNanos(), right.getPrimaryDurationNanos());
                }
            });
            if (capture.getPrimaryDurationNanos() <= smallest.getPrimaryDurationNanos()) return 0;
            removedSamples = smallest.getSamples().size();
            if (capture.getSamples().size() > remainingDetailedSamples + removedSamples) return 0;
            representatives.remove(smallest);
            representatives.add(capture);
        }
        Collections.sort(representatives, new Comparator<HitchCapture>() {
            @Override public int compare(HitchCapture left, HitchCapture right) {
                return Long.compare(right.getPrimaryDurationNanos(), left.getPrimaryDurationNanos());
            }
        });
        return capture.getSamples().size() - removedSamples;
    }

    public String getKey() { return key; }
    public Diagnosis getDiagnosis() { return diagnosis; }
    public long getOccurrences() { return occurrences; }
    public long getFirstEpochMillis() { return firstEpochMillis; }
    public long getLastEpochMillis() { return lastEpochMillis; }
    public double getAverageDurationMs() { return occurrences == 0L ? 0.0D : totalDurationNanos / 1_000_000.0D / occurrences; }
    public double getMaximumDurationMs() { return maximumDurationNanos / 1_000_000.0D; }
    public List<HitchCapture> getRepresentatives() { return Collections.unmodifiableList(representatives); }
    public Map<TriggerType, Integer> getTriggerCounts() { return Collections.unmodifiableMap(triggerCounts); }
}
