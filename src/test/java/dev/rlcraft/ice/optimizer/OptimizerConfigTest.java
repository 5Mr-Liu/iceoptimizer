package dev.rlcraft.ice.optimizer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class OptimizerConfigTest {
    @Test
    public void fbpCubeSubmissionDoesNotDependOnModernParticleQualification() {
        boolean originalEnabled = OptimizerConfig.settings.enabled;
        boolean originalModern = OptimizerConfig.settings.modernRenderer;
        boolean originalParticles = OptimizerConfig.settings.modernParticleBackend;
        boolean originalCube = OptimizerConfig.settings.fbpCubeSubmission;
        try {
            OptimizerConfig.settings.enabled = true;
            OptimizerConfig.settings.modernRenderer = false;
            OptimizerConfig.settings.modernParticleBackend = false;
            OptimizerConfig.settings.fbpCubeSubmission = true;
            assertTrue(ClientOptimizerConfig.capture().enabled(OptimizationModule.FBP_CUBE_SUBMISSION));
            assertFalse(OptimizationModule.FBP_CUBE_SUBMISSION.isRuntimeManagedRenderer());
            assertFalse(OptimizationModule.FBP_CUBE_SUBMISSION.supports(OptimizerRuntimeSide.DEDICATED_SERVER));
            OptimizerConfig.settings.fbpCubeSubmission = false;
            assertFalse(ClientOptimizerConfig.capture().enabled(OptimizationModule.FBP_CUBE_SUBMISSION));
        } finally {
            OptimizerConfig.settings.enabled = originalEnabled;
            OptimizerConfig.settings.modernRenderer = originalModern;
            OptimizerConfig.settings.modernParticleBackend = originalParticles;
            OptimizerConfig.settings.fbpCubeSubmission = originalCube;
        }
    }

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
        assertTrue(OptimizerConfig.settings.iceAndFirePathNodeCache);
        assertTrue(OptimizerConfig.settings.otgBo4WriteSuppression);
        assertTrue(OptimizerConfig.settings.otgConfigParser);
        assertTrue(OptimizerConfig.settings.otgBo4Layout);
        assertTrue(OptimizerConfig.settings.otgSynchronousFileCache);
        assertFalse(OptimizerConfig.settings.allowIncompletePlayerSkullProfileLookup);
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

    @Test
    public void automaticWorkerSettingUsesFixedHardwareIndependentInitialValue() {
        int original = OptimizerConfig.settings.workerThreads;
        try {
            OptimizerConfig.settings.workerThreads = 0;
            assertEquals(1, ClientOptimizerConfig.capture().getWorkerThreads());
            OptimizerConfig.settings.workerThreads = 7;
            assertEquals(7, ClientOptimizerConfig.capture().getWorkerThreads());
        } finally {
            OptimizerConfig.settings.workerThreads = original;
        }
    }

    @Test
    public void skullAsyncModuleRequiresExplicitProfileLookupPermission() {
        boolean originalEnabled = OptimizerConfig.settings.enabled;
        boolean originalLookup = OptimizerConfig.settings.allowIncompletePlayerSkullProfileLookup;
        boolean originalAsync = OptimizerConfig.settings.skullProfileAsync;
        try {
            OptimizerConfig.settings.enabled = true;
            OptimizerConfig.settings.allowIncompletePlayerSkullProfileLookup = false;
            OptimizerConfig.settings.skullProfileAsync = true;
            ClientOptimizerConfig existingOnly = ClientOptimizerConfig.capture();
            assertFalse(existingOnly.isIncompletePlayerSkullProfileLookupAllowed());
            assertFalse(existingOnly.enabled(OptimizationModule.SKULL_PROFILE_ASYNC));

            OptimizerConfig.settings.allowIncompletePlayerSkullProfileLookup = true;
            ClientOptimizerConfig asynchronous = ClientOptimizerConfig.capture();
            assertTrue(asynchronous.isIncompletePlayerSkullProfileLookupAllowed());
            assertTrue(asynchronous.enabled(OptimizationModule.SKULL_PROFILE_ASYNC));

            OptimizerConfig.settings.skullProfileAsync = false;
            ClientOptimizerConfig vanilla = ClientOptimizerConfig.capture();
            assertTrue(vanilla.isIncompletePlayerSkullProfileLookupAllowed());
            assertFalse(vanilla.enabled(OptimizationModule.SKULL_PROFILE_ASYNC));
        } finally {
            OptimizerConfig.settings.enabled = originalEnabled;
            OptimizerConfig.settings.allowIncompletePlayerSkullProfileLookup = originalLookup;
            OptimizerConfig.settings.skullProfileAsync = originalAsync;
        }
    }

    @Test
    public void terrainSwitchAlsoControlsItsIndependentMdiCandidate() {
        boolean originalRenderer = OptimizerConfig.settings.modernRenderer;
        boolean originalTerrain = OptimizerConfig.settings.modernTerrainBackend;
        try {
            OptimizerConfig.settings.modernRenderer = true;
            OptimizerConfig.settings.modernTerrainBackend = false;
            ClientOptimizerConfig disabled = ClientOptimizerConfig.capture();
            assertFalse(disabled.enabled(OptimizationModule.MODERN_TERRAIN_BACKEND));
            assertFalse(disabled.enabled(OptimizationModule.MODERN_TERRAIN_MDI));
            assertFalse(disabled.enabled(
                OptimizationModule.MODERN_TERRAIN_PERSISTENT_MAPPING));

            OptimizerConfig.settings.modernTerrainBackend = true;
            ClientOptimizerConfig enabled = ClientOptimizerConfig.capture();
            assertTrue(enabled.enabled(OptimizationModule.MODERN_TERRAIN_BACKEND));
            assertTrue(enabled.enabled(OptimizationModule.MODERN_TERRAIN_MDI));
            assertTrue(enabled.enabled(
                OptimizationModule.MODERN_TERRAIN_PERSISTENT_MAPPING));
        } finally {
            OptimizerConfig.settings.modernRenderer = originalRenderer;
            OptimizerConfig.settings.modernTerrainBackend = originalTerrain;
        }
    }

    @Test
    public void particleSwitchAlsoControlsTheIndependentFbpPacketCandidate() {
        boolean originalRenderer = OptimizerConfig.settings.modernRenderer;
        boolean originalParticles = OptimizerConfig.settings.modernParticleBackend;
        try {
            OptimizerConfig.settings.modernRenderer = true;
            OptimizerConfig.settings.modernParticleBackend = false;
            ClientOptimizerConfig disabled = ClientOptimizerConfig.capture();
            assertFalse(disabled.enabled(OptimizationModule.MODERN_PARTICLE_BACKEND));
            assertFalse(disabled.enabled(OptimizationModule.FBP_PARTICLE_ADAPTER));

            OptimizerConfig.settings.modernParticleBackend = true;
            ClientOptimizerConfig enabled = ClientOptimizerConfig.capture();
            assertTrue(enabled.enabled(OptimizationModule.MODERN_PARTICLE_BACKEND));
            assertTrue(enabled.enabled(OptimizationModule.FBP_PARTICLE_ADAPTER));
        } finally {
            OptimizerConfig.settings.modernRenderer = originalRenderer;
            OptimizerConfig.settings.modernParticleBackend = originalParticles;
        }
    }

    @Test
    public void textureSwitchControlsUploadMappingAndVisibilityIndependently() {
        boolean originalRenderer = OptimizerConfig.settings.modernRenderer;
        boolean originalTextures = OptimizerConfig.settings.modernTextureStream;
        try {
            OptimizerConfig.settings.modernRenderer = true;
            OptimizerConfig.settings.modernTextureStream = false;
            ClientOptimizerConfig disabled = ClientOptimizerConfig.capture();
            assertFalse(disabled.enabled(OptimizationModule.MODERN_TEXTURE_STREAM));
            assertFalse(disabled.enabled(
                OptimizationModule.MODERN_TEXTURE_PERSISTENT_RING));
            assertFalse(disabled.enabled(
                OptimizationModule.MODERN_TEXTURE_VISIBILITY));

            OptimizerConfig.settings.modernTextureStream = true;
            ClientOptimizerConfig enabled = ClientOptimizerConfig.capture();
            assertTrue(enabled.enabled(OptimizationModule.MODERN_TEXTURE_STREAM));
            assertTrue(enabled.enabled(
                OptimizationModule.MODERN_TEXTURE_PERSISTENT_RING));
            assertTrue(enabled.enabled(
                OptimizationModule.MODERN_TEXTURE_VISIBILITY));
        } finally {
            OptimizerConfig.settings.modernRenderer = originalRenderer;
            OptimizerConfig.settings.modernTextureStream = originalTextures;
        }
    }
}
