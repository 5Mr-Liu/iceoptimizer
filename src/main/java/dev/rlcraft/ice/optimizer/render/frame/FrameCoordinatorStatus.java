package dev.rlcraft.ice.optimizer.render.frame;

import dev.rlcraft.ice.optimizer.render.backend.RenderBackendId;

public final class FrameCoordinatorStatus {
    private final boolean frameActive;
    private final long frameId;
    private final long viewId;
    private final RenderPass pass;
    private final RenderBackendId backend;
    private final int viewDepth;
    private final long barriers;
    private final long failures;
    private final long observedDeviations;

    FrameCoordinatorStatus(boolean frameActive, long frameId, long viewId,
                           RenderPass pass, RenderBackendId backend, int viewDepth,
                           long barriers, long failures,
                           long observedDeviations) {
        this.frameActive = frameActive;
        this.frameId = frameId;
        this.viewId = viewId;
        this.pass = pass;
        this.backend = backend;
        this.viewDepth = viewDepth;
        this.barriers = barriers;
        this.failures = failures;
        this.observedDeviations = observedDeviations;
    }

    public boolean isFrameActive() { return frameActive; }
    public long getFrameId() { return frameId; }
    public long getViewId() { return viewId; }
    public RenderPass getPass() { return pass; }
    public RenderBackendId getBackend() { return backend; }
    public int getViewDepth() { return viewDepth; }
    public long getBarriers() { return barriers; }
    public long getFailures() { return failures; }
    public long getObservedDeviations() { return observedDeviations; }
}
