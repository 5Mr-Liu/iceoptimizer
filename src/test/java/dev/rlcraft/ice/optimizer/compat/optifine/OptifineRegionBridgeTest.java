package dev.rlcraft.ice.optimizer.compat.optifine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Test;

public final class OptifineRegionBridgeTest {
    @After public void reset() { OptifineRegionBridge.resetForTest(); }

    @Test
    public void wrappedFatalUnwindsTheRegionScopeBeforeEscaping() {
        ThreadDeath fatal = new ThreadDeath();
        try {
            OptifineRegionBridge.unwindBeginFailureForTest(
                new IllegalStateException("wrapper", fatal));
            fail("wrapped fatal must escape");
        } catch (ThreadDeath expected) {
            assertSame(fatal, expected);
        }
        assertEquals(0, OptifineRegionBridge.depthForTest());
    }
}
