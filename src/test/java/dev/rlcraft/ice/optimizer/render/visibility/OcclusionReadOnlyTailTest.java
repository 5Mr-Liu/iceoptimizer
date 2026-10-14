package dev.rlcraft.ice.optimizer.render.visibility;

import static org.junit.Assert.*;
import java.lang.reflect.*;
import java.util.Random;
import org.junit.Test;

/** Differential oracle retains the original heap extraction and budget order. */
public final class OcclusionReadOnlyTailTest {
    private static final float[] IDENTITY = {1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};

    @Test public void boundedReadOnlyQueriesMatchHeapAcrossDepthAndCoveragePatterns() throws Exception {
        Random random = new Random(194207);
        for (int sample = 0; sample < 100; sample++) {
            CurrentFrameTerrainOcclusion old = new CurrentFrameTerrainOcclusion();
            CurrentFrameTerrainOcclusion next = new CurrentFrameTerrainOcclusion();
            SoftwareOcclusionRaster a = raster(old), b = raster(next);
            a.begin(IDENTITY, IDENTITY, 0, 0, 0, 1000000);
            b.begin(IDENTITY, IDENTITY, 0, 0, 0, 1000000);
            for (int wall = 0; wall < 7; wall++) {
                float left = random.nextFloat() * 1.4F - .9F;
                float bottom = random.nextFloat() * 1.4F - .9F;
                float z = random.nextFloat() * .8F - .8F;
                float[] face = {left,bottom,z,left+.4F,bottom,z,left+.4F,bottom+.4F,z,left,bottom+.4F,z};
                a.rasterize(face, 0, 0, 0, 0); b.rasterize(face, 0, 0, 0, 0);
            }
            int count = 20 + random.nextInt(180);
            for (int i = 0; i < count; i++) {
                double x = random.nextDouble() * 1.8 - .9;
                double y = random.nextDouble() * 1.8 - .9;
                double z = random.nextDouble() * 1.6 - .8;
                double size = .005 + random.nextDouble() * .08;
                populate(old, i, x, y, z, size, random.nextInt(7) != 0);
                populate(next, i, x, y, z, size, queryable(work(old)[i]));
            }
            boolean constrained = sample % 3 == 0;
            if (constrained) field(SoftwareOcclusionRaster.class, "remaining")
                .setInt(raster(next), 1 + random.nextInt(150));
            int expected = heapOracle(old, count);
            int actual = tail(next, count);
            if (constrained) {
                assertTrue(actual <= expected);
                for (int i = 0; i < count; i++) if (hidden(next)[i])
                    assertTrue("Budgeted query needs full-raster proof: " + sample + "/" + i, hidden(old)[i]);
            } else {
                assertEquals("sample " + sample, expected, actual);
                assertArrayEquals(hidden(old), hidden(next));
                assertEquals(old.getTested(), next.getTested());
            }
        }
    }

    @Test public void exhaustedBudgetKeepsPartialAndUntouchedQueriesVisible() throws Exception {
        CurrentFrameTerrainOcclusion source = new CurrentFrameTerrainOcclusion();
        raster(source).begin(IDENTITY, IDENTITY, 0, 0, 0, 1000000);
        raster(source).rasterize(new float[]{-1,-1,-.5F,1,-1,-.5F,1,1,-.5F,-1,1,-.5F},0,0,0,0);
        populate(source, 0, -.8, -.8, .5, .8, true);
        populate(source, 1, -.2, -.2, .5, .01, true);
        field(SoftwareOcclusionRaster.class, "remaining").setInt(raster(source), 10);
        assertEquals(0, tail(source, 2));
        assertEquals(0, raster(source).remainingQueryCells());
        assertEquals(1, source.getTested());
        assertEquals(1, source.getReadOnlyTailBudgetStops());
        assertFalse(hidden(source)[0]); assertFalse(hidden(source)[1]);
    }

    @Test public void exactBudgetAndNonQueryableItemsKeepAllDecisions() throws Exception {
        CurrentFrameTerrainOcclusion source = new CurrentFrameTerrainOcclusion();
        raster(source).begin(IDENTITY, IDENTITY, 0, 0, 0, 10000);
        populate(source, 0, -.2, -.2, .5, .01, true);
        populate(source, 1, -.8, -.8, .5, 1.5, false);
        SoftwareOcclusionRaster.Bounds box = bounds(work(source)[0]);
        int cells = (box.maxX - box.minX) * (box.maxY - box.minY);
        field(SoftwareOcclusionRaster.class, "remaining").setInt(raster(source), cells);
        assertEquals(0, tail(source, 2));
        assertEquals(1, source.getTested());
        assertFalse(hidden(source)[0]); assertFalse(hidden(source)[1]);
    }

    private static int heapOracle(CurrentFrameTerrainOcclusion source, int count) throws Exception {
        method("heapNearFirst", int.class).invoke(source, count);
        Object[] work = work(source);
        int removed = 0;
        for (int end = count - 1; end >= 0 && !raster(source).exhausted(); end--) {
            Object item = work[0]; work[0] = work[end]; work[end] = item;
            method("sift", int.class, int.class).invoke(source, 0, end);
            if (queryable(item)) {
                Field tested = field(CurrentFrameTerrainOcclusion.class, "tested");
                tested.setLong(source, tested.getLong(source) + 1);
                if (raster(source).occluded(bounds(item))) {
                    hidden(source)[field(item.getClass(), "index").getInt(item)] = true;
                    removed++;
                }
            }
        }
        return removed;
    }
    private static void populate(CurrentFrameTerrainOcclusion source, int i, double x, double y,
                                 double z, double size, boolean query) throws Exception {
        Object item = work(source)[i];
        boolean valid = raster(source).projectBounds(x, y, z, x+size, y+size, z+.001, bounds(item));
        field(item.getClass(), "index").setInt(item, i);
        field(item.getClass(), "queryable").setBoolean(item, query && valid);
    }
    private static boolean queryable(Object item) throws Exception { return field(item.getClass(), "queryable").getBoolean(item); }
    private static SoftwareOcclusionRaster.Bounds bounds(Object item) throws Exception { return (SoftwareOcclusionRaster.Bounds) field(item.getClass(), "bounds").get(item); }
    private static Object[] work(Object source) throws Exception { return (Object[]) field(CurrentFrameTerrainOcclusion.class, "work").get(source); }
    private static boolean[] hidden(Object source) throws Exception { return (boolean[]) field(CurrentFrameTerrainOcclusion.class, "hidden").get(source); }
    private static SoftwareOcclusionRaster raster(Object source) throws Exception { return (SoftwareOcclusionRaster) field(CurrentFrameTerrainOcclusion.class, "raster").get(source); }
    private static int tail(Object source, int count) throws Exception { return (Integer) method("filterReadOnlyTail", int.class).invoke(source, count); }
    private static Method method(String name, Class<?>... args) throws Exception { Method m=CurrentFrameTerrainOcclusion.class.getDeclaredMethod(name,args);m.setAccessible(true);return m; }
    private static Field field(Class<?> owner, String name) throws Exception { Field f=owner.getDeclaredField(name);f.setAccessible(true);return f; }
}
