package dev.rlcraft.ice.optimizer.compat.sound;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.RandomAccess;

/** Invocation-owned room-search frontier. Random removal retains the original list order. */
public final class SoundSearchBridge {
    private static final int MODULE = OptimizationModule.SOUND_FILTERS.ordinal();
    private static final ThreadLocal<Workspace> WORK = new ThreadLocal<Workspace>() {
        @Override protected Workspace initialValue() { return new Workspace(); }
    };
    private SoundSearchBridge() { }
    public static boolean isEnabled() { return OptimizerBridge.isEnabled(MODULE); }
    public static int begin() { return WORK.get().depth++; }
    public static Frontier frontier() {
        Workspace work = WORK.get();
        int index = work.depth - 1;
        while (work.buffers.size() <= index) work.buffers.add(new Frontier());
        return work.buffers.get(index);
    }
    public static void end(int level) {
        Workspace work = WORK.get();
        if (level < work.buffers.size()) work.buffers.get(level).clear();
        work.depth = level;
    }
    private static final class Workspace {
        final ArrayList<Frontier> buffers = new ArrayList<Frontier>();
        int depth;
    }

    /** Only used for the audited integer-grid flood fill, not continuous sound-source positions. */
    public static final class Frontier extends AbstractList<Object> implements RandomAccess {
        private static final Object DELETED = new Object();
        private Object[] values = new Object[64];
        private Object[] table = new Object[128];
        private int size;
        private int occupied;
        @Override public int size() { return size; }
        @Override public Object get(int index) { check(index); return values[index]; }
        @Override public boolean add(Object value) { add(size, value); return true; }
        @Override public void add(int index, Object value) {
            if (index < 0 || index > size) throw new IndexOutOfBoundsException();
            if (size == values.length) values = Arrays.copyOf(values, size << 1);
            if (occupied * 2 >= table.length) rehash(size * 4 >= table.length ? table.length << 1 : table.length);
            System.arraycopy(values, index, values, index + 1, size - index);
            values[index] = value;
            insert(value);
            size++;
            modCount++;
        }
        @Override public boolean contains(Object value) { return find(value) >= 0; }
        @Override public Object remove(int index) {
            check(index);
            Object value = values[index];
            int slot = find(value);
            table[slot] = DELETED;
            System.arraycopy(values, index + 1, values, index, size - index - 1);
            values[--size] = null;
            modCount++;
            return value;
        }
        @Override public void clear() {
            Arrays.fill(values, 0, size, null);
            Arrays.fill(table, null);
            size = occupied = 0;
            modCount++;
        }
        private int find(Object value) {
            int mask = table.length - 1;
            for (int slot = hash(value) & mask; ; slot = (slot + 1) & mask) {
                Object current = table[slot];
                if (current == null) return -1;
                if (current != DELETED && same(value, current)) return slot;
            }
        }
        private void insert(Object value) {
            int mask = table.length - 1;
            for (int slot = hash(value) & mask; ; slot = (slot + 1) & mask) {
                Object current = table[slot];
                if (current == null || current == DELETED) {
                    if (current == null) occupied++;
                    table[slot] = value;
                    return;
                }
            }
        }
        private void rehash(int capacity) {
            table = new Object[capacity];
            occupied = 0;
            for (int i = 0; i < size; i++) insert(values[i]);
        }
        private static int hash(Object value) {
            SoundPositionAccess p = (SoundPositionAccess) value;
            int h = bits(p.ice$soundX()) * 0x9e3779b9 ^ bits(p.ice$soundY()) * 0x85ebca6b ^ bits(p.ice$soundZ());
            h ^= h >>> 16; h *= 0x7feb352d; return h ^ h >>> 15;
        }
        private static int bits(float value) { return value == 0F ? 0 : Float.floatToIntBits(value); }
        private static boolean same(Object a, Object b) {
            SoundPositionAccess x = (SoundPositionAccess) a, y = (SoundPositionAccess) b;
            return x.ice$soundX() == y.ice$soundX() && x.ice$soundY() == y.ice$soundY()
                && x.ice$soundZ() == y.ice$soundZ();
        }
        private void check(int index) { if (index < 0 || index >= size) throw new IndexOutOfBoundsException(); }
    }
}
