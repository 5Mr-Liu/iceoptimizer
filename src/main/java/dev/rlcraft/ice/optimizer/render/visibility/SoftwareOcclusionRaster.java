package dev.rlcraft.ice.optimizer.render.visibility;

import java.util.Arrays;

/**
 * Current-view, inner-conservative coverage. A depth cell is written only when
 * its entire square lies strictly inside one real front-facing convex quad.
 * Bounding boxes are queries only, never occluders. No result survives begin().
 */
final class SoftwareOcclusionRaster {
    private static final double EDGE_MARGIN = 1.0E-5D;
    private static final double DEPTH_MARGIN = 1.0E-5D;
    private final int width;
    private final int height;
    private final double[] depth;
    // Horizontal eight-cell maxima retain row-major query/budget semantics.
    // NaN marks a changed block; it is rebuilt only when a query uses it.
    private final int blocksPerRow;
    private final double[] blockDepth;
    // Depth only decreases. A stale maximum remains a safe upper bound and
    // can reject a redundant update even while the exact maximum is dirty.
    private final double[] blockUpperDepth;
    private final double[] matrix = new double[16];
    private final double[] projected = new double[12];
    private final double[] boundProducts = new double[24];
    private final double[] edgeDx = new double[4];
    private final double[] edgeDy = new double[4];
    private final double[] edgeMargin = new double[4];
    private double viewX, viewY, viewZ;
    private int remaining;
    private boolean valid;

    public SoftwareOcclusionRaster(int width, int height) {
        if (width < 1 || height < 1 || width > 320 || height > 180) {
            throw new IllegalArgumentException("software depth dimensions");
        }
        this.width = width;
        this.height = height;
        this.depth = new double[width * height];
        this.blocksPerRow = (width + 7) >>> 3;
        this.blockDepth = new double[blocksPerRow * height];
        this.blockUpperDepth = new double[blockDepth.length];
    }

    /** Matrices are column-major; positions passed to this class are world coordinates. */
    public void begin(float[] model, float[] projection,
                      double cameraX, double cameraY, double cameraZ, int cellBudget) {
        Arrays.fill(depth, 1.0D);
        Arrays.fill(blockDepth, 1.0D);
        Arrays.fill(blockUpperDepth, 1.0D);
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
        // An AABB has only two values on each axis. Reuse their clip-space
        // products across its eight corners; the addition order stays exactly
        // the scalar projection order, including near-plane decisions.
        minX -= viewX; maxX -= viewX;
        minY -= viewY; maxY -= viewY;
        minZ -= viewZ; maxZ -= viewZ;
        for (int component = 0; component < 4; component++) {
            boundProducts[component] = matrix[component] * minX;
            boundProducts[component + 4] = matrix[component] * maxX;
            boundProducts[component + 8] = matrix[component + 4] * minY;
            boundProducts[component + 12] = matrix[component + 4] * maxY;
            boundProducts[component + 16] = matrix[component + 8] * minZ;
            boundProducts[component + 20] = matrix[component + 8] * maxZ;
        }
        for (int corner = 0; corner < 8; corner++) {
            int x = (corner & 1) << 2, y = 8 + ((corner & 2) << 1), z = 16 + (corner & 4);
            double w = boundProducts[x + 3] + boundProducts[y + 3] + boundProducts[z + 3] + matrix[15];
            double clipZ = boundProducts[x + 2] + boundProducts[y + 2] + boundProducts[z + 2] + matrix[14];
            if (!(w > 1.0E-7D) || !Double.isFinite(w)
                || !(clipZ > -w + 1.0E-7D && clipZ < w - 1.0E-7D)) return false;
            double sx = ((boundProducts[x] + boundProducts[y] + boundProducts[z] + matrix[12]) / w
                * 0.5D + 0.5D) * width;
            double sy = ((boundProducts[x + 1] + boundProducts[y + 1] + boundProducts[z + 1] + matrix[13]) / w
                * 0.5D + 0.5D) * height;
            double sz = clipZ / w * 0.5D + 0.5D;
            if (!Double.isFinite(sx) || !Double.isFinite(sy) || !Double.isFinite(sz)) return false;
            left = Math.min(left, sx); bottom = Math.min(bottom, sy);
            right = Math.max(right, sx); top = Math.max(top, sy);
            near = Math.min(near, sz);
        }
        // Grow by a whole coarse cell, covering real framebuffer rasterization
        // and the independently rounded legacy/arena model transform.
        if (right < 0 || top < 0 || left > width || bottom > height) return false;
        // Necessary geometric condition for ANY contained quad to cover a
        // complete depth cell. Positive W on all eight AABB corners bounds
        // every projected point in the box; no occlusion inference is made.
        output.canCoverCell = Math.max(0.0D, Math.ceil(left)) < Math.min(width, Math.floor(right))
            && Math.max(0.0D, Math.ceil(bottom)) < Math.min(height, Math.floor(top));
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
            int row = y * width;
            for (int x = box.minX; x < box.maxX;) {
                if ((x & 7) == 0 && x + 8 <= box.maxX && remaining >= 8) {
                    int block = y * blocksPerRow + (x >>> 3);
                    double farthest = blockDepth[block];
                    if (Double.isNaN(farthest)) {
                        farthest = depth[row + x];
                        // A visible first cell already answers this query;
                        // do not rebuild an eight-cell maximum to rediscover it.
                        if (!(box.nearest > farthest + DEPTH_MARGIN)) {
                            remaining--;
                            return false;
                        }
                        for (int i = row + x + 1, end = row + x + 8; i < end; i++) {
                            farthest = Math.max(farthest, depth[i]);
                        }
                        blockDepth[block] = farthest;
                        blockUpperDepth[block] = farthest;
                    }
                    if (box.nearest > farthest + DEPTH_MARGIN) {
                        remaining -= 8;
                        x += 8;
                        continue;
                    }
                }
                if (remaining-- <= 0) { remaining = 0; return false; }
                if (!(box.nearest > depth[row + x] + DEPTH_MARGIN)) return false;
                x++;
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
            if (vertex == 2 && !(edge(0, 3, projected[6], projected[7]) > EDGE_MARGIN)) return;
        }
        // Upload capture already proves convexity. Positive homogeneous W
        // preserves it, so one winding test determines the whole face.
        if (right <= 0 || top <= 0 || left >= width || bottom >= height) return;
        int x0 = Math.max(0, (int) Math.floor(left));
        int y0 = Math.max(0, (int) Math.floor(bottom));
        int x1 = Math.min(width, (int) Math.ceil(right));
        int y1 = Math.min(height, (int) Math.ceil(top));
        // The maximum vertex z/w bounds the whole planar quad in screen space.
        // Bias away from the camera, and only retain completely covered cells.
        double safeDepth = Math.min(1.0D, far + DEPTH_MARGIN);
        for (int i = 0; i < 4; i++) {
            int a = i * 3, b = ((i + 1) & 3) * 3;
            double dx = projected[b] - projected[a];
            double dy = projected[b + 1] - projected[a + 1];
            edgeDx[i] = dx;
            edgeDy[i] = dy;
            edgeMargin[i] = EDGE_MARGIN * (1.0D + Math.abs(dx) + Math.abs(dy));
        }
        for (int y = y0; y < y1; y++) {
            // Charge exactly the old row-major bounding-box budget, including
            // rejected cells. A partially budgeted row keeps the same prefix.
            int start = x0, end = x0 + Math.min(x1 - x0, remaining);
            remaining -= end - start;
            if (start == end) return;
            // The four half-planes intersect in one interval on each row.
            // Search with the original IEEE edge expression, rather than a
            // rounded line intersection or accumulated edge increments.
            for (int i = 0; i < 4 && start < end; i++) {
                double dx = edgeDx[i], dy = edgeDy[i];
                double cy = dx < 0.0D ? y + 1.0D : y;
                double row = dx * (cy - projected[i * 3 + 1]);
                if (dy > 0.0D) {
                    if (inside(i, row, end - 1)) continue;
                    if (!inside(i, row, start)) { end = start; break; }
                    int low = start, high = end - 1;
                    while (high - low > 1) {
                        int middle = (low + high) >>> 1;
                        if (inside(i, row, middle)) low = middle; else high = middle;
                    }
                    end = high;
                } else if (dy < 0.0D) {
                    if (inside(i, row, start)) continue;
                    if (!inside(i, row, end - 1)) { end = start; break; }
                    int low = start, high = end - 1;
                    while (high - low > 1) {
                        int middle = (low + high) >>> 1;
                        if (inside(i, row, middle)) high = middle; else low = middle;
                    }
                    start = high;
                } else if (!inside(i, row, start)) {
                    end = start;
                }
            }
            for (int x = start; x < end;) {
                int blockStart = x & ~7;
                int blockEnd = Math.min(width, blockStart + 8);
                int limit = Math.min(end, blockEnd);
                int block = y * blocksPerRow + (x >>> 3);
                if (safeDepth < blockUpperDepth[block]) {
                    boolean changed = false;
                    for (int index = y * width + x, stop = y * width + limit; index < stop; index++) {
                        if (safeDepth < depth[index]) {
                            depth[index] = safeDepth;
                            changed = true;
                        }
                    }
                    if (x == blockStart && limit == blockEnd) {
                        blockUpperDepth[block] = safeDepth;
                        // max(min(cell,z)) == min(max(cell),z) for a whole block.
                        blockDepth[block] = Math.min(blockDepth[block], safeDepth);
                    } else if (changed) blockDepth[block] = Double.NaN;
                }
                x = limit;
            }
        }
    }

    private boolean inside(int edge, double row, int x) {
        double dy = edgeDy[edge];
        double cx = dy > 0.0D ? x + 1.0D : x;
        return row - dy * (cx - projected[edge * 3]) > edgeMargin[edge];
    }

    private double edge(int a, int b, double x, double y) {
        return (projected[b] - projected[a]) * (y - projected[a + 1])
            - (projected[b + 1] - projected[a + 1]) * (x - projected[a]);
    }

    public boolean exhausted() { return remaining <= 0; }

    int remainingQueryCells() { return remaining; }

    public static final class Bounds {
        int minX, minY, maxX, maxY;
        double nearest;
        boolean canCoverCell;
        public double nearestDepth() { return nearest; }
    }
}
