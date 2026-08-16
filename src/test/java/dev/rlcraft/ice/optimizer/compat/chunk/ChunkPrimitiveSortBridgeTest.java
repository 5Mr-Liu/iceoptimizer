package dev.rlcraft.ice.optimizer.compat.chunk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;
import org.junit.Test;

public final class ChunkPrimitiveSortBridgeTest {
    @Test
    public void primitiveSortMatchesVanillaStableObjectSortBitForBit() {
        Random random = new Random(0x1CEB00FEL);
        for (int sample = 0; sample < 64; sample++) {
            int quads = 2 + random.nextInt(96);
            Fixture fixture = new Fixture(quads, 7);
            for (int quad = 0; quad < quads; quad++) {
                float x = sample % 7 == 0 && quad % 5 == 0
                    ? Float.NaN : (float) (random.nextInt(9) - 4);
                float y = (float) (random.nextInt(7) - 3);
                float z = (float) (random.nextInt(9) - 4);
                fixture.putQuad(quad, x, y, z, 0x60000000 | quad);
            }
            int[] original = fixture.copyRaw();
            int[] expected = vanillaReference(original, quads, fixture.stride,
                0.25F, -1.5F, 2.0F);
            ChunkPrimitiveSortBridge.sortForTest(fixture, 0.25F, -1.5F, 2.0F);
            assertArrayEquals(expected, fixture.copyRaw());
            assertEquals(quads * 4 * fixture.stride, fixture.ints.position());
            assertEquals(fixture.ints.capacity(), fixture.ints.limit());
        }
    }

    @Test
    public void equalDistancesRetainOriginalQuadOrder() {
        Fixture fixture = new Fixture(3, 4);
        fixture.putQuad(0, 2.0F, 0.0F, 0.0F, 100);
        fixture.putQuad(1, 3.0F, 0.0F, 0.0F, 200);
        fixture.putQuad(2, -3.0F, 0.0F, 0.0F, 300);
        ChunkPrimitiveSortBridge.sortForTest(fixture, 0.0F, 0.0F, 0.0F);
        assertEquals(200, fixture.ints.get(3));
        assertEquals(300, fixture.ints.get(3 + 16));
        assertEquals(100, fixture.ints.get(3 + 32));
    }

    private static int[] vanillaReference(int[] original, int quads, int stride,
                                          float x, float y, float z) {
        float[] distance = new float[quads];
        int quadStride = stride * 4;
        for (int quad = 0; quad < quads; quad++) {
            int offset = quad * quadStride;
            float x0 = Float.intBitsToFloat(original[offset]);
            float y0 = Float.intBitsToFloat(original[offset + 1]);
            float z0 = Float.intBitsToFloat(original[offset + 2]);
            float x1 = Float.intBitsToFloat(original[offset + stride]);
            float y1 = Float.intBitsToFloat(original[offset + stride + 1]);
            float z1 = Float.intBitsToFloat(original[offset + stride + 2]);
            float x2 = Float.intBitsToFloat(original[offset + stride * 2]);
            float y2 = Float.intBitsToFloat(original[offset + stride * 2 + 1]);
            float z2 = Float.intBitsToFloat(original[offset + stride * 2 + 2]);
            float x3 = Float.intBitsToFloat(original[offset + stride * 3]);
            float y3 = Float.intBitsToFloat(original[offset + stride * 3 + 1]);
            float z3 = Float.intBitsToFloat(original[offset + stride * 3 + 2]);
            float dx = (x0 + x1 + x2 + x3) * 0.25F - x;
            float dy = (y0 + y1 + y2 + y3) * 0.25F - y;
            float dz = (z0 + z1 + z2 + z3) * 0.25F - z;
            distance[quad] = dx * dx + dy * dy + dz * dz;
        }
        Integer[] order = new Integer[quads];
        for (int i = 0; i < quads; i++) order[i] = Integer.valueOf(i);
        Arrays.sort(order, new Comparator<Integer>() {
            @Override public int compare(Integer left, Integer right) {
                return Float.compare(distance[right.intValue()], distance[left.intValue()]);
            }
        });
        int[] expected = new int[original.length];
        for (int destination = 0; destination < quads; destination++) {
            System.arraycopy(original, order[destination].intValue() * quadStride,
                expected, destination * quadStride, quadStride);
        }
        return expected;
    }

    private static final class Fixture implements ChunkBufferAccessor {
        private final int quads;
        private final int stride;
        private final IntBuffer ints;
        private final FloatBuffer floats;

        private Fixture(int quads, int stride) {
            this.quads = quads;
            this.stride = stride;
            ByteBuffer bytes = ByteBuffer.allocateDirect(quads * 4 * stride * 4)
                .order(ByteOrder.nativeOrder());
            ints = bytes.asIntBuffer();
            floats = bytes.asFloatBuffer();
        }

        private void putQuad(int quad, float x, float y, float z, int marker) {
            int offset = quad * stride * 4;
            for (int vertex = 0; vertex < 4; vertex++) {
                int base = offset + vertex * stride;
                ints.put(base, Float.floatToRawIntBits(x));
                ints.put(base + 1, Float.floatToRawIntBits(y));
                ints.put(base + 2, Float.floatToRawIntBits(z));
                for (int element = 3; element < stride; element++) {
                    ints.put(base + element, element == 3 ? marker : marker + element);
                }
            }
        }

        private int[] copyRaw() {
            int[] result = new int[ints.capacity()];
            for (int i = 0; i < result.length; i++) result[i] = ints.get(i);
            return result;
        }

        @Override public FloatBuffer ice$sortFloatBuffer() { return floats; }
        @Override public IntBuffer ice$sortIntBuffer() { return ints; }
        @Override public int ice$sortVertexCount() { return quads * 4; }
        @Override public int ice$sortVertexStrideInts() { return stride; }
    }
}
