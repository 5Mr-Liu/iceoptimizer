package dev.rlcraft.ice.optimizer.render.terrain;

public enum TerrainLayer {
    SOLID(false),
    CUTOUT_MIPPED(false),
    CUTOUT(false),
    TRANSLUCENT(true);

    private final boolean translucent;

    TerrainLayer(boolean translucent) { this.translucent = translucent; }
    public boolean isTranslucent() { return translucent; }
}
