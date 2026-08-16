package dev.rlcraft.ice.optimizer.compat.chunk;

/** Immutable low-overhead snapshot shown only on the vanilla F3 debug screen. */
public final class ChunkRenderStatus {
    private final int vanillaWorkers;
    private final int effectiveWorkers;
    private final int renderBuilders;
    private final long sortedQuads;
    private final long gpuUploads;
    private final long uploadFallbacks;
    private final String gpuBackend;

    public ChunkRenderStatus(int vanillaWorkers, int effectiveWorkers, int renderBuilders,
                             long sortedQuads, long gpuUploads, long uploadFallbacks,
                             String gpuBackend) {
        this.vanillaWorkers = vanillaWorkers;
        this.effectiveWorkers = effectiveWorkers;
        this.renderBuilders = renderBuilders;
        this.sortedQuads = sortedQuads;
        this.gpuUploads = gpuUploads;
        this.uploadFallbacks = uploadFallbacks;
        this.gpuBackend = gpuBackend == null ? "UNSEEN" : gpuBackend;
    }

    public int getVanillaWorkers() { return vanillaWorkers; }
    public int getEffectiveWorkers() { return effectiveWorkers; }
    public int getRenderBuilders() { return renderBuilders; }
    public long getSortedQuads() { return sortedQuads; }
    public long getGpuUploads() { return gpuUploads; }
    public long getUploadFallbacks() { return uploadFallbacks; }
    public String getGpuBackend() { return gpuBackend; }
}
