package dev.rlcraft.ice.profiler.sampling;

import java.util.List;

/** A lazy pre-window source, read only when a new capture is created. */
public interface RecentSampleSource {
    List<StackSample> recentSince(long timestampNanos);
}
