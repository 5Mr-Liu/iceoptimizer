package dev.rlcraft.ice.optimizer.render.terrain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Stable opaque face buckets and conservative whole-bucket backface bounds. */
public final class TerrainFaceRanges {
    private static final int EXTRA_DRAW_VERTEX_COST = 2048;
    private static final int[] SPAN_PLANS = spanPlans();
    private final int[] first = new int[TerrainQuadClassifier.GROUPS];
    private final int[] count = new int[TerrainQuadClassifier.GROUPS];
    private final float[] minPlane = new float[TerrainQuadClassifier.GROUPS];
    private final float[] maxPlane = new float[TerrainQuadClassifier.GROUPS];
    // One conservative plan for each combination of known face directions.
    // UNKNOWN is always retained; tables are built only on mesh upload.
    private final byte[] plannedMasks = new byte[64];

    private TerrainFaceRanges() {
        Arrays.fill(minPlane, Float.POSITIVE_INFINITY);
        Arrays.fill(maxPlane, Float.NEGATIVE_INFINITY);
    }

    /** One cached face byte per quad follows the output vertex storage. */
    static int storageBytes(int bytes) { return bytes + bytes / 112; }

    /** Input/output must be distinct storage. Both preserve complete vertex bytes. */
    static TerrainFaceRanges group(ByteBuffer input, ByteBuffer output, int bytes) {
        if (input == null || output == null || input == output || bytes <= 0
            || bytes % 112 != 0 || input.limit() < bytes
            || output.capacity() < storageBytes(bytes)) {
            throw new IllegalArgumentException("terrain face grouping");
        }
        output.clear();
        output.limit(storageBytes(bytes));
        TerrainFaceRanges result = new TerrainFaceRanges();
        for (int offset = 0; offset < bytes; offset += 112) {
            int face = TerrainQuadClassifier.classifyKnownRange(input, offset, 28);
            output.put(bytes + offset / 112, (byte) face);
            result.count[face] += 4;
            if (face != TerrainQuadClassifier.UNKNOWN) {
                int component = face <= TerrainQuadClassifier.UP ? 4
                    : face <= TerrainQuadClassifier.SOUTH ? 8 : 0;
                float plane = input.getFloat(offset + component);
                result.minPlane[face] = Math.min(result.minPlane[face], plane);
                result.maxPlane[face] = Math.max(result.maxPlane[face], plane);
            }
        }
        int cursor = 0;
        for (int face = 0; face < TerrainQuadClassifier.GROUPS; face++) {
            result.first[face] = cursor;
            cursor += result.count[face];
        }
        result.planMasks();
        int[] next = result.first.clone();
        ByteBuffer source = input.duplicate().order(ByteOrder.nativeOrder());
        for (int offset = 0; offset < bytes; offset += 112) {
            int face = output.get(bytes + offset / 112);
            source.limit(offset + 112);
            source.position(offset);
            output.position(next[face] * 28);
            output.put(source);
            next[face] += 4;
        }
        output.position(0);
        output.limit(bytes);
        return result;
    }

    public int first(int face) { return first[face]; }
    public int count(int face) { return count[face]; }

    /** Keep cheap interior gaps while omitting profitable gaps and free end spans. */
    public int submissionMask(double cameraX, double cameraY, double cameraZ) {
        int mask = 1;
        for (int face = 1; face < TerrainQuadClassifier.GROUPS; face++) {
            if (visible(face, cameraX, cameraY, cameraZ)) mask |= 1 << face;
        }
        return plannedMasks[mask >>> 1] & 127;
    }

    /** Four ordered intervals fit in 24 bits; the high byte is the interval
     * count. The topology table is shared by every mesh, not copied per mesh. */
    public int submissionPlan(double cameraX, double cameraY, double cameraZ) {
        return SPAN_PLANS[submissionMask(cameraX, cameraY, cameraZ)];
    }

    public int spanFirst(int plan) { return first[plan & 7]; }
    public int spanCount(int plan) {
        int last = (plan >>> 3) & 7;
        return first[last] + count[last] - first[plan & 7];
    }

    static int[] spanPlans() {
        int[] plans = new int[128];
        for (int mask = 0; mask < plans.length; mask++) {
            int plan = 0, spans = 0;
            for (int face = 0; face < 7;) {
                if ((mask & (1 << face)) == 0) { face++; continue; }
                int first = face++;
                while (face < 7 && (mask & (1 << face)) != 0) face++;
                plan |= (first | (face - 1) << 3) << (spans++ * 6);
            }
            plans[mask] = plan | spans << 24;
        }
        return plans;
    }

    private void planMasks() {
        for (int known = 0; known < 64; known++) {
            plannedMasks[known] = (byte) planMask(count, known << 1 | 1);
        }
    }

    /** Original cost model, minimized over every conservative span merge.
     * Prefix/suffix omission is free. An interior gap costs one extra draw;
     * bridge that gap only when its vertices cost less than the extra draw.
     * Decisions are independent because all face spans remain in order. */
    static int planMask(int[] counts, int required) {
        int result = required;
        boolean retained = false;
        int gapVertices = 0, gapBits = 0;
        for (int face = 0; face < TerrainQuadClassifier.GROUPS; face++) {
            int bit = 1 << face;
            if (counts[face] == 0) { result |= bit; continue; }
            if ((required & bit) != 0) {
                if (retained && gapVertices < EXTRA_DRAW_VERTEX_COST) result |= gapBits;
                retained = true;
                gapVertices = gapBits = 0;
            } else {
                gapVertices += counts[face];
                gapBits |= bit;
            }
        }
        return result;
    }

    /** Camera is expressed in the same region-local space as the mesh. */
    public boolean visible(int face, double cameraX, double cameraY, double cameraZ) {
        if (count[face] == 0) return false;
        // A small outward margin protects floating-point boundary cases. It
        // can only retain extra geometry; it never relaxes an occlusion test.
        final double margin = 0.001D;
        switch (face) {
            case TerrainQuadClassifier.DOWN: return !(cameraY > maxPlane[face] + margin);
            case TerrainQuadClassifier.UP: return !(cameraY < minPlane[face] - margin);
            case TerrainQuadClassifier.NORTH: return !(cameraZ > maxPlane[face] + margin);
            case TerrainQuadClassifier.SOUTH: return !(cameraZ < minPlane[face] - margin);
            case TerrainQuadClassifier.WEST: return !(cameraX > maxPlane[face] + margin);
            case TerrainQuadClassifier.EAST: return !(cameraX < minPlane[face] - margin);
            default: return true;
        }
    }
}
