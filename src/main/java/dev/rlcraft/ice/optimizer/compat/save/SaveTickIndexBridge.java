package dev.rlcraft.ice.optimizer.compat.save;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;
import java.util.NavigableSet;
import java.util.SortedSet;
import java.util.AbstractSet;
import java.util.Comparator;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.chunk.Chunk;
import org.agrona.collections.Long2ObjectHashMap;

/** World-owned ordered buckets, maintained by successful collection mutations. */
public final class SaveTickIndexBridge {
    private static final int MODULE = OptimizationModule.VANILLA_SAVE_TICK_INDEX.ordinal();
    private SaveTickIndexBridge() { }

    public static List<NextTickListEntry> pendingBlockUpdates(PendingTickAccessor world,
                                                             Chunk chunk, boolean remove) {
        if (remove || !OptimizerBridge.isEnabled(MODULE)) {
            return world.ice$originalPendingBlockUpdates(chunk, remove);
        }
        return pendingForChunk(world, chunk.x, chunk.z);
    }
    static List<NextTickListEntry> pendingForChunk(PendingTickAccessor world, int x, int z) {
        Collection<NextTickListEntry> tree = ((TickTree) world.ice$pendingTickTree()).buckets.get(key(x, z));
        Collection<NextTickListEntry> current = ((TickList) world.ice$pendingTicksThisTick()).buckets.get(key(x, z));
        if (tree == null && current == null) return null;
        ArrayList<NextTickListEntry> result = new ArrayList<NextTickListEntry>(
            (tree == null ? 0 : tree.size()) + (current == null ? 0 : current.size()));
        if (tree != null) result.addAll(tree);
        if (current != null) result.addAll(current);
        return result;
    }
    private static long key(int x, int z) { return (long) x & 0xffffffffL | (long) z << 32; }
    private static int overlap(NextTickListEntry entry) {
        return ((entry.position.getX() & 15) >= 14 ? 1 : 0)
            | ((entry.position.getZ() & 15) >= 14 ? 2 : 0);
    }
    private static long keyFor(NextTickListEntry entry, int slot) {
        return key((entry.position.getX() >> 4) + (slot & 1),
            (entry.position.getZ() >> 4) + (slot >> 1));
    }

    /** All vanilla tree writes use add, remove, iterator.remove and clear. */
    public static final class TickTree extends TreeSet<NextTickListEntry> {
        private final Long2ObjectHashMap<TreeSet<NextTickListEntry>> buckets =
            new Long2ObjectHashMap<TreeSet<NextTickListEntry>>();
        @Override public boolean add(NextTickListEntry entry) {
            if (!super.add(entry)) return false;
            added(entry);
            return true;
        }
        private void added(NextTickListEntry entry) {
            for (int slot = 0, mask = overlap(entry); slot <= mask; slot++) {
                if ((slot & ~mask) != 0) continue;
                long key = keyFor(entry, slot);
                TreeSet<NextTickListEntry> bucket = buckets.get(key);
                if (bucket == null) { bucket = new TreeSet<NextTickListEntry>(); buckets.put(key, bucket); }
                bucket.add(entry);
            }
        }
        @Override public boolean addAll(Collection<? extends NextTickListEntry> entries) {
            boolean changed = false;
            for (NextTickListEntry entry : entries) changed |= add(entry);
            return changed;
        }
        @Override public boolean remove(Object value) {
            if (!super.remove(value)) return false;
            removed((NextTickListEntry) value); return true;
        }
        private void removed(NextTickListEntry entry) {
            for (int slot = 0, mask = overlap(entry); slot <= mask; slot++) {
                if ((slot & ~mask) != 0) continue;
                long key = keyFor(entry, slot);
                TreeSet<NextTickListEntry> bucket = buckets.get(key);
                bucket.remove(entry);
                if (bucket.isEmpty()) buckets.remove(key);
            }
        }
        @Override public Iterator<NextTickListEntry> iterator() { return tracked(super.iterator()); }
        @Override public Iterator<NextTickListEntry> descendingIterator() { return tracked(super.descendingIterator()); }
        private Iterator<NextTickListEntry> tracked(final Iterator<NextTickListEntry> source) {
            return new Iterator<NextTickListEntry>() {
                private NextTickListEntry current;
                public boolean hasNext() { return source.hasNext(); }
                public NextTickListEntry next() { return current = source.next(); }
                public void remove() { source.remove(); removed(current); }
            };
        }
        @Override public NextTickListEntry pollFirst() {
            NextTickListEntry entry = super.pollFirst(); if (entry != null) removed(entry); return entry;
        }
        @Override public NextTickListEntry pollLast() {
            NextTickListEntry entry = super.pollLast(); if (entry != null) removed(entry); return entry;
        }
        @Override public void clear() { super.clear(); buckets.clear(); }
        @Override public Object clone() {
            TickTree copy = new TickTree();
            copy.addAll(this);
            return copy;
        }
        @Override public NavigableSet<NextTickListEntry> descendingSet() { return view(super.descendingSet()); }
        @Override public NavigableSet<NextTickListEntry> subSet(NextTickListEntry a, boolean ai, NextTickListEntry b, boolean bi) { return view(super.subSet(a, ai, b, bi)); }
        @Override public NavigableSet<NextTickListEntry> headSet(NextTickListEntry a, boolean inclusive) { return view(super.headSet(a, inclusive)); }
        @Override public NavigableSet<NextTickListEntry> tailSet(NextTickListEntry a, boolean inclusive) { return view(super.tailSet(a, inclusive)); }
        @Override public SortedSet<NextTickListEntry> subSet(NextTickListEntry a, NextTickListEntry b) { return subSet(a, true, b, false); }
        @Override public SortedSet<NextTickListEntry> headSet(NextTickListEntry a) { return headSet(a, false); }
        @Override public SortedSet<NextTickListEntry> tailSet(NextTickListEntry a) { return tailSet(a, true); }
        private NavigableSet<NextTickListEntry> view(NavigableSet<NextTickListEntry> source) { return new View(source); }
        private final class View extends AbstractSet<NextTickListEntry> implements NavigableSet<NextTickListEntry> {
            private final NavigableSet<NextTickListEntry> source;
            View(NavigableSet<NextTickListEntry> source) { this.source = source; }
            @Override public int size() { return source.size(); }
            @Override public boolean contains(Object entry) { return source.contains(entry); }
            @Override public boolean add(NextTickListEntry entry) {
                if (!source.add(entry)) return false;
                added(entry); return true;
            }
            @Override public boolean remove(Object entry) {
                if (!source.remove(entry)) return false;
                removed((NextTickListEntry) entry); return true;
            }
            @Override public Iterator<NextTickListEntry> iterator() { return tracked(source.iterator()); }
            @Override public Iterator<NextTickListEntry> descendingIterator() { return tracked(source.descendingIterator()); }
            @Override public Comparator<? super NextTickListEntry> comparator() { return source.comparator(); }
            @Override public NextTickListEntry first() { return source.first(); }
            @Override public NextTickListEntry last() { return source.last(); }
            @Override public NextTickListEntry lower(NextTickListEntry e) { return source.lower(e); }
            @Override public NextTickListEntry floor(NextTickListEntry e) { return source.floor(e); }
            @Override public NextTickListEntry ceiling(NextTickListEntry e) { return source.ceiling(e); }
            @Override public NextTickListEntry higher(NextTickListEntry e) { return source.higher(e); }
            @Override public NextTickListEntry pollFirst() { NextTickListEntry e=source.pollFirst(); if(e!=null) removed(e); return e; }
            @Override public NextTickListEntry pollLast() { NextTickListEntry e=source.pollLast(); if(e!=null) removed(e); return e; }
            @Override public NavigableSet<NextTickListEntry> descendingSet() { return view(source.descendingSet()); }
            @Override public NavigableSet<NextTickListEntry> subSet(NextTickListEntry a, boolean ai, NextTickListEntry b, boolean bi) { return view(source.subSet(a,ai,b,bi)); }
            @Override public NavigableSet<NextTickListEntry> headSet(NextTickListEntry a, boolean inclusive) { return view(source.headSet(a,inclusive)); }
            @Override public NavigableSet<NextTickListEntry> tailSet(NextTickListEntry a, boolean inclusive) { return view(source.tailSet(a,inclusive)); }
            @Override public SortedSet<NextTickListEntry> subSet(NextTickListEntry a, NextTickListEntry b) { return subSet(a,true,b,false); }
            @Override public SortedSet<NextTickListEntry> headSet(NextTickListEntry a) { return headSet(a,false); }
            @Override public SortedSet<NextTickListEntry> tailSet(NextTickListEntry a) { return tailSet(a,true); }
        }
    }
    /** An ordered current-tick list; iterator removal delegates to remove(index). */
    public static final class TickList extends AbstractList<NextTickListEntry> {
        private final ArrayList<NextTickListEntry> values = new ArrayList<NextTickListEntry>();
        private final Long2ObjectHashMap<ArrayList<NextTickListEntry>> buckets =
            new Long2ObjectHashMap<ArrayList<NextTickListEntry>>();
        @Override public int size() { return values.size(); }
        @Override public NextTickListEntry get(int index) { return values.get(index); }
        @Override public void add(int index, NextTickListEntry entry) {
            int previousSize = values.size(); values.add(index, entry); modCount++;
            for (int slot = 0, mask = overlap(entry); slot <= mask; slot++) {
                if ((slot & ~mask) != 0) continue;
                long key = keyFor(entry, slot);
                ArrayList<NextTickListEntry> bucket = buckets.get(key);
                if (bucket == null) { bucket = new ArrayList<NextTickListEntry>(); buckets.put(key, bucket); }
                if (index == previousSize) bucket.add(entry);
                else {
                    int before = 0;
                    for (int i = 0; i < index; i++) if (belongs(values.get(i), key)) before++;
                    bucket.add(before, entry);
                }
            }
        }
        @Override public NextTickListEntry remove(int index) {
            NextTickListEntry entry = values.get(index);
            for (int slot = 0, mask = overlap(entry); slot <= mask; slot++) {
                if ((slot & ~mask) != 0) continue;
                long key = keyFor(entry, slot);
                ArrayList<NextTickListEntry> bucket = buckets.get(key);
                int before = 0;
                for (int i = 0; i < index; i++) if (belongs(values.get(i), key)) before++;
                bucket.remove(before);
                if (bucket.isEmpty()) buckets.remove(key);
            }
            values.remove(index); modCount++; return entry;
        }
        @Override public NextTickListEntry set(int index, NextTickListEntry entry) {
            int originalModCount = modCount;
            NextTickListEntry previous = remove(index); add(index, entry);
            modCount = originalModCount;
            return previous;
        }
        @Override public void clear() { values.clear(); buckets.clear(); modCount++; }
        private static boolean belongs(NextTickListEntry entry, long key) {
            int x = (int) key, z = (int) (key >> 32);
            int px = entry.position.getX(), pz = entry.position.getZ();
            return px >= (x << 4) - 2 && px < (x << 4) + 16
                && pz >= (z << 4) - 2 && pz < (z << 4) + 16;
        }
    }
}
