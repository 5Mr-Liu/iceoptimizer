package dev.rlcraft.ice.optimizer.render.visibility;

import dev.rlcraft.ice.optimizer.render.terrain.TerrainQuadClassifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/** Frozen pre-fixed-point reference used only by equivalence tests. Cold upload snapshot: exact source bounds and a bounded subset of actual opaque faces. */
final class ReferenceTerrainOccluderMesh {
    private static final int MAX_QUADS = 512;
    private static final int MAX_SOURCE_BYTES = 16 * 1024 * 1024;
    private static final float MODEL_SCALE = 1.000001F;
    private static final float MODEL_TRANSLATION = 8.0F * MODEL_SCALE - 8.0F;
    final float[] quads;
    final double minX, minY, minZ, maxX, maxY, maxZ;

    interface Opacity {
        boolean opaque(float minU, float minV, float maxU, float maxV);
    }

    private ReferenceTerrainOccluderMesh(float[] quads, double[] bounds) {
        this.quads = quads;
        minX = bounds[0]; minY = bounds[1]; minZ = bounds[2];
        maxX = bounds[3]; maxY = bounds[4]; maxZ = bounds[5];
    }

    static ReferenceTerrainOccluderMesh capture(ByteBuffer source, int stride, int vertexCount,
                                      Opacity opacity) {
        return capture(source, stride, vertexCount, opacity, MODEL_SCALE, MODEL_TRANSLATION);
    }

    static ReferenceTerrainOccluderMesh capture(ByteBuffer source, int stride, int vertexCount,
                                      Opacity opacity, float scale, float translation) {
        if (source == null || opacity == null || stride < 28 || source.position() != 0
            || source.limit() > MAX_SOURCE_BYTES || vertexCount <= 0 || (vertexCount & 3) != 0
            || (long) stride * vertexCount != source.limit()) return null;
        ByteBuffer data = source.duplicate().order(ByteOrder.nativeOrder());
        double[] bounds = { Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY };
        // Bounds cover ALL vertices, including unsupported quads and geometry
        // outside a vanilla section. Such geometry must never escape its query.
        ArrayList<Rectangle> captured = new ArrayList<Rectangle>(Math.min(vertexCount / 4, MAX_QUADS));
        float[] positions = new float[12];
        for (int v = 0; v < vertexCount; v += 4) {
            int base = v * stride;
            // Decode each coordinate once for bounds, classification and
            // rectangle construction. Bounds still include every quad after
            // the bounded occluder set is full.
            for (int i = 0; i < 4; i++) {
                for (int axis = 0; axis < 3; axis++) {
                    float coordinate = data.getFloat(base + i * stride + axis * 4);
                    if (!Float.isFinite(coordinate) || Math.abs(coordinate) > 65536.0F) return null;
                    positions[i * 3 + axis] = coordinate;
                    double transformed = coordinate * scale + translation;
                    bounds[axis] = Math.min(bounds[axis], transformed - 0.002D);
                    bounds[axis + 3] = Math.max(bounds[axis + 3], transformed + 0.002D);
                }
            }
            if (captured.size() >= MAX_QUADS) continue;
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
            captured.add(new Rectangle(positions, face, scale, translation));
        }
        // Exact rectangle unions remove artificial coarse-grid cracks between
        // adjacent stone faces. Equal spans and touching edges are mandatory:
        // a window, missing tile, opposite winding or another plane never fills.
        for (int pass = 0; pass < 2; pass++) {
            merge(captured, false);
            merge(captured, true);
        }
        float[] quads = new float[captured.size() * 12];
        for (int i = 0; i < captured.size(); i++) captured.get(i).write(quads, i * 12);
        // Mesh bounds remain useful as an occludee even with zero safe occluders.
        return new ReferenceTerrainOccluderMesh(quads, bounds);
    }

    private static void merge(ArrayList<Rectangle> values, final boolean vertical) {
        if (values.size() < 2) return;
        Collections.sort(values, vertical ? Rectangle.BY_V : Rectangle.BY_U);
        int write = 0;
        for (int read = 1; read < values.size(); read++) {
            Rectangle a = values.get(write), b = values.get(read);
            boolean same = a.face == b.face && a.plane == b.plane;
            if (vertical && same && a.u0 == b.u0 && a.u1 == b.u1
                && a.v1 >= b.v0) {
                a.v1 = Math.max(a.v1, b.v1);
            } else if (!vertical && same && a.v0 == b.v0 && a.v1 == b.v1
                && a.u1 >= b.u0) {
                a.u1 = Math.max(a.u1, b.u1);
            } else values.set(++write, b);
        }
        values.subList(write + 1, values.size()).clear();
    }

    private static final class Rectangle {
        private static final Comparator<Rectangle> BY_U = order(false);
        private static final Comparator<Rectangle> BY_V = order(true);
        final int face, axis;
        final float plane;
        float u0 = Float.POSITIVE_INFINITY, v0 = Float.POSITIVE_INFINITY;
        float u1 = Float.NEGATIVE_INFINITY, v1 = Float.NEGATIVE_INFINITY;

        Rectangle(float[] positions, int face, float scale, float translation) {
            this.face = face;
            axis = face == TerrainQuadClassifier.WEST || face == TerrainQuadClassifier.EAST ? 0
                : face == TerrainQuadClassifier.DOWN || face == TerrainQuadClassifier.UP ? 1 : 2;
            plane = positions[axis] * scale + translation;
            for (int i = 0; i < 4; i++) {
                float u = positions[i * 3 + (axis + 1) % 3] * scale + translation;
                float v = positions[i * 3 + (axis + 2) % 3] * scale + translation;
                u0 = Math.min(u0, u); u1 = Math.max(u1, u);
                v0 = Math.min(v0, v); v1 = Math.max(v1, v);
            }
        }

        void write(float[] output, int offset) {
            boolean positive = face == TerrainQuadClassifier.EAST || face == TerrainQuadClassifier.UP
                || face == TerrainQuadClassifier.SOUTH;
            for (int i = 0; i < 4; i++) {
                int corner = positive ? i : (4 - i) & 3;
                output[offset + i * 3 + axis] = plane;
                output[offset + i * 3 + (axis + 1) % 3] = corner == 1 || corner == 2 ? u1 : u0;
                output[offset + i * 3 + (axis + 2) % 3] = corner >= 2 ? v1 : v0;
            }
        }

        private static Comparator<Rectangle> order(final boolean vertical) {
            return new Comparator<Rectangle>() {
                @Override public int compare(Rectangle a, Rectangle b) {
                    int result = Integer.compare(a.face, b.face);
                    if (result == 0) result = Float.compare(a.plane, b.plane);
                    if (result == 0) result = Float.compare(vertical ? a.u0 : a.v0, vertical ? b.u0 : b.v0);
                    if (result == 0) result = Float.compare(vertical ? a.u1 : a.v1, vertical ? b.u1 : b.v1);
                    if (result == 0) result = Float.compare(vertical ? a.v0 : a.u0, vertical ? b.v0 : b.u0);
                    return result;
                }
            };
        }
    }
}
