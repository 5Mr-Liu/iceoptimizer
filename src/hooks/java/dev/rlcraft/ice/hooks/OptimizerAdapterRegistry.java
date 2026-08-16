package dev.rlcraft.ice.hooks;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Structurally validated adapters; known hashes remain audit metadata in the target catalog. */
final class OptimizerAdapterRegistry {
    private static final Map<String, OptimizerBytecodeAdapter> ADAPTERS;

    static {
        Map<String, OptimizerBytecodeAdapter> adapters = new HashMap<String, OptimizerBytecodeAdapter>();
        adapters.put("foamfix-pbo-upload", new FoamFixTextureUploadAdapter());
        adapters.put("xaero-texture-batch", new XaeroTextureUploadAdapter());
        adapters.put("renderlib-visibility-cache", new RenderLibTileEntityAdapter());
        adapters.put("srp-kirin-static-subtree", new SrpKirinStaticMeshAdapter());
        adapters.put("srp-model-static-branches", new SrpKirinStaticMeshAdapter());
        adapters.put("srp-parasite-navigator", new SrpParasiteNavigatorAdapter());
        adapters.put("srp-target-linear-select", new SrpTargetSearchAdapter());
        adapters.put("minecraft-pending-tick-index", new MinecraftSaveTickAdapter());
        adapters.put("minecraft-full-save-scope", new MinecraftSaveTickAdapter());
        adapters.put("lycanites-path-search-cache", new LycanitesNodeProcessorAdapter());
        adapters.put("lycanites-registry-single-probe", new LycanitesObjectManagerAdapter());
        adapters.put("lycanites-spawn-scan", new LycanitesSpawnScanAdapter());
        adapters.put("lycanites-obj-display-list", new LycanitesObjRenderAdapter());
        adapters.put("lycanites-animator-identities", new LycanitesAnimatorAdapter());
        adapters.put("lycanites-part-indexed", new LycanitesModelObjPartAdapter());
        adapters.put("lycanites-frame-dispatch", new LycanitesAnimationFrameAdapter());
        adapters.put("lycanites-lowercase-cache", new LycanitesLowercaseAdapter());
        adapters.put("lycanites-effect-slots", new LycanitesPotionEffectsAdapter());
        adapters.put("mobends-model-part", new MoBendsModelPartAdapter());
        adapters.put("mobends-quaternion-cache", new MoBendsQuaternionAdapter());
        adapters.put("mobends-climbing-shortcut", new MoBendsLivingEntityDataAdapter());
        adapters.put("iceandfire-pose-local", new IceAndFirePoseAdapter());
        adapters.put("iceandfire-particle-args", new IceAndFireSeaSerpentAdapter());
        adapters.put("orelib-gl-state-cache", new OreLibOpenGlStateAdapter());
        adapters.put("betterfoliage-ao-scratch", new BetterFoliageAoScratchAdapter());
        adapters.put("dynamic-trees-connections", new DynamicTreesConnectionAdapter());
        adapters.put("otg-bo4-runtime", new OtgBo4Adapter());
        adapters.put("otg-bo4-column-layout", new OtgBo4ConfigAdapter());
        adapters.put("otg-comma-parser", new OtgStringHelperAdapter());
        adapters.put("otg-function-name-cache", new OtgResourcesAdapter());
        adapters.put("player-skull-async", new PlayerSkullLayerAdapter());
        adapters.put("bettercaves-noise-tuple", new BetterCavesNoiseTupleAdapter());
        adapters.put("bettercaves-noise-column", new BetterCavesNoiseColumnAdapter());
        adapters.put("bettercaves-noise-generation", new BetterCavesNoiseGenAdapter());
        adapters.put("bettercaves-threshold-column", new BetterCavesCaveCarverAdapter());
        adapters.put("betterfoliage-optifine-colors", new BetterFoliageOptifineColorAdapter());
        adapters.put("qualitytools-stable-attributes", new QualityToolsAttributeAdapter());
        adapters.put("quark-item-sync-state", new QuarkItemSyncAdapter());
        ADAPTERS = Collections.unmodifiableMap(adapters);
    }

    private OptimizerAdapterRegistry() {
    }

    static OptimizerBytecodeAdapter find(String id) {
        return ADAPTERS.get(id);
    }
}
