package dev.rlcraft.ice.optimizer.compat.optifine;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public final class OptifinePassLifecycleBridgeTest {
    @After public void reset() { OptifinePassLifecycleBridge.resetForTest(); }

    @Test
    public void shadowFlagIsBoundedNestedAndAlwaysCleared() {
        long outer = OptifinePassLifecycleBridge.beginShadow();
        long inner = OptifinePassLifecycleBridge.beginShadow();
        assertTrue(OptifinePassLifecycleBridge.isShadowPass());
        OptifinePassLifecycleBridge.endShadow(inner);
        assertTrue(OptifinePassLifecycleBridge.isShadowPass());
        OptifinePassLifecycleBridge.endShadow(outer);
        assertFalse(OptifinePassLifecycleBridge.isShadowPass());
    }

    @Test
    public void mismatchedShadowTokenDrainsInsteadOfLeakingClassification() {
        OptifinePassLifecycleBridge.beginShadow();
        OptifinePassLifecycleBridge.endShadow(1234567L);
        assertFalse(OptifinePassLifecycleBridge.isShadowPass());
    }

    @Test
    public void compositeTransitionsToFinalAndClosesItsScope() {
        long token = OptifinePassLifecycleBridge.beginComposite();
        assertTrue(OptifinePassLifecycleBridge.compositeDepthForTest() == 1);
        OptifinePassLifecycleBridge.transitionFinal();
        OptifinePassLifecycleBridge.endComposite(token);
        assertTrue(OptifinePassLifecycleBridge.compositeDepthForTest() == 0);
    }
}
