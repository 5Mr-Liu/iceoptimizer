package dev.rlcraft.ice.optimizer.compat.chunk;

import java.lang.reflect.*;
import java.nio.*;
import java.util.*;

/** Offline full distance/sort/payload-copy benchmark, with a reused production workspace. */
public final class ChunkPrimitiveSortProbe {
    private static volatile int sink;
    public static void main(String[] args) throws Exception {
        Class<?> scratchType = Class.forName(ChunkPrimitiveSortBridge.class.getName() + "$Scratch");
        Constructor<?> constructor = scratchType.getDeclaredConstructor(); constructor.setAccessible(true);
        Method sort = ChunkPrimitiveSortBridge.class.getDeclaredMethod("sortValidated",
            ChunkBufferAccessor.class, float.class, float.class, float.class, int.class, scratchType);
        sort.setAccessible(true);
        for (String pattern : new String[]{"random", "ties", "ordered", "reverse"}) {
            for (int quads : new int[]{32, 128, 256, 512, 1024, 4096, 16384}) {
                Fixture fixture = new Fixture(quads, pattern);
                Object scratch = constructor.newInstance();
                int repeats = Math.max(12, 131072 / quads);
                long[] elapsed = new long[9];
                for (int round = -5; round < elapsed.length; round++) {
                    long total = 0;
                    for (int n = 0; n < repeats; n++) {
                        fixture.reset();
                        long begin = System.nanoTime();
                        sort.invoke(null, fixture, 0.25F, -1.5F, 2.0F, quads, scratch);
                        total += System.nanoTime() - begin;
                        sink ^= fixture.ints.get(3);
                    }
                    if (round >= 0) elapsed[round] = total;
                }
                Arrays.sort(elapsed);
                System.out.printf(Locale.ROOT, "%s,%d,%.1f%n", pattern, quads,
                    elapsed[elapsed.length / 2] / (double) repeats);
            }
        }
    }
    private static final class Fixture implements ChunkBufferAccessor {
        final int quads;
        final IntBuffer ints;
        final FloatBuffer floats;
        final int[] original;
        Fixture(int quads, String pattern) {
            this.quads = quads;
            ByteBuffer bytes = ByteBuffer.allocateDirect(quads * 28 * 4).order(ByteOrder.nativeOrder());
            ints = bytes.asIntBuffer(); floats = bytes.asFloatBuffer(); original = new int[ints.capacity()];
            Random random = new Random(0x19A160L + quads);
            for (int q = 0; q < quads; q++) {
                float x = pattern.equals("ties") ? (q % 8) * 0.5F
                    : pattern.equals("ordered") ? 1.0F + (quads - q) * 0.03F
                    : pattern.equals("reverse") ? q * 0.03F : random.nextFloat() * 128F - 64F;
                float y = pattern.equals("random") ? random.nextFloat() * 64F - 32F : 0F;
                float z = pattern.equals("random") ? random.nextFloat() * 128F - 64F : 0F;
                for (int v = 0; v < 4; v++) {
                    int at = q * 28 + v * 7;
                    original[at] = Float.floatToRawIntBits(x);
                    original[at+1] = Float.floatToRawIntBits(y);
                    original[at+2] = Float.floatToRawIntBits(z);
                    for (int e = 3; e < 7; e++) original[at+e] = q ^ (e << 24);
                }
            }
        }
        void reset() { ints.clear(); ints.put(original); }
        public FloatBuffer ice$sortFloatBuffer() { return floats; }
        public IntBuffer ice$sortIntBuffer() { return ints; }
        public int ice$sortVertexCount() { return quads * 4; }
        public int ice$sortVertexStrideInts() { return 7; }
    }
}
