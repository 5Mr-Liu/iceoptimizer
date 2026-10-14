package dev.rlcraft.ice.optimizer.compat.chunk;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Allocation-stable equivalent of BufferBuilder's boxed stable translucent-quad sort. */
public final class ChunkPrimitiveSortBridge {
    private static final String MODULE = "vanilla-chunk-sort";
    private static final int MAX_QUADS = 1 << 20;
    private static final int RADIX_MIN_QUADS = 512;
    private static final ThreadLocal<Scratch> SCRATCH = new ThreadLocal<Scratch>() {
        @Override protected Scratch initialValue() { return new Scratch(); }
    };
    private static final AtomicLong SORTED_QUADS = new AtomicLong();
    private static final AtomicBoolean ACTIVATED = new AtomicBoolean();

    private ChunkPrimitiveSortBridge() {
    }

    public static boolean trySort(ChunkBufferAccessor accessor, float x, float y, float z) {
        if (!OptimizerBridge.isEnabled(MODULE) || accessor == null) return false;
        try {
            int quads = validate(accessor);
            if (quads < 0) return false;
            sortValidated(accessor, x, y, z, quads, SCRATCH.get());
            SORTED_QUADS.addAndGet(quads);
            if (!ACTIVATED.get() && ACTIVATED.compareAndSet(false, true)) {
                OptimizerBridge.activate(MODULE,
                    "透明区块四边形已使用逐线程原始类型稳定排序");
            }
            OptimizerBridge.success(MODULE);
            return true;
        } catch (Throwable error) {
            OptimizerBridge.failure(MODULE, error);
            return false;
        }
    }

    static void sortForTest(ChunkBufferAccessor accessor, float x, float y, float z) {
        int quads = validate(accessor);
        if (quads < 0) throw new IllegalArgumentException("invalid BufferBuilder state");
        sortValidated(accessor, x, y, z, quads, new Scratch());
    }

    static long sortedQuads() {
        return SORTED_QUADS.get();
    }

    private static int validate(ChunkBufferAccessor accessor) {
        int vertexCount = accessor.ice$sortVertexCount();
        int stride = accessor.ice$sortVertexStrideInts();
        FloatBuffer floats = accessor.ice$sortFloatBuffer();
        IntBuffer ints = accessor.ice$sortIntBuffer();
        if (vertexCount < 0 || (vertexCount & 3) != 0 || stride < 3
            || floats == null || ints == null) return -1;
        int quads = vertexCount >>> 2;
        if (quads > MAX_QUADS) return -1;
        long required = (long) vertexCount * (long) stride;
        if (required > Integer.MAX_VALUE || required > floats.capacity()
            || required > ints.capacity()) return -1;
        return quads;
    }

    private static void sortValidated(ChunkBufferAccessor accessor, float x, float y, float z,
                                      int quads, Scratch scratch) {
        IntBuffer ints = accessor.ice$sortIntBuffer();
        int stride = accessor.ice$sortVertexStrideInts();
        int requiredInts = accessor.ice$sortVertexCount() * stride;
        if (quads <= 1) {
            ints.limit(ints.capacity());
            ints.position(requiredInts);
            return;
        }

        FloatBuffer floats = accessor.ice$sortFloatBuffer();
        scratch.ensureQuads(quads);
        int quadStride = stride * 4;
        boolean ordered = true;
        if (quads < RADIX_MIN_QUADS) {
            int quad = 0;
            for (; quad < quads; quad++) {
                scratch.smallDistances[quad] = distanceSq(floats, x, y, z, stride, quad * quadStride);
                scratch.order[quad] = quad;
                if (quad > 0 && Float.compare(scratch.smallDistances[quad - 1],
                    scratch.smallDistances[quad]) < 0) { ordered = false; quad++; break; }
            }
            if (ordered) {
                ints.limit(ints.capacity());
                ints.position(requiredInts);
                return;
            }
            for (; quad < quads; quad++) {
                scratch.smallDistances[quad] = distanceSq(floats, x, y, z, stride, quad * quadStride);
                scratch.order[quad] = quad;
            }
            stableSort(scratch.smallDistances, scratch.order, scratch.merge, quads);
        } else {
            int quad = 0;
            for (; quad < quads; quad++) {
                int key = descendingKey(distanceSq(floats, x, y, z, stride, quad * quadStride));
                scratch.distanceKeys[quad] = key;
                scratch.order[quad] = quad;
                if (quad > 0 && Integer.compareUnsigned(
                    scratch.distanceKeys[quad - 1], key) > 0) { ordered = false; quad++; break; }
            }
            // Ordered input needs neither histogram construction nor payload
            // writes. Once the first inversion is found, stop testing order
            // and retain the original tight histogram loop for the remainder.
            if (ordered) {
                ints.limit(ints.capacity());
                ints.position(requiredInts);
                return;
            }
            Arrays.fill(scratch.counts, 0);
            for (int prefix = 0; prefix < quad; prefix++) {
                int key = scratch.distanceKeys[prefix];
                scratch.counts[key & 255]++;
                scratch.counts[256 + ((key >>> 8) & 255)]++;
                scratch.counts[512 + ((key >>> 16) & 255)]++;
                scratch.counts[768 + (key >>> 24)]++;
            }
            for (; quad < quads; quad++) {
                int key = descendingKey(
                    distanceSq(floats, x, y, z, stride, quad * quadStride));
                scratch.distanceKeys[quad] = key;
                // All four histograms depend only on keys, not on the current
                // permutation. Build them once while those keys are in registers.
                scratch.counts[key & 255]++;
                scratch.counts[256 + ((key >>> 8) & 255)]++;
                scratch.counts[512 + ((key >>> 16) & 255)]++;
                scratch.counts[768 + (key >>> 24)]++;
                scratch.order[quad] = quad;
            }
            radixSort(scratch.distanceKeys, scratch.order, scratch.merge,
                scratch.counts, quads);
        }

        scratch.ensureRaw(requiredInts);
        ints.position(0);
        ints.limit(requiredInts);
        ints.get(scratch.raw, 0, requiredInts);
        try {
            for (int destination = 0; destination < quads; destination++) {
                int source = scratch.order[destination];
                ints.position(destination * quadStride);
                ints.put(scratch.raw, source * quadStride, quadStride);
            }
        } catch (Throwable error) {
            Throwable failure = error;
            try {
                ints.position(0);
                ints.limit(requiredInts);
                ints.put(scratch.raw, 0, requiredInts);
                ints.limit(ints.capacity());
                ints.position(requiredInts);
            } catch (Throwable restoreFailure) {
                failure = appendFailure(failure, restoreFailure);
            }
            rethrow(failure);
        }
        ints.limit(ints.capacity());
        ints.position(requiredInts);
    }

    /** Unsigned ascending key with exactly Float.compare's descending order. */
    static int descendingKey(float value) {
        // Canonical NaNs preserve the original comparator's equal-NaN ties.
        int bits = Float.floatToIntBits(value);
        return bits < 0 ? bits : ~(bits ^ Integer.MIN_VALUE);
    }

    private static void stableSort(float[] distances, int[] order, int[] merge, int size) {
        int[] source = order;
        int[] target = merge;
        for (int width = 1; width < size; width <<= 1) {
            for (int start = 0; start < size; start += width << 1) {
                int middle = Math.min(size, start + width);
                int end = Math.min(size, start + (width << 1));
                int left = start;
                int right = middle;
                int out = start;
                while (left < middle && right < end) {
                    int leftIndex = source[left];
                    int rightIndex = source[right];
                    if (Float.compare(distances[rightIndex], distances[leftIndex]) <= 0) {
                        target[out++] = leftIndex;
                        left++;
                    } else {
                        target[out++] = rightIndex;
                        right++;
                    }
                }
                while (left < middle) target[out++] = source[left++];
                while (right < end) target[out++] = source[right++];
            }
            int[] swap = source;
            source = target;
            target = swap;
            if (width > (Integer.MAX_VALUE >>> 1)) break;
        }
        if (source != order) System.arraycopy(source, 0, order, 0, size);
    }

    /** Stable LSD distribution of indirect 32-bit keys; no boxed comparator. */
    private static void radixSort(int[] keys, int[] order, int[] merge,
                                  int[] counts, int size) {
        int[] source = order;
        int[] target = merge;
        for (int shift = 0; shift < 32; shift += 8) {
            int histogram = shift << 5;
            int position = 0;
            boolean uniform = false;
            for (int digit = 0; digit < 256; digit++) {
                int at = histogram + digit;
                int count = counts[at];
                if (count == size) {
                    uniform = true;
                    break;
                }
                counts[at] = position;
                position += count;
            }
            // A constant digit leaves the permutation unchanged.
            if (uniform) continue;
            for (int i = 0; i < size; i++) {
                int index = source[i];
                target[counts[histogram + ((keys[index] >>> shift) & 255)]++] = index;
            }
            int[] swap = source;
            source = target;
            target = swap;
        }
        if (source != order) System.arraycopy(source, 0, order, 0, size);
    }

    private static float distanceSq(FloatBuffer buffer, float x, float y, float z,
                                    int integerSize, int offset) {
        float f = buffer.get(offset + integerSize * 0);
        float f1 = buffer.get(offset + integerSize * 0 + 1);
        float f2 = buffer.get(offset + integerSize * 0 + 2);
        float f3 = buffer.get(offset + integerSize);
        float f4 = buffer.get(offset + integerSize + 1);
        float f5 = buffer.get(offset + integerSize + 2);
        float f6 = buffer.get(offset + integerSize * 2);
        float f7 = buffer.get(offset + integerSize * 2 + 1);
        float f8 = buffer.get(offset + integerSize * 2 + 2);
        float f9 = buffer.get(offset + integerSize * 3);
        float f10 = buffer.get(offset + integerSize * 3 + 1);
        float f11 = buffer.get(offset + integerSize * 3 + 2);
        float dx = (f + f3 + f6 + f9) * 0.25F - x;
        float dy = (f1 + f4 + f7 + f10) * 0.25F - y;
        float dz = (f2 + f5 + f8 + f11) * 0.25F - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static int grownLength(int current, int required) {
        int result = Math.max(16, current);
        while (result < required) {
            int next = result << 1;
            if (next <= result) return required;
            result = next;
        }
        return result;
    }

    private static Throwable appendFailure(Throwable first, Throwable next) {
        if (first == null) return next;
        Throwable nextFatal = FatalErrors.findFatal(next);
        if (nextFatal != null && FatalErrors.findFatal(first) == null) {
            if (nextFatal != first) nextFatal.addSuppressed(first);
            return nextFatal;
        }
        if (next != null && first != next) first.addSuppressed(next);
        return first;
    }

    private static void rethrow(Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("chunk primitive sort failed", failure);
    }

    private static final class Scratch {
        private final float[] smallDistances = new float[RADIX_MIN_QUADS];
        private int[] distanceKeys = new int[0];
        private int[] order = new int[0];
        private int[] merge = new int[0];
        private final int[] counts = new int[4 * 256];
        private int[] raw = new int[0];

        private void ensureQuads(int required) {
            if (distanceKeys.length >= required) return;
            int length = grownLength(distanceKeys.length, required);
            distanceKeys = new int[length];
            order = new int[length];
            merge = new int[length];
        }

        private void ensureRaw(int required) {
            if (raw.length < required) raw = new int[grownLength(raw.length, required)];
        }
    }
}
