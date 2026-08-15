package dev.rlcraft.ice.optimizer.compat.otg;

import static org.junit.Assert.assertEquals;

import dev.rlcraft.ice.optimizer.ClientOptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class OtgBo4OptimizationBridgeTest {
    private boolean previousEnabled;
    private boolean previousModule;

    @Before
    public void enableModule() {
        previousEnabled = OptimizerConfig.settings.enabled;
        previousModule = OptimizerConfig.settings.otgBo4Layout;
        OptimizerConfig.settings.enabled = true;
        OptimizerConfig.settings.otgBo4Layout = true;
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
        OptimizerRegistry.breaker(OptimizationModule.OTG_BO4_LAYOUT)
            .patchInstalled("synthetic", "test");
    }

    @After
    public void restoreModule() {
        OptimizerConfig.settings.enabled = previousEnabled;
        OptimizerConfig.settings.otgBo4Layout = previousModule;
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
    }

    @Test
    public void prefixTableMatchesOriginalForEveryColumn() {
        short[][] sizes = new short[16][16];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) sizes[x][z] = (short) ((x * 5 + z * 3) % 7);
        }
        Object owner = new Object();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                assertEquals(OtgBo4OptimizationBridge.originalColumnBlockIndex(sizes, x, z),
                    OtgBo4OptimizationBridge.columnBlockIndex(owner, sizes, x, z));
            }
        }
    }

    @Test
    public void aNewColumnArrayInvalidatesTheThreadLocalPrefixTable() {
        short[][] first = new short[16][16];
        short[][] second = new short[16][16];
        first[0][0] = 2;
        second[0][0] = 9;
        assertEquals(2, OtgBo4OptimizationBridge.columnBlockIndex(this, first, 0, 1));
        assertEquals(9, OtgBo4OptimizationBridge.columnBlockIndex(this, second, 0, 1));
    }
}
