package dev.rlcraft.ice.optimizer.render.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LwjglCapabilitySelfTestTest {
    @Test
    public void globalFailuresReportEveryCapabilityWithBoundedEvidence() {
        StringBuilder oversized = new StringBuilder(6000);
        for (int index = 0; index < 6000; index++) oversized.append('x');
        CapabilityReport context = LwjglCapabilitySelfTest.globalFailureReport(
            "context", new IllegalStateException(oversized.toString()),
            "context unavailable");
        assertGlobalFailure(context, "context",
            IllegalStateException.class.getName(), "not_queried");

        CapabilityReport budget = LwjglCapabilitySelfTest.globalFailureReport(
            "allocation", null, oversized.toString());
        assertGlobalFailure(budget, "allocation", "", "not_queried");
    }

    @Test
    public void persistentUnmapOutcomeIsAttemptedOnlyOnce() {
        LwjglCapabilitySelfTest.UnmapState state =
            new LwjglCapabilitySelfTest.UnmapState();
        state.markMapped();
        assertTrue(state.beginAttempt());
        assertFalse("a throwing native unmap must not be retried",
            state.beginAttempt());
    }

    @Test
    public void coherentFailureDoesNotDisableNonCoherentPersistentStorage() {
        CapabilityReport.Builder builder = CapabilityReport.builder();
        LwjglCapabilitySelfTest.recordPersistentMappingResult(
            builder, false, true, null);
        LwjglCapabilitySelfTest.recordPersistentMappingResult(
            builder, true, false, "coherent failed");

        CapabilityReport report = builder.build();
        assertTrue(report.passed(ModernCapability.BUFFER_STORAGE));
        assertTrue(report.passed(ModernCapability.PERSISTENT_MAPPING));
        assertFalse(report.passed(ModernCapability.COHERENT_MAPPING));
    }

    @Test
    public void coherentSuccessCannotMaskNonCoherentPersistentFailure() {
        CapabilityReport.Builder builder = CapabilityReport.builder();
        LwjglCapabilitySelfTest.recordPersistentMappingResult(
            builder, false, false, "persistent failed");
        LwjglCapabilitySelfTest.recordPersistentMappingResult(
            builder, true, true, null);

        CapabilityReport report = builder.build();
        assertFalse(report.passed(ModernCapability.BUFFER_STORAGE));
        assertFalse(report.passed(ModernCapability.PERSISTENT_MAPPING));
        assertTrue(report.passed(ModernCapability.COHERENT_MAPPING));
    }

    @Test
    public void timerQueryWaitAllowsAsynchronousRetirementButRemainsBounded() {
        FakeTimerQueryProbe retires = new FakeTimerQueryProbe(3, 10L);
        assertTrue(LwjglCapabilitySelfTest.awaitTimerQuery(retires, 50L));
        assertEquals(3, retires.pauses);

        FakeTimerQueryProbe stuck = new FakeTimerQueryProbe(Integer.MAX_VALUE, 10L);
        assertFalse(LwjglCapabilitySelfTest.awaitTimerQuery(stuck, 25L));
        assertEquals(3, stuck.pauses);
    }

    private static final class FakeTimerQueryProbe
        implements LwjglCapabilitySelfTest.TimerQueryProbe {
        private final int availableAfter;
        private final long step;
        private int polls;
        private int pauses;
        private long now;

        private FakeTimerQueryProbe(int availableAfter, long step) {
            this.availableAfter = availableAfter;
            this.step = step;
        }

        @Override public boolean available() {
            return polls++ >= availableAfter;
        }

        @Override public long nanoTime() { return now; }

        @Override public void pause() {
            pauses++;
            now += step;
        }
    }

    private static void assertGlobalFailure(CapabilityReport report,
                                            String stage,
                                            String exceptionType,
                                            String glErrors) {
        assertEquals(ModernCapability.values().length,
            report.getFailureDetails().size());
        for (ModernCapability capability : ModernCapability.values()) {
            assertTrue(capability.name(), report.reported(capability));
            assertFalse(capability.name(), report.passed(capability));
            CapabilityReport.FailureDetail detail =
                report.getFailureDetail(capability);
            assertNotNull(capability.name(), detail);
            assertEquals(stage, detail.getStage());
            assertEquals(exceptionType, detail.getExceptionType());
            assertEquals("not_captured", detail.getGlState());
            assertEquals(glErrors, detail.getGlErrors());
            assertTrue(detail.getMessage().length() <= 4096);
        }
    }
}
