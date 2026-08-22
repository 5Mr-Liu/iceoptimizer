package dev.rlcraft.ice.optimizer.runtime;

import java.util.concurrent.atomic.AtomicLong;

/** Generation counters prevent late async results from entering a new world or GL/resource context. */
public final class ClientEpochs {
    private final AtomicLong frameId = new AtomicLong();
    private final AtomicLong clientTickId = new AtomicLong();
    private final AtomicLong worldGeneration = new AtomicLong(1L);
    private final AtomicLong resourceGeneration = new AtomicLong(1L);
    private final AtomicLong atlasGeneration = new AtomicLong(1L);
    private final AtomicLong glContextGeneration = new AtomicLong(1L);
    private final AtomicLong shaderPackGeneration = new AtomicLong(1L);
    private final AtomicLong shaderPermutationGeneration = new AtomicLong(1L);
    private final AtomicLong vertexFormatGeneration = new AtomicLong(1L);
    private final AtomicLong viewFrustumGeneration = new AtomicLong(1L);

    public long nextFrame() { return increment(frameId, "frame"); }
    public long nextClientTick() { return increment(clientTickId, "client tick"); }
    public long invalidateWorld() { return increment(worldGeneration, "world generation"); }
    public long invalidateResources() {
        return increment(resourceGeneration, "resource generation");
    }
    public long invalidateAtlas() { return increment(atlasGeneration, "atlas generation"); }
    public long invalidateGlContext() {
        return increment(glContextGeneration, "GL context generation");
    }
    public long invalidateShaderPack() {
        return increment(shaderPackGeneration, "ShaderPack generation");
    }
    public long invalidateShaderPermutation() {
        return increment(shaderPermutationGeneration,
            "shader permutation generation");
    }
    public long invalidateVertexFormat() {
        return increment(vertexFormatGeneration, "vertex format generation");
    }
    public long invalidateViewFrustum() {
        return increment(viewFrustumGeneration, "view frustum generation");
    }

    public long currentFrameId() { return frameId.get(); }
    public long currentClientTickId() { return clientTickId.get(); }
    public long currentWorldGeneration() { return worldGeneration.get(); }
    public long currentResourceGeneration() { return resourceGeneration.get(); }
    public long currentAtlasGeneration() { return atlasGeneration.get(); }
    public long currentGlContextGeneration() { return glContextGeneration.get(); }
    public long currentShaderPackGeneration() { return shaderPackGeneration.get(); }
    public long currentShaderPermutationGeneration() { return shaderPermutationGeneration.get(); }
    public long currentVertexFormatGeneration() { return vertexFormatGeneration.get(); }
    public long currentViewFrustumGeneration() { return viewFrustumGeneration.get(); }

    public EpochToken snapshot() {
        return new EpochToken(frameId.get(), clientTickId.get(), worldGeneration.get(),
            resourceGeneration.get(), glContextGeneration.get(), shaderPackGeneration.get(),
            shaderPermutationGeneration.get(), vertexFormatGeneration.get(),
            viewFrustumGeneration.get());
    }

    public boolean isCurrent(EpochToken token, int mask) {
        if (token == null) return mask == EpochMask.NONE;
        if ((mask & EpochMask.FRAME) != 0 && token.getFrameId() != frameId.get()) return false;
        if ((mask & EpochMask.CLIENT_TICK) != 0 && token.getClientTickId() != clientTickId.get()) return false;
        if ((mask & EpochMask.WORLD) != 0 && token.getWorldGeneration() != worldGeneration.get()) return false;
        if ((mask & EpochMask.RESOURCE) != 0 && token.getResourceGeneration() != resourceGeneration.get()) return false;
        if ((mask & EpochMask.GL_CONTEXT) != 0 && token.getGlContextGeneration() != glContextGeneration.get()) return false;
        if ((mask & EpochMask.SHADER_PACK) != 0 && token.getShaderPackGeneration() != shaderPackGeneration.get()) return false;
        if ((mask & EpochMask.SHADER_PERMUTATION) != 0
            && token.getShaderPermutationGeneration() != shaderPermutationGeneration.get()) return false;
        if ((mask & EpochMask.VERTEX_FORMAT) != 0
            && token.getVertexFormatGeneration() != vertexFormatGeneration.get()) return false;
        return (mask & EpochMask.VIEW_FRUSTUM) == 0
            || token.getViewFrustumGeneration() == viewFrustumGeneration.get();
    }

    /** Never wraps a generation/counter into a value which can alias old work. */
    static long increment(AtomicLong counter, String label) {
        if (counter == null) throw new IllegalArgumentException("epoch counter");
        while (true) {
            long current = counter.get();
            if (current < 0L || current == Long.MAX_VALUE) {
                throw new IllegalStateException((label == null ? "epoch" : label)
                    + " exhausted");
            }
            long next = current + 1L;
            if (counter.compareAndSet(current, next)) return next;
        }
    }
}
