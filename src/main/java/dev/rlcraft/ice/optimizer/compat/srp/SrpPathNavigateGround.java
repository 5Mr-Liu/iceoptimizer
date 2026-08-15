package dev.rlcraft.ice.optimizer.compat.srp;

import net.minecraft.entity.EntityLiving;
import net.minecraft.pathfinding.PathFinder;
import net.minecraft.pathfinding.PathNavigateGround;
import net.minecraft.world.World;

/** Vanilla ground navigator with a search-local, result-equivalent node processor. */
public final class SrpPathNavigateGround extends PathNavigateGround {
    public SrpPathNavigateGround(EntityLiving entity, World world) {
        super(entity, world);
    }

    @Override
    protected PathFinder getPathFinder() {
        nodeProcessor = new SrpCachingWalkNodeProcessor();
        nodeProcessor.setCanEnterDoors(true);
        return new PathFinder(nodeProcessor);
    }
}
