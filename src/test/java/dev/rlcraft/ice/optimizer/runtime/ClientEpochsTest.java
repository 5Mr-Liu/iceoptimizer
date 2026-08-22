package dev.rlcraft.ice.optimizer.runtime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class ClientEpochsTest {
    @Test
    public void invalidatesOnlyTasksGuardedByChangedGeneration() {
        ClientEpochs epochs = new ClientEpochs();
        EpochToken token = epochs.snapshot();
        epochs.nextFrame();
        assertFalse(epochs.isCurrent(token, EpochMask.FRAME));
        assertTrue(epochs.isCurrent(token, EpochMask.WORLD));
        epochs.invalidateWorld();
        assertFalse(epochs.isCurrent(token, EpochMask.WORLD));
        assertTrue(epochs.isCurrent(token, EpochMask.RESOURCE));
        assertEquals(1L, epochs.currentFrameId());
        assertEquals(0L, epochs.currentClientTickId());
        assertEquals(2L, epochs.currentWorldGeneration());
        assertEquals(1L, epochs.currentResourceGeneration());
        assertEquals(1L, epochs.currentGlContextGeneration());
        assertEquals(1L, epochs.currentShaderPackGeneration());
        assertEquals(1L, epochs.currentShaderPermutationGeneration());
        assertEquals(1L, epochs.currentVertexFormatGeneration());
        assertEquals(1L, epochs.currentViewFrustumGeneration());
    }

    @Test
    public void tracksEveryModernRenderGenerationIndependently() {
        ClientEpochs epochs = new ClientEpochs();
        EpochToken token = epochs.snapshot();
        epochs.invalidateShaderPack();
        assertFalse(epochs.isCurrent(token, EpochMask.SHADER_PACK));
        assertTrue(epochs.isCurrent(token, EpochMask.SHADER_PERMUTATION));
        epochs.invalidateShaderPermutation();
        epochs.invalidateVertexFormat();
        epochs.invalidateViewFrustum();
        assertFalse(epochs.isCurrent(token, EpochMask.RENDER_PIPELINE));
        EpochToken current = epochs.snapshot();
        assertTrue(epochs.isCurrent(current, EpochMask.RENDER_PIPELINE));
    }

    @Test
    public void exhaustedCounterNeverWrapsOrAliasesOldWork() {
        AtomicLong exhausted = new AtomicLong(Long.MAX_VALUE);
        try {
            ClientEpochs.increment(exhausted, "test generation");
            throw new AssertionError("expected exhaustion");
        } catch (IllegalStateException expected) {
            assertEquals("test generation exhausted", expected.getMessage());
        }
        assertEquals(Long.MAX_VALUE, exhausted.get());

        AtomicLong corrupted = new AtomicLong(Long.MIN_VALUE);
        try {
            ClientEpochs.increment(corrupted, "test generation");
            throw new AssertionError("expected corruption rejection");
        } catch (IllegalStateException expected) {
            assertEquals("test generation exhausted", expected.getMessage());
        }
        assertEquals(Long.MIN_VALUE, corrupted.get());
    }
}
