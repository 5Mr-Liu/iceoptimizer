package dev.rlcraft.ice.optimizer.compat.bettercaves;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/** Primitive, contiguous replacement for CaveCarver's per-column HashMap. */
public final class BetterCavesThresholdMap extends AbstractMap<Integer, Float> {
    private final int bottom;
    private final float[] values;
    private final Set<Map.Entry<Integer, Float>> entries = new EntrySet();

    public BetterCavesThresholdMap(int top, int bottom, int surface, float baseThreshold) {
        this.bottom = bottom;
        int length = Math.max(0, top - bottom + 1);
        this.values = new float[length];
        for (int index = 0; index < length; index++) {
            int y = bottom + index;
            float threshold = baseThreshold;
            if (y >= surface) {
                threshold *= 1.0F + 0.3F * ((float) (y - surface) / (float) (top - surface));
            }
            values[index] = threshold;
        }
    }

    @Override
    public Float get(Object key) {
        if (!(key instanceof Integer)) return null;
        int index = ((Integer) key).intValue() - bottom;
        return index < 0 || index >= values.length ? null : Float.valueOf(values[index]);
    }

    @Override
    public boolean containsKey(Object key) {
        if (!(key instanceof Integer)) return false;
        int index = ((Integer) key).intValue() - bottom;
        return index >= 0 && index < values.length;
    }

    @Override
    public int size() {
        return values.length;
    }

    @Override
    public Set<Map.Entry<Integer, Float>> entrySet() {
        return entries;
    }

    private final class EntrySet extends AbstractSet<Map.Entry<Integer, Float>> {
        @Override
        public int size() {
            return values.length;
        }

        @Override
        public Iterator<Map.Entry<Integer, Float>> iterator() {
            return new Iterator<Map.Entry<Integer, Float>>() {
                private int index;

                @Override public boolean hasNext() { return index < values.length; }

                @Override
                public Map.Entry<Integer, Float> next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    int current = index++;
                    return new SimpleImmutableEntry<Integer, Float>(
                        Integer.valueOf(bottom + current), Float.valueOf(values[current]));
                }

                @Override public void remove() { throw new UnsupportedOperationException(); }
            };
        }
    }
}
