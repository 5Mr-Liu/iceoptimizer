package dev.rlcraft.ice.optimizer.compat.events;

import it.unimi.dsi.fastutil.longs.AbstractLongIterator;
import it.unimi.dsi.fastutil.longs.AbstractLongSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.AbstractObjectCollection;
import it.unimi.dsi.fastutil.objects.AbstractObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.Map;
import net.minecraft.world.chunk.Chunk;

/** Original fastutil table and iteration order, with an owned snapshot invalidated by mutations. */
public final class TrackedLoadedChunkMap extends Long2ObjectOpenHashMap<Chunk> {
    private transient Chunk[] snapshot;
    private transient Values trackedValues;
    private transient LongSet trackedKeys;
    private transient FastEntrySet<Chunk> trackedEntries;
    public TrackedLoadedChunkMap() { super(); }
    public TrackedLoadedChunkMap(int expected) { super(expected); }
    private void changed() { snapshot = null; }
    @Override public Chunk put(long key, Chunk value) { changed(); return super.put(key, value); }
    @Override public Chunk put(Long key, Chunk value) { changed(); return super.put(key, value); }
    @Override public Chunk remove(long key) { changed(); return super.remove(key); }
    @Override public Chunk remove(Object key) { changed(); return super.remove(key); }
    @Override public void clear() { changed(); super.clear(); }
    @Override public void putAll(Map<? extends Long, ? extends Chunk> values) { changed(); super.putAll(values); }
    @Override protected void rehash(int capacity) { changed(); super.rehash(capacity); }
    @Override public TrackedLoadedChunkMap clone() {
        TrackedLoadedChunkMap copy = (TrackedLoadedChunkMap) super.clone();
        copy.snapshot = null; copy.trackedValues = null; copy.trackedKeys = null; copy.trackedEntries = null;
        return copy;
    }
    @Override public ObjectCollection<Chunk> values() {
        if (trackedValues == null) trackedValues = new Values();
        return trackedValues;
    }
    @Override public LongSet keySet() {
        if (trackedKeys == null) trackedKeys = new AbstractLongSet() {
            @Override public int size() { return TrackedLoadedChunkMap.this.size(); }
            @Override public boolean contains(long key) { return containsKey(key); }
            @Override public void clear() { TrackedLoadedChunkMap.this.clear(); }
            @Override public LongIterator iterator() {
                final LongIterator iterator = TrackedLoadedChunkMap.super.keySet().iterator();
                return new AbstractLongIterator() {
                    @Override public boolean hasNext() { return iterator.hasNext(); }
                    @Override public long nextLong() { return iterator.nextLong(); }
                    @Override public void remove() { iterator.remove(); changed(); }
                };
            }
        };
        return trackedKeys;
    }
    @Override public FastEntrySet<Chunk> long2ObjectEntrySet() {
        if (trackedEntries == null) trackedEntries = new Entries();
        return trackedEntries;
    }
    public final class Values extends AbstractObjectCollection<Chunk> {
        private Values() { }
        @Override public int size() { return TrackedLoadedChunkMap.this.size(); }
        @Override public void clear() { TrackedLoadedChunkMap.this.clear(); }
        @Override public boolean contains(Object value) { return containsValue(value); }
        @Override public ObjectIterator<Chunk> iterator() {
            final ObjectIterator<Chunk> iterator = TrackedLoadedChunkMap.super.values().iterator();
            return new ObjectIterator<Chunk>() {
                @Override public boolean hasNext() { return iterator.hasNext(); }
                @Override public Chunk next() { return iterator.next(); }
                @Override public void remove() { iterator.remove(); changed(); }
                @Override public int skip(int n) { return iterator.skip(n); }
            };
        }
        /** Read-only to the adapted radiation call; older snapshots survive mutations during callbacks. */
        public Chunk[] orderedSnapshot(int requestedSize) {
            Chunk[] result = snapshot;
            if (result == null) {
                result = TrackedLoadedChunkMap.super.values().toArray(new Chunk[requestedSize]);
                snapshot = result;
            }
            return result;
        }
    }
    private final class Entries extends AbstractObjectSet<Long2ObjectMap.Entry<Chunk>> implements FastEntrySet<Chunk> {
        @Override public int size() { return TrackedLoadedChunkMap.this.size(); }
        @Override public void clear() { TrackedLoadedChunkMap.this.clear(); }
        @Override public boolean contains(Object entry) { return TrackedLoadedChunkMap.super.long2ObjectEntrySet().contains(entry); }
        @Override public boolean remove(Object entry) {
            boolean removed = TrackedLoadedChunkMap.super.long2ObjectEntrySet().remove(entry);
            if (removed) changed();
            return removed;
        }
        @Override public ObjectIterator<Long2ObjectMap.Entry<Chunk>> iterator() {
            return wrap(TrackedLoadedChunkMap.super.long2ObjectEntrySet().iterator(), false);
        }
        @Override public ObjectIterator<Long2ObjectMap.Entry<Chunk>> fastIterator() {
            return wrap(TrackedLoadedChunkMap.super.long2ObjectEntrySet().fastIterator(), true);
        }
        private ObjectIterator<Long2ObjectMap.Entry<Chunk>> wrap(final ObjectIterator<Long2ObjectMap.Entry<Chunk>> iterator,
                                                                final boolean reuse) {
            return new ObjectIterator<Long2ObjectMap.Entry<Chunk>>() {
                final EntryView scratch = reuse ? new EntryView() : null;
                @Override public boolean hasNext() { return iterator.hasNext(); }
                @Override public Long2ObjectMap.Entry<Chunk> next() {
                    EntryView result = reuse ? scratch : new EntryView();
                    result.entry = iterator.next();
                    return result;
                }
                @Override public void remove() { iterator.remove(); changed(); }
                @Override public int skip(int n) { return iterator.skip(n); }
            };
        }
    }
    private final class EntryView implements Long2ObjectMap.Entry<Chunk> {
        Long2ObjectMap.Entry<Chunk> entry;
        @Override public long getLongKey() { return entry.getLongKey(); }
        @Override public Long getKey() { return entry.getKey(); }
        @Override public Chunk getValue() { return entry.getValue(); }
        @Override public Chunk setValue(Chunk value) { Chunk previous = entry.setValue(value); changed(); return previous; }
        @Override public boolean equals(Object other) { return entry.equals(other); }
        @Override public int hashCode() { return entry.hashCode(); }
        @Override public String toString() { return entry.toString(); }
    }
}
