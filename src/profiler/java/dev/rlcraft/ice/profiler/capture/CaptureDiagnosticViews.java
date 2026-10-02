package dev.rlcraft.ice.profiler.capture;

import dev.rlcraft.ice.profiler.sampling.StackSample;
import dev.rlcraft.ice.profiler.sampling.ThreadRole;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Splits mixed-role incidents without duplicating samples or changing ICECAP v4. */
final class CaptureDiagnosticViews {
    private CaptureDiagnosticViews() { }

    static List<HitchCapture> split(HitchCapture source) {
        Map<TriggerIntervals.Group, List<HitchTrigger>> groups = new EnumMap<TriggerIntervals.Group, List<HitchTrigger>>(TriggerIntervals.Group.class);
        Map<TriggerIntervals.Group, List<StackSample>> samples = new EnumMap<TriggerIntervals.Group, List<StackSample>>(TriggerIntervals.Group.class);
        TriggerIntervals.Group context = TriggerIntervals.Group.MARKER;
        long longest = -1L;
        for (HitchTrigger trigger : source.getTriggers()) {
            TriggerIntervals.Group group = TriggerIntervals.groupOf(trigger);
            if (!groups.containsKey(group)) {
                groups.put(group, new ArrayList<HitchTrigger>());
                samples.put(group, new ArrayList<StackSample>());
            }
            groups.get(group).add(trigger);
            if (trigger.getDurationNanos() > longest) {
                longest = trigger.getDurationNanos();
                context = group;
            }
        }
        if (groups.isEmpty()) {
            groups.put(context, new ArrayList<HitchTrigger>());
            samples.put(context, new ArrayList<StackSample>());
        }
        for (StackSample sample : source.getSamples()) {
            TriggerIntervals.Group group = sample.getRole() == ThreadRole.CLIENT_MAIN ? TriggerIntervals.Group.CLIENT
                : sample.getRole() == ThreadRole.SERVER_MAIN ? TriggerIntervals.Group.SERVER : context;
            if (!groups.containsKey(group)) group = context;
            samples.get(group).add(sample);
        }
        List<HitchCapture> result = new ArrayList<HitchCapture>(groups.size());
        for (TriggerIntervals.Group group : TriggerIntervals.Group.values()) {
            if (groups.containsKey(group)) result.add(source.diagnosticView(
                source.getSequence() * TriggerIntervals.Group.values().length + group.ordinal(),
                groups.get(group), samples.get(group), groups.size() == 1));
        }
        return result;
    }
}
