package dev.rlcraft.ice.profiler.metrics;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Correlates server chunk events without changing chunk lifetime or retaining chunk objects.
 * Persistent coordinate state and per-interval dimension buckets are both strictly bounded.
 */
public final class ChunkChurnTracker {
    static final int OVERFLOW_DIMENSION = Integer.MIN_VALUE;
    private static final int DEFAULT_MAX_TRACKED_CHUNKS = 1 << 15;
    private static final int MAX_DIMENSION_BUCKETS = 64;
    private static final long ONE_SECOND = TimeUnit.SECONDS.toNanos(1L);
    private static final long FIVE_SECONDS = TimeUnit.SECONDS.toNanos(5L);
    private static final long THIRTY_SECONDS = TimeUnit.SECONDS.toNanos(30L);

    private final int maximumTrackedChunks;
    private final LinkedHashMap<ChunkKey, State> states;
    private final Map<Integer, MutableDimension> interval =
        new HashMap<Integer, MutableDimension>();
    private long stateEvictions;

    public ChunkChurnTracker() {
        this(DEFAULT_MAX_TRACKED_CHUNKS);
    }

    ChunkChurnTracker(int maximumTrackedChunks) {
        if (maximumTrackedChunks <= 0) throw new IllegalArgumentException("maximumTrackedChunks");
        this.maximumTrackedChunks = maximumTrackedChunks;
        this.states = new LinkedHashMap<ChunkKey, State>(128, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ChunkKey, State> eldest) {
                boolean remove = size() > ChunkChurnTracker.this.maximumTrackedChunks;
                if (remove) stateEvictions = saturatedIncrement(stateEvictions);
                return remove;
            }
        };
    }

    public synchronized void dataLoaded(int dimension, int chunkX, int chunkZ,
                                        Object chunkIdentity) {
        dataLoaded(dimension, chunkX, chunkZ, chunkIdentity, System.nanoTime());
    }

    public synchronized void loaded(int dimension, int chunkX, int chunkZ,
                                    Object chunkIdentity) {
        loaded(dimension, chunkX, chunkZ, chunkIdentity, System.nanoTime());
    }

    public synchronized void unloaded(int dimension, int chunkX, int chunkZ) {
        unloaded(dimension, chunkX, chunkZ, System.nanoTime());
    }

    synchronized void dataLoaded(int dimension, int chunkX, int chunkZ,
                                 Object chunkIdentity, long nowNanos) {
        State state = state(dimension, chunkX, chunkZ);
        state.pendingDataIdentity = chunkIdentity == null
            ? null : new WeakReference<Object>(chunkIdentity);
        state.pendingDataNanos = nowNanos;
    }

    synchronized void loaded(int dimension, int chunkX, int chunkZ,
                             Object chunkIdentity, long nowNanos) {
        State state = state(dimension, chunkX, chunkZ);
        MutableDimension counters = counters(dimension);
        Object pendingIdentity = state.pendingDataIdentity == null
            ? null : state.pendingDataIdentity.get();
        if (chunkIdentity != null && pendingIdentity == chunkIdentity
            && state.pendingDataNanos <= nowNanos) {
            counters.dataBackedLoads = saturatedIncrement(
                counters.dataBackedLoads);
        } else {
            counters.loadsWithoutDataEvent = saturatedIncrement(
                counters.loadsWithoutDataEvent);
        }
        state.pendingDataIdentity = null;
        state.pendingDataNanos = 0L;

        if (!state.loaded && state.hasUnloadTime) {
            incrementReloadWindows(counters, elapsed(state.unloadNanos, nowNanos));
        }
        state.loaded = true;
        state.hasLoadTime = true;
        state.loadNanos = nowNanos;
        state.hasUnloadTime = false;
    }

    synchronized void unloaded(int dimension, int chunkX, int chunkZ, long nowNanos) {
        State state = state(dimension, chunkX, chunkZ);
        MutableDimension counters = counters(dimension);
        if (state.loaded && state.hasLoadTime) {
            incrementUnloadWindows(counters, elapsed(state.loadNanos, nowNanos));
        }
        state.loaded = false;
        state.hasLoadTime = false;
        state.hasUnloadTime = true;
        state.unloadNanos = nowNanos;
        state.pendingDataIdentity = null;
        state.pendingDataNanos = 0L;
    }

    public synchronized void removeDimension(int dimension) {
        Iterator<Map.Entry<ChunkKey, State>> iterator = states.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getKey().dimension == dimension) iterator.remove();
        }
    }

    public synchronized ChunkChurnSnapshot drain() {
        List<ChunkChurnDimensionSnapshot> dimensions =
            new ArrayList<ChunkChurnDimensionSnapshot>(interval.size());
        for (MutableDimension value : interval.values()) dimensions.add(value.snapshot());
        Collections.sort(dimensions, new Comparator<ChunkChurnDimensionSnapshot>() {
            @Override
            public int compare(ChunkChurnDimensionSnapshot left,
                               ChunkChurnDimensionSnapshot right) {
                return Integer.compare(left.getDimension(), right.getDimension());
            }
        });
        ChunkChurnSnapshot result = new ChunkChurnSnapshot(
            dimensions, stateEvictions, states.size());
        interval.clear();
        stateEvictions = 0L;
        return result;
    }

    public synchronized void clear() {
        states.clear();
        interval.clear();
        stateEvictions = 0L;
    }

    private State state(int dimension, int chunkX, int chunkZ) {
        ChunkKey key = new ChunkKey(dimension, chunkX, chunkZ);
        State result = states.get(key);
        if (result == null) {
            result = new State();
            states.put(key, result);
        }
        return result;
    }

    private MutableDimension counters(int dimension) {
        Integer key = Integer.valueOf(dimension);
        MutableDimension result = interval.get(key);
        if (result != null) return result;
        if (interval.size() >= MAX_DIMENSION_BUCKETS - 1) {
            key = Integer.valueOf(OVERFLOW_DIMENSION);
            result = interval.get(key);
            if (result != null) return result;
            dimension = OVERFLOW_DIMENSION;
        }
        result = new MutableDimension(dimension);
        interval.put(key, result);
        return result;
    }

    private static long elapsed(long start, long end) {
        long result = end - start;
        return result < 0L ? Long.MAX_VALUE : result;
    }

    private static void incrementReloadWindows(MutableDimension counters, long elapsed) {
        if (elapsed <= ONE_SECOND) counters.reloadWithinOneSecond =
            saturatedIncrement(counters.reloadWithinOneSecond);
        if (elapsed <= FIVE_SECONDS) counters.reloadWithinFiveSeconds =
            saturatedIncrement(counters.reloadWithinFiveSeconds);
        if (elapsed <= THIRTY_SECONDS) counters.reloadWithinThirtySeconds =
            saturatedIncrement(counters.reloadWithinThirtySeconds);
    }

    private static void incrementUnloadWindows(MutableDimension counters, long elapsed) {
        if (elapsed <= ONE_SECOND) counters.shortUnloadWithinOneSecond =
            saturatedIncrement(counters.shortUnloadWithinOneSecond);
        if (elapsed <= FIVE_SECONDS) counters.shortUnloadWithinFiveSeconds =
            saturatedIncrement(counters.shortUnloadWithinFiveSeconds);
        if (elapsed <= THIRTY_SECONDS) counters.shortUnloadWithinThirtySeconds =
            saturatedIncrement(counters.shortUnloadWithinThirtySeconds);
    }

    private static long saturatedIncrement(long value) {
        return value == Long.MAX_VALUE ? value : value + 1L;
    }

    private static final class ChunkKey {
        private final int dimension;
        private final int chunkX;
        private final int chunkZ;

        private ChunkKey(int dimension, int chunkX, int chunkZ) {
            this.dimension = dimension;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        @Override
        public int hashCode() {
            int hash = dimension * 0x9e3779b9;
            hash = 31 * hash + chunkX;
            return 31 * hash + chunkZ;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ChunkKey)) return false;
            ChunkKey key = (ChunkKey) other;
            return dimension == key.dimension && chunkX == key.chunkX && chunkZ == key.chunkZ;
        }
    }

    private static final class State {
        private boolean loaded;
        private boolean hasLoadTime;
        private boolean hasUnloadTime;
        private long loadNanos;
        private long unloadNanos;
        private long pendingDataNanos;
        private WeakReference<Object> pendingDataIdentity;
    }

    private static final class MutableDimension {
        private final int dimension;
        private long dataBackedLoads;
        private long loadsWithoutDataEvent;
        private long reloadWithinOneSecond;
        private long reloadWithinFiveSeconds;
        private long reloadWithinThirtySeconds;
        private long shortUnloadWithinOneSecond;
        private long shortUnloadWithinFiveSeconds;
        private long shortUnloadWithinThirtySeconds;

        private MutableDimension(int dimension) {
            this.dimension = dimension;
        }

        private ChunkChurnDimensionSnapshot snapshot() {
            return new ChunkChurnDimensionSnapshot(
                dimension,
                dataBackedLoads,
                loadsWithoutDataEvent,
                reloadWithinOneSecond,
                reloadWithinFiveSeconds,
                reloadWithinThirtySeconds,
                shortUnloadWithinOneSecond,
                shortUnloadWithinFiveSeconds,
                shortUnloadWithinThirtySeconds);
        }
    }
}
