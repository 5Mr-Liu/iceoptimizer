package dev.rlcraft.ice.profiler.analysis;

import dev.rlcraft.ice.profiler.capture.HitchCapture;
import dev.rlcraft.ice.profiler.capture.HitchTrigger;
import dev.rlcraft.ice.profiler.capture.TriggerIntervals;
import dev.rlcraft.ice.profiler.sampling.StackSample;
import dev.rlcraft.ice.profiler.sampling.ThreadRole;
import java.util.ArrayList;
import java.util.List;

/** No nearby-stack fallback: only the union of real intervals for each target role. */
final class AttributionSamples {
    final List<StackSample> samples;
    final String detail;
    final int missingTriggers;

    private AttributionSamples(List<StackSample> samples, String detail, int missingTriggers) {
        this.samples = samples;
        this.detail = detail;
        this.missingTriggers = missingTriggers;
    }

    boolean covers(HitchTrigger trigger) {
        ThreadRole role = TriggerIntervals.groupOf(trigger).getRole();
        long start = TriggerIntervals.start(trigger);
        for (StackSample sample : samples) {
            if (sample.getRole() == role && sample.getTimestampNanos() >= start
                && sample.getTimestampNanos() <= trigger.getTimestampNanos()) return true;
        }
        return false;
    }

    static AttributionSamples select(HitchCapture capture) {
        List<StackSample> all = capture.getSamples();
        List<TriggerIntervals> roles = new ArrayList<TriggerIntervals>();
        boolean marker = capture.getTriggers().isEmpty();
        for (TriggerIntervals.Group group : TriggerIntervals.Group.values()) {
            TriggerIntervals intervals = new TriggerIntervals(group, capture.getTriggers());
            if (group.getRole() != null && !intervals.getTriggers().isEmpty()) roles.add(intervals);
            if (group == TriggerIntervals.Group.MARKER && !intervals.getTriggers().isEmpty()) marker = true;
        }
        List<StackSample> selected = new ArrayList<StackSample>();
        if (!roles.isEmpty()) {
            int missing = 0;
            for (TriggerIntervals intervals : roles) missing += intervals.missingTriggerCount(all);
            for (StackSample sample : all) {
                for (TriggerIntervals intervals : roles) {
                    if (intervals.matches(sample)) { selected.add(sample); break; }
                }
            }
            String detail = roles.size() == 1 ? "实际触发区间" + roles.get(0).getGroup().getDetail()
                : "按线程角色划分的实际触发区间并集";
            return new AttributionSamples(selected, detail, missing);
        }
        if (marker) {
            for (StackSample sample : all) {
                if (sample.getRole() == ThreadRole.CLIENT_MAIN || sample.getRole() == ThreadRole.SERVER_MAIN) selected.add(sample);
            }
            if (selected.isEmpty()) selected.addAll(all);
            return new AttributionSamples(selected, "手动/标记窗口", 0);
        }
        // GC collection-time deltas have no measured target-thread execution stack.
        return new AttributionSamples(selected, "GC 触发信号", 0);
    }
}
