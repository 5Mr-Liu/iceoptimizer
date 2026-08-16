package dev.rlcraft.ice.optimizer.compat.foamfix;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
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

    @Test
    public void onlyLargeTrueBatchesAreEligibleForPbo() {
        assertEquals(256 * 1024, FoamFixUploadBridge.minimumBatchPboBytesForTest());
        assertFalse(FoamFixUploadBridge.tryUploadLevel(0, new int[] { 1 },
            1, 1, 0, 0, false, false, false));
    }
}
