package dev.rlcraft.ice.optimizer.compat.texture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public final class AnimatedTextureUploadBridgeTest {
    @After public void clearThreadState() {
        AnimatedTextureUploadBridge.resetForTest();
    }

    @Test
    public void overflowAndTokenMismatchDrainEveryBoundedScope() {
        long[] tokens = new long[10];
        for (int index = 0; index < tokens.length; index++) {
            tokens[index] = AnimatedTextureUploadBridge.begin(null);
        }
        assertEquals(8, AnimatedTextureUploadBridge.depthForTest());
        assertEquals(2, AnimatedTextureUploadBridge.overflowForTest());
        assertTrue(tokens[8] < 0L);
        assertTrue(tokens[9] < 0L);

        AnimatedTextureUploadBridge.end(tokens[9]);
        AnimatedTextureUploadBridge.end(tokens[8]);
        assertEquals(0, AnimatedTextureUploadBridge.overflowForTest());

        // Ending the outer token while inner scopes remain deliberately
        // exercises the corruption drain, not the ordinary LIFO close.
        AnimatedTextureUploadBridge.end(tokens[0]);
        assertEquals(0, AnimatedTextureUploadBridge.depthForTest());
        assertEquals(0, AnimatedTextureUploadBridge.overflowForTest());
    }
}
