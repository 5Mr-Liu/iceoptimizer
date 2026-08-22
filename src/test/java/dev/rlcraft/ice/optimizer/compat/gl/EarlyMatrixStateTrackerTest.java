package dev.rlcraft.ice.optimizer.compat.gl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

import java.nio.FloatBuffer;
import org.junit.Test;
import org.lwjgl.BufferUtils;

public final class EarlyMatrixStateTrackerTest {
    @Test
    public void mirrorsPostMultipliedTranslateScaleAndStackRestore() {
        seedIdentity();
        EarlyMatrixStateTracker.translate(2.0F, 3.0F, 4.0F);
        EarlyMatrixStateTracker.pushMatrix();
        EarlyMatrixStateTracker.scale(2.0F, 3.0F, 4.0F);
        float[] scaled = EarlyMatrixStateTracker.modelView();
        assertArrayEquals(new float[] {
            2, 0, 0, 0, 0, 3, 0, 0, 0, 0, 4, 0, 2, 3, 4, 1
        }, scaled, 0.0F);
        EarlyMatrixStateTracker.popMatrix();
        assertArrayEquals(new float[] {
            1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 2, 3, 4, 1
        }, EarlyMatrixStateTracker.modelView(), 0.0F);
    }

    @Test
    public void underflowAndUnknownModeFailClosed() {
        seedIdentity();
        EarlyMatrixStateTracker.popMatrix();
        assertFalse(EarlyMatrixStateTracker.isKnown());
        assertNull(EarlyMatrixStateTracker.modelView());
        seedIdentity();
        EarlyMatrixStateTracker.matrixMode(12345);
        assertFalse(EarlyMatrixStateTracker.isKnown());
    }

    @Test
    public void stateIsStrictlyThreadLocal() throws Exception {
        seedIdentity();
        EarlyMatrixStateTracker.translate(5.0F, 0.0F, 0.0F);
        final float[][] other = new float[1][];
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                other[0] = EarlyMatrixStateTracker.modelView();
            }
        });
        thread.start();
        thread.join();
        assertNull(other[0]);
        assertEquals(5.0F, EarlyMatrixStateTracker.modelView()[12], 0.0F);
    }

    @Test
    public void mirrorsOrthoAndPublishesAllCompatibilityMatrices() {
        seedIdentity();
        EarlyMatrixStateTracker.matrixMode(5889);
        EarlyMatrixStateTracker.ortho(0.0D, 800.0D, 600.0D, 0.0D,
            1000.0D, 3000.0D);
        EarlyMatrixStateTracker.Snapshot snapshot =
            EarlyMatrixStateTracker.snapshot();
        assertNotNull(snapshot);
        assertEquals(5889, snapshot.getMode());
        float[] projection = snapshot.getProjection();
        assertEquals(2.0F / 800.0F, projection[0], 0.0F);
        assertEquals(-2.0F / 600.0F, projection[5], 0.0F);
        assertEquals(-1.0F, projection[12], 0.0F);
        assertEquals(1.0F, projection[13], 0.0F);
        assertEquals(16, snapshot.getTexture().length);
    }

    @Test
    public void nonFiniteOrOverflowingTransformsInvalidateInsteadOfPublishing() {
        seedIdentity();
        EarlyMatrixStateTracker.scale(Float.POSITIVE_INFINITY, 1.0F, 1.0F);
        assertFalse(EarlyMatrixStateTracker.isKnown());

        seedIdentity();
        FloatBuffer invalid = BufferUtils.createFloatBuffer(16);
        for (int i = 0; i < 16; i++) invalid.put(i == 0 ? Float.NaN
            : i % 5 == 0 ? 1.0F : 0.0F);
        invalid.flip();
        EarlyMatrixStateTracker.multMatrix(invalid);
        assertFalse(EarlyMatrixStateTracker.isKnown());

        seedIdentity();
        EarlyMatrixStateTracker.scale(Float.MAX_VALUE, Float.MAX_VALUE,
            Float.MAX_VALUE);
        EarlyMatrixStateTracker.scale(Float.MAX_VALUE, 1.0F, 1.0F);
        assertFalse(EarlyMatrixStateTracker.isKnown());
    }

    @Test
    public void nonFiniteSeedMatricesNeverBecomeKnown() {
        FloatBuffer model = identity();
        FloatBuffer projection = identity();
        model.put(7, Float.NaN);
        EarlyMatrixStateTracker.seed(5888, model, projection);
        assertFalse(EarlyMatrixStateTracker.isKnown());

        model = identity();
        projection = identity();
        projection.put(12, Float.POSITIVE_INFINITY);
        EarlyMatrixStateTracker.seed(5888, model, projection);
        assertFalse(EarlyMatrixStateTracker.isKnown());

        FloatBuffer texture = identity();
        texture.put(0, Float.NEGATIVE_INFINITY);
        EarlyMatrixStateTracker.seed(5888, identity(), identity(), texture);
        assertFalse(EarlyMatrixStateTracker.isKnown());
    }

    private static void seedIdentity() {
        EarlyMatrixStateTracker.seed(5888, identity(), identity());
    }

    private static FloatBuffer identity() {
        FloatBuffer result = BufferUtils.createFloatBuffer(16);
        for (int i = 0; i < 16; i++) {
            float value = i % 5 == 0 ? 1.0F : 0.0F;
            result.put(value);
        }
        result.flip();
        return result;
    }
}
