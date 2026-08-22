package dev.rlcraft.ice.optimizer.render.visibility;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.memory.RetainedHeap;
import java.nio.FloatBuffer;

/** CPU reference implementation used to validate the GPU HZB reduction/query path. */
public final class ConservativeHzb implements AutoCloseable {
    private int[] widths;
    private int[] heights;
    private float[][] levels;
    private CacheBudget.Reservation heapReservation;

    private ConservativeHzb(int[] widths, int[] heights, float[][] levels,
                            CacheBudget.Reservation heapReservation) {
        this.widths = widths;
        this.heights = heights;
        this.levels = levels;
        this.heapReservation = heapReservation;
    }

    /** Standard 0-near/1-far depth uses max reduction for conservative testing. */
    public static ConservativeHzb buildStandardDepth(float[] depth, int width,
                                                     int height) {
        validateDimensions(depth == null ? -1 : depth.length, width, height);
        CacheBudget.Reservation reservation = RetainedHeap.reserve(null,
            heapBytesForDimensions(width, height), "conservative HZB");
        try {
            Hierarchy hierarchy = allocate(width, height);
            float[] base = hierarchy.levels[0];
            for (int index = 0; index < base.length; index++) {
                float value = depth[index];
                validateDepth(value);
                base[index] = value;
            }
            reduce(hierarchy);
            return new ConservativeHzb(hierarchy.widths, hierarchy.heights,
                hierarchy.levels, reservation);
        } catch (RuntimeException | Error failure) {
            reservation.close();
            throw failure;
        }
    }

    /**
     * Budgeted readback constructor. It copies the mapped PBO exactly once;
     * the copied base level becomes part of the retained hierarchy.
     */
    static ConservativeHzb buildStandardDepth(FloatBuffer depth, int width,
                                              int height, CacheBudget budget) {
        int pixels = checkedPixels(width, height);
        if (depth == null || depth.remaining() < pixels) {
            throw new IllegalArgumentException("depth image");
        }
        CacheBudget.Reservation reservation = RetainedHeap.reserve(budget,
            heapBytesForDimensions(width, height), "conservative HZB");
        try {
            Hierarchy hierarchy = allocate(width, height);
            float[] base = hierarchy.levels[0];
            int position = depth.position();
            for (int index = 0; index < base.length; index++) {
                float value = depth.get(position + index);
                validateDepth(value);
                base[index] = value;
            }
            reduce(hierarchy);
            return new ConservativeHzb(hierarchy.widths, hierarchy.heights,
                hierarchy.levels, reservation);
        } catch (RuntimeException | Error failure) {
            reservation.close();
            throw failure;
        }
    }

    /** Coordinates are inclusive-exclusive pixels in the base depth image. */
    public OcclusionResult test(int minX, int minY, int maxX, int maxY,
                                float candidateNearestDepth, float epsilon) {
        checkOpen();
        if (minX < 0 || minY < 0 || maxX > widths[0] || maxY > heights[0]
            || minX >= maxX || minY >= maxY || Float.isNaN(candidateNearestDepth)
            || candidateNearestDepth < 0.0F || candidateNearestDepth > 1.0F) {
            return OcclusionResult.UNKNOWN;
        }
        int extent = Math.max(maxX - minX, maxY - minY);
        int level = 0;
        while (level + 1 < levels.length && (1L << (level + 1)) <= extent) level++;
        long scale = 1L << level;
        int x0 = (int) (minX / scale);
        int y0 = (int) (minY / scale);
        int x1 = (int) ((maxX + scale - 1L) / scale);
        int y1 = (int) ((maxY + scale - 1L) / scale);
        float maximumOccluderDepth = 0.0F;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                maximumOccluderDepth = Math.max(maximumOccluderDepth,
                    levels[level][y * widths[level] + x]);
            }
        }
        float safeEpsilon = Math.max(0.0F, epsilon);
        return candidateNearestDepth > maximumOccluderDepth + safeEpsilon
            ? OcclusionResult.OCCLUDED : OcclusionResult.VISIBLE;
    }

    public int getLevelCount() {
        checkOpen();
        return levels.length;
    }

    /** Exact invariant used by live output validation after GPU readback. */
    public boolean isConservativeHierarchy() {
        if (levels == null || levels.length == 0 || widths.length != levels.length
            || heights.length != levels.length || widths[0] <= 0 || heights[0] <= 0
            || levels[0].length != widths[0] * heights[0]) return false;
        for (int level = 1; level < levels.length; level++) {
            int sourceWidth = widths[level - 1];
            int sourceHeight = heights[level - 1];
            if (widths[level] != Math.max(1, (sourceWidth + 1) >>> 1)
                || heights[level] != Math.max(1, (sourceHeight + 1) >>> 1)
                || levels[level].length != widths[level] * heights[level]) return false;
            for (int y = 0; y < heights[level]; y++) {
                for (int x = 0; x < widths[level]; x++) {
                    float expected = reducedValue(levels[level - 1], sourceWidth,
                        sourceHeight, x, y);
                    if (Float.floatToRawIntBits(expected) != Float.floatToRawIntBits(
                        levels[level][y * widths[level] + x])) return false;
                }
            }
        }
        return true;
    }

    /** Brute-force base-level oracle for sampled online validation. */
    public OcclusionResult testBaseReference(int minX, int minY, int maxX, int maxY,
                                             float candidateNearestDepth,
                                             float epsilon) {
        checkOpen();
        if (minX < 0 || minY < 0 || maxX > widths[0] || maxY > heights[0]
            || minX >= maxX || minY >= maxY || Float.isNaN(candidateNearestDepth)
            || candidateNearestDepth < 0.0F || candidateNearestDepth > 1.0F) {
            return OcclusionResult.UNKNOWN;
        }
        float maximum = 0.0F;
        for (int y = minY; y < maxY; y++) {
            for (int x = minX; x < maxX; x++) {
                maximum = Math.max(maximum, levels[0][y * widths[0] + x]);
            }
        }
        return candidateNearestDepth > maximum + Math.max(0.0F, epsilon)
            ? OcclusionResult.OCCLUDED : OcclusionResult.VISIBLE;
    }

    float[] levelZeroUnsafe() {
        checkOpen();
        return levels[0];
    }

    public boolean isClosed() { return levels == null; }

    @Override public void close() {
        if (levels == null) return;
        widths = null;
        heights = null;
        levels = null;
        CacheBudget.Reservation reservation = heapReservation;
        heapReservation = null;
        if (reservation != null) reservation.close();
    }

    public static long heapBytesForDimensions(int width, int height) {
        checkedPixels(width, height);
        int count = levelCount(width, height);
        long bytes = Math.addExact(RetainedHeap.intArray(count),
            RetainedHeap.intArray(count));
        bytes = Math.addExact(bytes, RetainedHeap.referenceArray(count));
        int levelWidth = width;
        int levelHeight = height;
        for (int level = 0; level < count; level++) {
            bytes = Math.addExact(bytes, RetainedHeap.floatArray(
                Math.multiplyExact(levelWidth, levelHeight)));
            levelWidth = Math.max(1, (levelWidth + 1) >>> 1);
            levelHeight = Math.max(1, (levelHeight + 1) >>> 1);
        }
        return bytes;
    }

    private static Hierarchy allocate(int width, int height) {
        int count = levelCount(width, height);
        int[] widths = new int[count];
        int[] heights = new int[count];
        float[][] levels = new float[count][];
        int levelWidth = width;
        int levelHeight = height;
        for (int level = 0; level < count; level++) {
            widths[level] = levelWidth;
            heights[level] = levelHeight;
            levels[level] = new float[Math.multiplyExact(levelWidth, levelHeight)];
            levelWidth = Math.max(1, (levelWidth + 1) >>> 1);
            levelHeight = Math.max(1, (levelHeight + 1) >>> 1);
        }
        return new Hierarchy(widths, heights, levels);
    }

    private static void reduce(Hierarchy hierarchy) {
        for (int level = 1; level < hierarchy.levels.length; level++) {
            float[] source = hierarchy.levels[level - 1];
            int sourceWidth = hierarchy.widths[level - 1];
            int sourceHeight = hierarchy.heights[level - 1];
            float[] target = hierarchy.levels[level];
            int targetWidth = hierarchy.widths[level];
            int targetHeight = hierarchy.heights[level];
            for (int y = 0; y < targetHeight; y++) {
                for (int x = 0; x < targetWidth; x++) {
                    target[y * targetWidth + x] = reducedValue(source,
                        sourceWidth, sourceHeight, x, y);
                }
            }
        }
    }

    private static float reducedValue(float[] source, int sourceWidth,
                                      int sourceHeight, int x, int y) {
        float maximum = 0.0F;
        for (int dy = 0; dy < 2; dy++) {
            int sourceY = y * 2 + dy;
            if (sourceY >= sourceHeight) continue;
            for (int dx = 0; dx < 2; dx++) {
                int sourceX = x * 2 + dx;
                if (sourceX < sourceWidth) {
                    maximum = Math.max(maximum,
                        source[sourceY * sourceWidth + sourceX]);
                }
            }
        }
        return maximum;
    }

    private static int levelCount(int width, int height) {
        int count = 1;
        while (width > 1 || height > 1) {
            width = Math.max(1, (width + 1) >>> 1);
            height = Math.max(1, (height + 1) >>> 1);
            count++;
        }
        return count;
    }

    private static void validateDimensions(int length, int width, int height) {
        if (length < 0 || checkedPixels(width, height) != length) {
            throw new IllegalArgumentException("depth image");
        }
    }

    private static int checkedPixels(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException(
            "depth image");
        try {
            return Math.multiplyExact(width, height);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("depth image", overflow);
        }
    }

    private static void validateDepth(float value) {
        if (Float.isNaN(value) || value < 0.0F || value > 1.0F) {
            throw new IllegalArgumentException("invalid depth value");
        }
    }

    private void checkOpen() {
        if (levels == null) throw new IllegalStateException(
            "conservative HZB is closed");
    }

    private static final class Hierarchy {
        private final int[] widths;
        private final int[] heights;
        private final float[][] levels;

        private Hierarchy(int[] widths, int[] heights, float[][] levels) {
            this.widths = widths;
            this.heights = heights;
            this.levels = levels;
        }
    }
}
