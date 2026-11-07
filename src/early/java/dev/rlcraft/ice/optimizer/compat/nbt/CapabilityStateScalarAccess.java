package dev.rlcraft.ice.optimizer.compat.nbt;

import net.minecraft.nbt.NBTTagInt;

/** A provider-local immutable leaf, never a shared mutable compound or list. */
public interface CapabilityStateScalarAccess {
    NBTTagInt ice$stateScalar();
    void ice$stateScalar(NBTTagInt value);
}
