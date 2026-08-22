package dev.rlcraft.ice.optimizer.render.visibility;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public final class PrimitiveVisibilityTest {
    private static final PrimitiveSectionGrid.Frustum ALL =
        new PrimitiveSectionGrid.Frustum() {
            @Override public boolean visible(double minX, double minY, double minZ,
                                             double maxX, double maxY, double maxZ) {
                return true;
            }
        };

    @Test
    public void bfsUsesMinecraftDirectionOrderAndConnectivityWithoutHotCollections() {
        PrimitiveSectionGrid grid = new PrimitiveSectionGrid(0, 0, 0, 3, 1, 1);
        long all = (1L << 36) - 1L;
        for (int x = 0; x < 3; x++) grid.set(x, 0, 0, all,
            x * 16, 0, 0, x * 16 + 16, 16, 16);
        int[] output = new int[3];
        assertEquals(3, grid.collectVisible(1, 0, 0, ALL, false, output));
        assertEquals(grid.indexOf(1, 0, 0), output[0]);
        assertEquals(grid.indexOf(0, 0, 0), output[1]);
        assertEquals(grid.indexOf(2, 0, 0), output[2]);

        PrimitiveSectionGrid blocked = new PrimitiveSectionGrid(0, 0, 0, 4, 1, 1);
        for (int x = 0; x < 4; x++) blocked.set(x, 0, 0, 0L,
            x, 0, 0, x + 1, 1, 1);
        Arrays.fill(output, -1);
        assertEquals(2, blocked.collectVisible(0, 0, 0, ALL, false, output));
    }

    @Test
    public void hzbOnlyRejectsWhenEveryCoveredDepthIsProvablyNearer() {
        float[] depth = new float[16];
        Arrays.fill(depth, 0.2F);
        ConservativeHzb hzb = ConservativeHzb.buildStandardDepth(depth, 4, 4);
        assertEquals(OcclusionResult.OCCLUDED,
            hzb.test(0, 0, 4, 4, 0.5F, 0.0001F));
        depth[5] = 1.0F;
        ConservativeHzb withHole = ConservativeHzb.buildStandardDepth(depth, 4, 4);
        assertEquals(OcclusionResult.VISIBLE,
            withHole.test(0, 0, 4, 4, 0.5F, 0.0001F));

        HzbHistoryKey key = new HzbHistoryKey(0, 4, 4, 70.0F, 1L, 1L, 1L, true);
        ConservativeOcclusionHistory history = new ConservativeOcclusionHistory();
        history.publish(key, hzb);
        assertEquals(OcclusionResult.OCCLUDED,
            history.test(key, true, 0, 0, 4, 4, 0.5F, 0.0001F));
        assertEquals(OcclusionResult.UNKNOWN,
            history.test(key, false, 0, 0, 4, 4, 0.5F, 0.0001F));
        HzbHistoryKey moved = new HzbHistoryKey(0, 4, 4, 70.0F, 2L, 1L, 1L, true);
        assertEquals(OcclusionResult.UNKNOWN,
            history.test(moved, true, 0, 0, 4, 4, 0.5F, 0.0001F));
    }

    @Test
    public void oddSizedHierarchyIsExactAndNeverStrongerThanBaseOracle() {
        float[] depth = new float[35];
        for (int i = 0; i < depth.length; i++) {
            depth[i] = ((i * 37) % 101) / 100.0F;
        }
        ConservativeHzb hzb = ConservativeHzb.buildStandardDepth(depth, 7, 5);
        assertTrue(hzb.isConservativeHierarchy());
        for (int minY = 0; minY < 5; minY++) {
            for (int minX = 0; minX < 7; minX++) {
                for (int maxY = minY + 1; maxY <= 5; maxY++) {
                    for (int maxX = minX + 1; maxX <= 7; maxX++) {
                        for (int candidate = 0; candidate <= 10; candidate++) {
                            float value = candidate / 10.0F;
                            OcclusionResult coarse = hzb.test(minX, minY, maxX,
                                maxY, value, 0.0001F);
                            OcclusionResult base = hzb.testBaseReference(minX, minY,
                                maxX, maxY, value, 0.0001F);
                            if (coarse == OcclusionResult.OCCLUDED) {
                                assertEquals(OcclusionResult.OCCLUDED, base);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void bfsCarriesVanillasWholePathDirectionMask() {
        PrimitiveSectionGrid grid = new PrimitiveSectionGrid(0, 0, 0, 2, 1, 2);
        long all = (1L << 36) - 1L;
        for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) {
            grid.set(x, 0, z, all, x, 0, z, x + 1, 1, z + 1);
        }
        int[] output = new int[4];
        assertEquals(4, grid.collectVisible(0, 0, 0, ALL, false, output));
        // DOWN, UP and NORTH leave the grid; SOUTH is first, then EAST.
        assertEquals(grid.indexOf(0, 0, 0), output[0]);
        assertEquals(grid.indexOf(0, 0, 1), output[1]);
        assertEquals(grid.indexOf(1, 0, 0), output[2]);
    }
}
