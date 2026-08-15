package dev.rlcraft.ice.optimizer.compat.lycanites;

import net.minecraft.pathfinding.PathNodeType;
import net.minecraft.world.IBlockAccess;

/** ABI injected into the reviewed Lycanites CreatureNodeProcessor class. */
public interface LycanitesRawNodeAccessor {
    PathNodeType ice$rawNodeType(IBlockAccess source, int x, int y, int z);
}
