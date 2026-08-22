package dev.rlcraft.ice.optimizer.compat.hud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.render.hud.LwjglHudRenderer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventBus;

public final class HudRenderBridgeDispatchTest {
    @Test
    public void noModernSubmissionCallsEventDirectlyWithoutLegacyIsland() {
        final int[] eventCalls = new int[1];
        final int[] legacyCalls = new int[1];

        boolean cancelled = HudRenderBridge.dispatchEvent(false,
            new HudRenderBridge.EventInvocation() {
                @Override public boolean invoke() {
                    eventCalls[0]++;
                    return true;
                }
            }, new HudRenderBridge.LegacyEventInvocation() {
                @Override public boolean invoke(HudRenderBridge.EventInvocation event) {
                    legacyCalls[0]++;
                    throw new AssertionError("legacy island must not be entered");
                }
            });

        assertTrue(cancelled);
        assertEquals(1, eventCalls[0]);
        assertEquals(0, legacyCalls[0]);
    }

    @Test
    public void submittedModernWorkPreservesOrderAndCancellationResult() {
        final List<String> order = new ArrayList<String>();
        boolean cancelled = HudRenderBridge.dispatchEvent(true,
            new HudRenderBridge.EventInvocation() {
                @Override public boolean invoke() {
                    order.add("event");
                    return false;
                }
            }, new HudRenderBridge.LegacyEventInvocation() {
                @Override public boolean invoke(HudRenderBridge.EventInvocation event) {
                    order.add("legacy-enter");
                    boolean result = event.invoke();
                    order.add("legacy-exit");
                    return result;
                }
            });

        assertFalse(cancelled);
        assertEquals(Arrays.asList("legacy-enter", "event", "legacy-exit"), order);
    }

    @Test
    public void originalRuntimeExceptionIsPropagatedWithoutDuplicatePost() {
        final RuntimeException expected = new RuntimeException("event failed");
        final int[] eventCalls = new int[1];
        try {
            HudRenderBridge.dispatchEvent(true,
                new HudRenderBridge.EventInvocation() {
                    @Override public boolean invoke() {
                        eventCalls[0]++;
                        throw expected;
                    }
                }, new HudRenderBridge.LegacyEventInvocation() {
                    @Override public boolean invoke(HudRenderBridge.EventInvocation event) {
                        return event.invoke();
                    }
                });
        } catch (RuntimeException actual) {
            assertSame(expected, actual);
            assertEquals(1, eventCalls[0]);
            return;
        }
        throw new AssertionError("expected event failure");
    }

    @Test
    public void failedAfterDrawRequiresBoundaryButFailedBeforeDrawDoesNot() {
        assertFalse(HudRenderBridge.boundaryAfterFlush(false,
            LwjglHudRenderer.FlushResult.EMPTY));
        assertFalse(HudRenderBridge.boundaryAfterFlush(false,
            LwjglHudRenderer.FlushResult.FAILED_BEFORE_DRAW));
        assertTrue(HudRenderBridge.boundaryAfterFlush(false,
            LwjglHudRenderer.FlushResult.MODERN));
        assertTrue(HudRenderBridge.boundaryAfterFlush(false,
            LwjglHudRenderer.FlushResult.FAILED_AFTER_DRAW));
        assertTrue(HudRenderBridge.boundaryAfterFlush(true,
            LwjglHudRenderer.FlushResult.LEGACY_STATE));
    }

    @Test
    public void emptyActualPostIsDirectAndInvalidatesKnownTracker() {
        publishTrackedState();
        assertTrue(EarlyGlStateTracker.isKnown());
        assertFalse(HudRenderBridge.post(new EventBus(), new Event()));
        assertFalse(EarlyGlStateTracker.isKnown());
    }

    @Test
    public void queuedBatchGenerationMustMatchAtSubmissionTime() {
        assertTrue(HudRenderBridge.hudGenerationCurrent(7L, 11L, 7L, 11L));
        assertFalse(HudRenderBridge.hudGenerationCurrent(7L, 11L, 8L, 11L));
        assertFalse(HudRenderBridge.hudGenerationCurrent(7L, 11L, 7L, 12L));
        assertFalse(HudRenderBridge.hudGenerationCurrent(0L, 11L, 0L, 11L));
    }

    private static void publishTrackedState() {
        EarlyGlStateTracker.beginProbe();
        EarlyGlStateTracker.useProgram(7);
        EarlyGlStateTracker.bindFramebuffer(36160, 8);
        EarlyGlStateTracker.depthFunction(515);
        EarlyGlStateTracker.bindBuffer(35051, 9);
    }
}
