package dev.rlcraft.ice.optimizer.render.visibility;

import static org.junit.Assert.*;
import org.junit.Test;

public final class SoftwareOcclusionRasterTest {
    @Test public void realFrontFacingWallHidesOnlyStrictlyDeeperBounds() {
        SoftwareOcclusionRaster raster = raster();
        raster.rasterize(quad(-0.95F, -0.95F, 0.95F, 0.95F, -0.5F), 0, 0, 0, 0);
        assertTrue(raster.occluded(bounds(raster, -0.2, -0.2, 0.2, 0.2, 0.5)));
        assertFalse(raster.occluded(bounds(raster, -0.2, -0.2, 0.2, 0.2, -0.5)));
        assertFalse(raster.occluded(bounds(raster, -0.2, -0.2, 0.2, 0.2, -0.7)));
    }

    @Test public void windowAndPartiallyCoveredCellsRemainVisible() {
        SoftwareOcclusionRaster raster = raster();
        raster.rasterize(quad(-0.95F, -0.95F, -0.25F, 0.95F, -0.5F), 0, 0, 0, 0);
        raster.rasterize(quad(0.25F, -0.95F, 0.95F, 0.95F, -0.5F), 0, 0, 0, 0);
        raster.rasterize(quad(-0.25F, 0.25F, 0.25F, 0.95F, -0.5F), 0, 0, 0, 0);
        raster.rasterize(quad(-0.25F, -0.95F, 0.25F, -0.25F, -0.5F), 0, 0, 0, 0);
        assertFalse(raster.occluded(bounds(raster, -0.05, -0.05, 0.05, 0.05, 0.5)));
        assertFalse(raster.occluded(bounds(raster, -0.3, -0.1, -0.24, 0.1, 0.5)));
        assertTrue(raster.occluded(bounds(raster, -0.7, -0.1, -0.6, 0.1, 0.5)));
    }

    @Test public void cameraMovementClearsPreviousCoverageAndUsesNewProjection() {
        SoftwareOcclusionRaster raster = raster();
        float[] wall = quad(-0.95F, -0.95F, 0.95F, 0.95F, -0.5F);
        raster.rasterize(wall, 0, 0, 0, 0);
        assertTrue(raster.occluded(bounds(raster, -0.1, -0.1, 0.1, 0.1, 0.5)));
        raster.begin(identity(), identity(), 0.7, 0, 0, 100000);
        assertFalse(raster.occluded(bounds(raster, 0.6, -0.1, 0.8, 0.1, 0.5)));
        raster.rasterize(wall, 0, 0, 0, 0);
        assertFalse(raster.occluded(bounds(raster, 0.9, -0.1, 1.0, 0.1, 0.5)));
    }

    @Test public void reverseWindingNearClipAndExhaustedBudgetNeverCreateCoverage() {
        SoftwareOcclusionRaster raster = raster();
        float[] clockwise = { -.95F, -.95F, -.5F, -.95F, .95F, -.5F,
            .95F, .95F, -.5F, .95F, -.95F, -.5F };
        raster.rasterize(clockwise, 0, 0, 0, 0);
        assertFalse(raster.occluded(bounds(raster, -.1, -.1, .1, .1, .5)));
        float[] clipped = quad(-.95F, -.95F, .95F, .95F, -.5F);
        clipped[2] = -1.1F;
        raster.rasterize(clipped, 0, 0, 0, 0);
        assertFalse(raster.occluded(bounds(raster, -.1, -.1, .1, .1, .5)));
        assertFalse(raster.projectBounds(-.1, -.1, -1.1, .1, .1, .5,
            new SoftwareOcclusionRaster.Bounds()));
        raster.begin(identity(), identity(), 0, 0, 0, 1);
        raster.rasterize(quad(-.95F, -.95F, .95F, .95F, -.5F), 0, 0, 0, 0);
        assertFalse(raster.occluded(bounds(raster, -.1, -.1, .1, .1, .5)));
        assertTrue(raster.exhausted());
    }

    @Test public void perspectiveDepthAndObliqueWallKeepFarDepthConservative() {
        float[] projection = new float[16];
        projection[0] = projection[5] = 1;
        projection[10] = -11F / 9F;
        projection[11] = -1;
        projection[14] = -20F / 9F;
        SoftwareOcclusionRaster raster = new SoftwareOcclusionRaster(64, 64);
        raster.begin(identity(), projection, 0, 0, 0, 100000);
        float[] sloped = { -2, -2, -3, 2, -2, -5, 2, 2, -5, -2, 2, -3 };
        raster.rasterize(sloped, 0, 0, 0, 0);
        assertTrue(raster.occluded(bounds(raster, -.1, -.1, .1, .1, -7)));
        assertFalse(raster.occluded(bounds(raster, -.1, -.1, .1, .1, -4)));
        assertFalse(raster.projectBounds(-.1, -.1, -.5, .1, .1, .5,
            new SoftwareOcclusionRaster.Bounds()));
    }

    static SoftwareOcclusionRaster raster() {
        SoftwareOcclusionRaster value = new SoftwareOcclusionRaster(64, 64);
        value.begin(identity(), identity(), 0, 0, 0, 100000);
        return value;
    }
    static float[] identity() {
        float[] value = new float[16]; value[0] = value[5] = value[10] = value[15] = 1; return value;
    }
    static float[] quad(float x0, float y0, float x1, float y1, float z) {
        return new float[] { x0,y0,z, x1,y0,z, x1,y1,z, x0,y1,z };
    }
    static SoftwareOcclusionRaster.Bounds bounds(SoftwareOcclusionRaster raster,
            double x0, double y0, double x1, double y1, double z) {
        SoftwareOcclusionRaster.Bounds value = new SoftwareOcclusionRaster.Bounds();
        assertTrue(raster.projectBounds(x0,y0,z,x1,y1,z,value));
        return value;
    }
}
