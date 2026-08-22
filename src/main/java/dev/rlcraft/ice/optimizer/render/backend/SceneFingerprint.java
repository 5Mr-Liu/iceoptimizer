package dev.rlcraft.ice.optimizer.render.backend;

/**
 * Workload-only pairing key. It intentionally contains no vendor, model,
 * core-count, integrated/discrete or other hardware identity.
 */
public final class SceneFingerprint {
    private final int dimension;
    private final int regionX;
    private final int regionY;
    private final int regionZ;
    private final int yawBucket;
    private final int pitchBucket;
    private final int visibleSections;
    private final int visibleEntities;
    private final int visibleTileEntities;
    private final int viewDistance;
    private final int width;
    private final int height;
    private final int weatherFlags;
    private final long resourceGeneration;
    private final long shaderGeneration;
    private final int terrainArenaOwned;
    private final int terrainRegionRuns;

    public SceneFingerprint(int dimension, int regionX, int regionY, int regionZ,
                            int yawBucket, int pitchBucket, int visibleSections,
                            int visibleEntities, int visibleTileEntities,
                            int viewDistance, int width, int height, int weatherFlags,
                            long resourceGeneration, long shaderGeneration) {
        this(dimension, regionX, regionY, regionZ, yawBucket, pitchBucket,
            visibleSections, visibleEntities, visibleTileEntities,
            viewDistance, width, height, weatherFlags, resourceGeneration,
            shaderGeneration, -1, -1);
    }

    /** Terrain measurements additionally bind ownership and batching shape. */
    public SceneFingerprint(int dimension, int regionX, int regionY, int regionZ,
                            int yawBucket, int pitchBucket, int visibleSections,
                            int visibleEntities, int visibleTileEntities,
                            int viewDistance, int width, int height, int weatherFlags,
                            long resourceGeneration, long shaderGeneration,
                            int terrainArenaOwned, int terrainRegionRuns) {
        this.dimension = dimension;
        this.regionX = regionX;
        this.regionY = regionY;
        this.regionZ = regionZ;
        this.yawBucket = yawBucket;
        this.pitchBucket = pitchBucket;
        this.visibleSections = Math.max(0, visibleSections);
        this.visibleEntities = Math.max(0, visibleEntities);
        this.visibleTileEntities = Math.max(0, visibleTileEntities);
        this.viewDistance = Math.max(0, viewDistance);
        this.width = Math.max(0, width);
        this.height = Math.max(0, height);
        this.weatherFlags = weatherFlags;
        this.resourceGeneration = resourceGeneration;
        this.shaderGeneration = shaderGeneration;
        this.terrainArenaOwned = terrainArenaOwned < 0 ? -1
            : Math.min(this.visibleSections, terrainArenaOwned);
        this.terrainRegionRuns = terrainRegionRuns < 0 ? -1
            : Math.min(this.terrainArenaOwned, terrainRegionRuns);
    }

    /** Workload-only key for an animation-atlas traversal outside a view pass. */
    public static SceneFingerprint textureWorkload(int commands, int mipLevels,
                                                   long bytes, int customSprites,
                                                   long resourceGeneration,
                                                   long shaderGeneration) {
        int byteBucket = bytes <= 0L ? 0 : (int) Math.min(Integer.MAX_VALUE,
            (bytes + 4095L) >>> 12);
        return new SceneFingerprint(Integer.MIN_VALUE, commands, mipLevels,
            byteBucket, customSprites, 0, 0, 0, 0, 0, 0, 0, 0,
            resourceGeneration, shaderGeneration);
    }

    /**
     * Coarse logarithmic entity-load class used by the online benefit loop.
     * The exact count remains part of the pairing fingerprint; this class is
     * only an outer profile selector, so a result learned in an ordinary
     * scene cannot permanently decide a much denser scene.
     */
    public int entityLoadBucket() {
        return logarithmicLoadBucket(visibleEntities, 16);
    }

    /** Coarse logarithmic tile-entity-load class for the TESR backend. */
    public int tileEntityLoadBucket() {
        return logarithmicLoadBucket(visibleTileEntities, 8);
    }

    /** Coarse visible-section class used for bounded terrain remeasurement. */
    public int terrainLoadBucket() {
        return logarithmicLoadBucket(visibleSections, 64);
    }

    static int logarithmicLoadBucket(int count, int firstBoundary) {
        if (count < firstBoundary) return 0;
        int scaled = count / Math.max(1, firstBoundary);
        int bucket = 1 + 31 - Integer.numberOfLeadingZeros(scaled);
        return Math.min(7, bucket);
    }

    /**
     * Pairing compatibility for adjacent render samples.  World/view buckets
     * and generations remain exact, while the three naturally jittering
     * visibility counts may move by roughly three percent (with a floor of
     * two).  Synthetic texture workloads deliberately retain exact identity.
     */
    public boolean isPairingCompatible(SceneFingerprint other) {
        if (other == null) return false;
        if (dimension == Integer.MIN_VALUE
            || other.dimension == Integer.MIN_VALUE) return equals(other);
        return dimension == other.dimension && regionX == other.regionX
            && regionY == other.regionY && regionZ == other.regionZ
            && yawBucket == other.yawBucket && pitchBucket == other.pitchBucket
            && withinNaturalJitter(visibleSections, other.visibleSections)
            && withinNaturalJitter(visibleEntities, other.visibleEntities)
            && withinNaturalJitter(visibleTileEntities,
                other.visibleTileEntities)
            && matchingTerrainCoverage(other)
            && viewDistance == other.viewDistance && width == other.width
            && height == other.height && weatherFlags == other.weatherFlags
            && resourceGeneration == other.resourceGeneration
            && shaderGeneration == other.shaderGeneration;
    }

    private boolean matchingTerrainCoverage(SceneFingerprint other) {
        if (terrainArenaOwned < 0 || other.terrainArenaOwned < 0) {
            return terrainArenaOwned == other.terrainArenaOwned
                && terrainRegionRuns == other.terrainRegionRuns;
        }
        return withinNaturalJitter(terrainArenaOwned,
                other.terrainArenaOwned)
            && withinNaturalJitter(terrainRegionRuns,
                other.terrainRegionRuns);
    }

    private static boolean withinNaturalJitter(int first, int second) {
        int tolerance = Math.max(2, Math.max(first, second) / 32);
        return Math.abs((long) first - second) <= tolerance;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof SceneFingerprint)) return false;
        SceneFingerprint other = (SceneFingerprint) value;
        return dimension == other.dimension && regionX == other.regionX
            && regionY == other.regionY && regionZ == other.regionZ
            && yawBucket == other.yawBucket && pitchBucket == other.pitchBucket
            && visibleSections == other.visibleSections
            && visibleEntities == other.visibleEntities
            && visibleTileEntities == other.visibleTileEntities
            && viewDistance == other.viewDistance && width == other.width
            && height == other.height && weatherFlags == other.weatherFlags
            && resourceGeneration == other.resourceGeneration
            && shaderGeneration == other.shaderGeneration
            && terrainArenaOwned == other.terrainArenaOwned
            && terrainRegionRuns == other.terrainRegionRuns;
    }

    @Override
    public int hashCode() {
        int result = dimension;
        result = 31 * result + regionX;
        result = 31 * result + regionY;
        result = 31 * result + regionZ;
        result = 31 * result + yawBucket;
        result = 31 * result + pitchBucket;
        result = 31 * result + visibleSections;
        result = 31 * result + visibleEntities;
        result = 31 * result + visibleTileEntities;
        result = 31 * result + viewDistance;
        result = 31 * result + width;
        result = 31 * result + height;
        result = 31 * result + weatherFlags;
        result = 31 * result + (int) (resourceGeneration ^ (resourceGeneration >>> 32));
        result = 31 * result + (int) (shaderGeneration ^ (shaderGeneration >>> 32));
        result = 31 * result + terrainArenaOwned;
        result = 31 * result + terrainRegionRuns;
        return result;
    }
}
