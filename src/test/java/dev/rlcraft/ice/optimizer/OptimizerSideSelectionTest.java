package dev.rlcraft.ice.optimizer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class OptimizerSideSelectionTest {
    @Test
    public void dedicatedServerEnablesOnlyReviewedCommonHotPaths() {
        ServerOptimizerConfig server = ServerOptimizerConfig.capture();
        assertTrue(server.enabled(OptimizationModule.CORE_RUNTIME));
        assertTrue(server.enabled(OptimizationModule.SRP_PATH_NODE_CACHE));
        assertTrue(server.enabled(OptimizationModule.SRP_TARGET_SEARCH));
        assertTrue(server.enabled(OptimizationModule.VANILLA_SAVE_TICK_INDEX));
        assertTrue(server.enabled(OptimizationModule.LYCANITES_PATH_NODE_CACHE));
        assertTrue(server.enabled(OptimizationModule.LYCANITES_REGISTRY_LOOKUP));
        assertTrue(server.enabled(OptimizationModule.LYCANITES_SPAWN_SCAN));
        assertTrue(server.enabled(OptimizationModule.LYCANITES_EFFECT_CACHE));
        assertTrue(server.enabled(OptimizationModule.ICEANDFIRE_PARTICLE_SCRATCH));
        assertTrue(server.enabled(OptimizationModule.OTG_BO4_IO));
        assertTrue(server.enabled(OptimizationModule.OTG_CONFIG_PARSER));
        assertTrue(server.enabled(OptimizationModule.OTG_BO4_LAYOUT));
        assertTrue(server.enabled(OptimizationModule.BETTER_CAVES_NOISE));
        assertTrue(server.enabled(OptimizationModule.QUALITY_TOOLS_ATTRIBUTES));
        assertTrue(server.enabled(OptimizationModule.QUARK_ITEM_SYNC));

        assertFalse(server.enabled(OptimizationModule.SRP_STATIC_MESH));
        assertFalse(server.enabled(OptimizationModule.VANILLA_CHUNK_DISPATCH));
        assertFalse(server.enabled(OptimizationModule.VANILLA_CHUNK_SORT));
        assertFalse(server.enabled(OptimizationModule.VANILLA_CHUNK_VBO_UPLOAD));
        assertFalse(server.enabled(OptimizationModule.LYCANITES_OBJ_RENDER));
        assertFalse(server.enabled(OptimizationModule.MOBENDS_MODEL_RENDER));
        assertFalse(server.enabled(OptimizationModule.FOAMFIX_TEXTURE_UPLOAD));
        assertFalse(server.enabled(OptimizationModule.XAERO_GPU_FENCE));
        assertFalse(server.enabled(OptimizationModule.RENDERLIB_VISIBILITY));
        assertFalse(server.enabled(OptimizationModule.ORELIB_GL_STATE));
        assertFalse(server.enabled(OptimizationModule.CHUNK_MESH_AO));
        assertFalse(server.enabled(OptimizationModule.CHUNK_MESH_DYNAMIC_TREES));
        assertFalse(server.enabled(OptimizationModule.BETTER_FOLIAGE_OPTIFINE_COLORS));
        assertFalse(server.enabled(OptimizationModule.SKULL_PROFILE_ASYNC));
        assertFalse(server.enabled(OptimizationModule.RENDER_SUBMISSION));
    }

    @Test
    public void clientKeepsClientAndIntegratedServerOptimizations() {
        ClientOptimizerConfig client = ClientOptimizerConfig.capture();
        assertTrue(client.enabled(OptimizationModule.SRP_STATIC_MESH));
        assertTrue(client.enabled(OptimizationModule.SRP_PATH_NODE_CACHE));
        assertTrue(client.enabled(OptimizationModule.VANILLA_CHUNK_DISPATCH));
        assertTrue(client.enabled(OptimizationModule.VANILLA_CHUNK_SORT));
        assertTrue(client.enabled(OptimizationModule.VANILLA_CHUNK_VBO_UPLOAD));
        assertTrue(client.enabled(OptimizationModule.VANILLA_SAVE_TICK_INDEX));
        assertTrue(client.enabled(OptimizationModule.LYCANITES_SPAWN_SCAN));
        assertTrue(client.enabled(OptimizationModule.LYCANITES_OBJ_RENDER));
        assertTrue(client.enabled(OptimizationModule.OTG_BO4_LAYOUT));
        assertTrue(client.enabled(OptimizationModule.BETTER_CAVES_NOISE));
        assertTrue(client.enabled(OptimizationModule.BETTER_FOLIAGE_OPTIFINE_COLORS));
        assertTrue(client.enabled(OptimizationModule.QUALITY_TOOLS_ATTRIBUTES));
        assertTrue(client.enabled(OptimizationModule.QUARK_ITEM_SYNC));
        assertTrue(client.enabled(OptimizationModule.SKULL_PROFILE_ASYNC));
        assertTrue(client.enabled(OptimizationModule.RENDER_SUBMISSION));
    }
}
