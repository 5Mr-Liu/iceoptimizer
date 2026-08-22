package dev.rlcraft.ice.profiler.sampling;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class StackTraceRepository {
    public static final int OVERFLOW_ID = 0;
    private static final int PREFIX_DEPTH = 3;
    private static final int OVERFLOW_HOTSPOTS = 16;
    private static final int HOTSPOT_LABEL_DEPTH = 4;
    private static final int MAX_STATISTICS_WINDOWS = 8;

    private final int maximumEntries;
    private final int maximumDepth;
    private final Map<StackKey, Integer> ids = new HashMap<StackKey, Integer>();
    private final Map<PrefixKey, Integer> prefixIds =
        new HashMap<PrefixKey, Integer>();
    private final List<StackTraceElement[]> traces = new ArrayList<StackTraceElement[]>();
    private final HotspotCounter[] overflowHotspots =
        new HotspotCounter[OVERFLOW_HOTSPOTS];
    private final List<StatisticsWindow> statisticsWindows =
        new ArrayList<StatisticsWindow>(2);
    // intern is synchronized, so these mutable probes remove per-sample key
    // allocation without ever becoming stored HashMap keys.
    private final StackProbe stackProbe = new StackProbe();
    private final PrefixProbe prefixProbe = new PrefixProbe();
    private long overflowCount;
    private long prefixMergedCount;
    private long droppedCount;

    public StackTraceRepository(int maximumEntries, int maximumDepth) {
        this.maximumEntries = Math.max(1, maximumEntries);
        this.maximumDepth = Math.max(1, maximumDepth);
        traces.add(new StackTraceElement[] { new StackTraceElement("ice.profiler", "dictionaryOverflow", null, -1) });
    }

    public synchronized int intern(StackTraceElement[] source) {
        int depth = Math.min(source == null ? 0 : source.length, maximumDepth);
        stackProbe.set(source, depth);
        Integer existing = ids.get(stackProbe);
        stackProbe.clear();
        if (existing != null) return existing.intValue();
        if (ids.size() >= maximumEntries) {
            overflowCount = saturatedIncrement(overflowCount);
            prefixProbe.set(source, depth);
            Integer representative = depth == 0 ? null : prefixIds.get(prefixProbe);
            prefixProbe.clear();
            if (representative != null) {
                prefixMergedCount = saturatedIncrement(prefixMergedCount);
                return representative.intValue();
            }
            droppedCount = saturatedIncrement(droppedCount);
            recordDropped(source, depth);
            return OVERFLOW_ID;
        }
        // Never retain the sampler/caller's mutable array as a dictionary key.
        StackTraceElement[] value = depth == 0
            ? new StackTraceElement[0] : Arrays.copyOf(source, depth);
        StackKey stored = new StackKey(value);
        int id = traces.size();
        ids.put(stored, Integer.valueOf(id));
        traces.add(value);
        PrefixKey prefix = PrefixKey.of(value);
        if (prefix != null && !prefixIds.containsKey(prefix)) {
            prefixIds.put(prefix, Integer.valueOf(id));
        }
        return id;
    }

    public synchronized StackTraceElement[] get(int id) {
        if (id < 0 || id >= traces.size()) return new StackTraceElement[0];
        StackTraceElement[] trace = traces.get(id);
        return Arrays.copyOf(trace, trace.length);
    }

    public synchronized List<StackTraceRecord> snapshot() {
        List<StackTraceRecord> result = new ArrayList<StackTraceRecord>(traces.size());
        for (int i = 0; i < traces.size(); i++) {
            result.add(new StackTraceRecord(i, Arrays.copyOf(traces.get(i), traces.get(i).length)));
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized int size() { return traces.size(); }
    public synchronized long getOverflowCount() { return overflowCount; }
    public synchronized long getPrefixMergedCount() { return prefixMergedCount; }
    public synchronized long getDroppedCount() { return droppedCount; }

    /** Starts an exact, independently bounded statistics window. */
    public synchronized StatisticsWindow beginStatisticsWindow() {
        StatisticsWindow window = new StatisticsWindow(overflowCount,
            prefixMergedCount, droppedCount,
            statisticsWindows.size() < MAX_STATISTICS_WINDOWS);
        if (window.hotspots != null) statisticsWindows.add(window);
        return window;
    }

    public synchronized Statistics statisticsSince(StatisticsWindow window) {
        return statisticsSinceLocked(window);
    }

    /** Freezes one session window and releases its hot-path update slot. */
    public synchronized Statistics finishStatisticsWindow(
        StatisticsWindow window) {
        Statistics result = statisticsSinceLocked(window);
        if (window != null && window.finishedStatistics == null) {
            if (window.active) {
                for (int i = 0; i < statisticsWindows.size(); i++) {
                    if (statisticsWindows.get(i) == window) {
                        statisticsWindows.remove(i);
                        break;
                    }
                }
                window.active = false;
            }
            window.finishedHotspots = result.getHotspots();
            window.finishedStatistics = result;
        }
        return result;
    }

    private Statistics statisticsSinceLocked(StatisticsWindow window) {
        if (window != null && window.finishedStatistics != null) {
            return window.finishedStatistics;
        }
        long overflowStart = window == null ? 0L : window.overflow;
        long mergedStart = window == null ? 0L : window.prefixMerged;
        long droppedStart = window == null ? 0L : window.dropped;
        List<OverflowHotspot> hotspots = window == null
            ? hotspotSnapshot(overflowHotspots)
            : window.active ? hotspotSnapshot(window.hotspots)
            : window.finishedHotspots;
        return new Statistics(nonNegativeDifference(overflowCount, overflowStart),
            nonNegativeDifference(prefixMergedCount, mergedStart),
            nonNegativeDifference(droppedCount, droppedStart),
            hotspots == null
                ? Collections.<OverflowHotspot>emptyList() : hotspots);
    }

    public synchronized List<OverflowHotspot> getOverflowHotspots() {
        return hotspotSnapshot(overflowHotspots);
    }

    private void recordDropped(StackTraceElement[] trace, int traceDepth) {
        recordDropped(overflowHotspots, trace, traceDepth);
        for (int i = 0; i < statisticsWindows.size(); i++) {
            recordDropped(statisticsWindows.get(i).hotspots, trace, traceDepth);
        }
    }

    private static void recordDropped(HotspotCounter[] counters,
                                      StackTraceElement[] trace,
                                      int traceDepth) {
        if (counters == null) return;
        int empty = -1;
        int minimum = -1;
        for (int i = 0; i < counters.length; i++) {
            HotspotCounter item = counters[i];
            if (item == null) {
                if (empty < 0) empty = i;
                continue;
            }
            if (item.matches(trace, traceDepth)) {
                item.count = saturatedIncrement(item.count);
                return;
            }
            if (minimum < 0 || item.count < counters[minimum].count) {
                minimum = i;
            }
        }
        if (empty >= 0) {
            counters[empty] = new HotspotCounter(trace, traceDepth,
                1L, 0L);
            return;
        }
        HotspotCounter replaced = counters[minimum];
        long previous = replaced.count;
        replaced.replace(trace, traceDepth, saturatedIncrement(previous),
            previous);
    }

    private static List<OverflowHotspot> hotspotSnapshot(
        HotspotCounter[] counters) {
        if (counters == null) return Collections.emptyList();
        List<OverflowHotspot> result = new ArrayList<OverflowHotspot>(
            counters.length);
        for (HotspotCounter item : counters) {
            if (item != null) {
                result.add(new OverflowHotspot(item.label(), item.count, item.error));
            }
        }
        Collections.sort(result, new java.util.Comparator<OverflowHotspot>() {
            @Override public int compare(OverflowHotspot left,
                                         OverflowHotspot right) {
                int count = Long.compare(right.estimatedCount,
                    left.estimatedCount);
                return count != 0 ? count : left.prefix.compareTo(right.prefix);
            }
        });
        return Collections.unmodifiableList(result);
    }

    private static long nonNegativeDifference(long value, long baseline) {
        return value >= baseline ? value - baseline : 0L;
    }

    private static long saturatedIncrement(long value) {
        return value == Long.MAX_VALUE ? value : value + 1L;
    }

    private static final class StackKey {
        private final StackTraceElement[] elements;
        private final int hash;

        private StackKey(StackTraceElement[] elements) {
            this.elements = elements;
            this.hash = Arrays.hashCode(elements);
        }

        @Override
        public int hashCode() { return hash; }

        @Override
        public boolean equals(Object other) {
            return other instanceof StackKey && Arrays.equals(elements, ((StackKey) other).elements);
        }
    }

    private static final class StackProbe {
        private StackTraceElement[] elements;
        private int length;
        private int hash;

        private void set(StackTraceElement[] elements, int length) {
            this.elements = elements;
            this.length = length;
            int value = 1;
            for (int i = 0; i < length; i++) {
                StackTraceElement element = elements[i];
                value = 31 * value + (element == null ? 0 : element.hashCode());
            }
            hash = value;
        }

        private void clear() {
            elements = null;
            length = 0;
            hash = 0;
        }

        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof StackKey)) return false;
            StackTraceElement[] stored = ((StackKey) other).elements;
            if (stored.length != length) return false;
            for (int i = 0; i < length; i++) {
                StackTraceElement left = elements[i];
                if (left == null ? stored[i] != null : !left.equals(stored[i])) return false;
            }
            return true;
        }
    }

    private static final class PrefixKey {
        private final String[] parts;
        private final int hash;

        private PrefixKey(String[] parts) {
            this.parts = parts;
            this.hash = Arrays.hashCode(parts);
        }

        private static PrefixKey of(StackTraceElement[] trace) {
            if (trace == null || trace.length == 0) return null;
            int depth = Math.min(trace.length, PREFIX_DEPTH);
            String[] parts = new String[depth << 1];
            for (int i = 0; i < depth; i++) {
                StackTraceElement frame = trace[i];
                parts[i << 1] = frame == null ? "" : frame.getClassName();
                parts[(i << 1) + 1] = frame == null ? "" : frame.getMethodName();
            }
            return new PrefixKey(parts);
        }

        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return other instanceof PrefixKey
                && Arrays.equals(parts, ((PrefixKey) other).parts);
        }
    }

    private static final class PrefixProbe {
        private StackTraceElement[] trace;
        private int depth;
        private int hash;

        private void set(StackTraceElement[] trace, int traceDepth) {
            this.trace = trace;
            depth = Math.min(traceDepth, PREFIX_DEPTH);
            int value = 1;
            for (int i = 0; i < depth; i++) {
                StackTraceElement frame = trace[i];
                String owner = frame == null ? "" : frame.getClassName();
                String method = frame == null ? "" : frame.getMethodName();
                value = 31 * value + owner.hashCode();
                value = 31 * value + method.hashCode();
            }
            hash = value;
        }

        private void clear() {
            trace = null;
            depth = 0;
            hash = 0;
        }

        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof PrefixKey)) return false;
            String[] parts = ((PrefixKey) other).parts;
            if (parts.length != (depth << 1)) return false;
            for (int i = 0; i < depth; i++) {
                StackTraceElement frame = trace[i];
                String owner = frame == null ? "" : frame.getClassName();
                String method = frame == null ? "" : frame.getMethodName();
                if (!owner.equals(parts[i << 1])
                    || !method.equals(parts[(i << 1) + 1])) return false;
            }
            return true;
        }
    }

    private static final class HotspotCounter {
        private final String[] parts = new String[HOTSPOT_LABEL_DEPTH << 1];
        private int depth;
        private long count;
        private long error;

        private HotspotCounter(StackTraceElement[] trace, int traceDepth,
                               long count, long error) {
            replace(trace, traceDepth, count, error);
        }

        private void replace(StackTraceElement[] trace, int traceDepth,
                             long count, long error) {
            depth = Math.min(traceDepth, HOTSPOT_LABEL_DEPTH);
            Arrays.fill(parts, null);
            for (int i = 0; i < depth; i++) {
                StackTraceElement frame = trace[i];
                if (frame != null) {
                    parts[i << 1] = frame.getClassName();
                    parts[(i << 1) + 1] = frame.getMethodName();
                }
            }
            this.count = count;
            this.error = error;
        }

        private boolean matches(StackTraceElement[] trace, int traceDepth) {
            int comparedDepth = Math.min(traceDepth, HOTSPOT_LABEL_DEPTH);
            if (depth != comparedDepth) return false;
            for (int i = 0; i < depth; i++) {
                StackTraceElement frame = trace[i];
                String owner = frame == null ? null : frame.getClassName();
                String method = frame == null ? null : frame.getMethodName();
                if (!equal(owner, parts[i << 1])
                    || !equal(method, parts[(i << 1) + 1])) return false;
            }
            return true;
        }

        private String label() {
            if (depth == 0) return "<empty stack>";
            StringBuilder label = new StringBuilder(160);
            for (int i = 0; i < depth; i++) {
                if (i > 0) label.append(" <- ");
                String owner = parts[i << 1];
                String method = parts[(i << 1) + 1];
                if (owner == null) label.append("<null>");
                else label.append(owner).append('.').append(method);
            }
            return label.length() <= 512 ? label.toString()
                : label.substring(0, 512);
        }

        private static boolean equal(String left, String right) {
            return left == null ? right == null : left.equals(right);
        }
    }

    public static final class StatisticsWindow {
        private final long overflow;
        private final long prefixMerged;
        private final long dropped;
        private final HotspotCounter[] hotspots;
        private boolean active;
        private List<OverflowHotspot> finishedHotspots;
        private Statistics finishedStatistics;

        private StatisticsWindow(long overflow, long prefixMerged, long dropped,
                                 boolean trackHotspots) {
            this.overflow = overflow;
            this.prefixMerged = prefixMerged;
            this.dropped = dropped;
            this.hotspots = trackHotspots
                ? new HotspotCounter[OVERFLOW_HOTSPOTS] : null;
            this.active = trackHotspots;
            this.finishedHotspots = Collections.emptyList();
            this.finishedStatistics = null;
        }
    }

    public static final class Statistics {
        private final long overflow;
        private final long prefixMerged;
        private final long dropped;
        private final List<OverflowHotspot> hotspots;

        private Statistics(long overflow, long prefixMerged, long dropped,
                           List<OverflowHotspot> hotspots) {
            this.overflow = overflow;
            this.prefixMerged = prefixMerged;
            this.dropped = dropped;
            this.hotspots = hotspots;
        }

        public long getOverflow() { return overflow; }
        public long getPrefixMerged() { return prefixMerged; }
        public long getDropped() { return dropped; }
        public List<OverflowHotspot> getHotspots() { return hotspots; }
    }

    public static final class OverflowHotspot {
        private final String prefix;
        private final long estimatedCount;
        private final long maximumError;

        private OverflowHotspot(String prefix, long estimatedCount,
                                long maximumError) {
            this.prefix = prefix;
            this.estimatedCount = estimatedCount;
            this.maximumError = maximumError;
        }

        public String getPrefix() { return prefix; }
        public long getEstimatedCount() { return estimatedCount; }
        public long getMaximumError() { return maximumError; }
    }
}
