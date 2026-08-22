package dev.rlcraft.ice.optimizer.render.visibility;

/**
 * Pure CPU cadence gate for delayed depth capture.  A depth image can only be
 * consumed by the next frame when the exact view is unchanged, so submitting
 * work for a continuously moving camera is guaranteed waste.  Geometry
 * identity deliberately does not participate here: LwjglDepthHistory owns
 * that safety generation independently and rejects stale payloads itself.
 */
final class StableViewCaptureGate {
    enum Decision {
        INVALID_INPUT,
        FIRST_OBSERVATION,
        VIEW_CHANGED,
        DUPLICATE_FRAME,
        FRAME_GAP,
        CAPTURE_ALLOWED
    }

    private long observedSignature;
    private long observedFrame = Long.MIN_VALUE;
    private int stableFrames;

    Decision observe(long frameId, long viewSignature) {
        if (frameId <= 0L) {
            invalidate();
            return Decision.INVALID_INPUT;
        }
        if (observedFrame == Long.MIN_VALUE) {
            observedSignature = viewSignature;
            observedFrame = frameId;
            stableFrames = 1;
            return Decision.FIRST_OBSERVATION;
        }
        if (observedSignature != viewSignature) {
            observedSignature = viewSignature;
            observedFrame = frameId;
            stableFrames = 1;
            return Decision.VIEW_CHANGED;
        }
        if (observedFrame == frameId) return Decision.DUPLICATE_FRAME;
        boolean consecutive = observedFrame > 0L
            && observedFrame != Long.MAX_VALUE
            && frameId == observedFrame + 1L;
        observedFrame = frameId;
        stableFrames = consecutive ? Math.min(2, stableFrames + 1) : 1;
        return stableFrames >= 2 ? Decision.CAPTURE_ALLOWED
            : Decision.FRAME_GAP;
    }

    void invalidate() {
        observedSignature = 0L;
        observedFrame = Long.MIN_VALUE;
        stableFrames = 0;
    }
}
