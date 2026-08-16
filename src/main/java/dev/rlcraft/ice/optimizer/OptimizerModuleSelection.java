package dev.rlcraft.ice.optimizer;

import java.util.EnumSet;

/** Captures Forge's mutable module switches into a side-filtered immutable set. */
final class OptimizerModuleSelection {
    private OptimizerModuleSelection() {
    }

    static EnumSet<OptimizationModule> capture(OptimizerConfig.Settings source, OptimizerRuntimeSide side) {
        EnumSet<OptimizationModule> result = EnumSet.noneOf(OptimizationModule.class);
        if (!source.enabled) return result;
        for (OptimizationModule module : OptimizationModule.values()) {
            if (module.supports(side) && configured(source, module)) result.add(module);
        }
        return result;
    }

    private static boolean configured(OptimizerConfig.Settings source, OptimizationModule module) {
        switch (module) {
            case SRP_STATIC_MESH: return source.srpStaticMesh;
            case SRP_PATH_NODE_CACHE: return source.srpPathNodeCache;
            case SRP_TARGET_SEARCH: return source.srpTargetSearch;
            case SRP_POSE_CACHE: return source.srpPoseCache;
            case SRP_PARTICLE_COLLISION: return source.srpParticleCollision;
            case SRP_SPAWN_FILTER: return source.srpSpawnFilter;
            case VANILLA_CHUNK_DISPATCH: return source.vanillaChunkDispatch;
            case VANILLA_CHUNK_SORT: return source.vanillaChunkSort;
            case VANILLA_CHUNK_VBO_UPLOAD: return source.vanillaChunkVboUpload;
            case OPTIFINE_DYNAMIC_LIGHTS: return source.optifineDynamicLights;
            case RUSTIC_LATTICE_STATE: return source.rusticLatticeStateCache;
            case VANILLA_SAVE_TICK_INDEX: return source.vanillaSaveTickIndex;
            case LYCANITES_PATH_NODE_CACHE: return source.lycanitesPathNodeCache;
            case LYCANITES_REGISTRY_LOOKUP: return source.lycanitesRegistryLookup;
            case LYCANITES_SPAWN_SCAN: return source.lycanitesSpawnScan;
            case LYCANITES_BLOCK_MEMBERSHIP: return source.lycanitesBlockMembership;
            case LYCANITES_OBJ_RENDER: return source.lycanitesObjRender;
            case LYCANITES_MODEL_ANIMATION: return source.lycanitesModelAnimation;
            case LYCANITES_EFFECT_CACHE: return source.lycanitesEffectCache;
            case MOBENDS_MODEL_RENDER: return source.moBendsModelRender;
            case MOBENDS_QUATERNION_CACHE: return source.moBendsQuaternionCache;
            case MOBENDS_ENTITY_ANIMATION: return source.moBendsEntityAnimation;
            case ICEANDFIRE_POSE_LOOKUP: return source.iceAndFirePoseLookup;
            case ICEANDFIRE_PARTICLE_SCRATCH: return source.iceAndFireParticleScratch;
            case FOAMFIX_TEXTURE_UPLOAD: return source.foamFixTextureUpload;
            case XAERO_TEXTURE_UPLOAD: return source.xaeroTextureUpload;
            case XAERO_GPU_FENCE: return source.xaeroGpuFence;
            case RENDERLIB_VISIBILITY: return source.renderLibVisibility;
            case ORELIB_GL_STATE: return source.oreLibGlState;
            case CHUNK_MESH_AO: return source.betterFoliageAoScratch;
            case CHUNK_MESH_DYNAMIC_TREES: return source.dynamicTreesConnectionMemo;
            case BETTER_CAVES_NOISE: return source.betterCavesNoisePipeline;
            case BETTER_FOLIAGE_OPTIFINE_COLORS: return source.betterFoliageOptifineColors;
            case QUALITY_TOOLS_ATTRIBUTES: return source.qualityToolsEntityAttributes;
            case QUARK_ITEM_SYNC: return source.quarkItemSync;
            case OTG_BO4_IO: return source.otgBo4WriteSuppression;
            case OTG_CONFIG_PARSER: return source.otgConfigParser;
            case OTG_BO4_LAYOUT: return source.otgBo4Layout;
            case SKULL_PROFILE_ASYNC: return source.skullProfileAsync;
            case KONKRETE_LOCALE_LOOKUP: return source.konkreteLocaleLookup;
            case VANILLA_CHUNK_COMPRESSION: return source.vanillaChunkCompression;
            case FORGE_BLOCKSTATE_DIRECT_CALLS: return source.forgeBlockStateDirectCalls;
            default: return true;
        }
    }
}
