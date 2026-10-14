package dev.rlcraft.ice.optimizer.render.terrain;

import java.nio.ByteBuffer;

/** Geometry-only classification. UNKNOWN is always submitted, never culled. */
public final class TerrainQuadClassifier {
    public static final int UNKNOWN = 0, DOWN = 1, UP = 2, NORTH = 3,
        SOUTH = 4, WEST = 5, EAST = 6, GROUPS = 7;

    private TerrainQuadClassifier() { }

    /**
     * Accepts only finite, non-degenerate axis-aligned rectangles, including
     * both triangles of the quad. This does not certify texture opacity.
     */
    public static int classify(ByteBuffer vertices, int offset, int stride) {
        if (vertices == null || stride < 12 || offset < 0
            || (long) offset + 3L * stride + 12L > vertices.limit()) return UNKNOWN;
        return classifyKnownRange(vertices, offset, stride);
    }

    /** Upload grouping has already validated the entire vertex-buffer range. */
    static int classifyKnownRange(ByteBuffer vertices, int offset, int stride) {
        double ax = vertices.getFloat(offset), ay = vertices.getFloat(offset + 4),
            az = vertices.getFloat(offset + 8);
        double bx = vertices.getFloat(offset + stride), by = vertices.getFloat(offset + stride + 4),
            bz = vertices.getFloat(offset + stride + 8);
        double cx = vertices.getFloat(offset + stride * 2), cy = vertices.getFloat(offset + stride * 2 + 4),
            cz = vertices.getFloat(offset + stride * 2 + 8);
        double dx = vertices.getFloat(offset + stride * 3), dy = vertices.getFloat(offset + stride * 3 + 4),
            dz = vertices.getFloat(offset + stride * 3 + 8);
        return classify(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz);
    }

    /** Reuses positions already decoded for upload-time bounds. */
    public static int classify(float[] positions) {
        if (positions == null || positions.length < 12) return UNKNOWN;
        return classify(positions[0], positions[1], positions[2],
            positions[3], positions[4], positions[5], positions[6],
            positions[7], positions[8], positions[9], positions[10], positions[11]);
    }

    private static int classify(double ax, double ay, double az,
                                double bx, double by, double bz,
                                double cx, double cy, double cz,
                                double dx, double dy, double dz) {
        if (!finite(ax) || !finite(ay) || !finite(az) || !finite(bx)
            || !finite(by) || !finite(bz) || !finite(cx) || !finite(cy)
            || !finite(cz) || !finite(dx) || !finite(dy) || !finite(dz)) return UNKNOWN;
        if (ax == bx && ax == cx && ax == dx) {
            int winding = rectangle(ay, az, by, bz, cy, cz, dy, dz);
            return winding > 0 ? EAST : winding < 0 ? WEST : UNKNOWN;
        }
        if (ay == by && ay == cy && ay == dy) {
            int winding = rectangle(az, ax, bz, bx, cz, cx, dz, dx);
            return winding > 0 ? UP : winding < 0 ? DOWN : UNKNOWN;
        }
        if (az == bz && az == cz && az == dz) {
            int winding = rectangle(ax, ay, bx, by, cx, cy, dx, dy);
            return winding > 0 ? SOUTH : winding < 0 ? NORTH : UNKNOWN;
        }
        return UNKNOWN;
    }

    private static int rectangle(double au, double av, double bu, double bv,
                                 double cu, double cv, double du, double dv) {
        // Every edge must be a nonzero edge of the rectangle; rejecting a
        // diagonal edge also rejects bow ties and duplicate-corner quads.
        boolean firstU = au != bu && av == bv;
        boolean firstV = au == bu && av != bv;
        if (firstU) {
            if (bu != cu || bv == cv || cu == du || cv != dv
                || du != au || dv == av) return 0;
        } else if (firstV) {
            if (bu == cu || bv != cv || cu != du || cv == dv
                || du == au || dv != av) return 0;
        } else return 0;
        double cross = (bu - au) * (cv - av) - (bv - av) * (cu - au);
        return cross > 0 ? 1 : cross < 0 ? -1 : 0;
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
