package dev.rlcraft.ice.profiler.capture;

import dev.rlcraft.ice.profiler.sampling.StackSample;
import dev.rlcraft.ice.profiler.sampling.ThreadRole;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded per-role storage. Confirmed same-role trigger intervals survive
 * unrelated later samples; the remaining budget retains the recent tail.
 */
final class RoleAwareSampleBuffer {
    private final int perRoleCapacity;
    private final Map<ThreadRole, TriggeredSampleStore> samples =
        new EnumMap<ThreadRole, TriggeredSampleStore>(ThreadRole.class);
    private long added;
    private final HitchTrigger[] latestTriggers = new HitchTrigger[ThreadRole.values().length];

    RoleAwareSampleBuffer(int perRoleCapacity) {
        this.perRoleCapacity = Math.max(1, perRoleCapacity);
    }

    void add(StackSample sample) {
        if (sample == null) return;
        ThreadRole role = sample.getRole() == null ? ThreadRole.OTHER : sample.getRole();
        TriggeredSampleStore buffer = samples.get(role);
        if (buffer == null) {
            buffer = new TriggeredSampleStore(perRoleCapacity,
                role == ThreadRole.CLIENT_MAIN || role == ThreadRole.SERVER_MAIN);
            samples.put(role, buffer);
        }
        buffer.add(sample, latestTriggers[role.ordinal()]);
        added++;
    }

    void protect(HitchTrigger trigger) {
        ThreadRole role = TriggerIntervals.groupOf(trigger).getRole();
        if (role == null) return;
        latestTriggers[role.ordinal()] = trigger;
        TriggeredSampleStore buffer = samples.get(role);
        if (buffer != null) buffer.protect(trigger);
    }

    Selection select(int maximumSamples) {
        int limit = Math.max(0, maximumSamples);
        if (limit == 0 || samples.isEmpty()) return new Selection(Collections.<StackSample>emptyList(), added);

        List<RoleSlice> protectedSlices = new ArrayList<RoleSlice>();
        List<RoleSlice> recentSlices = new ArrayList<RoleSlice>();
        for (ThreadRole role : ThreadRole.values()) {
            TriggeredSampleStore buffer = samples.get(role);
            if (buffer == null) continue;
            List<StackSample> protectedValues = buffer.protectedSnapshot();
            List<StackSample> recentValues = buffer.recentSnapshot();
            if (!protectedValues.isEmpty()) protectedSlices.add(new RoleSlice(role, protectedValues));
            if (!recentValues.isEmpty()) recentSlices.add(new RoleSlice(role, recentValues));
        }
        List<StackSample> selected = new ArrayList<StackSample>(limit);
        selectSlices(protectedSlices, selected, limit);
        selectSlices(recentSlices, selected, limit);
        sortChronologically(selected);
        return new Selection(selected, Math.max(0L, added - selected.size()));
    }

    private static void selectSlices(List<RoleSlice> slices, List<StackSample> selected, int limit) {
        int remaining = limit - selected.size();
        int totalWeight = 0;
        for (RoleSlice slice : slices) totalWeight += weight(slice.role);
        for (RoleSlice slice : slices) {
            int quota = (int) ((long) remaining * weight(slice.role) / Math.max(1, totalWeight));
            int take = Math.min(slice.values.size(), quota);
            slice.next = take;
            for (int index = 0; index < take; index++) selected.add(slice.values.get(index));
        }

        while (selected.size() < limit) {
            RoleSlice newest = null;
            StackSample newestSample = null;
            for (RoleSlice slice : slices) {
                if (slice.next >= slice.values.size()) continue;
                StackSample candidate = slice.values.get(slice.next);
                if (newestSample == null || compareChronologically(candidate, newestSample) > 0) {
                    newest = slice;
                    newestSample = candidate;
                }
            }
            if (newest == null) break;
            selected.add(newestSample);
            newest.next++;
        }
    }

    long getAdded() {
        return added;
    }

    private static int weight(ThreadRole role) {
        switch (role) {
            case SERVER_MAIN: return 10;
            case CLIENT_MAIN: return 8;
            case CHUNK_WORKER: return 5;
            case CHUNK_IO: return 2;
            case FILE_IO: return 2;
            case WORKER: return 2;
            case NETWORK: return 1;
            default: return 1;
        }
    }

    private static void sortChronologically(List<StackSample> values) {
        Collections.sort(values, new Comparator<StackSample>() {
            @Override
            public int compare(StackSample left, StackSample right) {
                return compareChronologically(left, right);
            }
        });
    }

    private static int compareChronologically(StackSample left, StackSample right) {
        int timestamp = Long.compare(left.getTimestampNanos(), right.getTimestampNanos());
        if (timestamp != 0) return timestamp;
        int role = Integer.compare(left.getRole().ordinal(), right.getRole().ordinal());
        if (role != 0) return role;
        int thread = Long.compare(left.getThreadId(), right.getThreadId());
        return thread != 0 ? thread : Integer.compare(left.getStackTraceId(), right.getStackTraceId());
    }

    static final class Selection {
        private final List<StackSample> samples;
        private final long dropped;

        private Selection(List<StackSample> samples, long dropped) {
            this.samples = Collections.unmodifiableList(samples);
            this.dropped = dropped;
        }

        List<StackSample> getSamples() { return samples; }
        long getDropped() { return dropped; }
    }

    private static final class RoleSlice {
        private final ThreadRole role;
        private final List<StackSample> values;
        private int next;

        private RoleSlice(ThreadRole role, List<StackSample> values) {
            this.role = role;
            this.values = values;
        }
    }
}
