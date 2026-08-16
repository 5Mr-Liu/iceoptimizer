package dev.rlcraft.ice.optimizer.compat.optifine;

import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.Arrays;
import java.util.List;
import net.minecraft.util.math.BlockPos;

/**
 * Publishes OptiFine's 50 ms dynamic-light update as an immutable primitive
 * snapshot. Chunk workers no longer contend on DynamicLightsMap or call five
 * virtual accessors for every ambient-occlusion sample.
 */
public final class OptifineDynamicLightsBridge {
    private static final String MODULE = "optifine-dynamic-lights";
    private static final double MAX_DISTANCE = 7.5D;
    private static final double MAX_DISTANCE_SQUARED = MAX_DISTANCE * MAX_DISTANCE;
    private static final int INDEX_THRESHOLD = 96;
    private static final Snapshot UNAVAILABLE = new Snapshot(false, new double[0], new double[0],
        new double[0], new byte[0], new boolean[0], null, null, null);
    private static final Snapshot EMPTY = new Snapshot(true, new double[0], new double[0],
        new double[0], new byte[0], new boolean[0], null, null, null);
    private static volatile Snapshot snapshot = UNAVAILABLE;
    private static volatile boolean activated;

    private OptifineDynamicLightsBridge() {
    }

    /** Called after OptiFine mutates or updates its light map. */
    public static void refresh(Object dynamicLightsMap) {
        if (!OptimizerBridge.isEnabled(MODULE)) {
            snapshot = UNAVAILABLE;
            return;
        }
        if (!(dynamicLightsMap instanceof DynamicLightsMapAccessor)) {
            snapshot = UNAVAILABLE;
            return;
        }
        try {
            Snapshot replacement;
            synchronized (dynamicLightsMap) {
                List<?> values = ((DynamicLightsMapAccessor) dynamicLightsMap).ice$valueList();
                replacement = copy(values);
            }
            snapshot = replacement;
            if (!activated) {
                activated = true;
                OptimizerBridge.activate(MODULE,
                    "OptiFine 动态光源已使用不可变快照；大量光源自动启用空间索引");
            }
            OptimizerBridge.success(MODULE);
        } catch (Throwable error) {
            snapshot = UNAVAILABLE;
            OptimizerBridge.failure(MODULE, error);
        }
    }

    /** Returns a negative value when the untouched OptiFine method must run. */
    public static double getLightLevel(BlockPos pos, boolean clearWater) {
        if (!OptimizerBridge.isEnabled(MODULE) || pos == null) return -1.0D;
        Snapshot current = snapshot;
        if (!current.available) return -1.0D;
        return current.lightAt(pos.getX(), pos.getY(), pos.getZ(), clearWater);
    }

    static Snapshot copy(List<?> values) {
        if (values == null || values.isEmpty()) return EMPTY;
        int size = values.size();
        double[] x = new double[size];
        double[] y = new double[size];
        double[] z = new double[size];
        byte[] light = new byte[size];
        boolean[] underwater = new boolean[size];
        int retained = 0;
        for (int i = 0; i < size; i++) {
            Object value = values.get(i);
            if (!(value instanceof DynamicLightAccessor)) {
                throw new IllegalStateException("OptiFine DynamicLight 访问接口未安装："
                    + (value == null ? "null" : value.getClass().getName()));
            }
            DynamicLightAccessor accessor = (DynamicLightAccessor) value;
            int level = accessor.ice$lastLightLevel();
            if (level <= 0) continue;
            x[retained] = accessor.ice$lastPosX();
            y[retained] = accessor.ice$lastPosY();
            z[retained] = accessor.ice$lastPosZ();
            light[retained] = (byte) Math.max(0, Math.min(15, level));
            underwater[retained] = accessor.ice$isUnderwater();
            retained++;
        }
        if (retained == 0) return EMPTY;
        if (retained != size) {
            x = Arrays.copyOf(x, retained);
            y = Arrays.copyOf(y, retained);
            z = Arrays.copyOf(z, retained);
            light = Arrays.copyOf(light, retained);
            underwater = Arrays.copyOf(underwater, retained);
        }
        if (retained < INDEX_THRESHOLD) {
            return new Snapshot(true, x, y, z, light, underwater, null, null, null);
        }
        SpatialIndex index = SpatialIndex.build(x, y, z);
        return new Snapshot(true, x, y, z, light, underwater,
            index.cellKeys, index.cellOffsets, index.order);
    }

    static final class Snapshot {
        private final boolean available;
        private final double[] x;
        private final double[] y;
        private final double[] z;
        private final byte[] light;
        private final boolean[] underwater;
        private final long[] cellKeys;
        private final int[] cellOffsets;
        private final int[] order;

        private Snapshot(boolean available, double[] x, double[] y, double[] z, byte[] light,
                         boolean[] underwater, long[] cellKeys, int[] cellOffsets, int[] order) {
            this.available = available;
            this.x = x;
            this.y = y;
            this.z = z;
            this.light = light;
            this.underwater = underwater;
            this.cellKeys = cellKeys;
            this.cellOffsets = cellOffsets;
            this.order = order;
        }

        private double lightAt(int blockX, int blockY, int blockZ, boolean clearWater) {
            if (cellKeys == null) return lightFromAll(blockX, blockY, blockZ, clearWater);
            double maximum = 0.0D;
            int cellX = blockX >> 3;
            int cellY = blockY >> 3;
            int cellZ = blockZ >> 3;
            for (int offsetX = -1; offsetX <= 1; offsetX++) {
                for (int offsetY = -1; offsetY <= 1; offsetY++) {
                    for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                        long key = cellKey(cellX + offsetX, cellY + offsetY, cellZ + offsetZ);
                        int cell = Arrays.binarySearch(cellKeys, key);
                        if (cell < 0) continue;
                        int end = cellOffsets[cell + 1];
                        for (int at = cellOffsets[cell]; at < end; at++) {
                            maximum = sample(order[at], blockX, blockY, blockZ, clearWater, maximum);
                        }
                    }
                }
            }
            return clamp(maximum);
        }

        private double lightFromAll(int blockX, int blockY, int blockZ, boolean clearWater) {
            double maximum = 0.0D;
            for (int i = 0; i < light.length; i++) {
                maximum = sample(i, blockX, blockY, blockZ, clearWater, maximum);
            }
            return clamp(maximum);
        }

        private double sample(int index, int blockX, int blockY, int blockZ,
                              boolean clearWater, double maximum) {
            int level = light[index] & 0xFF;
            double deltaX = blockX - x[index];
            double deltaY = blockY - y[index];
            double deltaZ = blockZ - z[index];
            double distanceSquared = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
            if (underwater[index] && !clearWater) {
                level = Math.max(0, Math.min(15, level - 2));
                distanceSquared *= 2.0D;
            }
            if (distanceSquared > MAX_DISTANCE_SQUARED) return maximum;
            double attenuation = 1.0D - Math.sqrt(distanceSquared) / MAX_DISTANCE;
            double candidate = attenuation * level;
            return candidate > maximum ? candidate : maximum;
        }
    }

    private static final class SpatialIndex {
        private final long[] cellKeys;
        private final int[] cellOffsets;
        private final int[] order;

        private SpatialIndex(long[] cellKeys, int[] cellOffsets, int[] order) {
            this.cellKeys = cellKeys;
            this.cellOffsets = cellOffsets;
            this.order = order;
        }

        private static SpatialIndex build(double[] x, double[] y, double[] z) {
            int size = x.length;
            long[] keysByEntry = new long[size];
            int[] order = new int[size];
            for (int i = 0; i < size; i++) {
                keysByEntry[i] = cellKey(floorCell(x[i]), floorCell(y[i]), floorCell(z[i]));
                order[i] = i;
            }
            sort(keysByEntry, order, 0, size - 1);
            int cells = 1;
            for (int i = 1; i < size; i++) if (keysByEntry[i] != keysByEntry[i - 1]) cells++;
            long[] cellKeys = new long[cells];
            int[] cellOffsets = new int[cells + 1];
            int cell = 0;
            cellKeys[0] = keysByEntry[0];
            cellOffsets[0] = 0;
            for (int i = 1; i < size; i++) {
                if (keysByEntry[i] == keysByEntry[i - 1]) continue;
                cellOffsets[++cell] = i;
                cellKeys[cell] = keysByEntry[i];
            }
            cellOffsets[cells] = size;
            return new SpatialIndex(cellKeys, cellOffsets, order);
        }
    }

    private static int floorCell(double coordinate) {
        return (int) Math.floor(coordinate / 8.0D);
    }

    private static long cellKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38
            | ((long) z & 0x3FFFFFFL) << 12
            | (long) y & 0xFFFL;
    }

    private static double clamp(double value) {
        if (value < 0.0D) return 0.0D;
        return value > 15.0D ? 15.0D : value;
    }

    private static void sort(long[] keys, int[] order, int low, int high) {
        int left = low;
        int right = high;
        long pivot = keys[(low + high) >>> 1];
        while (left <= right) {
            while (keys[left] < pivot) left++;
            while (keys[right] > pivot) right--;
            if (left <= right) {
                long key = keys[left];
                keys[left] = keys[right];
                keys[right] = key;
                int value = order[left];
                order[left] = order[right];
                order[right] = value;
                left++;
                right--;
            }
        }
        if (low < right) sort(keys, order, low, right);
        if (left < high) sort(keys, order, left, high);
    }
}
