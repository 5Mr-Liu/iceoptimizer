package dev.rlcraft.ice.profiler.capture;

import dev.rlcraft.ice.profiler.sampling.StackSample;
import dev.rlcraft.ice.profiler.sampling.ThreadRole;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Actual trigger intervals, kept separate from the surrounding capture window. */
public final class TriggerIntervals {
    public enum Group {
        CLIENT(ThreadRole.CLIENT_MAIN, "客户端主线程"),
        SERVER(ThreadRole.SERVER_MAIN, "服务端主线程"),
        GC(null, "GC"), MARKER(null, "标记捕获");
        private final ThreadRole role;
        private final String detail;
        Group(ThreadRole role, String detail) { this.role = role; this.detail = detail; }
        public ThreadRole getRole() { return role; }
        public String getDetail() { return detail; }
    }

    private final Group group;
    private final List<HitchTrigger> triggers;
    private final List<Interval> intervals = new ArrayList<Interval>();

    public TriggerIntervals(Group group, List<HitchTrigger> candidates) {
        this.group = group;
        List<HitchTrigger> selected = new ArrayList<HitchTrigger>();
        for (HitchTrigger trigger : candidates) if (groupOf(trigger) == group) selected.add(trigger);
        triggers = Collections.unmodifiableList(selected);
        List<Interval> ordered = new ArrayList<Interval>();
        for (HitchTrigger trigger : selected) ordered.add(new Interval(start(trigger), trigger.getTimestampNanos()));
        Collections.sort(ordered, new Comparator<Interval>() {
            @Override public int compare(Interval a, Interval b) { return Long.compare(a.start, b.start); }
        });
        for (Interval next : ordered) {
            if (intervals.isEmpty() || next.start > intervals.get(intervals.size() - 1).end) {
                intervals.add(next);
            } else {
                Interval previous = intervals.get(intervals.size() - 1);
                previous.end = Math.max(previous.end, next.end);
            }
        }
    }

    public static Group groupOf(HitchTrigger trigger) {
        switch (trigger.getType()) {
            case CLIENT_FRAME: case CLIENT_TICK: return Group.CLIENT;
            case SERVER_TICK: return Group.SERVER;
            case GC_PAUSE: return Group.GC;
            default: return Group.MARKER;
        }
    }

    public static long start(HitchTrigger trigger) {
        long end = trigger.getTimestampNanos();
        long duration = Math.max(0L, trigger.getDurationNanos());
        return end < Long.MIN_VALUE + duration ? Long.MIN_VALUE : end - duration;
    }

    public Group getGroup() { return group; }
    public List<HitchTrigger> getTriggers() { return triggers; }
    public boolean matches(StackSample sample) {
        if (group == Group.MARKER) return true;
        if (group.getRole() != null && sample.getRole() != group.getRole()) return false;
        return contains(sample.getTimestampNanos());
    }

    public boolean contains(long timestamp) {
        int low = 0, high = intervals.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            Interval value = intervals.get(middle);
            if (timestamp < value.start) high = middle - 1;
            else if (timestamp > value.end) low = middle + 1;
            else return true;
        }
        return false;
    }

    public int missingTriggerCount(List<StackSample> samples) {
        if (group.getRole() == null) return 0;
        List<Long> times = new ArrayList<Long>();
        for (StackSample sample : samples) if (sample.getRole() == group.getRole()) times.add(sample.getTimestampNanos());
        Collections.sort(times);
        int missing = 0;
        for (HitchTrigger trigger : triggers) {
            int index = Collections.binarySearch(times, Long.valueOf(start(trigger)));
            if (index < 0) index = -index - 1;
            if (index >= times.size() || times.get(index).longValue() > trigger.getTimestampNanos()) missing++;
        }
        return missing;
    }

    private static final class Interval {
        final long start;
        long end;
        Interval(long start, long end) { this.start = start; this.end = end; }
    }
}
