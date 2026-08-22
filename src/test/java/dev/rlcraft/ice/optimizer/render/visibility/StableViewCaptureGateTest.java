package dev.rlcraft.ice.optimizer.render.visibility;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class StableViewCaptureGateTest {
    @Test
    public void requiresTwoConsecutiveFramesWithTheSameExactView() {
        StableViewCaptureGate gate = new StableViewCaptureGate();
        assertEquals(StableViewCaptureGate.Decision.FIRST_OBSERVATION,
            gate.observe(10L, 100L));
        assertEquals(StableViewCaptureGate.Decision.DUPLICATE_FRAME,
            gate.observe(10L, 100L));
        assertEquals(StableViewCaptureGate.Decision.CAPTURE_ALLOWED,
            gate.observe(11L, 100L));
    }

    @Test
    public void motionFrameGapAndExplicitInvalidationRestartCadence() {
        StableViewCaptureGate gate = new StableViewCaptureGate();
        gate.observe(1L, 10L);
        assertEquals(StableViewCaptureGate.Decision.VIEW_CHANGED,
            gate.observe(2L, 11L));
        assertEquals(StableViewCaptureGate.Decision.FRAME_GAP,
            gate.observe(4L, 11L));
        assertEquals(StableViewCaptureGate.Decision.CAPTURE_ALLOWED,
            gate.observe(5L, 11L));
        gate.invalidate();
        assertEquals(StableViewCaptureGate.Decision.FIRST_OBSERVATION,
            gate.observe(6L, 11L));
        assertEquals(StableViewCaptureGate.Decision.VIEW_CHANGED,
            gate.observe(7L, 12L));
    }

    @Test
    public void invalidInputCannotLeaveAnOldCadenceAuthorized() {
        StableViewCaptureGate gate = new StableViewCaptureGate();
        gate.observe(1L, 10L);
        assertEquals(StableViewCaptureGate.Decision.INVALID_INPUT,
            gate.observe(0L, 10L));
        assertEquals(StableViewCaptureGate.Decision.FIRST_OBSERVATION,
            gate.observe(2L, 10L));
    }
}
