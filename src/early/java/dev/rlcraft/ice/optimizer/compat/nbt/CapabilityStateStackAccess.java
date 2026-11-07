package dev.rlcraft.ice.optimizer.compat.nbt;

import net.minecraft.item.Item;
import net.minecraft.nbt.NBTTagCompound;

/** Raw fields written by ItemStack.writeToNBT, before byte/short narrowing. */
public interface CapabilityStateStackAccess {
    Item ice$stateItem();
    int ice$stateCount();
    int ice$stateDamage();
    Object ice$stateCapabilities();
    NBTTagCompound ice$statePendingNbt();
}
