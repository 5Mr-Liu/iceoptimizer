package dev.rlcraft.ice.optimizer.render.visibility;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.memory.RetainedHeap;
import java.util.Arrays;

/** Continuous primitive storage and allocation-free BFS for section visibility. */
public final class PrimitiveSectionGrid implements AutoCloseable {
    public interface Frustum {
        boolean visible(double minX, double minY, double minZ,
                        double maxX, double maxY, double maxZ);
    }

    private static final int MAX_SECTIONS = 1 << 20;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private boolean[] occupied;
    private long[] connectivity;
    private double[] bounds;
    private int[] visits;
    private int[] queue;
    private byte[] queueIncoming;
    private byte[] queueDirections;
    private CacheBudget.Reservation heapReservation;
    private int visitGeneration = 1;

    public PrimitiveSectionGrid(int minX, int minY, int minZ,
                                int sizeX, int sizeY, int sizeZ) {
        this(minX, minY, minZ, sizeX, sizeY, sizeZ, null);
    }

    /** Production constructor which charges every fixed grid/BFS array. */
    public PrimitiveSectionGrid(int minX, int minY, int minZ,
                                int sizeX, int sizeY, int sizeZ,
                                CacheBudget budget) {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            throw new IllegalArgumentException("section grid bounds");
        }
        long count;
        try {
            count = Math.multiplyExact(Math.multiplyExact((long) sizeX,
                (long) sizeY), (long) sizeZ);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("section grid bounds", overflow);
        }
        if (count > MAX_SECTIONS) throw new IllegalArgumentException("section grid bounds");
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        int cells = (int) count;
        CacheBudget.Reservation reservation = RetainedHeap.reserve(budget,
            heapBytesForCells(cells), "primitive section grid");
        try {
            occupied = new boolean[cells];
            connectivity = new long[cells];
            bounds = new double[Math.multiplyExact(cells, 6)];
            visits = new int[cells];
            queue = new int[cells];
            queueIncoming = new byte[cells];
            queueDirections = new byte[cells];
            heapReservation = reservation;
        } catch (RuntimeException | Error failure) {
            reservation.close();
            throw failure;
        }
    }

    public int indexOf(int x, int y, int z) {
        long localX = (long) x - minX;
        long localY = (long) y - minY;
        long localZ = (long) z - minZ;
        if (localX < 0 || localX >= sizeX || localY < 0 || localY >= sizeY
            || localZ < 0 || localZ >= sizeZ) return -1;
        return (int) ((localY * sizeZ + localZ) * sizeX + localX);
    }

    public void set(int x, int y, int z, long visibilityMask,
                    double minBoundX, double minBoundY, double minBoundZ,
                    double maxBoundX, double maxBoundY, double maxBoundZ) {
        checkOpen();
        int index = indexOf(x, y, z);
        if (index < 0 || !finite(minBoundX) || !finite(minBoundY)
            || !finite(minBoundZ) || !finite(maxBoundX) || !finite(maxBoundY)
            || !finite(maxBoundZ) || minBoundX > maxBoundX
            || minBoundY > maxBoundY || minBoundZ > maxBoundZ) {
            throw new IllegalArgumentException("section");
        }
        occupied[index] = true;
        connectivity[index] = visibilityMask & ((1L << 36) - 1L);
        int offset = index * 6;
        bounds[offset] = minBoundX;
        bounds[offset + 1] = minBoundY;
        bounds[offset + 2] = minBoundZ;
        bounds[offset + 3] = maxBoundX;
        bounds[offset + 4] = maxBoundY;
        bounds[offset + 5] = maxBoundZ;
    }

    public void clear(int x, int y, int z) {
        checkOpen();
        int index = indexOf(x, y, z);
        if (index >= 0) {
            occupied[index] = false;
            connectivity[index] = 0L;
        }
    }

    /**
     * Writes visible section indices in the original six-direction BFS order.
     * Returns the written count; output pressure conservatively stops traversal.
     */
    public int collectVisible(int startX, int startY, int startZ,
                              Frustum frustum, boolean spectator,
                              int[] output) {
        checkOpen();
        if (frustum == null || output == null) throw new IllegalArgumentException("visibility output");
        int start = indexOf(startX, startY, startZ);
        if (start < 0 || !occupied[start] || output.length == 0) return 0;
        int stamp = nextVisitGeneration();
        int head = 0;
        int tail = 0;
        int written = 0;
        queue[tail] = start;
        queueIncoming[tail++] = -1;
        queueDirections[0] = 0;
        visits[start] = stamp;
        Direction[] directions = Direction.cachedValues();
        while (head < tail && written < output.length) {
            int current = queue[head];
            int incoming = queueIncoming[head];
            int pathDirections = queueDirections[head++] & 0xff;
            int bound = current * 6;
            if (!frustum.visible(bounds[bound], bounds[bound + 1], bounds[bound + 2],
                bounds[bound + 3], bounds[bound + 4], bounds[bound + 5])) continue;
            output[written++] = current;
            int localX = current % sizeX;
            int yz = current / sizeX;
            int localZ = yz % sizeZ;
            int localY = yz / sizeZ;
            int worldX = minX + localX;
            int worldY = minY + localY;
            int worldZ = minZ + localZ;
            for (Direction direction : directions) {
                int outgoing = direction.ordinal();
                if (!spectator) {
                    if ((pathDirections & 1 << direction.opposite().ordinal()) != 0) continue;
                    if (incoming >= 0
                        && !connected(connectivity[current], incoming, outgoing)) continue;
                }
                int neighbor = indexOf(worldX + direction.x, worldY + direction.y,
                    worldZ + direction.z);
                if (neighbor < 0 || !occupied[neighbor] || visits[neighbor] == stamp) continue;
                visits[neighbor] = stamp;
                queue[tail] = neighbor;
                queueIncoming[tail] = (byte) direction.opposite().ordinal();
                queueDirections[tail++] = (byte) (pathDirections | 1 << outgoing);
            }
        }
        return written;
    }

    public static long connect(long mask, Direction from, Direction to) {
        if (from == null || to == null) throw new IllegalArgumentException("direction");
        return mask | 1L << (from.ordinal() * 6 + to.ordinal());
    }

    public static boolean connected(long mask, int from, int to) {
        if (from < 0 || from >= 6 || to < 0 || to >= 6) return false;
        return (mask & 1L << (from * 6 + to)) != 0L;
    }

    public int[] coordinates(int index) {
        checkOpen();
        if (index < 0 || index >= occupied.length) throw new IllegalArgumentException("index");
        int localX = index % sizeX;
        int yz = index / sizeX;
        int localZ = yz % sizeZ;
        int localY = yz / sizeZ;
        return new int[] { minX + localX, minY + localY, minZ + localZ };
    }

    public boolean isClosed() { return occupied == null; }

    @Override public void close() {
        if (occupied == null) return;
        occupied = null;
        connectivity = null;
        bounds = null;
        visits = null;
        queue = null;
        queueIncoming = null;
        queueDirections = null;
        CacheBudget.Reservation reservation = heapReservation;
        heapReservation = null;
        if (reservation != null) reservation.close();
    }

    public static long heapBytesForCells(int cells) {
        if (cells < 0 || cells > MAX_SECTIONS) {
            throw new IllegalArgumentException("section grid bounds");
        }
        long bytes = RetainedHeap.booleanArray(cells);
        bytes = Math.addExact(bytes, RetainedHeap.longArray(cells));
        bytes = Math.addExact(bytes, RetainedHeap.doubleArray(
            Math.multiplyExact(cells, 6)));
        bytes = Math.addExact(bytes,
            Math.multiplyExact(2L, RetainedHeap.intArray(cells)));
        return Math.addExact(bytes,
            Math.multiplyExact(2L, RetainedHeap.byteArray(cells)));
    }

    private int nextVisitGeneration() {
        if (visitGeneration == Integer.MAX_VALUE) {
            Arrays.fill(visits, 0);
            visitGeneration = 1;
        }
        return visitGeneration++;
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private void checkOpen() {
        if (occupied == null) throw new IllegalStateException(
            "primitive section grid is closed");
    }
}
