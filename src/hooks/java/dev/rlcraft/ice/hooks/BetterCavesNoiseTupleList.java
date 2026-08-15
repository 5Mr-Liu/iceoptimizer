package dev.rlcraft.ice.hooks;

import java.util.AbstractList;
import java.util.RandomAccess;

/** Live List view used only for Better Caves' public compatibility accessor. */
public final class BetterCavesNoiseTupleList extends AbstractList<Double> implements RandomAccess {
    private final BetterCavesNoiseTupleAccess tuple;

    public BetterCavesNoiseTupleList(BetterCavesNoiseTupleAccess tuple) {
        if (tuple == null) throw new NullPointerException("tuple");
        this.tuple = tuple;
    }

    @Override
    public Double get(int index) {
        return Double.valueOf(tuple.get(index));
    }

    @Override
    public int size() {
        return tuple.size();
    }

    @Override
    public Double set(int index, Double value) {
        double previous = tuple.get(index);
        tuple.set(index, value.doubleValue());
        return Double.valueOf(previous);
    }

    @Override
    public boolean add(Double value) {
        tuple.put(value.doubleValue());
        modCount++;
        return true;
    }
}
