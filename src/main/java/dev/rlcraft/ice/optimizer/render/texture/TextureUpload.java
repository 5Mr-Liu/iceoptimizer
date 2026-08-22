package dev.rlcraft.ice.optimizer.render.texture;

/** Immutable atlas rectangle update with exact ordering metadata. */
public final class TextureUpload {
    private final long spriteId;
    private final int textureId;
    private final int mipLevel;
    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final int bytesPerPixel;
    private final int format;
    private final int type;
    private final byte[] pixels;
    private final long resourceGeneration;
    private final long atlasGeneration;
    private final long sequence;

    public TextureUpload(long spriteId, int textureId, int mipLevel,
                         int x, int y, int width, int height, int bytesPerPixel,
                         int format, int type, byte[] pixels,
                         long resourceGeneration, long atlasGeneration,
                         long sequence) {
        long expected;
        boolean arithmeticValid = true;
        try {
            Math.addExact(x, width);
            Math.addExact(y, height);
            expected = Math.multiplyExact(Math.multiplyExact((long) width,
                (long) height), (long) bytesPerPixel);
        } catch (ArithmeticException overflow) {
            expected = -1L;
            arithmeticValid = false;
        }
        if (spriteId <= 0L || textureId <= 0 || mipLevel < 0 || x < 0 || y < 0
            || width <= 0 || height <= 0 || bytesPerPixel <= 0
            || !arithmeticValid || pixels == null || expected != pixels.length
            || expected > Integer.MAX_VALUE || resourceGeneration <= 0L
            || atlasGeneration <= 0L || sequence < 0L) {
            throw new IllegalArgumentException("texture upload");
        }
        this.spriteId = spriteId;
        this.textureId = textureId;
        this.mipLevel = mipLevel;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.bytesPerPixel = bytesPerPixel;
        this.format = format;
        this.type = type;
        this.pixels = pixels.clone();
        this.resourceGeneration = resourceGeneration;
        this.atlasGeneration = atlasGeneration;
        this.sequence = sequence;
    }

    public long getSpriteId() { return spriteId; }
    public int getTextureId() { return textureId; }
    public int getMipLevel() { return mipLevel; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getBytesPerPixel() { return bytesPerPixel; }
    public int getFormat() { return format; }
    public int getType() { return type; }
    public byte[] copyPixels() { return pixels.clone(); }
    public int getByteCount() { return pixels.length; }
    public long getResourceGeneration() { return resourceGeneration; }
    public long getAtlasGeneration() { return atlasGeneration; }
    public long getSequence() { return sequence; }

    boolean canMergeRight(TextureUpload next) {
        return next != null && textureId == next.textureId
            && mipLevel == next.mipLevel && y == next.y && height == next.height
            && (long) x + (long) width == (long) next.x
            && bytesPerPixel == next.bytesPerPixel
            && format == next.format && type == next.type
            && resourceGeneration == next.resourceGeneration
            && atlasGeneration == next.atlasGeneration && sequence <= next.sequence;
    }

    TextureUpload mergeRight(TextureUpload next) {
        if (!canMergeRight(next)) throw new IllegalArgumentException("non-adjacent upload");
        int combinedWidth = Math.addExact(width, next.width);
        int combinedRow = Math.multiplyExact(combinedWidth, bytesPerPixel);
        byte[] combined = new byte[Math.multiplyExact(combinedRow, height)];
        int leftRow = Math.multiplyExact(width, bytesPerPixel);
        int rightRow = Math.multiplyExact(next.width, bytesPerPixel);
        for (int row = 0; row < height; row++) {
            int destination = Math.multiplyExact(row, combinedRow);
            int leftSource = Math.multiplyExact(row, leftRow);
            int rightSource = Math.multiplyExact(row, rightRow);
            System.arraycopy(pixels, leftSource, combined, destination, leftRow);
            System.arraycopy(next.pixels, rightSource, combined,
                Math.addExact(destination, leftRow), rightRow);
        }
        // A merged run is an atlas operation, not one sprite; retain the first
        // identity only for diagnostics and the final sequence for ordering.
        return new TextureUpload(spriteId, textureId, mipLevel, x, y,
            combinedWidth, height, bytesPerPixel, format, type, combined,
            resourceGeneration, atlasGeneration, next.sequence);
    }
}
