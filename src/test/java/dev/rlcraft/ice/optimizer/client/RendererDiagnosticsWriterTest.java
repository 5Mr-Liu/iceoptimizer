package dev.rlcraft.ice.optimizer.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.lang.reflect.Method;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class RendererDiagnosticsWriterTest {
    @Before
    @After
    public void resetPublication() {
        ModernRendererDiagnostics.resetForTest();
    }

    @Test
    public void publishesOncePer120FramesAndReadsLatestCounters() throws Exception {
        ModernRendererRuntime runtime = runtime();
        publish(runtime, false);
        assertEquals("", ModernRendererDiagnostics.report());

        runtime.lastRuntimeFrame = 1L;
        runtime.terrainLegacyUpload();
        publish(runtime, false);
        String first = ModernRendererDiagnostics.report();
        String firstSummary = ModernRendererDiagnostics.summary();
        assertTrue(first.contains("published_frame=1\n"));
        assertTrue(first.contains("terrain_upload_legacy=1\n"));

        runtime.lastRuntimeFrame = 120L;
        runtime.terrainLegacyUpload();
        publish(runtime, false);
        assertSame(first, ModernRendererDiagnostics.report());
        assertSame(firstSummary, ModernRendererDiagnostics.summary());

        runtime.lastRuntimeFrame = 121L;
        publish(runtime, false);
        assertTrue(ModernRendererDiagnostics.report().contains(
            "published_frame=121\n"));
        assertTrue(ModernRendererDiagnostics.report().contains(
            "terrain_upload_legacy=2\n"));
        assertTrue(ModernRendererDiagnostics.summary().contains("U A/L 0/2"));
    }

    @Test
    public void forcedPublicationWorksBeforeAFrameAndInsideTheInterval()
        throws Exception {
        ModernRendererRuntime runtime = runtime();
        publish(runtime, true);
        assertTrue(ModernRendererDiagnostics.report().contains(
            "published_frame=" + Long.MIN_VALUE + "\n"));

        runtime.lastRuntimeFrame = 100L;
        publish(runtime, false);
        runtime.lastRuntimeFrame = 101L;
        runtime.terrainLegacyUpload();
        publish(runtime, true);
        assertTrue(ModernRendererDiagnostics.report().contains(
            "published_frame=101\n"));
        assertTrue(ModernRendererDiagnostics.report().contains(
            "terrain_upload_legacy=1\n"));
    }

    @Test
    public void restartingFrameNumbersPublishesImmediately() throws Exception {
        ModernRendererRuntime runtime = runtime();
        runtime.lastRuntimeFrame = 1000L;
        publish(runtime, false);
        runtime.lastRuntimeFrame = 1L;
        publish(runtime, false);
        assertTrue(ModernRendererDiagnostics.report().contains(
            "published_frame=1\n"));
    }

    @Test
    public void currentFrameStampTakesPrecedenceOverLastRuntimeFrame()
        throws Exception {
        ClientEpochs epochs = new ClientEpochs();
        ModernRendererRuntime runtime = new ModernRendererRuntime(epochs,
            new CacheBudget(1L, 1L, 1L));
        runtime.lastRuntimeFrame = 1000L;
        runtime.currentStamp = new FrameStamp(7L, 1L, epochs.snapshot());
        publish(runtime, false);
        assertTrue(ModernRendererDiagnostics.report().contains(
            "published_frame=7\n"));
    }

    @Test
    public void fieldsRemainSanitizedAndReportsBoundTheirText() throws Exception {
        ModernRendererRuntime runtime = runtime();
        StringBuilder text = new StringBuilder("line\n\t\u007f");
        for (int i = 0; i < 2000; i++) text.append('x');
        runtime.detail = text.toString();
        publish(runtime, true);
        String report = ModernRendererDiagnostics.report();
        assertTrue(report.contains("detail=line   "));
        int start = report.indexOf("detail=") + "detail=".length();
        int end = report.indexOf('\n', start);
        assertEquals(1024, end - start);
    }

    private static ModernRendererRuntime runtime() {
        return new ModernRendererRuntime(new ClientEpochs(),
            new CacheBudget(1L, 1L, 1L));
    }

    private static void publish(ModernRendererRuntime runtime, boolean force)
        throws Exception {
        Method method = ModernRendererRuntime.class.getDeclaredMethod(
            "publishRendererDiagnostics", boolean.class);
        method.setAccessible(true);
        method.invoke(runtime, force);
    }
}
