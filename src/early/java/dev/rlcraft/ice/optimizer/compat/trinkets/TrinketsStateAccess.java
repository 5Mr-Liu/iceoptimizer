package dev.rlcraft.ice.optimizer.compat.trinkets;

/** A fixed-size encoding cache owned by one Trinkets state object. */
public interface TrinketsStateAccess {
    Object ice$trinketsScalarCache();
    void ice$trinketsScalarCache(Object cache);
}
