package dev.rlcraft.ice.optimizer.render.visibility;

import static org.junit.Assert.*;
import java.lang.reflect.Field;
import java.util.Random;
import org.junit.Test;

/** Compare all depth cells and the budget with the frozen scalar implementation. */
public final class SoftwareOcclusionRasterEquivalenceTest {
    @Test public void randomMovingViewsQuadsQueriesAndBudgetsMatchScalarRaster() throws Exception {
        Random random = new Random(0x170CC105L);
        int[] widths = { 1, 7, 32, 63, 160, 320 };
        int[] heights = { 1, 9, 23, 47, 90, 180 };
        for (int frame = 0; frame < 600; frame++) {
            int shape = frame % widths.length;
            SoftwareOcclusionRaster actual = new SoftwareOcclusionRaster(widths[shape], heights[shape]);
            ReferenceSoftwareOcclusionRaster expected = new ReferenceSoftwareOcclusionRaster(widths[shape], heights[shape]);
            boolean perspective = (frame & 1) != 0;
            float[] model = SoftwareOcclusionRasterTest.identity();
            double yaw = random.nextDouble() * 1.6 - .8;
            model[0] = model[10] = (float) Math.cos(yaw);
            model[2] = -(float) Math.sin(yaw); model[8] = (float) Math.sin(yaw);
            float[] projection = SoftwareOcclusionRasterTest.identity();
            if (perspective) {
                projection[10] = -100.1F / 99.9F; projection[11] = -1;
                projection[14] = -20F / 99.9F; projection[15] = 0;
            }
            double cameraX = random.nextDouble() - .5, cameraY = random.nextDouble() - .5;
            double cameraZ = random.nextDouble() - .5;
            int budget = frame % 4 == 0 ? random.nextInt(120000) : 1000000;
            actual.begin(model, projection, cameraX, cameraY, cameraZ, budget);
            expected.begin(model, projection, cameraX, cameraY, cameraZ, budget);
            for (int step = 0; step < 12; step++) {
                float[] quad = quad(random, perspective);
                actual.rasterize(quad, 0, 0, 0, 0);
                expected.rasterize(quad, 0, 0, 0, 0);
                assertArrayEquals("depth frame=" + frame + " step=" + step,
                    depth(expected), depth(actual), 0.0);
                assertEquals(remaining(expected), remaining(actual));
                for (int query = 0; query < 5; query++) {
                    double x = random.nextDouble() * 3 - 1.5, y = random.nextDouble() * 3 - 1.5;
                    double z = perspective ? -random.nextDouble() * 15 : random.nextDouble() * 3 - 1.5;
                    double size = random.nextDouble();
                    SoftwareOcclusionRaster.Bounds a = new SoftwareOcclusionRaster.Bounds();
                    ReferenceSoftwareOcclusionRaster.Bounds e = new ReferenceSoftwareOcclusionRaster.Bounds();
                    boolean ap = actual.projectBounds(x,y,z,x+size,y+size,z+.1,a);
                    boolean ep = expected.projectBounds(x,y,z,x+size,y+size,z+.1,e);
                    assertEquals(ep, ap);
                    if (ap) {
                        assertEquals(expected.occluded(e), actual.occluded(a));
                        assertEquals(remaining(expected), remaining(actual));
                    }
                }
            }
        }
    }

    @Test public void exactCellEdgesThinSlopesAndPartialRowsMatchScalarRaster() throws Exception {
        SoftwareOcclusionRaster actual = new SoftwareOcclusionRaster(64, 64);
        ReferenceSoftwareOcclusionRaster expected = new ReferenceSoftwareOcclusionRaster(64, 64);
        for (int budget : new int[] { 0, 1, 31, 32, 63, 64, 65, 512, 4095, 4096, 10000 }) {
            for (float delta : new float[] { 0, Math.ulp(.5F), -Math.ulp(.5F), 1e-5F, -1e-5F }) {
                actual.begin(SoftwareOcclusionRasterTest.identity(), SoftwareOcclusionRasterTest.identity(), 0,0,0,budget);
                expected.begin(SoftwareOcclusionRasterTest.identity(), SoftwareOcclusionRasterTest.identity(), 0,0,0,budget);
                float[] wall = { -1,-1,-.5F, 1,-1+delta,-.5F, 1,1,-.5F, -1,1+delta,-.5F };
                actual.rasterize(wall,0,0,0,0); expected.rasterize(wall,0,0,0,0);
                assertArrayEquals(depth(expected),depth(actual),0.0);
                assertEquals(remaining(expected), remaining(actual));
            }
        }
    }

    @Test public void projectedBoxesWithoutWholeCellsCannotWriteAnyDepth() throws Exception {
        Random random = new Random(0xCE11L);
        SoftwareOcclusionRaster raster = new SoftwareOcclusionRaster(160, 90);
        int rejected = 0;
        for (int frame = 0; frame < 3000; frame++) {
            float x = random.nextFloat() * 2 - 1, y = random.nextFloat() * 2 - 1;
            float sx = random.nextFloat() * .025F, sy = random.nextFloat() * .025F;
            float[] projection = SoftwareOcclusionRasterTest.identity();
            projection[0] = .7F + random.nextFloat(); projection[5] = .7F + random.nextFloat();
            raster.begin(SoftwareOcclusionRasterTest.identity(), projection, .01, -.01, 0, 100000);
            SoftwareOcclusionRaster.Bounds bounds = new SoftwareOcclusionRaster.Bounds();
            if (raster.projectBounds(x,y,-.5,x+sx,y+sy,-.5,bounds) && !bounds.canCoverCell) {
                rejected++;
                raster.rasterize(SoftwareOcclusionRasterTest.quad(x,y,x+sx,y+sy,-.5F),0,0,0,0);
                for (double value : depth(raster)) assertEquals(1.0, value, 0.0);
            }
        }
        assertTrue(rejected > 1000);
    }

    @Test public void repeatedLargeQueriesAndPartialDepthChangesMatchScalarBudget() throws Exception {
        for (int budget : new int[] { 14501, 20000, 45000, 200000 }) {
            SoftwareOcclusionRaster actual = new SoftwareOcclusionRaster(160,90);
            ReferenceSoftwareOcclusionRaster expected = new ReferenceSoftwareOcclusionRaster(160,90);
            actual.begin(SoftwareOcclusionRasterTest.identity(),SoftwareOcclusionRasterTest.identity(),0,0,0,budget);
            expected.begin(SoftwareOcclusionRasterTest.identity(),SoftwareOcclusionRasterTest.identity(),0,0,0,budget);
            for (int pass = 0; pass < 5; pass++) {
                float size = pass == 0 ? 1.1F : .45F;
                float[] wall = SoftwareOcclusionRasterTest.quad(-size,-size,size,size,-.2F-pass*.1F);
                actual.rasterize(wall,0,0,0,0); expected.rasterize(wall,0,0,0,0);
                for (int query = 0; query < 16; query++) {
                    double sizeQ = query % 2 == 0 ? .25 : .8;
                    double z = query % 3 == 0 ? -.4 : .5;
                    SoftwareOcclusionRaster.Bounds a = new SoftwareOcclusionRaster.Bounds();
                    ReferenceSoftwareOcclusionRaster.Bounds e = new ReferenceSoftwareOcclusionRaster.Bounds();
                    assertTrue(actual.projectBounds(-sizeQ,-sizeQ,z,sizeQ,sizeQ,z,a));
                    assertTrue(expected.projectBounds(-sizeQ,-sizeQ,z,sizeQ,sizeQ,z,e));
                    assertEquals(expected.occluded(e),actual.occluded(a));
                    assertEquals(remaining(expected),remaining(actual));
                }
                assertArrayEquals(depth(expected),depth(actual),0.0);
            }
        }
    }

    @Test public void redundantDepthBlocksAndPartialNearUpdatesPreserveEveryCellAndQuery() throws Exception {
        SoftwareOcclusionRaster actual = new SoftwareOcclusionRaster(17, 11);
        ReferenceSoftwareOcclusionRaster expected = new ReferenceSoftwareOcclusionRaster(17, 11);
        for (int frame = 0; frame < 10; frame++) {
            double camera = frame * .035;
            actual.begin(SoftwareOcclusionRasterTest.identity(),SoftwareOcclusionRasterTest.identity(),
                camera, -camera, 0, 100000);
            expected.begin(SoftwareOcclusionRasterTest.identity(),SoftwareOcclusionRasterTest.identity(),
                camera, -camera, 0, 100000);
            for (int pass = 0; pass < 40; pass++) {
                float[] wall = pass % 7 == 0
                    ? SoftwareOcclusionRasterTest.quad(-.35F,-.65F,.7F,.3F,-.9F)
                    : SoftwareOcclusionRasterTest.quad(-1.5F,-1.5F,1.5F,1.5F,pass == 1 ? -.8F : -.5F);
                actual.rasterize(wall,0,0,0,0); expected.rasterize(wall,0,0,0,0);
                assertArrayEquals(depth(expected),depth(actual),0D);
                assertEquals(remaining(expected),remaining(actual));
                for (double z : new double[] {-.9,-.8,-.6,.5}) {
                    SoftwareOcclusionRaster.Bounds a = new SoftwareOcclusionRaster.Bounds();
                    ReferenceSoftwareOcclusionRaster.Bounds e = new ReferenceSoftwareOcclusionRaster.Bounds();
                    assertTrue(actual.projectBounds(-.3,-.3,z,.3,.3,z,a));
                    assertTrue(expected.projectBounds(-.3,-.3,z,.3,.3,z,e));
                    assertEquals(expected.occluded(e),actual.occluded(a));
                    assertEquals(remaining(expected),remaining(actual));
                }
            }
        }
    }

    private static float[] quad(Random random, boolean perspective) {
        float x = random.nextFloat() * 4 - 2, y = random.nextFloat() * 4 - 2;
        float z = perspective ? -random.nextFloat() * 12 : random.nextFloat() * 3 - 1.5F;
        double angle = random.nextDouble() * Math.PI * 2;
        float size = random.nextFloat() * (perspective ? 10 : 3);
        float ux = (float) Math.cos(angle) * size, uy = (float) Math.sin(angle) * size;
        float vx = -uy * random.nextFloat(), vy = ux * random.nextFloat();
        float uz = perspective ? random.nextFloat() * 4 - 2 : 0;
        float vz = perspective ? random.nextFloat() * 4 - 2 : 0;
        if (random.nextInt(5) == 0) { vx = -vx; vy = -vy; }
        return new float[] { x,y,z, x+ux,y+uy,z+uz, x+ux+vx,y+uy+vy,z+uz+vz, x+vx,y+vy,z+vz };
    }

    static double[] depth(Object value) throws Exception {
        Field field = value.getClass().getDeclaredField("depth"); field.setAccessible(true);
        return (double[]) field.get(value);
    }
    static int remaining(Object value) throws Exception {
        Field field = value.getClass().getDeclaredField("remaining"); field.setAccessible(true);
        return field.getInt(value);
    }
}
