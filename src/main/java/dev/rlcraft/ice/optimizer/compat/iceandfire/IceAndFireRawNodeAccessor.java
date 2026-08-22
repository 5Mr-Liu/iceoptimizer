package dev.rlcraft.ice.optimizer.compat.iceandfire;

import net.minecraft.pathfinding.PathNodeType;
import net.minecraft.world.IBlockAccess;

/** ABI injected into the reviewed Ice and Fire experimental walk processor. */
public interface IceAndFireRawNodeAccessor {
    PathNodeType ice$rawNodeType(IBlockAccess source, int x, int y, int z);
}
