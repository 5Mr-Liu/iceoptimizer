package dev.rlcraft.ice.profiler.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FixedRingBuffer<T> {
    private final Object[] values;
    private int cursor;
    private int size;
    private long overwritten;

    public FixedRingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        values = new Object[capacity];
    }

    public synchronized void add(T value) {
        if (size == values.length) {
            overwritten++;
        } else {
            size++;
        }
        values[cursor] = value;
        cursor = (cursor + 1) % values.length;
    }

    @SuppressWarnings("unchecked")
    public synchronized List<T> snapshot() {
        if (size == 0) {
            return Collections.emptyList();
        }
        List<T> result = new ArrayList<T>(size);
        int start = cursor - size;
        if (start < 0) {
            start += values.length;
        }
        for (int i = 0; i < size; i++) {
            result.add((T) values[(start + i) % values.length]);
        }
        return result;
    }

    public synchronized void clear() {
        for (int i = 0; i < values.length; i++) {
            values[i] = null;
        }
        cursor = 0;
        size = 0;
        overwritten = 0L;
    }

    public synchronized int size() {
        return size;
    }

    public int capacity() {
        return values.length;
    }

    public synchronized long overwrittenCount() {
        return overwritten;
    }
}
