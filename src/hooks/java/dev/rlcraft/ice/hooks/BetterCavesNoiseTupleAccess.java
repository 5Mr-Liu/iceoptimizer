package dev.rlcraft.ice.hooks;

/** Primitive ABI injected into compatible Better Caves NoiseTuple classes. */
public interface BetterCavesNoiseTupleAccess {
    double get(int index);
    void set(int index, double value);
    void put(double value);
    int size();
}
