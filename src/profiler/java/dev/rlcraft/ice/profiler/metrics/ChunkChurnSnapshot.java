package dev.rlcraft.ice.profiler.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable bounded-state and event deltas for one profiler timeline interval. */
public final class ChunkChurnSnapshot {
    public static final ChunkChurnSnapshot EMPTY =
        new ChunkChurnSnapshot(Collections.<ChunkChurnDimensionSnapshot>emptyList(), 0L, 0);

    private final List<ChunkChurnDimensionSnapshot> dimensions;
    private final long stateEvictions;
    private final int trackedEntries;

    public ChunkChurnSnapshot(List<ChunkChurnDimensionSnapshot> dimensions,
                              long stateEvictions, int trackedEntries) {
        this.dimensions = Collections.unmodifiableList(
            new ArrayList<ChunkChurnDimensionSnapshot>(dimensions));
        this.stateEvictions = Math.max(0L, stateEvictions);
        this.trackedEntries = Math.max(0, trackedEntries);
    }

    public List<ChunkChurnDimensionSnapshot> getDimensions() { return dimensions; }
    public long getStateEvictions() { return stateEvictions; }
    public int getTrackedEntries() { return trackedEntries; }
    public long getDataBackedLoads() { return sum(Field.DATA_BACKED); }
    public long getLoadsWithoutDataEvent() { return sum(Field.NO_DATA); }
    public long getReloadWithinOneSecond() { return sum(Field.RELOAD_ONE); }
    public long getReloadWithinFiveSeconds() { return sum(Field.RELOAD_FIVE); }
    public long getReloadWithinThirtySeconds() { return sum(Field.RELOAD_THIRTY); }
    public long getShortUnloadWithinOneSecond() { return sum(Field.UNLOAD_ONE); }
    public long getShortUnloadWithinFiveSeconds() { return sum(Field.UNLOAD_FIVE); }
    public long getShortUnloadWithinThirtySeconds() { return sum(Field.UNLOAD_THIRTY); }

    private long sum(Field field) {
        long result = 0L;
        for (ChunkChurnDimensionSnapshot dimension : dimensions) {
            long addition;
            switch (field) {
                case DATA_BACKED: addition = dimension.getDataBackedLoads(); break;
                case NO_DATA: addition = dimension.getLoadsWithoutDataEvent(); break;
                case RELOAD_ONE: addition = dimension.getReloadWithinOneSecond(); break;
                case RELOAD_FIVE: addition = dimension.getReloadWithinFiveSeconds(); break;
                case RELOAD_THIRTY: addition = dimension.getReloadWithinThirtySeconds(); break;
                case UNLOAD_ONE: addition = dimension.getShortUnloadWithinOneSecond(); break;
                case UNLOAD_FIVE: addition = dimension.getShortUnloadWithinFiveSeconds(); break;
                case UNLOAD_THIRTY: addition = dimension.getShortUnloadWithinThirtySeconds(); break;
                default: throw new AssertionError(field);
            }
            result = addition > Long.MAX_VALUE - result
                ? Long.MAX_VALUE : result + addition;
        }
        return result;
    }

    private enum Field {
        DATA_BACKED,
        NO_DATA,
        RELOAD_ONE,
        RELOAD_FIVE,
        RELOAD_THIRTY,
        UNLOAD_ONE,
        UNLOAD_FIVE,
        UNLOAD_THIRTY
    }
}
