package dev.rlcraft.ice.optimizer.client;

import static org.junit.Assert.assertEquals;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import org.junit.Test;

public final class ModernRendererGenerationTest {
    @Test
    public void invalidationsAdvanceCollisionFreeRuntimeGeneration() {
        ModernRendererRuntime runtime = new ModernRendererRuntime(
            new ClientEpochs(), new CacheBudget(1L, 1L, 1L));
        assertEquals(1L, runtime.rendererGenerationForTest());
        runtime.invalidateWorld();
        assertEquals(2L, runtime.rendererGenerationForTest());
        runtime.invalidateResources();
        assertEquals(3L, runtime.rendererGenerationForTest());
        runtime.invalidateContext(1L);
        assertEquals(4L, runtime.rendererGenerationForTest());
    }
}
