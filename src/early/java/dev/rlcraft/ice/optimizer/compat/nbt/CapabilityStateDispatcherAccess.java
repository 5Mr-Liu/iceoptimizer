package dev.rlcraft.ice.optimizer.compat.nbt;

import net.minecraftforge.common.util.INBTSerializable;

/** Live writer arrays used by the reviewed capability state comparison. */
public interface CapabilityStateDispatcherAccess {
    INBTSerializable<?>[] ice$stateWriters();
    String[] ice$stateNames();
    boolean ice$plainStateDispatcher();
}
