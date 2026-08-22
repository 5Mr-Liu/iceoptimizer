package dev.rlcraft.ice.optimizer.render.visibility;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.junit.Test;

public final class RenderMatrixBridgeTest {
    @Test
    public void copiesWithoutChangingCallerBufferPositions() {
        FloatBuffer model = FloatBuffer.allocate(16);
        FloatBuffer projection = FloatBuffer.allocate(16);
        IntBuffer viewport = IntBuffer.allocate(4);
        for (int i = 0; i < 16; i++) {
            model.put(i, i + 0.25F);
            projection.put(i, 100.0F + i);
        }
        viewport.put(0, 3);
        viewport.put(1, 4);
        viewport.put(2, 1920);
        viewport.put(3, 1080);
        model.position(7);
        projection.position(8);
        viewport.position(2);
        RenderMatrixBridge.capture(model, projection, viewport);
        assertEquals(7, model.position());
        assertEquals(8, projection.position());
        assertEquals(2, viewport.position());

        RenderMatrixBridge.Snapshot first = RenderMatrixBridge.snapshot();
        assertNotNull(first);
        assertEquals(1920, first.getWidth());
        assertEquals(1080, first.getHeight());
        assertEquals(0.25F, first.copyModelView()[0], 0.0F);
        assertTrue(first.matrixEquals(RenderMatrixBridge.snapshot()));

        model.put(0, 999.0F);
        RenderMatrixBridge.capture(model, projection, viewport);
        assertFalse(first.matrixEquals(RenderMatrixBridge.snapshot()));
    }

    @Test
    public void rejectsBuffersWhoseAccessibleLimitIsTooSmall() {
        FloatBuffer model = FloatBuffer.allocate(16);
        FloatBuffer projection = FloatBuffer.allocate(16);
        IntBuffer viewport = IntBuffer.allocate(4);
        model.limit(15);
        RenderMatrixBridge.Snapshot before = RenderMatrixBridge.snapshot();
        RenderMatrixBridge.capture(model, projection, viewport);
        RenderMatrixBridge.Snapshot after = RenderMatrixBridge.snapshot();
        if (before == null) assertTrue(after == null);
        else assertEquals(before.getVersion(), after.getVersion());
    }

    @Test
    public void saturatedVersionNeverMakesDifferentMatricesMatch()
        throws Exception {
        Field version = RenderMatrixBridge.class.getDeclaredField("version");
        version.setAccessible(true);
        long previous = version.getLong(null);
        try {
            FloatBuffer model = FloatBuffer.allocate(16);
            FloatBuffer projection = FloatBuffer.allocate(16);
            IntBuffer viewport = IntBuffer.wrap(new int[] { 0, 0, 800, 600 });
            for (int index = 0; index < 16; index++) {
                model.put(index, index);
                projection.put(index, 100.0F + index);
            }
            version.setLong(null, Long.MAX_VALUE);
            RenderMatrixBridge.capture(model, projection, viewport);
            RenderMatrixBridge.Snapshot first = RenderMatrixBridge.snapshot();

            model.put(7, Float.intBitsToFloat(0x7fc00001));
            RenderMatrixBridge.capture(model, projection, viewport);
            RenderMatrixBridge.Snapshot second = RenderMatrixBridge.snapshot();

            assertEquals(Long.MAX_VALUE, first.getVersion());
            assertEquals(Long.MAX_VALUE, second.getVersion());
            assertFalse(first.matrixEquals(second));
        } finally {
            version.setLong(null, previous);
        }
    }
}
