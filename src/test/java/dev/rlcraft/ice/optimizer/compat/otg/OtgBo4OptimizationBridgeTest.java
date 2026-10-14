package dev.rlcraft.ice.optimizer.compat.otg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void disablingTheModuleBypassesAndInvalidatesAWarmPrefixTable() {
        short[][] sizes = new short[16][16];
        sizes[0][0] = 2;
        assertEquals(2, OtgBo4OptimizationBridge.columnBlockIndex(this, sizes, 0, 1));

        OptimizerRegistry.breaker(OptimizationModule.OTG_BO4_LAYOUT).configure(false, 3);
        sizes[0][0] = 9;
        assertEquals(9, OtgBo4OptimizationBridge.columnBlockIndex(this, sizes, 0, 1));

        OptimizerRegistry.breaker(OptimizationModule.OTG_BO4_LAYOUT).configure(true, 3);
        sizes[0][0] = 13;
        assertEquals(13, OtgBo4OptimizationBridge.columnBlockIndex(this, sizes, 0, 1));
    }

    @Test
    public void runtimePauseBypassesAWarmTableWithoutDisablingItsBreaker() {
        boolean previouslyPaused = OptimizerRegistry.isRuntimePaused();
        OptimizerRegistry.setRuntimePaused(false);
        try {
            short[][] sizes = new short[16][16];
            sizes[0][0] = 2;
            assertEquals(2, OtgBo4OptimizationBridge.columnBlockIndex(this, sizes, 0, 1));
            OptimizerRegistry.setRuntimePaused(true);
            assertTrue(OptimizerRegistry.breaker(OptimizationModule.OTG_BO4_LAYOUT).isOperational());
            sizes[0][0] = 9;
            assertEquals(9, OtgBo4OptimizationBridge.columnBlockIndex(this, sizes, 0, 1));
            OptimizerRegistry.setRuntimePaused(false);
            sizes[0][0] = 13;
            assertEquals(13, OtgBo4OptimizationBridge.columnBlockIndex(this, sizes, 0, 1));
        } finally {
            OptimizerRegistry.setRuntimePaused(previouslyPaused);
        }
    }
}
