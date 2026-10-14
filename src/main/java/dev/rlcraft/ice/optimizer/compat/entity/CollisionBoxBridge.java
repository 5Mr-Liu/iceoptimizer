package dev.rlcraft.ice.optimizer.compat.entity;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.List;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;

/** Defers the translated box allocation until an actual intersection is known. */
public final class CollisionBoxBridge {
    private static final int MODULE = OptimizationModule.ENTITY_COLLISION_BOXES.ordinal();

    private CollisionBoxBridge() { }

    public static void append(BlockPos position, AxisAlignedBB query,
                              List<AxisAlignedBB> output, AxisAlignedBB shape) {
        if (shape == null) return;
        // Custom boxes can override offset/intersects; those virtual calls are observable.
        if (!OptimizerBridge.isEnabled(MODULE) || shape.getClass() != AxisAlignedBB.class
            || query.getClass() != AxisAlignedBB.class) {
            AxisAlignedBB translated = shape.offset(position);
            if (query.intersects(translated)) output.add(translated);
            return;
        }
        appendStandard(position, query, output, shape);
    }

    static void appendStandard(BlockPos position, AxisAlignedBB query,
                               List<AxisAlignedBB> output, AxisAlignedBB shape) {
        double minX = shape.minX + position.getX();
        double minY = shape.minY + position.getY();
        double minZ = shape.minZ + position.getZ();
        double maxX = shape.maxX + position.getX();
        double maxY = shape.maxY + position.getY();
        double maxZ = shape.maxZ + position.getZ();
        if (query.intersects(minX, minY, minZ, maxX, maxY, maxZ)) {
            output.add(new AxisAlignedBB(minX, minY, minZ, maxX, maxY, maxZ));
        }
    }
}
