package dev.rlcraft.ice.optimizer.compat.optifine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Test;

public final class OptifineShaderLifecycleBridgeTest {
    @After
    public void reset() {
        OptifineShaderLifecycleBridge.resetForTest();
    }

    @Test
    public void scopesHaveABalancedFixedDepthLimit() {
        long[] tokens = new long[18];
        for (int index = 0; index < tokens.length; index++) {
            tokens[index] = OptifineShaderLifecycleBridge.begin(null);
        }
        assertEquals(16, OptifineShaderLifecycleBridge.depthForTest());
        assertEquals(2, OptifineShaderLifecycleBridge.overflowForTest());
        assertTrue(tokens[16] < 0L);
        for (int index = tokens.length - 1; index >= 0; index--) {
            OptifineShaderLifecycleBridge.end(tokens[index], null);
        }
        assertEquals(0, OptifineShaderLifecycleBridge.depthForTest());
        assertEquals(0, OptifineShaderLifecycleBridge.overflowForTest());
    }

    @Test
    public void tokenMismatchDrainsEveryScope() {
        long outer = OptifineShaderLifecycleBridge.begin(null);
        OptifineShaderLifecycleBridge.begin(null);
        OptifineShaderLifecycleBridge.end(outer, null);
        assertEquals(0, OptifineShaderLifecycleBridge.depthForTest());
        assertEquals(0, OptifineShaderLifecycleBridge.overflowForTest());
    }

    @Test
    public void wrappedFatalUnwindsTheBeginScopeBeforeEscaping() {
        OutOfMemoryError fatal = new OutOfMemoryError(
            "injected wrapped shader fatal");
        try {
            OptifineShaderLifecycleBridge.unwindBeginFailureForTest(
                new IllegalStateException("wrapper", fatal));
            fail("wrapped fatal must escape");
        } catch (OutOfMemoryError expected) {
            assertSame(fatal, expected);
        }
        assertEquals(0, OptifineShaderLifecycleBridge.depthForTest());
        assertEquals(0, OptifineShaderLifecycleBridge.overflowForTest());
    }
}
