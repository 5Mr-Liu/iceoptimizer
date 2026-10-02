package dev.rlcraft.ice.profiler.sampling;

import java.util.Arrays;

public final class StackTraceRecord {
    private final int id;
    private final StackTraceElement[] elements;

    StackTraceRecord(int id, StackTraceElement[] elements) {
        this.id = id;
        this.elements = elements;
    }

    public int getId() { return id; }
    public StackTraceElement[] getElements() { return Arrays.copyOf(elements, elements.length); }
}
