package dev.rlcraft.ice.optimizer.memory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.github.benmanes.caffeine.cache.Weigher;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.concurrent.Executor;

/** Caffeine-backed exact cache with a hard weighted limit and explicit eviction callback. */
public final class BudgetedCache<K, V extends WeightedValue> {
    private final Cache<K, V> cache;
    private final AtomicLong estimatedWeight = new AtomicLong();

    public BudgetedCache(long maximumBytes, final Consumer<V> eviction) {
        cache = Caffeine.newBuilder()
            .maximumWeight(Math.max(1L, maximumBytes))
            .executor(new Executor() {
                @Override public void execute(Runnable command) { command.run(); }
            })
            .weigher(new Weigher<K, V>() {
                @Override public int weigh(K key, V value) { return Math.max(1, value.weightBytes()); }
            })
            .removalListener(new RemovalListener<K, V>() {
                @Override public void onRemoval(K key, V value, RemovalCause cause) {
                    if (value != null) {
                        if (cause != RemovalCause.REPLACED) subtractWeight(value.weightBytes());
                        if (eviction != null) eviction.accept(value);
                    }
                }
            })
            .build();
    }

    public V getIfPresent(K key) {
        return cache.getIfPresent(key);
    }

    public V get(K key, Function<? super K, ? extends V> loader) {
        V existing = cache.getIfPresent(key);
        if (existing != null) return existing;
        V loaded = loader.apply(key);
        if (loaded == null) return null;
        put(key, loaded);
        return loaded;
    }

    public void put(K key, V value) {
        V previous = cache.asMap().put(key, value);
        estimatedWeight.addAndGet(Math.max(1, value.weightBytes()));
        if (previous != null) subtractWeight(previous.weightBytes());
        cache.cleanUp();
    }

    public void invalidate(K key) {
        cache.invalidate(key);
        cache.cleanUp();
    }

    public void invalidateAll() {
        cache.invalidateAll();
        cache.cleanUp();
    }

    public long estimatedEntries() { return cache.estimatedSize(); }
    public long estimatedWeightBytes() { return Math.max(0L, estimatedWeight.get()); }

    private void subtractWeight(long weight) {
        while (true) {
            long current = estimatedWeight.get();
            if (estimatedWeight.compareAndSet(current, Math.max(0L, current - Math.max(1L, weight)))) return;
        }
    }
}
