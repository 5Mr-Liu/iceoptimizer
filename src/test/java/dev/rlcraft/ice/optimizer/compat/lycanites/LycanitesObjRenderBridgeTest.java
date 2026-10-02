package dev.rlcraft.ice.optimizer.compat.lycanites;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
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

    @Test
    public void uploadedBufferIgnoresCpuElementChangesJustLikeVboModel() throws Exception {
        TestGroup group = new TestGroup();
        UploadedProbe probe = new UploadedProbe(group);
        assertTrue(probe.matches(group, 17));

        // The public arrays are not immutable. An existing VBOModel buffer is
        // unaffected by edits to their elements, including invalid CPU data.
        group.mesh.indices[0] = -1;
        group.mesh.vertices[1] = null;
        group.mesh.normals[2] = null;
        assertFalse(LycanitesObjRenderBridge.validMeshData(group.mesh.indices,
            group.mesh.vertices, group.mesh.normals));
        assertTrue(probe.matches(group, 17));
        assertEquals(0, group.mesh.getVboCalls);

        // Deletion makes those same CPU edits relevant to a subsequent upload.
        group.mesh.vbo = -1;
        assertFalse(probe.matches(group, 17));
        assertFalse(probe.captureValid(group));
        group.mesh.indices[0] = 0;
        group.mesh.vertices[1] = new Object();
        group.mesh.normals[2] = new Object();
        assertTrue(probe.captureValid(group));
    }

    @Test
    public void uploadedBufferMatchRejectsEveryReplacementAndDeletion() throws Exception {
        TestGroup group = new TestGroup();
        UploadedProbe probe = new UploadedProbe(group);
        int[] indices = group.mesh.indices;
        Object[] vertices = group.mesh.vertices;
        Object[] normals = group.mesh.normals;

        group.mesh.vbo = 18;
        assertFalse(probe.matches(group, 17));
        group.mesh.vbo = -1;
        assertFalse(probe.matches(group, 17));
        group.mesh.vbo = 17;
        group.mesh.indices = indices.clone();
        assertFalse(probe.matches(group, 17));
        group.mesh.indices = indices;
        group.mesh.vertices = vertices.clone();
        assertFalse(probe.matches(group, 17));
        group.mesh.vertices = vertices;
        group.mesh.normals = normals.clone();
        assertFalse(probe.matches(group, 17));
        group.mesh.normals = normals;
        assertTrue(probe.matches(group, 17));

        assertFalse(probe.matches(new TestGroup(), 17));
        group.mesh = new TestMesh();
        assertFalse(probe.matches(group, 17));
        group.mesh = null;
        assertFalse(probe.matches(group, 17));
    }

    @Test
    public void detachedAndUnpreparedBuffersCannotUseUploadedShortcut() throws Exception {
        TestGroup group = new TestGroup();
        UploadedProbe probe = new UploadedProbe(group);
        assertFalse(probe.matches(group, 0));
        assertFalse(probe.matches(group, -1));
        group.mesh.vbo = -1;
        UploadedProbe detached = new UploadedProbe(group);
        assertFalse(detached.matches(group, 17));
        assertFalse(detached.matches(group, -1));
    }

    private static final class UploadedProbe {
        private final Object access;
        private final Object signature;
        private final Method capture;
        private final Method matches;
        private final Method valid;

        private UploadedProbe(TestGroup group) throws Exception {
            Class<?> accessType = Class.forName(LycanitesObjRenderBridge.class.getName()
                + "$MeshAccess");
            Class<?> signatureType = Class.forName(LycanitesObjRenderBridge.class.getName()
                + "$MeshSignature");
            Constructor<?> constructor = accessType.getDeclaredConstructor(Class.class);
            constructor.setAccessible(true);
            access = constructor.newInstance(TestGroup.class);
            capture = accessType.getDeclaredMethod("capture", Object.class);
            capture.setAccessible(true);
            signature = capture.invoke(access, group);
            matches = accessType.getDeclaredMethod("matchesUploaded", Object.class,
                signatureType, Integer.TYPE);
            matches.setAccessible(true);
            valid = signatureType.getDeclaredMethod("isTriangleMesh");
            valid.setAccessible(true);
        }

        private boolean matches(TestGroup group, int vbo) throws Exception {
            return (Boolean) matches.invoke(access, group, signature, vbo);
        }

        private boolean captureValid(TestGroup group) throws Exception {
            return (Boolean) valid.invoke(capture.invoke(access, group));
        }
    }

    public static final class TestGroup {
        public TestMesh mesh = new TestMesh();
    }

    public static final class TestMesh {
        public int[] indices = { 0, 1, 2 };
        public Object[] vertices = { new Object(), new Object(), new Object() };
        public Object[] normals = { new Object(), new Object(), new Object() };
        private int vbo = 17;
        private int getVboCalls;

        public int getVbo() {
            getVboCalls++;
            return vbo;
        }
    }
}
