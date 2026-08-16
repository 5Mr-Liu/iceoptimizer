package dev.rlcraft.ice.profiler.sampling;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class StackTraceRepository {
    public static final int OVERFLOW_ID = 0;

    private final int maximumEntries;
    private final int maximumDepth;
    private final Map<StackKey, Integer> ids = new HashMap<StackKey, Integer>();
    private final List<StackTraceElement[]> traces = new ArrayList<StackTraceElement[]>();
    private long overflowCount;

    public StackTraceRepository(int maximumEntries, int maximumDepth) {
        this.maximumEntries = Math.max(1, maximumEntries);
        this.maximumDepth = Math.max(1, maximumDepth);
        traces.add(new StackTraceElement[] { new StackTraceElement("ice.profiler", "dictionaryOverflow", null, -1) });
    }

    public synchronized int intern(StackTraceElement[] source) {
        int depth = Math.min(source == null ? 0 : source.length, maximumDepth);
        StackTraceElement[] value;
        if (source != null && depth == source.length) value = source;
        else {
            value = new StackTraceElement[depth];
            if (depth > 0) System.arraycopy(source, 0, value, 0, depth);
        }
        StackKey lookup = new StackKey(value);
        Integer existing = ids.get(lookup);
        if (existing != null) return existing.intValue();
        if (ids.size() >= maximumEntries) {
            overflowCount++;
            return OVERFLOW_ID;
        }
        int id = traces.size();
        ids.put(lookup, Integer.valueOf(id));
        traces.add(value);
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
}
