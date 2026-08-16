package dev.rlcraft.ice.optimizer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class OptimizerConfigTest {
    @Test
    public void reviewedChunkMeshOptimizationsAreEnabledByDefault() {
        assertTrue(OptimizerConfig.settings.betterFoliageAoScratch);
        assertTrue(OptimizerConfig.settings.dynamicTreesConnectionMemo);
    }

    @Test
    public void reviewedCombatOptimizationsAreEnabledByDefault() {
        assertTrue(OptimizerConfig.settings.vanillaSaveTickIndex);
        assertTrue(OptimizerConfig.settings.lycanitesSpawnScan);
        assertTrue(OptimizerConfig.settings.lycanitesBlockMembership);
        assertTrue(OptimizerConfig.settings.srpSpawnFilter);
        assertTrue(OptimizerConfig.settings.lycanitesObjRender);
        assertTrue(OptimizerConfig.settings.lycanitesModelAnimation);
        assertTrue(OptimizerConfig.settings.lycanitesEffectCache);
        assertTrue(OptimizerConfig.settings.moBendsModelRender);
        assertTrue(OptimizerConfig.settings.moBendsQuaternionCache);
        assertTrue(OptimizerConfig.settings.moBendsEntityAnimation);
        assertTrue(OptimizerConfig.settings.iceAndFirePoseLookup);
        assertTrue(OptimizerConfig.settings.iceAndFireParticleScratch);
        assertTrue(OptimizerConfig.settings.otgBo4WriteSuppression);
        assertTrue(OptimizerConfig.settings.otgConfigParser);
        assertTrue(OptimizerConfig.settings.otgBo4Layout);
        assertTrue(OptimizerConfig.settings.skullProfileAsync);
        assertTrue(OptimizerConfig.settings.konkreteLocaleLookup);
        assertTrue(OptimizerConfig.settings.vanillaChunkCompression);
        assertTrue(OptimizerConfig.settings.forgeBlockStateDirectCalls);
        assertEquals(2048, OptimizerConfig.settings.skullProfileCacheEntries);
        assertEquals(360, OptimizerConfig.settings.skullProfilePositiveTtlMinutes);
        assertEquals(300, OptimizerConfig.settings.skullProfileNegativeTtlSeconds);
        assertEquals(128, OptimizerConfig.settings.skullProfileQueueCapacity);
    }

    @Test
    public void compactStatusIsF3OnlyByDefault() {
        assertTrue(OptimizerConfig.display.showF3Summary);
    }

    @Test
    public void developmentDiskOutputIsDisabledByDefault() {
        assertFalse(OptimizerConfig.settings.developmentDiskOutput);
    }
}
