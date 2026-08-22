package dev.rlcraft.ice.optimizer.compat.optifine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public final class OptifineShaderSourceBridgeTest {
    @After
    public void reset() {
        OptifineShaderSourceBridge.resetForTest();
    }

    @Test
    public void pairsOptionalGeometryAndReleasesEveryCapturedByte() {
        Object program = new Object();
        assertFalse(capture(program, 0, "terrain.vsh", "vertex", 1L, 2L));
        assertFalse(capture(program, 1, "terrain.gsh", "geometry", 1L, 2L));
        assertEquals(1, OptifineShaderSourceBridge.programCountForTest());
        assertEquals(14L,
            OptifineShaderSourceBridge.capturedBytesForTest());
        assertTrue(capture(program, 2, "terrain.fsh", "fragment", 1L, 2L));
        assertEquals(0, OptifineShaderSourceBridge.programCountForTest());
        assertEquals(0L, OptifineShaderSourceBridge.capturedBytesForTest());
    }

    @Test
    public void replacementInvalidInputAndGenerationChangeCannotLeakAccounting() {
        Object first = new Object();
        assertFalse(capture(first, 0, "a.vsh", "a", 1L, 1L));
        assertFalse(capture(first, 0, "a.vsh", "longer", 1L, 1L));
        assertEquals(6L, OptifineShaderSourceBridge.capturedBytesForTest());
        assertFalse(capture(first, 1, "a.gsh", "bad\0source", 1L, 1L));
        assertEquals(0, OptifineShaderSourceBridge.programCountForTest());
        assertEquals(0L, OptifineShaderSourceBridge.capturedBytesForTest());

        Object stale = new Object();
        assertFalse(capture(stale, 0, "old.vsh", "old", 1L, 1L));
        Object current = new Object();
        assertFalse(capture(current, 0, "new.vsh", "newer", 2L, 3L));
        assertEquals(1, OptifineShaderSourceBridge.programCountForTest());
        assertEquals(5L, OptifineShaderSourceBridge.capturedBytesForTest());
    }

    @Test
    public void identityAndByteBudgetsAreHardLimits() {
        for (int index = 0; index < 256; index++) {
            assertFalse(capture(new Object(), 0, "v" + index, "v", 1L, 1L));
        }
        assertEquals(256, OptifineShaderSourceBridge.programCountForTest());
        assertFalse(capture(new Object(), 0, "overflow", "v", 1L, 1L));
        assertEquals(256, OptifineShaderSourceBridge.programCountForTest());

        OptifineShaderSourceBridge.resetForTest();
        StringBuilder builder = new StringBuilder(8 * 1024 * 1024);
        for (int index = 0; index < 8 * 1024 * 1024; index++) builder.append('x');
        String maximumStage = builder.toString();
        for (int index = 0; index < 4; index++) {
            assertFalse(capture(new Object(), 0, "large" + index,
                maximumStage, 4L, 5L));
        }
        assertEquals(32L * 1024L * 1024L,
            OptifineShaderSourceBridge.capturedBytesForTest());
        assertFalse(capture(new Object(), 0, "over-budget", "x", 4L, 5L));
        assertEquals(4, OptifineShaderSourceBridge.programCountForTest());
    }

    @Test
    public void invalidUnicodeCannotEnterCaptureAccounting() {
        assertFalse(capture(new Object(), 0, "bad.vsh", "x\uD800y",
            1L, 1L));
        assertEquals(0, OptifineShaderSourceBridge.programCountForTest());
        assertEquals(0L, OptifineShaderSourceBridge.capturedBytesForTest());
    }

    @Test
    public void lifecycleResetDropsIncompleteProgramsAndSourceBytes() {
        assertFalse(capture(new Object(), 0, "pending.vsh", "source",
            9L, 11L));
        assertEquals(1, OptifineShaderSourceBridge.programCountForTest());
        assertEquals(6L, OptifineShaderSourceBridge.capturedBytesForTest());

        OptifineShaderSourceBridge.reset();

        assertEquals(0, OptifineShaderSourceBridge.programCountForTest());
        assertEquals(0L, OptifineShaderSourceBridge.capturedBytesForTest());
    }

    private static boolean capture(Object program, int stage, String path,
                                   String source, long resources, long shaders) {
        return OptifineShaderSourceBridge.captureForTest(1, source, program,
            path, stage, resources, shaders);
    }
}
