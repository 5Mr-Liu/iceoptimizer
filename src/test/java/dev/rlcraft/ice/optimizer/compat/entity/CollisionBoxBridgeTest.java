package dev.rlcraft.ice.optimizer.compat.entity;

import static org.junit.Assert.*;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import org.junit.Test;

public class CollisionBoxBridgeTest {
    @Test public void translatedComparisonMatchesOriginalIncludingLargeBoxesAndNegativeCoordinates() {
        Random random = new Random(571143L);
        for (int i = 0; i < 12000; i++) {
            BlockPos position = new BlockPos(random.nextInt(60000001) - 30000000,
                random.nextInt(512) - 128, random.nextInt(60000001) - 30000000);
            AxisAlignedBB shape = new AxisAlignedBB(random.nextDouble() * 12 - 6,
                random.nextDouble() * 8 - 4, random.nextDouble() * 12 - 6,
                random.nextDouble() * 12 - 6, random.nextDouble() * 8 - 4,
                random.nextDouble() * 12 - 6);
            AxisAlignedBB query = new AxisAlignedBB(position.getX() + random.nextDouble() * 8 - 4,
                position.getY() + random.nextDouble() * 8 - 4, position.getZ() + random.nextDouble() * 8 - 4,
                position.getX() + random.nextDouble() * 8 - 4, position.getY() + random.nextDouble() * 8 - 4,
                position.getZ() + random.nextDouble() * 8 - 4);
            List<AxisAlignedBB> actual = new ArrayList<AxisAlignedBB>();
            AxisAlignedBB translated = shape.offset(position);
            CollisionBoxBridge.appendStandard(position, query, actual, shape);
            assertEquals(query.intersects(translated) ? 1 : 0, actual.size());
            if (!actual.isEmpty()) assertEquals(translated, actual.get(0));
        }
        List<AxisAlignedBB> touching = new ArrayList<AxisAlignedBB>();
        CollisionBoxBridge.appendStandard(BlockPos.ORIGIN, new AxisAlignedBB(1, 0, 0, 2, 1, 1),
            touching, new AxisAlignedBB(0, 0, 0, 1, 1, 1));
        assertTrue(touching.isEmpty());
    }

    @Test public void retainsCustomOffsetAndIntersectionOverridesAndRuntimeSwitch() {
        OptimizationModule module = OptimizationModule.ENTITY_COLLISION_BOXES;
        OptimizerRegistry.breaker(module).configure(true, 3);
        OptimizerRegistry.breaker(module).patchInstalled("collision", "test");
        try {
            OffsetBox shape = new OffsetBox();
            QueryBox query = new QueryBox();
            List<AxisAlignedBB> output = new ArrayList<AxisAlignedBB>();
            CollisionBoxBridge.append(BlockPos.ORIGIN, query, output, shape);
            assertEquals(1, shape.calls);
            assertEquals(1, query.calls);
            assertSame(shape.result, output.get(0));
            OptimizerRegistry.breaker(module).configure(false, 3);
            CollisionBoxBridge.append(BlockPos.ORIGIN, query, output, shape);
            assertEquals(2, shape.calls);
            assertEquals(2, query.calls);
            CollisionBoxBridge.append(BlockPos.ORIGIN, null, output, null);
        } finally { OptimizerRegistry.breaker(module).configure(false, 3); }
    }

    private static final class OffsetBox extends AxisAlignedBB {
        int calls;
        final AxisAlignedBB result = new AxisAlignedBB(100, 100, 100, 101, 101, 101);
        OffsetBox() { super(0, 0, 0, 1, 1, 1); }
        @Override public AxisAlignedBB offset(BlockPos pos) { calls++; return result; }
    }
    private static final class QueryBox extends AxisAlignedBB {
        int calls;
        QueryBox() { super(0, 0, 0, 1, 1, 1); }
        @Override public boolean intersects(AxisAlignedBB box) { calls++; return true; }
    }
}
