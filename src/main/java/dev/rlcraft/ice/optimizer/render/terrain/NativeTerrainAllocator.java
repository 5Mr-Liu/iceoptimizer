package dev.rlcraft.ice.optimizer.render.terrain;

import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Render-thread-owned vertex ranges. Best-fit holes coalesce on mesh removal. */
final class NativeTerrainAllocator {
    private final TreeMap<Integer, Range> byOffset = new TreeMap<Integer, Range>();
    private final TreeSet<Range> bySize = new TreeSet<Range>(new Comparator<Range>() {
        @Override public int compare(Range a, Range b) {
            int size = Integer.compare(a.count, b.count);
            return size == 0 ? Integer.compare(a.first, b.first) : size;
        }
    });
    private int end;

    Range allocate(int count) {
        Range hole = bySize.ceiling(new Range(0, count));
        if (hole == null) {
            Range result = new Range(end, count);
            end = Math.addExact(end, count);
            return result;
        }
        remove(hole);
        if (hole.count > count) add(new Range(hole.first + count, hole.count - count));
        return new Range(hole.first, count);
    }

    void free(Range range) {
        int first = range.first, count = range.count;
        Map.Entry<Integer, Range> entry = byOffset.lowerEntry(first);
        if (entry != null) {
            Range left = entry.getValue();
            if (left.first + left.count == first) {
                first = left.first;
                count += left.count;
                remove(left);
            }
        }
        Range right = byOffset.get(first + count);
        if (right != null) {
            count += right.count;
            remove(right);
        }
        if (first + count == end) end = first;
        else add(new Range(first, count));
    }

    private void add(Range range) { byOffset.put(range.first, range); bySize.add(range); }
    private void remove(Range range) { byOffset.remove(range.first); bySize.remove(range); }

    static final class Range {
        final int first, count;
        Range(int first, int count) { this.first = first; this.count = count; }
    }
}
