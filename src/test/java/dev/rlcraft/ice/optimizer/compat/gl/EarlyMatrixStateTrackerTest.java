package dev.rlcraft.ice.optimizer.compat.gl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

import java.nio.FloatBuffer;
import java.util.Random;
import org.junit.Test;
import org.lwjgl.BufferUtils;

public final class EarlyMatrixStateTrackerTest {
    @Test
    public void optimizedMultiplicationMatchesOriginalFloatBitsAndOverflow() {
        Random random = new Random(0x1ce107L);
        for (int sample = 0; sample < 1024; sample++) {
            float[] left = randomFiniteMatrix(random, sample < 512);
            float[] right = randomFiniteMatrix(random, sample < 512);
            float[] expected = originalMultiply(left, right);
            EarlyMatrixStateTracker.seed(5888, FloatBuffer.wrap(left), identity());
            long invalidations = EarlyMatrixStateTracker.invalidations();
            EarlyMatrixStateTracker.multMatrix(FloatBuffer.wrap(right));
            if (expected == null) {
                assertFalse(EarlyMatrixStateTracker.isKnown());
                assertEquals(invalidations + 1,
                    EarlyMatrixStateTracker.invalidations());
            } else {
                assertRawMatrix(expected, EarlyMatrixStateTracker.modelView());
                assertEquals(invalidations,
                    EarlyMatrixStateTracker.invalidations());
            }
        }
    }

    @Test
    public void unitAndGeneralAxisRotationsMatchOriginalFloatBits() {
        Random random = new Random(0x107aceL);
        float[] angles = {0.0F, -0.0F, 90.0F, -90.0F, 180.0F,
            Float.MIN_VALUE, Float.MAX_VALUE, -367.125F};
        for (int sample = 0; sample < 32; sample++) {
            float[] left = randomFiniteMatrix(random, sample < 16);
            for (int axis = 0; axis < 3; axis++) {
                for (int signs = 0; signs < 8; signs++) {
                    float[] direction = {0.0F, 0.0F, 0.0F};
                    for (int i = 0; i < 3; i++) {
                        direction[i] = (signs & (1 << i)) == 0 ? 0.0F : -0.0F;
                    }
                    direction[axis] = (signs & (1 << axis)) == 0 ? 1.0F : -1.0F;
                    for (float angle : angles) {
                        assertRotationMatchesOriginal(left, angle,
                            direction[0], direction[1], direction[2]);
                    }
                }
            }
            // General and non-unit axes retain normalization and use the same
            // multiplication kernel as the unit axis path.
            assertRotationMatchesOriginal(left, 53.25F, 2.0F, -3.0F, 4.0F);
            assertRotationMatchesOriginal(left, -19.5F, -0.0F, 7.0F, -0.0F);
            assertRotationMatchesOriginal(left, 91.0F,
                Float.MIN_VALUE, 0.0F, 0.0F);
        }
    }

    @Test
    public void invalidRotationInputsStillInvalidateExactlyOnce() {
        float[][] invalid = {
            {30.0F, 0.0F, -0.0F, 0.0F},
            {30.0F, Float.NaN, 0.0F, 0.0F},
            {30.0F, 0.0F, Float.POSITIVE_INFINITY, 0.0F},
            {30.0F, 0.0F, 0.0F, Float.NEGATIVE_INFINITY},
            {Float.NaN, 1.0F, 0.0F, 0.0F},
            {Float.POSITIVE_INFINITY, 0.0F, 1.0F, 0.0F}
        };
        for (float[] values : invalid) {
            seedIdentity();
            long invalidations = EarlyMatrixStateTracker.invalidations();
            EarlyMatrixStateTracker.rotate(values[0], values[1], values[2], values[3]);
            assertFalse(EarlyMatrixStateTracker.isKnown());
            assertNull(EarlyMatrixStateTracker.modelView());
            assertEquals(invalidations + 1, EarlyMatrixStateTracker.invalidations());
        }
    }

    @Test
    public void seedAndMultiplyReadFromPositionWithoutConsumingCallerBuffers() {
        FloatBuffer matrix = FloatBuffer.allocate(24);
        for (int index = 0; index < 16; index++) {
            matrix.put(4 + index, index % 5 == 0 ? 1.0F : 0.0F);
        }
        matrix.put(16, 7.0F);
        matrix.position(4);
        matrix.limit(20);
        matrix = matrix.asReadOnlyBuffer();
        matrix.mark();
        EarlyMatrixStateTracker.seed(5888, matrix, identity());
        assertEquals(7.0F, EarlyMatrixStateTracker.modelView()[12], 0.0F);
        EarlyMatrixStateTracker.multMatrix(matrix);
        assertEquals(14.0F, EarlyMatrixStateTracker.modelView()[12], 0.0F);
        assertEquals(4, matrix.position());
        assertEquals(20, matrix.limit());
        matrix.reset();
    }

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

    private static void assertRotationMatchesOriginal(float[] left,
                                                       float degrees,
                                                       float x, float y, float z) {
        double length = Math.sqrt((double) x * x + (double) y * y + (double) z * z);
        float nx = (float) (x / length);
        float ny = (float) (y / length);
        float nz = (float) (z / length);
        double radians = degrees * (Math.PI / 180.0D);
        float c = (float) Math.cos(radians);
        float s = (float) Math.sin(radians);
        float one = 1.0F - c;
        float[] rotation = new float[16];
        rotation[0] = nx * nx * one + c;
        rotation[1] = ny * nx * one + nz * s;
        rotation[2] = nx * nz * one - ny * s;
        rotation[4] = nx * ny * one - nz * s;
        rotation[5] = ny * ny * one + c;
        rotation[6] = ny * nz * one + nx * s;
        rotation[8] = nx * nz * one + ny * s;
        rotation[9] = ny * nz * one - nx * s;
        rotation[10] = nz * nz * one + c;
        rotation[15] = 1.0F;
        float[] expected = originalMultiply(left, rotation);
        EarlyMatrixStateTracker.seed(5888, FloatBuffer.wrap(left), identity());
        EarlyMatrixStateTracker.rotate(degrees, x, y, z);
        if (expected == null) {
            assertFalse(EarlyMatrixStateTracker.isKnown());
        } else {
            assertRawMatrix(expected, EarlyMatrixStateTracker.modelView());
        }
    }

    /** Reference the original ordered loop, including its +0 accumulator. */
    private static float[] originalMultiply(float[] left, float[] right) {
        float[] result = new float[16];
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                float value = 0.0F;
                for (int k = 0; k < 4; k++) {
                    value += left[k * 4 + row] * right[column * 4 + k];
                }
                if (!Float.isFinite(value)) return null;
                result[column * 4 + row] = value;
            }
        }
        return result;
    }

    private static float[] randomFiniteMatrix(Random random, boolean bounded) {
        float[] result = new float[16];
        for (int i = 0; i < result.length; i++) {
            if (random.nextInt(4) == 0) {
                result[i] = random.nextBoolean() ? 0.0F : -0.0F;
            } else if (bounded) {
                result[i] = (random.nextFloat() - 0.5F) * 200.0F;
            } else {
                int bits;
                do { bits = random.nextInt(); }
                while ((bits & 0x7f800000) == 0x7f800000);
                result[i] = Float.intBitsToFloat(bits);
            }
        }
        return result;
    }

    private static void assertRawMatrix(float[] expected, float[] actual) {
        assertNotNull(actual);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("matrix slot " + i, Float.floatToRawIntBits(expected[i]),
                Float.floatToRawIntBits(actual[i]));
        }
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
