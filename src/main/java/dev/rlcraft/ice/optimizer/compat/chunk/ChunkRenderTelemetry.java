package dev.rlcraft.ice.optimizer.compat.chunk;

public final class ChunkRenderTelemetry {
    private ChunkRenderTelemetry() {
    }

    public static ChunkRenderStatus snapshot() {
        return new ChunkRenderStatus(ChunkRenderPolicyBridge.vanillaWorkers(),
            ChunkRenderPolicyBridge.workers(), ChunkRenderPolicyBridge.builders(),
            ChunkPrimitiveSortBridge.sortedQuads(), ChunkVboUploadBridge.gpuUploads(),
            ChunkVboUploadBridge.fallbacks(), ChunkVboUploadBridge.backend());
    }
}
