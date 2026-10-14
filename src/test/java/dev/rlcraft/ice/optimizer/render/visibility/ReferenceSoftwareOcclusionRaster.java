package dev.rlcraft.ice.optimizer.render.visibility;

import java.util.Arrays;

/**
 * Frozen 1.0.16 scalar oracle for optimization equivalence tests.
 * Current-view, inner-conservative coverage. A depth cell is written only when
 * its entire square lies strictly inside one real front-facing convex quad.
 * Bounding boxes are queries only, never occluders. No result survives begin().
 */
final class ReferenceSoftwareOcclusionRaster {
    private static final double EDGE_MARGIN = 1.0E-5D;
    private static final double DEPTH_MARGIN = 1.0E-5D;
    private final int width;
    private final int height;
    private final double[] depth;
    private final double[] matrix = new double[16];
    private final double[] projected = new double[12];
    private final double[] point = new double[3];
    private double viewX, viewY, viewZ;
    private int remaining;
    private boolean valid;

    public ReferenceSoftwareOcclusionRaster(int width, int height) {
        if (width < 1 || height < 1 || width > 320 || height > 180) {
            throw new IllegalArgumentException("software depth dimensions");
        }
        this.width = width;
        this.height = height;
        this.depth = new double[width * height];
    }

    /** Matrices are column-major; positions passed to this class are world coordinates. */
    public void begin(float[] model, float[] projection,
                      double cameraX, double cameraY, double cameraZ, int cellBudget) {
        Arrays.fill(depth, 1.0D);
        remaining = Math.max(0, cellBudget);
        valid = model != null && projection != null && model.length == 16
            && projection.length == 16 && Double.isFinite(cameraX)
            && Double.isFinite(cameraY) && Double.isFinite(cameraZ);
        viewX = cameraX;
        viewY = cameraY;
        viewZ = cameraZ;
        if (!valid) return;
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                double value = 0.0D;
                for (int k = 0; k < 4; k++) {
                    value += (double) projection[k * 4 + row] * model[column * 4 + k];
                }
                matrix[column * 4 + row] = value;
                if (!Double.isFinite(value)) valid = false;
            }
        }
    }

    /** Rejects all clipping/near-plane ambiguity instead of growing coverage. */
    private boolean project(double x, double y, double z, double[] output, int offset) {
        x -= viewX;
        y -= viewY;
        z -= viewZ;
        double w = matrix[3] * x + matrix[7] * y + matrix[11] * z + matrix[15];
        double clipZ = matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14];
        if (!(w > 1.0E-7D) || !Double.isFinite(w)
            || !(clipZ > -w + 1.0E-7D && clipZ < w - 1.0E-7D)) return false;
        double sx = ((matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12]) / w
            * 0.5D + 0.5D) * width;
        double sy = ((matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13]) / w
            * 0.5D + 0.5D) * height;
        double sz = clipZ / w * 0.5D + 0.5D;
        if (!Double.isFinite(sx) || !Double.isFinite(sy) || !Double.isFinite(sz)) return false;
        output[offset] = sx;
        output[offset + 1] = sy;
        output[offset + 2] = sz;
        return true;
    }

    /** World AABB is an outer bound of the exact uploaded layer, including mod extensions. */
    public boolean projectBounds(double minX, double minY, double minZ,
                                 double maxX, double maxY, double maxZ, Bounds output) {
        if (!valid || output == null || minX > maxX || minY > maxY || minZ > maxZ) return false;
        double left = Double.POSITIVE_INFINITY, bottom = Double.POSITIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY, top = Double.NEGATIVE_INFINITY;
        double near = 1.0D;
        for (int corner = 0; corner < 8; corner++) {
            if (!project((corner & 1) == 0 ? minX : maxX,
                (corner & 2) == 0 ? minY : maxY, (corner & 4) == 0 ? minZ : maxZ, point, 0)) {
                return false;
            }
            left = Math.min(left, point[0]);
            bottom = Math.min(bottom, point[1]);
            right = Math.max(right, point[0]);
            top = Math.max(top, point[1]);
            near = Math.min(near, point[2]);
        }
        // Grow by a whole coarse cell, covering real framebuffer rasterization
        // and the independently rounded legacy/arena model transform.
        if (right < 0 || top < 0 || left > width || bottom > height) return false;
        output.minX = Math.max(0, (int) Math.floor(left) - 1);
        output.minY = Math.max(0, (int) Math.floor(bottom) - 1);
        output.maxX = Math.min(width, (int) Math.ceil(right) + 1);
        output.maxY = Math.min(height, (int) Math.ceil(top) + 1);
        output.nearest = Math.max(0.0D, near - DEPTH_MARGIN);
        return output.minX < output.maxX && output.minY < output.maxY;
    }

    /** Entire query must be behind known coverage. Budget exhaustion always returns visible. */
    public boolean occluded(Bounds box) {
        // Bounds come exclusively from successful projectBounds calls.
        for (int y = box.minY; y < box.maxY; y++) {
            for (int x = box.minX; x < box.maxX; x++) {
                if (remaining-- <= 0) { remaining = 0; return false; }
                if (!(box.nearest > depth[y * width + x] + DEPTH_MARGIN)) return false;
            }
        }
        return true;
    }

    /** Input is four consecutive local XYZ vertices, already proved planar and opaque. */
    public void rasterize(float[] quads, int offset, double originX, double originY, double originZ) {
        if (!valid || remaining <= 0) return;
        double left = Double.POSITIVE_INFINITY, bottom = Double.POSITIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY, top = Double.NEGATIVE_INFINITY;
        double far = 0.0D;
        for (int vertex = 0; vertex < 4; vertex++) {
            int i = vertex * 3;
            if (!project(originX + quads[offset + i], originY + quads[offset + i + 1],
                originZ + quads[offset + i + 2], projected, i)) return;
            left = Math.min(left, projected[i]);
            bottom = Math.min(bottom, projected[i + 1]);
            right = Math.max(right, projected[i]);
            top = Math.max(top, projected[i + 1]);
            far = Math.max(far, projected[i + 2]);
        }
        // Upload capture already proves convexity. Positive homogeneous W
        // preserves it, so one winding test determines the whole face.
        if (!(edge(0, 3, projected[6], projected[7]) > EDGE_MARGIN)) return;
        if (right <= 0 || top <= 0 || left >= width || bottom >= height) return;
        int x0 = Math.max(0, (int) Math.floor(left));
        int y0 = Math.max(0, (int) Math.floor(bottom));
        int x1 = Math.min(width, (int) Math.ceil(right));
        int y1 = Math.min(height, (int) Math.ceil(top));
        // The maximum vertex z/w bounds the whole planar quad in screen space.
        // Bias away from the camera, and only retain completely covered cells.
        double safeDepth = Math.min(1.0D, far + DEPTH_MARGIN);
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if (remaining-- <= 0) { remaining = 0; return; }
                boolean covered = true;
                for (int i = 0; i < 4 && covered; i++) {
                    int a = i * 3, b = ((i + 1) & 3) * 3;
                    double dx = projected[b] - projected[a];
                    double dy = projected[b + 1] - projected[a + 1];
                    // Minimize the edge equation over the complete cell square.
                    double cx = dy > 0.0D ? x + 1.0D : x;
                    double cy = dx < 0.0D ? y + 1.0D : y;
                    double margin = EDGE_MARGIN * (1.0D + Math.abs(dx) + Math.abs(dy));
                    covered = edge(a, b, cx, cy) > margin;
                }
                if (covered) {
                    int index = y * width + x;
                    depth[index] = Math.min(depth[index], safeDepth);
                }
            }
        }
    }

    private double edge(int a, int b, double x, double y) {
        return (projected[b] - projected[a]) * (y - projected[a + 1])
            - (projected[b + 1] - projected[a + 1]) * (x - projected[a]);
    }

    public boolean exhausted() { return remaining <= 0; }

    public static final class Bounds {
        int minX, minY, maxX, maxY;
        double nearest;
        public double nearestDepth() { return nearest; }
    }
}
