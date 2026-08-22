package dev.rlcraft.ice.optimizer.render.optifine;

import java.util.Arrays;

/** Logical FBO attachment layout; it deliberately excludes backend GL names. */
public final class ShaderFramebufferState {
    private static final int MAX_DIMENSION = 32768;
    private static final int MAX_SAMPLES = 16;
    private static final long MAX_ESTIMATED_ATTACHMENT_BYTES =
        1024L * 1024L * 1024L;
    private final int width;
    private final int height;
    private final int samples;
    private final int depthInternalFormat;
    private final int[] colorInternalFormats;

    public ShaderFramebufferState(int width, int height, int samples,
                                  int depthInternalFormat,
                                  int[] colorInternalFormats) {
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION
            || height > MAX_DIMENSION || samples < 0 || samples > MAX_SAMPLES
            || depthInternalFormat < 0
            || colorInternalFormats == null || colorInternalFormats.length > 16) {
            throw new IllegalArgumentException("shader framebuffer state");
        }
        for (int format : colorInternalFormats) {
            if (format <= 0) throw new IllegalArgumentException(
                "invalid shader color attachment format");
        }
        long pixels = checkedMultiply(width, height);
        long bytesPerPixel = checkedAdd(
            checkedMultiply(colorInternalFormats.length, 16L),
            depthInternalFormat == 0 ? 0L : 8L);
        long estimatedBytes = checkedMultiply(checkedMultiply(pixels,
            Math.max(1, samples)), bytesPerPixel);
        if (estimatedBytes > MAX_ESTIMATED_ATTACHMENT_BYTES) {
            throw new IllegalArgumentException(
                "shader framebuffer attachment byte limit exceeded");
        }
        this.width = width;
        this.height = height;
        this.samples = samples;
        this.depthInternalFormat = depthInternalFormat;
        this.colorInternalFormats = colorInternalFormats.clone();
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getSamples() { return samples; }
    public int getDepthInternalFormat() { return depthInternalFormat; }
    public int[] getColorInternalFormats() { return colorInternalFormats.clone(); }

    @Override public boolean equals(Object value) {
        if (!(value instanceof ShaderFramebufferState)) return false;
        ShaderFramebufferState other = (ShaderFramebufferState) value;
        return width == other.width && height == other.height
            && samples == other.samples
            && depthInternalFormat == other.depthInternalFormat
            && Arrays.equals(colorInternalFormats, other.colorInternalFormats);
    }

    @Override public int hashCode() {
        int result = width;
        result = 31 * result + height;
        result = 31 * result + samples;
        result = 31 * result + depthInternalFormat;
        return 31 * result + Arrays.hashCode(colorInternalFormats);
    }

    private static long checkedAdd(long left, long right) {
        if (left < 0L || right < 0L || right > Long.MAX_VALUE - left) {
            throw new IllegalArgumentException("shader framebuffer size overflow");
        }
        return left + right;
    }

    private static long checkedMultiply(long left, long right) {
        if (left < 0L || right < 0L
            || (left != 0L && right > Long.MAX_VALUE / left)) {
            throw new IllegalArgumentException("shader framebuffer size overflow");
        }
        return left * right;
    }
}
