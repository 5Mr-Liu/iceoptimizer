package dev.rlcraft.ice.optimizer.compat.foamfix;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class FoamFixUploadBridgeTest {
    @Test
    public void acceptsCoreAndArbPboSynchronizationIndependently() {
        assertTrue(FoamFixUploadBridge.supportsPboForTest(true, true, false, true, false));
        assertTrue(FoamFixUploadBridge.supportsPboForTest(true, false, true, false, true));
        assertFalse(FoamFixUploadBridge.supportsPboForTest(true, true, false, false, false));
        assertFalse(FoamFixUploadBridge.supportsPboForTest(false, true, true, true, true));
    }
}
