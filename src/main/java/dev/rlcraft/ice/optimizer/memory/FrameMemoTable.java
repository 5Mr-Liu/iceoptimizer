package dev.rlcraft.ice.optimizer.memory;

import java.util.function.LongFunction;
import org.agrona.collections.Long2ObjectHashMap;

/** Render-thread-only exact-key memo table, automatically cleared on generation change. */
public final class FrameMemoTable<V> {
    private final Long2ObjectHashMap<V> values;
    private final int maximumEntries;
    private long generation = Long.MIN_VALUE;

    public FrameMemoTable(int maximumEntries) {
        this.maximumEntries = Math.max(16, maximumEntries);
        this.values = new Long2ObjectHashMap<V>(Math.min(this.maximumEntries, 1024), 0.65F);
    }

    public V getOrCompute(long currentGeneration, long exactKey, LongFunction<V> loader) {
        if (generation != currentGeneration) {
            values.clear();
            generation = currentGeneration;
        }
        V existing = values.get(exactKey);
        if (existing != null || values.containsKey(exactKey)) return existing;
        V loaded = loader.apply(exactKey);
        if (loaded == null) return null;
        if (values.size() >= maximumEntries) values.clear();
        values.put(exactKey, loaded);
        return loaded;
    }

    public void clear() {
        values.clear();
        generation = Long.MIN_VALUE;
    }

    public int size() { return values.size(); }
}
