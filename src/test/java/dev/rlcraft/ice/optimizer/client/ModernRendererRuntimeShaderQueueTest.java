package dev.rlcraft.ice.optimizer.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import org.junit.Test;

public final class ModernRendererRuntimeShaderQueueTest {
    @Test
    public void staleGenerationsArePurgedBeforeTheyCanConsumeCapacity() {
        ClientEpochs epochs = new ClientEpochs();
        ModernRendererRuntime runtime = runtime(epochs);
        assertTrue(queue(runtime, 1L, 1L, "v0", "f0"));
        long retained = runtime.cacheBudget().snapshot().getHeapUsed();
        assertTrue(retained > 0L);
        assertEquals(1, runtime.pendingShaderCandidateCountForTest());
        assertEquals(4L, runtime.pendingShaderCandidateBytesForTest());

        long resources = epochs.invalidateResources();
        assertTrue(queue(runtime, resources, 1L, "v1", "f1"));
        assertEquals(1, runtime.pendingShaderCandidateCountForTest());
        assertEquals(4L, runtime.pendingShaderCandidateBytesForTest());
        assertEquals(retained, runtime.cacheBudget().snapshot().getHeapUsed());
        assertFalse(queue(runtime, 1L, 1L, "stale", "stale"));
        assertEquals(1, runtime.pendingShaderCandidateCountForTest());
        runtime.shutdown();
        assertEquals(0L, runtime.cacheBudget().snapshot().getHeapUsed());
    }

    @Test
    public void commandAndByteBudgetsAreExactHardLimits() {
        ClientEpochs epochs = new ClientEpochs();
        ModernRendererRuntime runtime = runtime(epochs);
        for (int index = 0; index < 64; index++) {
            assertTrue(queue(runtime, 1L, 1L, "v", "f"));
        }
        assertFalse(queue(runtime, 1L, 1L, "v", "f"));
        assertEquals(64, runtime.pendingShaderCandidateCountForTest());

        epochs.invalidateShaderPack();
        StringBuilder source = new StringBuilder(8 * 1024 * 1024 - 1);
        for (int index = 0; index < 8 * 1024 * 1024 - 1; index++) {
            source.append('x');
        }
        String vertex = source.toString();
        for (int index = 0; index < 4; index++) {
            assertTrue(queue(runtime, 1L, 2L, vertex, "f"));
        }
        assertEquals(32L * 1024L * 1024L,
            runtime.pendingShaderCandidateBytesForTest());
        assertFalse(queue(runtime, 1L, 2L, vertex, "f"));
        assertEquals(4, runtime.pendingShaderCandidateCountForTest());
    }

    @Test
    public void malformedOrOversizedCandidateNeverPublishesPartially() {
        ClientEpochs epochs = new ClientEpochs();
        ModernRendererRuntime runtime = runtime(epochs);
        assertFalse(queue(runtime, 1L, 1L, "bad\0vertex", "fragment"));
        assertEquals(0, runtime.pendingShaderCandidateCountForTest());

        StringBuilder source = new StringBuilder(8 * 1024 * 1024);
        for (int index = 0; index < 8 * 1024 * 1024; index++) source.append('x');
        assertFalse(queue(runtime, 1L, 1L, source.toString(), "f"));
        assertEquals(0L, runtime.pendingShaderCandidateBytesForTest());
    }

    private static ModernRendererRuntime runtime(ClientEpochs epochs) {
        return new ModernRendererRuntime(epochs,
            new CacheBudget(128L * 1024L * 1024L, 1L, 1L));
    }

    private static boolean queue(ModernRendererRuntime runtime,
                                 long resources, long shaders,
                                 String vertex, String fragment) {
        return runtime.queueOptifineShaderSources("pack", "program", "base",
            resources, shaders, "program.vsh", vertex, null, null,
            "program.fsh", fragment, "");
    }
}
