package dev.rlcraft.ice.optimizer.render.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CapabilityReportTest {
    @Test
    public void preservesStructuredFailureAndLegacySummary() {
        CapabilityReport.FailureDetail detail =
            new CapabilityReport.FailureDetail("readback",
                "IllegalStateException", "pixel mismatch",
                "draw_fbo=9,scissor=true", "0x0502");
        CapabilityReport report = CapabilityReport.builder()
            .fail(ModernCapability.OFFSCREEN_FRAMEBUFFER, detail)
            .build();

        assertFalse(report.passed(ModernCapability.OFFSCREEN_FRAMEBUFFER));
        assertEquals("IllegalStateException: pixel mismatch",
            report.getFailures().get(ModernCapability.OFFSCREEN_FRAMEBUFFER));
        assertEquals("readback", report.getFailureDetail(
            ModernCapability.OFFSCREEN_FRAMEBUFFER).getStage());
        assertEquals("draw_fbo=9,scissor=true", report.getFailureDetail(
            ModernCapability.OFFSCREEN_FRAMEBUFFER).getGlState());
        assertEquals("0x0502", report.getFailureDetail(
            ModernCapability.OFFSCREEN_FRAMEBUFFER).getGlErrors());
    }

    @Test
    public void passClearsPriorFailureAndUntouchedCapabilityIsNotTested() {
        CapabilityReport report = CapabilityReport.builder()
            .fail(ModernCapability.BUFFER_OBJECT, "first failure")
            .pass(ModernCapability.BUFFER_OBJECT)
            .build();

        assertTrue(report.passed(ModernCapability.BUFFER_OBJECT));
        assertFalse(report.getFailures().containsKey(ModernCapability.BUFFER_OBJECT));
        assertNull(report.getFailureDetail(ModernCapability.BUFFER_OBJECT));
        assertFalse(report.passed(ModernCapability.TIMER_QUERY));
        assertFalse(report.reported(ModernCapability.TIMER_QUERY));
        assertNull(report.getFailureDetail(ModernCapability.TIMER_QUERY));
    }

    @Test
    public void failUnreportedPreservesExistingOutcomesAndCompletesReport() {
        CapabilityReport report = CapabilityReport.builder()
            .pass(ModernCapability.BUFFER_OBJECT)
            .fail(ModernCapability.TIMER_QUERY,
                new CapabilityReport.FailureDetail("readback", "", "timeout",
                    "query=7", "none"))
            .failUnreported(new CapabilityReport.FailureDetail("context", "",
                "aborted", "not_captured", "not_queried"))
            .build();

        assertEquals(ModernCapability.values().length,
            report.getPassed().size() + report.getFailureDetails().size());
        assertTrue(report.passed(ModernCapability.BUFFER_OBJECT));
        assertEquals("readback", report.getFailureDetail(
            ModernCapability.TIMER_QUERY).getStage());
        assertEquals("context", report.getFailureDetail(
            ModernCapability.SHADER_PROGRAM).getStage());
    }
}
