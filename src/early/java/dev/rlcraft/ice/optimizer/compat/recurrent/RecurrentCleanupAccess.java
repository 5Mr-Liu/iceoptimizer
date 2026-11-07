package dev.rlcraft.ice.optimizer.compat.recurrent;

import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.NBTTagCompound;

/** Installed only after the original RC writer's four statements have been checked. */
public interface RecurrentCleanupAccess {
    Map<?, ?> ice$cleanupEntries();
    Set<?> ice$cleanupCheckedChunks();
    Set<?> ice$cleanupCheckedChunksFinal();
    NBTTagCompound ice$writeCleanupNbt(NBTTagCompound tag);
}
