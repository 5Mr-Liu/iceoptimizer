package dev.rlcraft.ice.optimizer.render.optifine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public final class LwjglShaderImageCertificationTest {
    @Test
    public void retainedWorkspaceIsDirectBudgetedAndReleased() {
        CacheBudget budget = new CacheBudget(1L, 4096L, 1L);
        LwjglShaderImageCertification certification =
            new LwjglShaderImageCertification(
                RenderThreadGuard.captureCurrent(), budget);
        assertTrue(certification.isWorkspaceAvailableForTest());
        assertEquals(LwjglShaderImageCertification.WORKSPACE_BYTES,
            budget.snapshot().getDirectUsed());
        certification.close();
        certification.close();
        assertFalse(certification.isWorkspaceAvailableForTest());
        assertEquals(0L, budget.snapshot().getDirectUsed());
    }

    @Test
    public void retainedWorkspaceBudgetFailureIsFailClosedAndLeakFree() {
        CacheBudget budget = new CacheBudget(1L, 1L, 1L);
        LwjglShaderImageCertification certification =
            new LwjglShaderImageCertification(
                RenderThreadGuard.captureCurrent(), budget);
        assertFalse(certification.isWorkspaceAvailableForTest());
        assertEquals(0L, budget.snapshot().getDirectUsed());
        certification.close();
    }

    @Test
    public void requiresBothProgramsToChangeAIdenticalClearedBaseline() {
        Map<String, byte[]> baseline = images(new byte[] {1, 2},
            new byte[] {3, 4});
        Map<String, byte[]> legacy = images(new byte[] {1, 9},
            new byte[] {3, 4});
        Map<String, byte[]> candidate = images(new byte[] {8, 2},
            new byte[] {3, 4});
        assertTrue(LwjglShaderImageCertification.hasObservableSignal(
            baseline, legacy, candidate));

        assertFalse(LwjglShaderImageCertification.hasObservableSignal(
            baseline, baseline, candidate));
        assertFalse(LwjglShaderImageCertification.hasObservableSignal(
            baseline, legacy, baseline));
    }

    @Test
    public void missingOrDifferentAttachmentSetsFailClosed() {
        Map<String, byte[]> baseline = images(new byte[] {1}, new byte[] {2});
        Map<String, byte[]> colorOnly = new LinkedHashMap<String, byte[]>();
        colorOnly.put("color0", new byte[] {9});
        assertFalse(LwjglShaderImageCertification.hasObservableSignal(
            baseline, colorOnly, colorOnly));
        assertFalse(LwjglShaderImageCertification.hasObservableSignal(
            null, colorOnly, colorOnly));
    }

    @Test
    public void preservesFloatAndNormalizedAttachmentPrecision() {
        final int glFloat = 0x1406;
        final int unsignedNormalized = 0x8C17;
        assertEquals(8, LwjglShaderImageCertification
            .exactCaptureBytesPerPixel(glFloat, 16, false));
        assertEquals(16, LwjglShaderImageCertification
            .exactCaptureBytesPerPixel(glFloat, 32, false));
        assertEquals(4, LwjglShaderImageCertification
            .exactCaptureBytesPerPixel(unsignedNormalized, 8, false));
        assertEquals(8, LwjglShaderImageCertification
            .exactCaptureBytesPerPixel(unsignedNormalized, 16, false));
        assertEquals(4, LwjglShaderImageCertification
            .exactCaptureBytesPerPixel(glFloat, 32, true));
        assertEquals(0, LwjglShaderImageCertification
            .exactCaptureBytesPerPixel(glFloat, 11, false));
    }

    @Test
    public void acceptsOnlyExactAllocatableOptifineColorFormats() {
        assertTrue(LwjglShaderImageCertification
            .supportedRequestedColorFormat(6408));
        assertTrue(LwjglShaderImageCertification
            .supportedRequestedColorFormat(34842));
        assertTrue(LwjglShaderImageCertification
            .supportedRequestedColorFormat(34836));
        assertFalse(LwjglShaderImageCertification
            .supportedRequestedColorFormat(32857)); // RGB10_A2 is packed.
        assertFalse(LwjglShaderImageCertification
            .supportedRequestedColorFormat(36220)); // RGBA8UI is integer.
    }

    private static Map<String, byte[]> images(byte[] color, byte[] depth) {
        Map<String, byte[]> values = new LinkedHashMap<String, byte[]>();
        values.put("color0", color);
        values.put("depth", depth);
        return values;
    }
}
