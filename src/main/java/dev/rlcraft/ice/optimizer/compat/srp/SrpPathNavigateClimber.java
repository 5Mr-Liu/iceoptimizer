package dev.rlcraft.ice.optimizer.compat.srp;

import net.minecraft.entity.EntityLiving;
import net.minecraft.pathfinding.NodeProcessor;
import net.minecraft.pathfinding.PathFinder;
import net.minecraft.pathfinding.PathNavigateClimber;
import net.minecraft.world.World;

/** Keeps vanilla climbing and its no-path fallback, changing only node classification. */
public final class SrpPathNavigateClimber extends PathNavigateClimber {
    public SrpPathNavigateClimber(EntityLiving entity, World world) {
        super(entity, world);
    }

    public static NodeProcessor createProcessor() {
        SrpCachingWalkNodeProcessor processor = new SrpCachingWalkNodeProcessor();
        processor.setCanEnterDoors(true);
        return processor;
    }

    @Override
    protected PathFinder getPathFinder() {
        nodeProcessor = createProcessor();
        return new PathFinder(nodeProcessor);
    }
}
