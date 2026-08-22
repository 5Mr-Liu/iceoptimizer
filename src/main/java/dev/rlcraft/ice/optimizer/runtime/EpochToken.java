package dev.rlcraft.ice.optimizer.runtime;

public final class EpochToken {
    private final long frameId;
    private final long clientTickId;
    private final long worldGeneration;
    private final long resourceGeneration;
    private final long glContextGeneration;
    private final long shaderPackGeneration;
    private final long shaderPermutationGeneration;
    private final long vertexFormatGeneration;
    private final long viewFrustumGeneration;

    EpochToken(long frameId, long clientTickId, long worldGeneration, long resourceGeneration,
               long glContextGeneration, long shaderPackGeneration,
               long shaderPermutationGeneration, long vertexFormatGeneration,
               long viewFrustumGeneration) {
        this.frameId = frameId;
        this.clientTickId = clientTickId;
        this.worldGeneration = worldGeneration;
        this.resourceGeneration = resourceGeneration;
        this.glContextGeneration = glContextGeneration;
        this.shaderPackGeneration = shaderPackGeneration;
        this.shaderPermutationGeneration = shaderPermutationGeneration;
        this.vertexFormatGeneration = vertexFormatGeneration;
        this.viewFrustumGeneration = viewFrustumGeneration;
    }

    public long getFrameId() { return frameId; }
    public long getClientTickId() { return clientTickId; }
    public long getWorldGeneration() { return worldGeneration; }
    public long getResourceGeneration() { return resourceGeneration; }
    public long getGlContextGeneration() { return glContextGeneration; }
    public long getShaderPackGeneration() { return shaderPackGeneration; }
    public long getShaderPermutationGeneration() { return shaderPermutationGeneration; }
    public long getVertexFormatGeneration() { return vertexFormatGeneration; }
    public long getViewFrustumGeneration() { return viewFrustumGeneration; }
}
