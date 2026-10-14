package dev.rlcraft.ice.optimizer.compat.otg;

import com.pg85.otg.common.LocalMaterialData;
import com.pg85.otg.util.ChunkCoordinate;
import com.pg85.otg.util.FifoMap;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.*;
import net.minecraft.world.chunk.Chunk;

/** Bounded columns with the same lifetime as their generated, unpopulated chunk. */
public final class OtgColumnCache extends FifoMap<Long, LocalMaterialData[]> {
    private final Map<Long, Set<Long>> byChunk = new HashMap<Long, Set<Long>>();
    public OtgColumnCache(int maximum) { super(maximum); }
    @Override public LocalMaterialData[] get(Object key) {
        return OptimizerBridge.isEnabled("otg-column-queries") ? super.get(key) : null;
    }
    public static Long key(int x, int z) { return Long.valueOf(pack(x, z)); }
    private static long pack(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
    private static Long chunkKey(long column) { return key((int) (column >> 32) >> 4, (int) column >> 4); }
    @Override public LocalMaterialData[] put(Long key, LocalMaterialData[] value) {
        Set<Long> columns = byChunk.get(chunkKey(key.longValue()));
        if (columns == null) { columns = new HashSet<Long>(); byChunk.put(chunkKey(key.longValue()), columns); }
        columns.add(key);
        return super.put(key, value);
    }
    @Override protected boolean removeEldestEntry(Map.Entry<Long, LocalMaterialData[]> eldest) {
        if (!super.removeEldestEntry(eldest)) return false;
        unlink(eldest.getKey()); return true;
    }
    @Override public LocalMaterialData[] remove(Object key) {
        LocalMaterialData[] value = super.remove(key);
        if (value != null) unlink((Long) key);
        return value;
    }
    private void unlink(Long key) {
        Long chunk = chunkKey(key.longValue()); Set<Long> columns = byChunk.get(chunk);
        if (columns != null && columns.remove(key) && columns.isEmpty()) byChunk.remove(chunk);
    }
    public void invalidate(ChunkCoordinate chunk) {
        Set<Long> columns = byChunk.remove(key(chunk.getChunkX(), chunk.getChunkZ()));
        if (columns != null) for (Long column : columns) super.remove(column);
    }
    @Override public void clear() { super.clear(); byChunk.clear(); }
    public static void invalidate(FifoMap<?, ?> cache, ChunkCoordinate chunk) {
        ((OtgColumnCache) cache).invalidate(chunk);
    }
    public static final class PreviewChunks extends FifoMap<ChunkCoordinate, Chunk> {
        private final OtgColumnCache columns;
        public PreviewChunks(int maximum, FifoMap<?, ?> columns) { super(maximum); this.columns = (OtgColumnCache) columns; }
        @Override public Chunk put(ChunkCoordinate key, Chunk value) {
            Chunk previous = super.put(key, value);
            if (previous != null && previous != value) columns.invalidate(key);
            return previous;
        }
        @Override public Chunk remove(Object key) { columns.invalidate((ChunkCoordinate) key); return super.remove(key); }
        @Override protected boolean removeEldestEntry(Map.Entry<ChunkCoordinate, Chunk> eldest) {
            if (!super.removeEldestEntry(eldest)) return false;
            columns.invalidate(eldest.getKey()); return true;
        }
        @Override public void clear() { super.clear(); columns.clear(); }
    }
}
