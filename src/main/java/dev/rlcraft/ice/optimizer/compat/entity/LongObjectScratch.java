package dev.rlcraft.ice.optimizer.compat.entity;

/** Search-local open addressing; reset touches occupied slots instead of historical capacity. */
public final class LongObjectScratch<V> {
    private long[] keys = new long[512];
    private Object[] values = new Object[512];
    private int[] occupied = new int[512];
    private int size;

    @SuppressWarnings("unchecked") public V get(long key) {
        int slot = hash(key) & (keys.length - 1);
        while (values[slot] != null) {
            if (keys[slot] == key) return (V) values[slot];
            slot = (slot + 1) & (keys.length - 1);
        }
        return null;
    }

    public void put(long key, V value) {
        if (value == null) return;
        if ((size + 1) * 4 >= keys.length * 3) grow();
        putWithoutGrowth(key, value);
    }

    private void putWithoutGrowth(long key, Object value) {
        int slot = hash(key) & (keys.length - 1);
        while (values[slot] != null && keys[slot] != key) slot = (slot + 1) & (keys.length - 1);
        if (values[slot] == null) occupied[size++] = slot;
        keys[slot] = key;
        values[slot] = value;
    }

    public void clear() {
        while (size != 0) values[occupied[--size]] = null;
    }

    public int size() { return size; }

    private void grow() {
        long[] oldKeys = keys;
        Object[] oldValues = values;
        int[] oldOccupied = occupied;
        int oldSize = size;
        keys = new long[oldKeys.length << 1];
        values = new Object[keys.length];
        occupied = new int[keys.length];
        size = 0;
        for (int i = 0; i < oldSize; i++) {
            int slot = oldOccupied[i];
            putWithoutGrowth(oldKeys[slot], oldValues[slot]);
        }
    }

    private static int hash(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdl;
        value ^= value >>> 33;
        return (int) (value ^ (value >>> 32));
    }
}
