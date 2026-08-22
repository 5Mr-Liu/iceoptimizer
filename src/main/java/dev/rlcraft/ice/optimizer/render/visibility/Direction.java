package dev.rlcraft.ice.optimizer.render.visibility;

/** Minecraft 1.12 EnumFacing ordinal order. */
public enum Direction {
    DOWN(0, -1, 0),
    UP(0, 1, 0),
    NORTH(0, 0, -1),
    SOUTH(0, 0, 1),
    WEST(-1, 0, 0),
    EAST(1, 0, 0);

    private static final Direction[] CACHED = values();

    final int x;
    final int y;
    final int z;

    Direction(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Direction opposite() {
        return CACHED[ordinal() ^ 1];
    }

    static Direction[] cachedValues() { return CACHED; }
}
