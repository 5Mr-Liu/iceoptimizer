package dev.rlcraft.ice.optimizer.render.telemetry;

import dev.rlcraft.ice.optimizer.render.backend.RenderBackendId;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.frame.RenderPass;

/** Full correlation key; equality intentionally includes every render generation. */
public final class RenderProfileKey {
    private final FrameStamp stamp;
    private final RenderPass pass;
    private final RenderBackendId backend;

    public RenderProfileKey(FrameStamp stamp, RenderPass pass, RenderBackendId backend) {
        if (stamp == null || pass == null || backend == null) {
            throw new IllegalArgumentException("profile key");
        }
        this.stamp = stamp;
        this.pass = pass;
        this.backend = backend;
    }

    public FrameStamp getStamp() { return stamp; }
    public RenderPass getPass() { return pass; }
    public RenderBackendId getBackend() { return backend; }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof RenderProfileKey)) return false;
        RenderProfileKey other = (RenderProfileKey) value;
        FrameStamp left = stamp;
        FrameStamp right = other.stamp;
        return pass == other.pass && backend == other.backend
            && left.getFrameId() == right.getFrameId()
            && left.getViewId() == right.getViewId()
            && left.getWorldGeneration() == right.getWorldGeneration()
            && left.getResourceGeneration() == right.getResourceGeneration()
            && left.getGlContextGeneration() == right.getGlContextGeneration()
            && left.getShaderPackGeneration() == right.getShaderPackGeneration()
            && left.getShaderPermutationGeneration() == right.getShaderPermutationGeneration()
            && left.getVertexFormatGeneration() == right.getVertexFormatGeneration()
            && left.getViewFrustumGeneration() == right.getViewFrustumGeneration();
    }

    @Override
    public int hashCode() {
        long value = stamp.getFrameId() * 31L + stamp.getViewId();
        value = value * 31L + stamp.getWorldGeneration();
        value = value * 31L + stamp.getResourceGeneration();
        value = value * 31L + stamp.getGlContextGeneration();
        value = value * 31L + stamp.getShaderPackGeneration();
        value = value * 31L + stamp.getShaderPermutationGeneration();
        value = value * 31L + stamp.getVertexFormatGeneration();
        value = value * 31L + stamp.getViewFrustumGeneration();
        value = value * 31L + pass.ordinal();
        value = value * 31L + backend.ordinal();
        return (int) (value ^ (value >>> 32));
    }
}
