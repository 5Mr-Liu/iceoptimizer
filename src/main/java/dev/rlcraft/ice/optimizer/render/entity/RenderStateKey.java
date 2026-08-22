package dev.rlcraft.ice.optimizer.render.entity;

import dev.rlcraft.ice.optimizer.render.frame.RenderPass;

/** State equality key; only exactly equal consecutive packets may batch. */
public final class RenderStateKey {
    private final RenderPass pass;
    private final int program;
    private final int texture;
    private final int lightmapTexture;
    private final int blendSource;
    private final int blendDestination;
    private final int blendSourceAlpha;
    private final int blendDestinationAlpha;
    private final boolean blend;
    private final boolean depthTest;
    private final boolean depthMask;
    private final boolean cull;
    private final int colorMask;

    public RenderStateKey(RenderPass pass, int program, int texture,
                          int lightmapTexture, boolean blend, int blendSource,
                          int blendDestination, boolean depthTest,
                          boolean depthMask, boolean cull, int colorMask) {
        this(pass, program, texture, lightmapTexture, blend, blendSource,
            blendDestination, blendSource, blendDestination, depthTest,
            depthMask, cull, colorMask);
    }

    public RenderStateKey(RenderPass pass, int program, int texture,
                          int lightmapTexture, boolean blend, int blendSource,
                          int blendDestination, int blendSourceAlpha,
                          int blendDestinationAlpha, boolean depthTest,
                          boolean depthMask, boolean cull, int colorMask) {
        if (pass == null || program < 0 || texture < 0 || lightmapTexture < 0) {
            throw new IllegalArgumentException("render state");
        }
        this.pass = pass;
        this.program = program;
        this.texture = texture;
        this.lightmapTexture = lightmapTexture;
        this.blend = blend;
        this.blendSource = blendSource;
        this.blendDestination = blendDestination;
        this.blendSourceAlpha = blendSourceAlpha;
        this.blendDestinationAlpha = blendDestinationAlpha;
        this.depthTest = depthTest;
        this.depthMask = depthMask;
        this.cull = cull;
        this.colorMask = colorMask & 15;
    }

    public RenderPass getPass() { return pass; }
    public int getProgram() { return program; }
    public int getTexture() { return texture; }
    public int getLightmapTexture() { return lightmapTexture; }
    public boolean isBlend() { return blend; }
    public int getBlendSource() { return blendSource; }
    public int getBlendDestination() { return blendDestination; }
    public int getBlendSourceAlpha() { return blendSourceAlpha; }
    public int getBlendDestinationAlpha() { return blendDestinationAlpha; }
    public boolean isDepthTest() { return depthTest; }
    public boolean isDepthMask() { return depthMask; }
    public boolean isCull() { return cull; }
    public int getColorMask() { return colorMask; }

    @Override public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof RenderStateKey)) return false;
        RenderStateKey other = (RenderStateKey) value;
        return pass == other.pass && program == other.program
            && texture == other.texture && lightmapTexture == other.lightmapTexture
            && blendSource == other.blendSource
            && blendDestination == other.blendDestination
            && blendSourceAlpha == other.blendSourceAlpha
            && blendDestinationAlpha == other.blendDestinationAlpha
            && blend == other.blend
            && depthTest == other.depthTest && depthMask == other.depthMask
            && cull == other.cull && colorMask == other.colorMask;
    }

    @Override public int hashCode() {
        int result = pass.ordinal();
        result = 31 * result + program;
        result = 31 * result + texture;
        result = 31 * result + lightmapTexture;
        result = 31 * result + blendSource;
        result = 31 * result + blendDestination;
        result = 31 * result + blendSourceAlpha;
        result = 31 * result + blendDestinationAlpha;
        result = 31 * result + (blend ? 1 : 0);
        result = 31 * result + (depthTest ? 1 : 0);
        result = 31 * result + (depthMask ? 1 : 0);
        result = 31 * result + (cull ? 1 : 0);
        return 31 * result + colorMask;
    }
}
