package dev.rlcraft.ice.optimizer.runtime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
    }
}
