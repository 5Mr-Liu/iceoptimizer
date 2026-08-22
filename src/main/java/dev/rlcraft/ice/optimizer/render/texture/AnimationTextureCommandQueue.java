package dev.rlcraft.ice.optimizer.render.texture;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.memory.RetainedHeap;
import java.nio.IntBuffer;

/**
 * Bounded primitive metadata queue for one TextureMap animation traversal.
 * Pixel arrays remain owned by the sprite and are referenced only until the
 * same-thread scope flushes; no command or pixel-copy object is allocated.
 */
public final class AnimationTextureCommandQueue implements AutoCloseable {
    private static final int VANILLA_STAGING_INTS = 4_194_304;

    private final int maximumCommands;
    private final long maximumBytes;
    private int[][][] data;
    private int[] widths;
    private int[] heights;
    private int[] originX;
    private int[] originY;
    private boolean[] blur;
    private boolean[] clamp;
    private CacheBudget.Reservation heapReservation;
    private int size;
    private int mipLevels;
    private long bytes;
    private long rejected;

    public AnimationTextureCommandQueue(int maximumCommands, long maximumBytes) {
        this(maximumCommands, maximumBytes, null);
    }

    /** Production constructor which charges every retained metadata array. */
    public AnimationTextureCommandQueue(int maximumCommands, long maximumBytes,
                                        CacheBudget budget) {
        this.maximumCommands = Math.max(16, maximumCommands);
        this.maximumBytes = Math.max(4096L, maximumBytes);
        CacheBudget.Reservation reservation = RetainedHeap.reserve(budget,
            heapBytesForCapacity(this.maximumCommands),
            "animation texture command queue");
        try {
            data = new int[this.maximumCommands][][];
            widths = new int[this.maximumCommands];
            heights = new int[this.maximumCommands];
            originX = new int[this.maximumCommands];
            originY = new int[this.maximumCommands];
            blur = new boolean[this.maximumCommands];
            clamp = new boolean[this.maximumCommands];
            heapReservation = reservation;
        } catch (RuntimeException | Error failure) {
            reservation.close();
            throw failure;
        }
    }

    public boolean offer(int[][] pixels, int width, int height, int x, int y,
                         boolean blurred, boolean clamped) {
        checkOpen();
        long commandBytes = validatedByteCount(pixels, width, height);
        if (commandBytes < 0L || x < 0 || y < 0 || size >= maximumCommands
            || commandBytes > maximumBytes - bytes) {
            rejected++;
            return false;
        }
        data[size] = pixels;
        widths[size] = width;
        heights[size] = height;
        originX[size] = x;
        originY[size] = y;
        blur[size] = blurred;
        clamp[size] = clamped;
        mipLevels += usableMipLevels(pixels, width, height);
        bytes += commandBytes;
        size++;
        return true;
    }

    /** Copies the exact mip rectangles in original command/mip order. */
    public int copyPixels(IntBuffer destination) {
        checkOpen();
        if (destination == null || bytes / 4L > destination.remaining()) {
            throw new IllegalArgumentException("texture staging capacity");
        }
        int copied = 0;
        for (int command = 0; command < size; command++) {
            int[][] levels = data[command];
            for (int mip = 0; mip < levels.length; mip++) {
                int width = widths[command] >> mip;
                int height = heights[command] >> mip;
                if (width <= 0 || height <= 0) break;
                int count = Math.multiplyExact(width, height);
                destination.put(levels[mip], 0, count);
                copied = Math.addExact(copied, count);
            }
        }
        return copied;
    }

    public void clear() {
        checkOpen();
        for (int i = 0; i < size; i++) data[i] = null;
        size = 0;
        mipLevels = 0;
        bytes = 0L;
    }

    public int size() { return size; }
    public int getMipLevels() { return mipLevels; }
    public long getBytes() { return bytes; }
    public long getRejected() { return rejected; }

    public boolean isClosed() { return data == null; }

    @Override public void close() {
        int[][][] owned = data;
        if (owned == null) return;
        for (int index = 0; index < size; index++) owned[index] = null;
        data = null;
        widths = null;
        heights = null;
        originX = null;
        originY = null;
        blur = null;
        clamp = null;
        size = 0;
        mipLevels = 0;
        bytes = 0L;
        CacheBudget.Reservation reservation = heapReservation;
        heapReservation = null;
        if (reservation != null) reservation.close();
    }

    public static long heapBytesForCapacity(int requestedCapacity) {
        int actual = Math.max(16, requestedCapacity);
        long bytes = RetainedHeap.referenceArray(actual);
        bytes = Math.addExact(bytes,
            Math.multiplyExact(4L, RetainedHeap.intArray(actual)));
        return Math.addExact(bytes,
            Math.multiplyExact(2L, RetainedHeap.booleanArray(actual)));
    }

    int[][] data(int index) { return data[index]; }
    int width(int index) { return widths[index]; }
    int height(int index) { return heights[index]; }
    int originX(int index) { return originX[index]; }
    int originY(int index) { return originY[index]; }
    boolean blur(int index) { return blur[index]; }
    boolean clamp(int index) { return clamp[index]; }

    public static long validatedByteCount(int[][] pixels, int width, int height) {
        if (pixels == null || width <= 0 || height <= 0
            || width > VANILLA_STAGING_INTS) return -1L;
        long integers = 0L;
        for (int mip = 0; mip < pixels.length; mip++) {
            int mipWidth = width >> mip;
            int mipHeight = height >> mip;
            if (mipWidth <= 0 || mipHeight <= 0) break;
            long count = (long) mipWidth * (long) mipHeight;
            if (count <= 0L || count > Integer.MAX_VALUE || pixels[mip] == null
                || count > pixels[mip].length
                || integers > (Long.MAX_VALUE / 4L) - count) return -1L;
            integers += count;
        }
        return integers * 4L;
    }

    public static int usableMipLevels(int[][] pixels, int width, int height) {
        int result = 0;
        for (int mip = 0; mip < pixels.length; mip++) {
            if ((width >> mip) <= 0 || (height >> mip) <= 0) break;
            result++;
        }
        return result;
    }

    private void checkOpen() {
        if (data == null) throw new IllegalStateException(
            "animation texture command queue is closed");
    }
}
