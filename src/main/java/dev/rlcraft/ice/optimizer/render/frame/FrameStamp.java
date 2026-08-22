package dev.rlcraft.ice.optimizer.render.frame;

import dev.rlcraft.ice.optimizer.runtime.EpochToken;

/** Correlation key shared by CPU/GPU telemetry and every render packet. */
public final class FrameStamp {
    private final long frameId;
    private final long viewId;
    private final long worldGeneration;
    private final long resourceGeneration;
    private final long glContextGeneration;
    private final long shaderPackGeneration;
    private final long shaderPermutationGeneration;
    private final long vertexFormatGeneration;
    private final long viewFrustumGeneration;

    public FrameStamp(long frameId, long viewId, EpochToken token) {
        if (frameId <= 0L) throw new IllegalArgumentException("frameId");
        if (viewId <= 0L) throw new IllegalArgumentException("viewId");
        if (token == null) throw new IllegalArgumentException("token");
        this.frameId = frameId;
        this.viewId = viewId;
        worldGeneration = token.getWorldGeneration();
        resourceGeneration = token.getResourceGeneration();
        glContextGeneration = token.getGlContextGeneration();
        shaderPackGeneration = token.getShaderPackGeneration();
        shaderPermutationGeneration = token.getShaderPermutationGeneration();
        vertexFormatGeneration = token.getVertexFormatGeneration();
        viewFrustumGeneration = token.getViewFrustumGeneration();
    }

    public long getFrameId() { return frameId; }
    public long getViewId() { return viewId; }
    public long getWorldGeneration() { return worldGeneration; }
    public long getResourceGeneration() { return resourceGeneration; }
    public long getGlContextGeneration() { return glContextGeneration; }
    public long getShaderPackGeneration() { return shaderPackGeneration; }
    public long getShaderPermutationGeneration() { return shaderPermutationGeneration; }
    public long getVertexFormatGeneration() { return vertexFormatGeneration; }
    public long getViewFrustumGeneration() { return viewFrustumGeneration; }
}
