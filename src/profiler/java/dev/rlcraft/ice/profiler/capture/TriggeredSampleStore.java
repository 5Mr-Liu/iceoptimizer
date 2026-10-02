package dev.rlcraft.ice.profiler.capture;

import dev.rlcraft.ice.profiler.sampling.StackSample;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** One fixed role budget: a recent tail plus confirmed trigger evidence. */
final class TriggeredSampleStore {
    private static final Comparator<ProtectedSample> PRIORITY = new Comparator<ProtectedSample>() {
        @Override public int compare(ProtectedSample a, ProtectedSample b) {
            int duration = Long.compare(a.duration, b.duration);
            return duration != 0 ? duration : Long.compare(a.tie, b.tie);
        }
    };
    private final StackSample[] tail;
    private final int protectedCapacity;
    private final List<ProtectedSample> protectedSamples;
    private final Map<StackSample, ProtectedSample> protectedBySample = new IdentityHashMap<StackSample, ProtectedSample>();
    private int cursor;
    private int size;

    TriggeredSampleStore(int capacity, boolean mainThread) {
        protectedCapacity = mainThread && capacity > 1 ? capacity / 2 : 0;
        tail = new StackSample[capacity - protectedCapacity];
        protectedSamples = new ArrayList<ProtectedSample>(protectedCapacity);
    }

    void add(StackSample sample, HitchTrigger latestTrigger) {
        tail[cursor] = sample;
        cursor = (cursor + 1) % tail.length;
        size = Math.min(size + 1, tail.length);
        if (protectedCapacity > 0 && latestTrigger != null
            && sample.getTimestampNanos() >= TriggerIntervals.start(latestTrigger)
            && sample.getTimestampNanos() <= latestTrigger.getTimestampNanos()) {
            protect(sample, latestTrigger.getDurationNanos());
        }
    }

    void protect(HitchTrigger trigger) {
        if (protectedCapacity == 0) return;
        long start = TriggerIntervals.start(trigger);
        long end = trigger.getTimestampNanos();
        // Sampling appends monotonically within a role. Scan only this interval,
        // without copying the rolling buffer on every merged trigger.
        for (int age = 0; age < size; age++) {
            int index = (cursor - 1 - age + tail.length) % tail.length;
            StackSample sample = tail[index];
            if (sample.getTimestampNanos() < start) break;
            if (sample.getTimestampNanos() <= end) protect(sample, trigger.getDurationNanos());
        }
    }

    private void protect(StackSample sample, long duration) {
        ProtectedSample previous = protectedBySample.get(sample);
        if (previous != null) {
            if (duration <= previous.duration) return;
            previous.duration = duration;
            siftDown(previous.heapIndex);
            return;
        }
        boolean full = protectedSamples.size() >= protectedCapacity;
        long tie;
        if (full) {
            ProtectedSample minimum = protectedSamples.get(0);
            int priority = Long.compare(duration, minimum.duration);
            if (priority < 0) return;
            tie = tieFor(sample);
            if (priority == 0 && Long.compare(tie, minimum.tie) <= 0) return;
        } else {
            tie = tieFor(sample);
        }
        ProtectedSample value = new ProtectedSample(sample, duration, tie);
        if (full) {
            ProtectedSample removed = protectedSamples.get(0);
            protectedBySample.remove(removed.sample);
            protectedSamples.set(0, value);
            value.heapIndex = 0;
            siftDown(0);
        } else {
            value.heapIndex = protectedSamples.size();
            protectedSamples.add(value);
            siftUp(value.heapIndex);
        }
        protectedBySample.put(sample, value);
    }

    private static long tieFor(StackSample sample) {
        long value = sample.getTimestampNanos() ^ sample.getThreadId();
        value = (value ^ (value >>> 33)) * 0xff51afd7ed558ccdL;
        value = (value ^ (value >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return value ^ (value >>> 33);
    }

    private void siftUp(int index) {
        while (index > 0) {
            int parent = (index - 1) >>> 1;
            if (PRIORITY.compare(protectedSamples.get(parent), protectedSamples.get(index)) <= 0) break;
            swap(parent, index);
            index = parent;
        }
    }

    private void siftDown(int index) {
        int size = protectedSamples.size();
        while (index < size / 2) {
            int child = index * 2 + 1;
            if (child + 1 < size && PRIORITY.compare(protectedSamples.get(child + 1), protectedSamples.get(child)) < 0) child++;
            if (PRIORITY.compare(protectedSamples.get(index), protectedSamples.get(child)) <= 0) break;
            swap(index, child);
            index = child;
        }
    }

    private void swap(int a, int b) {
        ProtectedSample left = protectedSamples.get(a);
        ProtectedSample right = protectedSamples.get(b);
        protectedSamples.set(a, right);
        protectedSamples.set(b, left);
        right.heapIndex = a;
        left.heapIndex = b;
    }

    List<StackSample> protectedSnapshot() {
        List<ProtectedSample> ordered = new ArrayList<ProtectedSample>(protectedSamples);
        Collections.sort(ordered, Collections.reverseOrder(PRIORITY));
        List<StackSample> result = new ArrayList<StackSample>(ordered.size());
        for (ProtectedSample sample : ordered) result.add(sample.sample);
        return result;
    }

    List<StackSample> recentSnapshot() {
        List<StackSample> result = new ArrayList<StackSample>(size);
        for (int age = 0; age < size; age++) {
            StackSample sample = tail[(cursor - 1 - age + tail.length) % tail.length];
            if (!protectedBySample.containsKey(sample)) result.add(sample);
        }
        return result;
    }

    private static final class ProtectedSample {
        final StackSample sample;
        final long tie;
        long duration;
        int heapIndex;
        ProtectedSample(StackSample sample, long duration, long tie) {
            this.sample = sample;
            this.duration = duration;
            this.tie = tie;
        }
    }
}
