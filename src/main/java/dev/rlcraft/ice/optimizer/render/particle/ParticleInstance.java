package dev.rlcraft.ice.optimizer.render.particle;

/** Immutable billboard instance; CPU simulation remains outside this type. */
public final class ParticleInstance {
    private final float x;
    private final float y;
    private final float z;
    private final float scale;
    private final float rotation;
    private final int rgba;
    private final int packedLight;
    private final float minU;
    private final float minV;
    private final float maxU;
    private final float maxV;
    private final long sequence;

    public ParticleInstance(float x, float y, float z, float scale, float rotation,
                            int rgba, int packedLight, float minU, float minV,
                            float maxU, float maxV, long sequence) {
        if (!finite(x) || !finite(y) || !finite(z) || !finite(scale)
            || !finite(rotation) || !finite(minU) || !finite(minV)
            || !finite(maxU) || !finite(maxV) || scale < 0.0F
            || sequence < 0L) throw new IllegalArgumentException("particle instance");
        this.x = x;
        this.y = y;
        this.z = z;
        this.scale = scale;
        this.rotation = rotation;
        this.rgba = rgba;
        this.packedLight = packedLight;
        this.minU = minU;
        this.minV = minV;
        this.maxU = maxU;
        this.maxV = maxV;
        this.sequence = sequence;
    }

    public float getX() { return x; }
    public float getY() { return y; }
    public float getZ() { return z; }
    public float getScale() { return scale; }
    public float getRotation() { return rotation; }
    public int getRgba() { return rgba; }
    public int getPackedLight() { return packedLight; }
    public float getMinU() { return minU; }
    public float getMinV() { return minV; }
    public float getMaxU() { return maxU; }
    public float getMaxV() { return maxV; }
    public long getSequence() { return sequence; }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
