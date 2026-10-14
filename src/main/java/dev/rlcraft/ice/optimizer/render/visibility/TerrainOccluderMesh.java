package dev.rlcraft.ice.optimizer.render.visibility;

import dev.rlcraft.ice.optimizer.render.terrain.TerrainQuadClassifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;


/** Cold upload snapshot: exact source bounds and a bounded subset of actual opaque faces. */
final class TerrainOccluderMesh {
    private static final int MAX_QUADS = 512;
    private static final int MAX_SOURCE_BYTES = 16 * 1024 * 1024;
    private static final float MODEL_SCALE = 1.000001F;
    private static final float MODEL_TRANSLATION = 8.0F * MODEL_SCALE - 8.0F;
    private static final ThreadLocal<Workspace> WORK = new ThreadLocal<Workspace>() {
        @Override protected Workspace initialValue() { return new Workspace(); }
    };
    final float[] quads;
    final byte[] faces;
    final short[] priority;
    final double minX, minY, minZ, maxX, maxY, maxZ;

    interface Opacity {
        boolean opaque(float minU, float minV, float maxU, float maxV);
    }

    private TerrainOccluderMesh(float[] quads, double[] bounds) {
        this(quads, bounds, classifyFaces(quads));
    }

    private TerrainOccluderMesh(float[] quads, double[] bounds, byte[] faces) {
        this.quads = quads;
        this.faces = faces;
        this.priority = new short[faces.length];
        Workspace workspace = WORK.get();
        for (int i = 0; i < faces.length; i++) {
            int offset = i * 12;
            double a = 0D, b = 0D;
            for (int axis = 0; axis < 3; axis++) {
                a += Math.abs((double) quads[offset + 3 + axis] - quads[offset + axis]);
                b += Math.abs((double) quads[offset + 9 + axis] - quads[offset + axis]);
            }
            workspace.coverage[i] = a * b;
            workspace.order[i] = i;
        }
        workspace.sortCoverage(faces.length);
        for (int i = 0; i < priority.length; i++) priority[i] = (short) workspace.order[i];
        minX = bounds[0]; minY = bounds[1]; minZ = bounds[2];
        maxX = bounds[3]; maxY = bounds[4]; maxZ = bounds[5];
    }

    private static byte[] classifyFaces(float[] quads) {
        byte[] faces = new byte[quads.length / 12];
        float[] positions = WORK.get().positions;
        for (int i = 0; i < faces.length; i++) {
            System.arraycopy(quads, i * 12, positions, 0, 12);
            faces[i] = (byte) TerrainQuadClassifier.classify(positions);
        }
        return faces;
    }

    /** Omitting an occluder only preserves extra draws; it never hides a target. */
    boolean facesCamera(int index, double x, double y, double z) {
        int offset = index * 12;
        switch (faces[index]) {
            case TerrainQuadClassifier.DOWN: return y < quads[offset + 1];
            case TerrainQuadClassifier.UP: return y > quads[offset + 1];
            case TerrainQuadClassifier.NORTH: return z < quads[offset + 2];
            case TerrainQuadClassifier.SOUTH: return z > quads[offset + 2];
            case TerrainQuadClassifier.WEST: return x < quads[offset];
            case TerrainQuadClassifier.EAST: return x > quads[offset];
            default: return true;
        }
    }

    static TerrainOccluderMesh capture(ByteBuffer source, int stride, int vertexCount,
                                      Opacity opacity) {
        return capture(source, stride, vertexCount, opacity, MODEL_SCALE, MODEL_TRANSLATION);
    }

    static TerrainOccluderMesh capture(ByteBuffer source, int stride, int vertexCount,
                                      Opacity opacity, float scale, float translation) {
        if (source == null || opacity == null || stride < 28 || source.position() != 0
            || source.limit() > MAX_SOURCE_BYTES || vertexCount <= 0 || (vertexCount & 3) != 0
            || (long) stride * vertexCount != source.limit()) return null;
        return capture(source.duplicate().order(ByteOrder.nativeOrder()), stride,
            vertexCount, opacity, scale, translation, WORK.get());
    }

    private static TerrainOccluderMesh capture(ByteBuffer data, int stride, int vertexCount,
                                               Opacity opacity, float scale, float translation,
                                               Workspace work) {
        float[] bounds = work.bounds;
        for (int axis = 0; axis < 3; axis++) {
            bounds[axis] = Float.POSITIVE_INFINITY;
            bounds[axis + 3] = Float.NEGATIVE_INFINITY;
        }
        float[] positions = work.positions;
        int count = 0;
        for (int v = 0; v < vertexCount; v += 4) {
            int base = v * stride;
            for (int i = 0; i < 4; i++) {
                for (int axis = 0; axis < 3; axis++) {
                    float coordinate = data.getFloat(base + i * stride + axis * 4);
                    if (!Float.isFinite(coordinate) || Math.abs(coordinate) > 65536.0F) return null;
                    positions[i * 3 + axis] = coordinate;
                    if (coordinate < bounds[axis]) bounds[axis] = coordinate;
                    if (coordinate > bounds[axis + 3]) bounds[axis + 3] = coordinate;
                }
            }
            if (count >= MAX_QUADS) continue;
            int face = TerrainQuadClassifier.classify(positions);
            if (face == TerrainQuadClassifier.UNKNOWN) continue;
            float u0 = Float.POSITIVE_INFINITY, v0 = Float.POSITIVE_INFINITY;
            float u1 = Float.NEGATIVE_INFINITY, v1 = Float.NEGATIVE_INFINITY;
            boolean alpha = true;
            for (int i = 0; i < 4; i++) {
                int index = base + i * stride;
                if ((data.get(index + 15) & 255) != 255) alpha = false;
                float u = data.getFloat(index + 16), t = data.getFloat(index + 20);
                u0 = Math.min(u0, u); v0 = Math.min(v0, t);
                u1 = Math.max(u1, u); v1 = Math.max(v1, t);
            }
            if (!alpha || !opacity.opaque(u0, v0, u1, v1)) continue;
            work.add(count++, positions, face, scale, translation);
        }
        for (int pass = 0; pass < 2; pass++) {
            int before = count;
            count = work.merge(count, false);
            count = work.merge(count, true);
            if (count == before) break;
        }
        float[] quads = new float[count * 12];
        byte[] faces = new byte[count];
        for (int i = 0; i < count; i++) {
            work.write(work.order[i], quads, i * 12);
            faces[i] = work.faces[work.order[i]];
        }
        double[] transformedBounds = new double[6];
        for (int axis = 0; axis < 3; axis++) {
            float a = bounds[axis] * scale + translation;
            float b = bounds[axis + 3] * scale + translation;
            transformedBounds[axis] = Math.min(a, b) - 0.002D;
            transformedBounds[axis + 3] = Math.max(a, b) + 0.002D;
        }
        return new TerrainOccluderMesh(quads, transformedBounds, faces);
    }

    /** Stable primitive merge-sort over rectangle indices; no per-face objects. */
    private static final class Workspace {
        final int[] order = new int[MAX_QUADS];
        final int[] scratch = new int[MAX_QUADS];
        final double[] coverage = new double[MAX_QUADS];
        final byte[] faces = new byte[MAX_QUADS];
        final float[] planes = new float[MAX_QUADS];
        final float[] u0 = new float[MAX_QUADS], u1 = new float[MAX_QUADS];
        final float[] v0 = new float[MAX_QUADS], v1 = new float[MAX_QUADS];
        final float[] positions = new float[12], bounds = new float[6];

        void sortCoverage(int count) {
            for (int run = 1; run < count; run <<= 1) {
                for (int start = 0; start < count; start += run << 1) {
                    int middle = Math.min(count, start + run), end = Math.min(count, middle + run);
                    int left = start, right = middle, out = start;
                    while (left < middle && right < end) {
                        if (coverage[order[left]] >= coverage[order[right]]) scratch[out++] = order[left++];
                        else scratch[out++] = order[right++];
                    }
                    while (left < middle) scratch[out++] = order[left++];
                    while (right < end) scratch[out++] = order[right++];
                }
                System.arraycopy(scratch, 0, order, 0, count);
            }
        }

        void add(int index, float[] positions, int face, float scale, float translation) {
            faces[index] = (byte) face;
            order[index] = index;
            int axis = axis(face);
            planes[index] = positions[axis] * scale + translation;
            float left = Float.POSITIVE_INFINITY, bottom = Float.POSITIVE_INFINITY;
            float right = Float.NEGATIVE_INFINITY, top = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < 4; i++) {
                float u = positions[i * 3 + (axis + 1) % 3] * scale + translation;
                float v = positions[i * 3 + (axis + 2) % 3] * scale + translation;
                left = Math.min(left, u); right = Math.max(right, u);
                bottom = Math.min(bottom, v); top = Math.max(top, v);
            }
            u0[index] = left; u1[index] = right; v0[index] = bottom; v1[index] = top;
        }

        int merge(int count, boolean vertical) {
            if (count < 2) return count;
            sort(count, vertical);
            int write = 0;
            for (int read = 1; read < count; read++) {
                int a = order[write], b = order[read];
                boolean same = faces[a] == faces[b] && planes[a] == planes[b];
                if (vertical && same && u0[a] == u0[b] && u1[a] == u1[b] && v1[a] >= v0[b]) {
                    v1[a] = Math.max(v1[a], v1[b]);
                } else if (!vertical && same && v0[a] == v0[b] && v1[a] == v1[b] && u1[a] >= u0[b]) {
                    u1[a] = Math.max(u1[a], u1[b]);
                } else order[++write] = b;
            }
            return write + 1;
        }

        void sort(int count, boolean vertical) {
            for (int run = 1; run < count; run <<= 1) {
                for (int start = 0; start < count; start += run << 1) {
                    int middle = Math.min(count, start + run), end = Math.min(count, middle + run);
                    int left = start, right = middle, out = start;
                    while (left < middle && right < end) {
                        if (compare(order[left], order[right], vertical) <= 0) scratch[out++] = order[left++];
                        else scratch[out++] = order[right++];
                    }
                    while (left < middle) scratch[out++] = order[left++];
                    while (right < end) scratch[out++] = order[right++];
                }
                System.arraycopy(scratch, 0, order, 0, count);
            }
        }

        int compare(int a, int b, boolean vertical) {
            int result = faces[a] - faces[b];
            if (result == 0) result = Float.compare(planes[a], planes[b]);
            if (result == 0) result = Float.compare(vertical ? u0[a] : v0[a], vertical ? u0[b] : v0[b]);
            if (result == 0) result = Float.compare(vertical ? u1[a] : v1[a], vertical ? u1[b] : v1[b]);
            if (result == 0) result = Float.compare(vertical ? v0[a] : u0[a], vertical ? v0[b] : u0[b]);
            return result;
        }

        void write(int index, float[] output, int offset) {
            int face = faces[index], axis = axis(face);
            boolean positive = face == TerrainQuadClassifier.EAST || face == TerrainQuadClassifier.UP
                || face == TerrainQuadClassifier.SOUTH;
            for (int i = 0; i < 4; i++) {
                int corner = positive ? i : (4 - i) & 3;
                output[offset + i * 3 + axis] = planes[index];
                output[offset + i * 3 + (axis + 1) % 3] = corner == 1 || corner == 2 ? u1[index] : u0[index];
                output[offset + i * 3 + (axis + 2) % 3] = corner >= 2 ? v1[index] : v0[index];
            }
        }

        private static int axis(int face) {
            return face == TerrainQuadClassifier.WEST || face == TerrainQuadClassifier.EAST ? 0
                : face == TerrainQuadClassifier.DOWN || face == TerrainQuadClassifier.UP ? 1 : 2;
        }
    }
}
