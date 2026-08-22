package dev.rlcraft.ice.optimizer.render.particle;

public final class ParticleState {
    private final int layer;
    private final int texture;
    private final int lightmapTexture;
    private final int program;
    private final int blendSource;
    private final int blendDestination;
    private final int blendSourceAlpha;
    private final int blendDestinationAlpha;
    private final boolean blend;
    private final boolean depthTest;
    private final boolean depthMask;
    private final boolean cull;
    private final int colorMask;
    private final boolean lit;
    private final long eventScope;

    public ParticleState(int layer, int texture, int program, int blendSource,
                         int blendDestination, boolean depthMask, boolean lit,
                         long eventScope) {
        this(layer, texture, 0, program, true, blendSource, blendDestination,
            blendSource, blendDestination, true, depthMask, false, 15, lit,
            eventScope);
    }

    public ParticleState(int layer, int texture, int lightmapTexture, int program,
                         boolean blend, int blendSource, int blendDestination,
                         int blendSourceAlpha, int blendDestinationAlpha,
                         boolean depthTest, boolean depthMask, boolean cull,
                         int colorMask, boolean lit, long eventScope) {
        if (layer < 0 || texture < 0 || lightmapTexture < 0
            || program < 0 || eventScope < 0L) {
            throw new IllegalArgumentException("particle state");
        }
        this.layer = layer;
        this.texture = texture;
        this.lightmapTexture = lightmapTexture;
        this.program = program;
        this.blend = blend;
        this.blendSource = blendSource;
        this.blendDestination = blendDestination;
        this.blendSourceAlpha = blendSourceAlpha;
        this.blendDestinationAlpha = blendDestinationAlpha;
        this.depthTest = depthTest;
        this.depthMask = depthMask;
        this.cull = cull;
        this.colorMask = colorMask & 15;
        this.lit = lit;
        this.eventScope = eventScope;
    }

    public int getLayer() { return layer; }
    public int getTexture() { return texture; }
    public int getLightmapTexture() { return lightmapTexture; }
    public int getProgram() { return program; }
    public boolean isBlend() { return blend; }
    public int getBlendSource() { return blendSource; }
    public int getBlendDestination() { return blendDestination; }
    public int getBlendSourceAlpha() { return blendSourceAlpha; }
    public int getBlendDestinationAlpha() { return blendDestinationAlpha; }
    public boolean isDepthTest() { return depthTest; }
    public boolean isDepthMask() { return depthMask; }
    public boolean isCull() { return cull; }
    public int getColorMask() { return colorMask; }
    public boolean isLit() { return lit; }
    public long getEventScope() { return eventScope; }

    @Override public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof ParticleState)) return false;
        ParticleState other = (ParticleState) value;
        return layer == other.layer && texture == other.texture
            && lightmapTexture == other.lightmapTexture
            && program == other.program && blend == other.blend
            && blendSource == other.blendSource
            && blendDestination == other.blendDestination
            && blendSourceAlpha == other.blendSourceAlpha
            && blendDestinationAlpha == other.blendDestinationAlpha
            && depthTest == other.depthTest && depthMask == other.depthMask
            && cull == other.cull && colorMask == other.colorMask && lit == other.lit
            && eventScope == other.eventScope;
    }

    @Override public int hashCode() {
        int result = layer;
        result = 31 * result + texture;
        result = 31 * result + lightmapTexture;
        result = 31 * result + program;
        result = 31 * result + (blend ? 1 : 0);
        result = 31 * result + blendSource;
        result = 31 * result + blendDestination;
        result = 31 * result + blendSourceAlpha;
        result = 31 * result + blendDestinationAlpha;
        result = 31 * result + (depthTest ? 1 : 0);
        result = 31 * result + (depthMask ? 1 : 0);
        result = 31 * result + (cull ? 1 : 0);
        result = 31 * result + colorMask;
        result = 31 * result + (lit ? 1 : 0);
        return 31 * result + (int) (eventScope ^ (eventScope >>> 32));
    }
}
