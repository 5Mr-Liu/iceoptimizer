package dev.rlcraft.ice.optimizer.render.visibility;

public final class HzbHistoryKey {
    private final int dimension;
    private final int width;
    private final int height;
    private final int fovBits;
    private final long cameraCell;
    private final long viewGeneration;
    private final long shaderGeneration;
    private final long viewProjectionHash;
    private final boolean standardDepth;

    public HzbHistoryKey(int dimension, int width, int height, float fov,
                         long cameraCell, long viewGeneration,
                         long shaderGeneration, boolean standardDepth) {
        this(dimension, width, height, fov, cameraCell, viewGeneration,
            shaderGeneration, 0L, standardDepth);
    }

    public HzbHistoryKey(int dimension, int width, int height, float fov,
                         long cameraCell, long viewGeneration,
                         long shaderGeneration, long viewProjectionHash,
                         boolean standardDepth) {
        this.dimension = dimension;
        this.width = width;
        this.height = height;
        this.fovBits = Float.floatToRawIntBits(fov);
        this.cameraCell = cameraCell;
        this.viewGeneration = viewGeneration;
        this.shaderGeneration = shaderGeneration;
        this.viewProjectionHash = viewProjectionHash;
        this.standardDepth = standardDepth;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public boolean isStandardDepth() { return standardDepth; }

    @Override public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof HzbHistoryKey)) return false;
        HzbHistoryKey other = (HzbHistoryKey) value;
        return dimension == other.dimension && width == other.width
            && height == other.height && fovBits == other.fovBits
            && cameraCell == other.cameraCell && viewGeneration == other.viewGeneration
            && shaderGeneration == other.shaderGeneration
            && viewProjectionHash == other.viewProjectionHash
            && standardDepth == other.standardDepth;
    }

    @Override public int hashCode() {
        long value = dimension * 31L + width;
        value = value * 31L + height;
        value = value * 31L + fovBits;
        value = value * 31L + cameraCell;
        value = value * 31L + viewGeneration;
        value = value * 31L + shaderGeneration;
        value = value * 31L + viewProjectionHash;
        value = value * 31L + (standardDepth ? 1 : 0);
        return (int) (value ^ (value >>> 32));
    }
}
