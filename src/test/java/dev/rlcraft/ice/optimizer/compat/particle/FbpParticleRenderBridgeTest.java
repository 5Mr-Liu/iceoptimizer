package dev.rlcraft.ice.optimizer.compat.particle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public final class FbpParticleRenderBridgeTest {
    @After
    public void reset() { FbpParticleRenderBridge.resetForTest(); }

    @Test
    public void nestedScopesAreBoundedAndDegradeToLegacy() {
        long outer = FbpParticleRenderBridge.enter(null);
        long nested = FbpParticleRenderBridge.enter(null);
        assertEquals(2, FbpParticleRenderBridge.activeDepthForTest());
        assertFalse(FbpParticleRenderBridge.currentModernForTest());
        FbpParticleRenderBridge.exit(nested);
        FbpParticleRenderBridge.exit(outer);
        assertEquals(0, FbpParticleRenderBridge.activeDepthForTest());
    }

    @Test
    public void overflowTokenDoesNotReplaceTheBoundedScopeStack() {
        long[] tokens = new long[9];
        for (int index = 0; index < tokens.length; index++) {
            tokens[index] = FbpParticleRenderBridge.enter(null);
        }
        assertEquals(8, FbpParticleRenderBridge.activeDepthForTest());
        assertTrue(tokens[8] < 0L);
        FbpParticleRenderBridge.exit(tokens[8]);
        for (int index = 7; index >= 0; index--) {
            FbpParticleRenderBridge.exit(tokens[index]);
        }
        assertEquals(0, FbpParticleRenderBridge.activeDepthForTest());
    }

    @Test
    public void tokenMismatchDrainsAllScopesWithoutLeakingThreadLocalState() {
        long outer = FbpParticleRenderBridge.enter(null);
        FbpParticleRenderBridge.enter(null);
        FbpParticleRenderBridge.exit(outer);
        assertEquals(0, FbpParticleRenderBridge.activeDepthForTest());
    }
}
