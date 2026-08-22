package dev.rlcraft.ice.optimizer.compat.particle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Test;

public final class ParticleRenderBridgeTest {
    @After
    public void reset() { ParticleRenderBridge.resetForTest(); }

    @Test
    public void nestedTraversalIsBoundedAndAlwaysLegacy() {
        long outer = ParticleRenderBridge.begin(null, null, 0.0F);
        long nested = ParticleRenderBridge.begin(null, null, 0.0F);
        assertEquals(2, ParticleRenderBridge.activeDepthForTest());
        assertFalse(ParticleRenderBridge.currentModernForTest());
        ParticleRenderBridge.end(nested);
        ParticleRenderBridge.end(outer);
        assertEquals(0, ParticleRenderBridge.activeDepthForTest());
    }

    @Test
    public void overflowUsesNegativeTokensAndTokenMismatchDrainsEverything() {
        long[] tokens = new long[10];
        for (int i = 0; i < tokens.length; i++) {
            tokens[i] = ParticleRenderBridge.begin(null, null, 0.0F);
        }
        assertEquals(8, ParticleRenderBridge.activeDepthForTest());
        assertEquals(2, ParticleRenderBridge.overflowDepthForTest());
        assertTrue(tokens[8] < 0L);
        assertTrue(tokens[9] < 0L);
        for (int i = tokens.length - 1; i >= 0; i--) {
            ParticleRenderBridge.end(tokens[i]);
        }
        assertEquals(0, ParticleRenderBridge.activeDepthForTest());

        long outer = ParticleRenderBridge.begin(null, null, 0.0F);
        ParticleRenderBridge.begin(null, null, 0.0F);
        ParticleRenderBridge.end(outer);
        assertEquals(0, ParticleRenderBridge.activeDepthForTest());
        assertEquals(0, ParticleRenderBridge.overflowDepthForTest());
    }

    @Test
    public void fatalDrainStillPopsEveryNestedScopeAndRemovesThreadLocal() {
        ParticleRenderBridge.begin(null, null, 0.0F);
        ParticleRenderBridge.begin(null, null, 0.0F);
        OutOfMemoryError fatal = new OutOfMemoryError(
            "injected particle drain fatal");
        try {
            ParticleRenderBridge.drainForTest(
                new IllegalStateException("wrapper", fatal));
            fail("wrapped fatal must escape");
        } catch (OutOfMemoryError expected) {
            assertSame(fatal, expected);
        }
        assertEquals(0, ParticleRenderBridge.activeDepthForTest());
        assertEquals(0, ParticleRenderBridge.overflowDepthForTest());
    }
}
