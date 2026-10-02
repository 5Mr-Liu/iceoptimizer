package dev.rlcraft.ice.profiler.metrics;

public final class WorldGauge {
    private final int dimension;
    private final int loadedChunks;
    private final int entities;
    private final int tileEntities;

    public WorldGauge(int dimension, int loadedChunks, int entities, int tileEntities) {
        this.dimension = dimension;
        this.loadedChunks = Math.max(0, loadedChunks);
        this.entities = Math.max(0, entities);
        this.tileEntities = Math.max(0, tileEntities);
    }

    public int getDimension() {
        return dimension;
    }

    public int getLoadedChunks() {
        return loadedChunks;
    }

    public int getEntities() {
        return entities;
    }

    public int getTileEntities() {
        return tileEntities;
    }
}
