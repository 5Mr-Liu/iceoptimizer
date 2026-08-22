package dev.rlcraft.ice.optimizer.compat.lycanites;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LycanitesObjRenderBridgeTest {
    @Test
    public void acceptsOnlyCompleteIndexedTriangleData() {
        Object[] vertices = { new Object(), new Object(), new Object() };
        Object[] normals = { new Object(), new Object(), new Object() };
        assertTrue(LycanitesObjRenderBridge.validMeshData(
            new int[] { 0, 1, 2 }, vertices, null));
        assertTrue(LycanitesObjRenderBridge.validMeshData(
            new int[] { 0, 1, 2 }, vertices, normals));

        assertFalse(LycanitesObjRenderBridge.validMeshData(
            new int[] { 0, 1 }, vertices, null));
        assertFalse(LycanitesObjRenderBridge.validMeshData(
            new int[] { 0, 1, 3 }, vertices, null));
        assertFalse(LycanitesObjRenderBridge.validMeshData(
            new int[] { 0, 1, 2 }, new Object[] { new Object(), null, new Object() }, null));
        assertFalse(LycanitesObjRenderBridge.validMeshData(
            new int[] { 0, 1, 2 }, vertices,
            new Object[] { new Object(), null, new Object() }));
    }
}
