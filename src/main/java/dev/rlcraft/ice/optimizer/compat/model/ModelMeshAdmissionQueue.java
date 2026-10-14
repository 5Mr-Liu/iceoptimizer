package dev.rlcraft.ice.optimizer.compat.model;

import java.util.Iterator;
import java.util.LinkedHashSet;

/** Pending payloads wait for reusable storage, rather than frame deadlines. */
final class ModelMeshAdmissionQueue<T> {
    private final LinkedHashSet<T> ready = new LinkedHashSet<T>();
    private final LinkedHashSet<T> waiting = new LinkedHashSet<T>();
    private long progress = Long.MIN_VALUE;

    void offer(T value) {
        waiting.remove(value);
        ready.add(value);
    }

    T poll() {
        Iterator<T> iterator = ready.iterator();
        if (!iterator.hasNext()) return null;
        T value = iterator.next();
        iterator.remove();
        return value;
    }

    void awaitStorage(T value) { waiting.add(value); }

    void storageProgress(long value) {
        if (progress == value) return;
        progress = value;
        ready.addAll(waiting);
        waiting.clear();
    }

    int readyCount() { return ready.size(); }

    void remove(T value) {
        ready.remove(value);
        waiting.remove(value);
    }

    void clear() {
        ready.clear();
        waiting.clear();
        progress = Long.MIN_VALUE;
    }
}
