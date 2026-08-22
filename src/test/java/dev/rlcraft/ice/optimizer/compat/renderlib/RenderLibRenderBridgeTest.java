package dev.rlcraft.ice.optimizer.compat.renderlib;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.After;
import org.junit.Test;

public final class RenderLibRenderBridgeTest {
    @After
    public void reset() { RenderLibRenderBridge.resetForTest(); }

    @Test
    public void traversalAndObjectNestingHaveFixedFailOpenLimits() {
        long[] tokens = new long[10];
        for (int i = 0; i < tokens.length; i++) {
            tokens[i] = RenderLibRenderBridge.beginEntityTraversal(null);
        }
        assertEquals(8, RenderLibRenderBridge.traversalDepthForTest());
        assertEquals(2, RenderLibRenderBridge.traversalOverflowForTest());
        assertTrue(tokens[8] < 0L);
        for (int i = tokens.length - 1; i >= 0; i--) {
            RenderLibRenderBridge.endTraversal(tokens[i]);
        }
        assertEquals(0, RenderLibRenderBridge.traversalDepthForTest());

        long traversal = RenderLibRenderBridge.beginEntityTraversal(null);
        for (int i = 0; i < 19; i++) {
            RenderLibRenderBridge.beginObjectForTest();
        }
        assertEquals(16, RenderLibRenderBridge.objectDepthForTest());
        assertEquals(2, RenderLibRenderBridge.objectOverflowForTest());
        for (int i = 0; i < 19; i++) {
            RenderLibRenderBridge.endObjectForTest();
        }
        assertEquals(0, RenderLibRenderBridge.objectDepthForTest());
        assertEquals(0, RenderLibRenderBridge.objectOverflowForTest());
        RenderLibRenderBridge.endTraversal(traversal);
    }

    @Test
    public void mismatchedTraversalTokenDrainsAllScopes() {
        long outer = RenderLibRenderBridge.beginEntityTraversal(null);
        RenderLibRenderBridge.beginTesrTraversal(null);
        RenderLibRenderBridge.endTraversal(outer);
        assertEquals(0, RenderLibRenderBridge.traversalDepthForTest());
        assertEquals(0, RenderLibRenderBridge.traversalOverflowForTest());
    }

    @Test
    public void objectFinalizationPreservesTheOriginalRenderFailure()
        throws Exception {
        long traversal = RenderLibRenderBridge.beginEntityTraversal(null);
        RenderLibRenderBridge.beginObjectForTest();
        IllegalStateException primary = new IllegalStateException(
            "render primary");
        Method finish = RenderLibRenderBridge.class.getDeclaredMethod(
            "finishObject", Throwable.class);
        finish.setAccessible(true);
        try {
            finish.invoke(null, primary);
            throw new AssertionError("render primary was swallowed");
        } catch (InvocationTargetException expected) {
            assertSame(primary, expected.getCause());
        }
        RenderLibRenderBridge.endTraversal(traversal);
    }
}
