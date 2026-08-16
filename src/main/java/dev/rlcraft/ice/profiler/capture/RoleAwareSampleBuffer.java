package dev.rlcraft.ice.profiler.capture;

import dev.rlcraft.ice.profiler.core.FixedRingBuffer;
import dev.rlcraft.ice.profiler.sampling.StackSample;
import dev.rlcraft.ice.profiler.sampling.ThreadRole;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded per-role storage used while a hitch is active. Each role keeps its
 * newest samples independently, then the final snapshot applies weighted
 * quotas so a large worker pool cannot evict both game threads.
 */
final class RoleAwareSampleBuffer {
    private final int perRoleCapacity;
    private final Map<ThreadRole, FixedRingBuffer<StackSample>> samples =
        new EnumMap<ThreadRole, FixedRingBuffer<StackSample>>(ThreadRole.class);
    private long added;

    RoleAwareSampleBuffer(int perRoleCapacity) {
        this.perRoleCapacity = Math.max(1, perRoleCapacity);
    }

    void add(StackSample sample) {
        if (sample == null) return;
        ThreadRole role = sample.getRole() == null ? ThreadRole.OTHER : sample.getRole();
        FixedRingBuffer<StackSample> buffer = samples.get(role);
        if (buffer == null) {
            buffer = new FixedRingBuffer<StackSample>(perRoleCapacity);
            samples.put(role, buffer);
        }
        buffer.add(sample);
        added++;
    }

    Selection select(int maximumSamples) {
        int limit = Math.max(0, maximumSamples);
        if (limit == 0 || samples.isEmpty()) return new Selection(Collections.<StackSample>emptyList(), added);

        List<RoleSlice> slices = new ArrayList<RoleSlice>(samples.size());
        int retained = 0;
        int totalWeight = 0;
        for (ThreadRole role : ThreadRole.values()) {
            FixedRingBuffer<StackSample> buffer = samples.get(role);
            if (buffer == null || buffer.size() == 0) continue;
            RoleSlice slice = new RoleSlice(role, buffer.snapshot());
            slices.add(slice);
            retained += slice.values.size();
            totalWeight += weight(role);
        }

        if (retained <= limit) {
            List<StackSample> all = new ArrayList<StackSample>(retained);
            for (RoleSlice slice : slices) all.addAll(slice.values);
            sortChronologically(all);
            return new Selection(all, Math.max(0L, added - all.size()));
        }

        List<StackSample> selected = new ArrayList<StackSample>(limit);
        for (RoleSlice slice : slices) {
            int quota = (int) ((long) limit * weight(slice.role) / Math.max(1, totalWeight));
            int take = Math.min(slice.values.size(), quota);
            slice.nextOlder = slice.values.size() - take - 1;
            for (int index = slice.values.size() - take; index < slice.values.size(); index++) {
                selected.add(slice.values.get(index));
            }
        }

        while (selected.size() < limit) {
            RoleSlice newest = null;
            StackSample newestSample = null;
            for (RoleSlice slice : slices) {
                if (slice.nextOlder < 0) continue;
                StackSample candidate = slice.values.get(slice.nextOlder);
                if (newestSample == null || compareChronologically(candidate, newestSample) > 0) {
                    newest = slice;
                    newestSample = candidate;
                }
            }
            if (newest == null) break;
            selected.add(newestSample);
            newest.nextOlder--;
        }

        sortChronologically(selected);
        return new Selection(selected, Math.max(0L, added - selected.size()));
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
        private int nextOlder;

        private RoleSlice(ThreadRole role, List<StackSample> values) {
            this.role = role;
            this.values = values;
            this.nextOlder = values.size() - 1;
        }
    }
}
