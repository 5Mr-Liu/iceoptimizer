package dev.rlcraft.ice.optimizer.render.visibility;

/**
 * Stop paying a full raster pass when current-view probes hide nothing.
 * A skipped probe renders every candidate; no old visibility decision is reused.
 * Closed views with successful probes continue testing every frame.
 */
final class OcclusionWorkScheduler {
    private int misses;
    private int remaining;
    private boolean hasView;
    private double x, y, z;
    private float forwardX, forwardY, forwardZ;
    private long skipped;

    boolean shouldRun(double nextX, double nextY, double nextZ, float[] matrix) {
        double dx = nextX - x, dy = nextY - y, dz = nextZ - z;
        double dot = matrix[2] * forwardX + matrix[6] * forwardY + matrix[10] * forwardZ;
        double lengths = ((double) matrix[2] * matrix[2] + (double) matrix[6] * matrix[6]
            + (double) matrix[10] * matrix[10]) * ((double) forwardX * forwardX
            + (double) forwardY * forwardY + (double) forwardZ * forwardZ);
        if (!hasView || dx * dx + dy * dy + dz * dz >= 64D
            || dot <= 0D || dot * dot < lengths * .5D) {
            misses = remaining = 0;
            hasView = true;
            x = nextX; y = nextY; z = nextZ;
            forwardX = matrix[2]; forwardY = matrix[6]; forwardZ = matrix[10];
        }
        if (remaining == 0) return true;
        remaining--;
        skipped++;
        return false;
    }

    void result(int hidden) {
        if (hidden > 0) {
            misses = remaining = 0;
        } else {
            misses = Math.min(5, misses + 1);
            remaining = (1 << misses) - 1;
        }
    }

    void reset() { hasView = false; misses = remaining = 0; }
    long skipped() { return skipped; }
}
