package dev.rlcraft.ice.optimizer.compat.optifine;

import static org.junit.Assert.assertEquals;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.util.math.BlockPos;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class OptifineDynamicLightsBridgeTest {
    @Before
    public void enable() {
        OptimizerRegistry.breaker(OptimizationModule.OPTIFINE_DYNAMIC_LIGHTS).configure(true, 3);
        OptimizerRegistry.breaker(OptimizationModule.OPTIFINE_DYNAMIC_LIGHTS)
            .patchInstalled("DynamicLights", "test");
    }

    @After
    public void disable() {
        OptimizerRegistry.breaker(OptimizationModule.OPTIFINE_DYNAMIC_LIGHTS).configure(false, 3);
    }

    @Test
    public void reproducesOptifineDistanceAndUnderwaterFormula() {
        FakeLight light = new FakeLight(15, 0.0D, 0.0D, 0.0D, false);
        OptifineDynamicLightsBridge.refresh(new FakeMap(Collections.<Object>singletonList(light)));
        assertEquals(15.0D,
            OptifineDynamicLightsBridge.getLightLevel(new BlockPos(0, 0, 0), true), 0.0D);
        assertEquals(5.0D,
            OptifineDynamicLightsBridge.getLightLevel(new BlockPos(3, 4, 0), true), 1.0E-12D);

        light.underwater = true;
        OptifineDynamicLightsBridge.refresh(new FakeMap(Collections.<Object>singletonList(light)));
        assertEquals(13.0D,
            OptifineDynamicLightsBridge.getLightLevel(new BlockPos(0, 0, 0), false), 0.0D);
        assertEquals(15.0D,
            OptifineDynamicLightsBridge.getLightLevel(new BlockPos(0, 0, 0), true), 0.0D);
    }

    @Test
    public void snapshotDoesNotObserveMidIntervalEntityMutation() {
        FakeLight light = new FakeLight(15, 1.0D, 2.0D, 3.0D, false);
        FakeMap map = new FakeMap(Collections.<Object>singletonList(light));
        OptifineDynamicLightsBridge.refresh(map);
        light.x = 101.0D;
        assertEquals(15.0D,
            OptifineDynamicLightsBridge.getLightLevel(new BlockPos(1, 2, 3), true), 0.0D);
        OptifineDynamicLightsBridge.refresh(map);
        assertEquals(0.0D,
            OptifineDynamicLightsBridge.getLightLevel(new BlockPos(1, 2, 3), true), 0.0D);
    }

    @Test
    public void largeSnapshotsUseTheSameNearbyResult() {
        List<Object> lights = new ArrayList<Object>();
        for (int i = 0; i < 120; i++) {
            lights.add(new FakeLight(4, 1000.0D + i * 16.0D, 64.0D, 1000.0D, false));
        }
        lights.add(new FakeLight(12, -8.0D, 64.0D, 0.0D, false));
        OptifineDynamicLightsBridge.refresh(new FakeMap(lights));
        assertEquals(0.0D,
            OptifineDynamicLightsBridge.getLightLevel(new BlockPos(0, 64, 0), true), 1.0E-12D);
        assertEquals(12.0D,
            OptifineDynamicLightsBridge.getLightLevel(new BlockPos(-8, 64, 0), true), 0.0D);
    }

    @Test
    public void missingAccessorRequestsOriginalOptifineFallback() {
        OptifineDynamicLightsBridge.refresh(new Object());
        assertEquals(-1.0D,
            OptifineDynamicLightsBridge.getLightLevel(new BlockPos(0, 0, 0), true), 0.0D);
    }

    private static final class FakeMap implements DynamicLightsMapAccessor {
        private final List<?> values;
        private FakeMap(List<?> values) { this.values = values; }
        @Override public List<?> ice$valueList() { return values; }
    }

    private static final class FakeLight implements DynamicLightAccessor {
        private final int level;
        private double x;
        private final double y;
        private final double z;
        private boolean underwater;

        private FakeLight(int level, double x, double y, double z, boolean underwater) {
            this.level = level;
            this.x = x;
            this.y = y;
            this.z = z;
            this.underwater = underwater;
        }

        @Override public int ice$lastLightLevel() { return level; }
        @Override public double ice$lastPosX() { return x; }
        @Override public double ice$lastPosY() { return y; }
        @Override public double ice$lastPosZ() { return z; }
        @Override public boolean ice$isUnderwater() { return underwater; }
    }
}
