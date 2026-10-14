package dev.rlcraft.ice.optimizer.render.visibility;

import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public final class TerrainOccluderMeshTest {
    @Test public void coveragePriorityKeepsAllFacesAndRanksLargestOpaqueRectanglesFirst() {
        ByteBuffer bytes = buffer(3);
        put(bytes, SoftwareOcclusionRasterTest.quad(-1, -1, 1, 1, -.3F));
        put(bytes, SoftwareOcclusionRasterTest.quad(-4, -4, 4, 4, -.5F));
        put(bytes, SoftwareOcclusionRasterTest.quad(-2, -2, 2, 2, -.4F));
        TerrainOccluderMesh mesh = capture(bytes, OPAQUE);
        assertEquals(3, mesh.priority.length);
        boolean[] seen = new boolean[3];
        double previous = Double.POSITIVE_INFINITY;
        for (short index : mesh.priority) {
            assertFalse(seen[index]); seen[index] = true;
            int offset = index * 12;
            double width = Math.abs(mesh.quads[offset + 3] - mesh.quads[offset]);
            double height = Math.abs(mesh.quads[offset + 10] - mesh.quads[offset + 1]);
            assertTrue(width * height <= previous);
            previous = width * height;
            assertTrue(mesh.facesCamera(index, 0, 0, 1));
            assertFalse(mesh.facesCamera(index, 0, 0, -2));
        }
        assertEquals(36, mesh.quads.length);
    }
    @Test public void fusedCaptureStillIncludesVerticesAfterOccluderCapacityAndRejectsTheirNaN() {
        ByteBuffer bytes = buffer(514);
        for (int i = 0; i < 513; i++) put(bytes, SoftwareOcclusionRasterTest.quad(i, 0, i + 1, 1, 0));
        put(bytes, new float[] { -20,0,0, 600,0,0, 600,1,1, -20,1,0 });
        TerrainOccluderMesh mesh = capture(bytes, OPAQUE);
        assertNotNull(mesh);
        assertTrue(mesh.minX < -20);
        assertTrue(mesh.maxX > 600);
        assertTrue(mesh.maxZ > 1);
        bytes.putFloat(513 * 112, Float.NaN);
        assertNull(TerrainOccluderMesh.capture(bytes,28,514 * 4,OPAQUE));
    }

    private static final TerrainOccluderMesh.Opacity OPAQUE = new TerrainOccluderMesh.Opacity() {
        @Override public boolean opaque(float u0,float v0,float u1,float v1) { return true; }
    };
    @Test public void adjacentOpaqueTilesMergeButHoleAndOppositeFacingFacesDoNot() {
        ByteBuffer tiled = buffer(4);
        put(tiled, SoftwareOcclusionRasterTest.quad(-.9F,-.9F,0,0,-.5F));
        put(tiled, SoftwareOcclusionRasterTest.quad(0,-.9F,.9F,0,-.5F));
        put(tiled, SoftwareOcclusionRasterTest.quad(-.9F,0,0,.9F,-.5F));
        put(tiled, SoftwareOcclusionRasterTest.quad(0,0,.9F,.9F,-.5F));
        TerrainOccluderMesh merged = capture(tiled, OPAQUE);
        assertEquals(12, merged.quads.length);
        SoftwareOcclusionRaster raster = SoftwareOcclusionRasterTest.raster();
        raster.rasterize(merged.quads,0,0,0,0);
        assertTrue(raster.occluded(SoftwareOcclusionRasterTest.bounds(raster,-.1,-.1,.1,.1,.5)));

        ByteBuffer hole = buffer(8);
        for (int y=0;y<3;y++) for (int x=0;x<3;x++) if (x!=1 || y!=1) {
            put(hole, SoftwareOcclusionRasterTest.quad(-.9F+x*.6F,-.9F+y*.6F,
                -.9F+(x+1)*.6F,-.9F+(y+1)*.6F,-.5F));
        }
        TerrainOccluderMesh ring = capture(hole, OPAQUE);
        raster = SoftwareOcclusionRasterTest.raster();
        for(int i=0;i<ring.quads.length;i+=12) raster.rasterize(ring.quads,i,0,0,0);
        assertFalse(raster.occluded(SoftwareOcclusionRasterTest.bounds(raster,-.05,-.05,.05,.05,.5)));
        ByteBuffer opposite = buffer(2);
        put(opposite,SoftwareOcclusionRasterTest.quad(-.9F,-.9F,0,.9F,-.5F));
        put(opposite,new float[]{0,-.9F,-.5F, 0,.9F,-.5F, .9F,.9F,-.5F, .9F,-.9F,-.5F});
        assertEquals(24,capture(opposite,OPAQUE).quads.length);
    }

    @Test public void unknownOpacityVertexAlphaAndNonplanarFacesAreNeverOccluders() {
        ByteBuffer bytes = buffer(1);
        put(bytes, SoftwareOcclusionRasterTest.quad(0,0,1,1,0));
        bytes.flip();
        TerrainOccluderMesh unknown = TerrainOccluderMesh.capture(bytes,28,4,
            new TerrainOccluderMesh.Opacity() {
                @Override public boolean opaque(float u0,float v0,float u1,float v1) { return false; }
            });
        assertEquals(0, unknown.quads.length);
        bytes.put(15,(byte)0);
        assertEquals(0,TerrainOccluderMesh.capture(bytes,28,4,OPAQUE).quads.length);
        bytes.put(15,(byte)255);
        bytes.putFloat(2*28+8,.1F);
        assertEquals(0,TerrainOccluderMesh.capture(bytes,28,4,OPAQUE).quads.length);
        assertEquals(0,bytes.position());
    }

    @Test public void boundsIncludeUnsupportedGeometryBeyondVanillaSection() {
        ByteBuffer bytes = buffer(1);
        put(bytes, new float[] { 0,0,0, 40,0,0, 40,1,1, 0,1,0 });
        TerrainOccluderMesh mesh = capture(bytes,OPAQUE);
        assertEquals(0,mesh.quads.length);
        assertTrue(mesh.maxX>40);
        assertTrue(mesh.maxZ>1);
        assertTrue(mesh.minX<0);
        bytes.putFloat(0,Float.NaN);
        assertNull(TerrainOccluderMesh.capture(bytes,28,4,OPAQUE));
    }

    @Test public void opacityRequiresEveryMipPixelIncludingInvisibleLookingAlpha() {
        assertTrue(OpaqueTerrainTextures.fullyOpaque(new int[][] {{-1,0xff000000},{0xff123456}}));
        assertFalse(OpaqueTerrainTextures.fullyOpaque(new int[][] {{-1},{0x7fffffff}}));
        assertFalse(OpaqueTerrainTextures.fullyOpaque(new int[][] {{-1},null}));
        assertFalse(OpaqueTerrainTextures.fullyOpaque(new int[0][]));
    }

    static ByteBuffer buffer(int quads) { return ByteBuffer.allocate(quads*112).order(ByteOrder.nativeOrder()); }
    static void put(ByteBuffer bytes,float[] quad) {
        for(int i=0;i<4;i++) {
            bytes.putFloat(quad[i*3]).putFloat(quad[i*3+1]).putFloat(quad[i*3+2]);
            bytes.putInt(-1).putFloat((i==1||i==2)?.9F:.1F).putFloat(i>=2?.9F:.1F).putInt(0);
        }
    }
    static TerrainOccluderMesh capture(ByteBuffer bytes,TerrainOccluderMesh.Opacity opacity) {
        bytes.flip(); return TerrainOccluderMesh.capture(bytes,28,bytes.limit()/28,opacity);
    }
}
