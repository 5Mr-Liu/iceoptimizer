package dev.rlcraft.ice.optimizer.render.terrain;

/** Camera origin in the input coordinate system of an affine model-view matrix. */
public final class TerrainCamera {
    private TerrainCamera() { }

    public static boolean eye(float[] matrix, double[] result) {
        // The arena owns both fixed-size work arrays. Only GPU-derived matrix
        // values need checking here, not the internal array contract.
        for (float value : matrix) if (!Float.isFinite(value)) return false;
        if (matrix[3] != 0F || matrix[7] != 0F || matrix[11] != 0F || matrix[15] != 1F)
            return false;
        double a = matrix[0], b = matrix[4], c = matrix[8];
        double d = matrix[1], e = matrix[5], f = matrix[9];
        double g = matrix[2], h = matrix[6], i = matrix[10];
        double determinant = a * (e * i - f * h) - b * (d * i - f * g)
            + c * (d * h - e * g);
        // Reflections reverse front faces; a singular/ill-conditioned matrix
        // cannot establish a conservative eye position.
        if (!(determinant > 1.0E-8D) || !Double.isFinite(determinant)) return false;
        double x = -matrix[12], y = -matrix[13], z = -matrix[14];
        result[0] = ((e * i - f * h) * x + (c * h - b * i) * y + (b * f - c * e) * z) / determinant;
        result[1] = ((f * g - d * i) * x + (a * i - c * g) * y + (c * d - a * f) * z) / determinant;
        result[2] = ((d * h - e * g) * x + (b * g - a * h) * y + (a * e - b * d) * z) / determinant;
        return Double.isFinite(result[0]) && Double.isFinite(result[1]) && Double.isFinite(result[2]);
    }
}
