package dev.rlcraft.ice.optimizer.render.terrain;

import org.junit.Test;
import static org.junit.Assert.*;

public class NativeTerrainVisibilityTest {
    @Test public void traversalOnlyMovesAwayFromCameraAndKeepsBoundaryFaces() {
        assertEquals(63, NativeTerrainVisibility.awayFaces(0,0,0,8,8,8));
        assertEquals(63 & ~16, NativeTerrainVisibility.awayFaces(16,0,0,8,8,8));
        assertEquals(63 & ~32, NativeTerrainVisibility.awayFaces(-16,0,0,8,8,8));
        assertEquals(63, NativeTerrainVisibility.awayFaces(0,0,0,0,16,0));
        assertEquals(1|4|16, NativeTerrainVisibility.awayFaces(-32,-32,-32,8,8,8));
    }

    @Test public void multipleEntryFacesContributeAllReachableExits() {
        long visibility = (1L << (4*6+2)) | (1L << (0*6+3));
        assertEquals(4,NativeTerrainVisibility.exits(visibility,4));
        assertEquals(8,NativeTerrainVisibility.exits(visibility,0));
        assertEquals(12,NativeTerrainVisibility.exits(visibility,4)|NativeTerrainVisibility.exits(visibility,0));
        assertEquals(0,NativeTerrainVisibility.exits(visibility,1));
    }
}
