package dev.rlcraft.ice.hooks;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Structurally validated adapters; known hashes remain audit metadata in the target catalog. */
final class OptimizerAdapterRegistry {
    private static final Map<String, OptimizerBytecodeAdapter> ADAPTERS;

    static {
        Map<String, OptimizerBytecodeAdapter> adapters = new HashMap<String, OptimizerBytecodeAdapter>();
        adapters.put("nothirium-fog-compatibility", new NothiriumFogAdapter());
        adapters.put("foamfix-pbo-upload", new FoamFixTextureUploadAdapter());
        adapters.put("vanilla-texture-parameters", new VanillaTextureUploadAdapter());
        adapters.put("optifine-dynamic-lights", new OptifineDynamicLightsAdapter(
            OptifineDynamicLightsAdapter.Part.LIGHTS));
        adapters.put("optifine-dynamic-light-access", new OptifineDynamicLightsAdapter(
            OptifineDynamicLightsAdapter.Part.LIGHT));
        adapters.put("optifine-dynamic-map-access", new OptifineDynamicLightsAdapter(
            OptifineDynamicLightsAdapter.Part.MAP));
        adapters.put("rustic-lattice-cache", new RusticLatticeAdapter());
        adapters.put("xaero-texture-batch", new XaeroTextureUploadAdapter());
        adapters.put("renderlib-visibility-cache", new RenderLibTileEntityAdapter());
        adapters.put("srp-kirin-static-subtree", new SrpKirinStaticMeshAdapter());
        adapters.put("srp-model-static-branches", new SrpKirinStaticMeshAdapter());
        adapters.put("srp-parasite-navigator", new SrpParasiteNavigatorAdapter());
        adapters.put("srp-target-linear-select", new SrpTargetSearchAdapter());
        adapters.put("srp-weakness-rules", new SrpWeaknessAdapter());
        adapters.put("srp-config-rules", new SrpConfigRulesAdapter());
        adapters.put("srpmixins-mob-cap", new SrpMobCapAdapter());
        adapters.put("srpmixins-infestation-cache", new SrpInfestationCacheAdapter());
        adapters.put("srp-climber-navigator", new SrpClimberNavigatorAdapter());
        adapters.put("srp-light-search", new SrpLightSearchAdapter());
        adapters.put("srp-heblu-pose-local", new SrpHebluPoseAdapter());
        adapters.put("minecraft-pending-tick-index", new MinecraftSaveTickAdapter());
        adapters.put("player-skull-item", new PlayerSkullItemAdapter());
        adapters.put("minecraft-autosave-slices", new MinecraftAutosaveAdapter());
        adapters.put("sound-filters-search", new SoundFiltersSearchAdapter());
        adapters.put("sound-filter-parameters", new SoundFilterParametersAdapter());
        adapters.put("librarian-capability-query", new LibrarianCapabilityAdapter());
        adapters.put("levelup-processor-snapshot", new LevelUpProcessorSnapshotAdapter());
        adapters.put("nuclearcraft-radiation-snapshot", new NuclearRadiationSnapshotAdapter());
        adapters.put("loaded-chunk-snapshot", new LoadedChunkSnapshotAdapter());
        adapters.put("fishing-biome-query", new FishingBiomeAdapter());
        adapters.put("nbt-constant-keys", new NormalStringConstantAdapter());
        adapters.put("otg-bo3-execution", new OtgBo3ExecutionAdapter());
        adapters.put("otg-object-preparation", new OtgObjectPreparationAdapter());
        adapters.put("otg-column-queries", new OtgColumnQueryAdapter());
        adapters.put("recurrent-reflection", new RecurrentReflectionAdapter());
        adapters.put("srp-architect-bucket", new SrpArchitectBucketAdapter());
        adapters.put("srp-architect-query", new SrpArchitectQueryAdapter());
        adapters.put("phosphor-local-propagation", new PhosphorLocalPropagationAdapter());
        adapters.put("lycanites-path-search-cache", new LycanitesNodeProcessorAdapter());
        adapters.put("lycanites-registry-single-probe", new LycanitesObjectManagerAdapter());
        adapters.put("lycanites-spawn-scan", new LycanitesSpawnScanAdapter());
        adapters.put("lycanites-block-membership", new LycanitesBlockMembershipAdapter());
        adapters.put("srpmixins-spawn-filter", new SrpSpawnFilterAdapter());
        adapters.put("konkrete-locale-reverse-index", new KonkreteLocaleAdapter());
        adapters.put("minecraft-chunk-compression-pipeline", new ChunkSaveCompressionAdapter(
            ChunkSaveCompressionAdapter.Part.ANVIL_PIPELINE));
        adapters.put("minecraft-region-compressed-write", new ChunkSaveCompressionAdapter(
            ChunkSaveCompressionAdapter.Part.REGION_RAW_WRITE));
        adapters.put("minecraft-section-nbt-palette", new ChunkSectionNbtAdapter(
            ChunkSectionNbtAdapter.Part.SECTION));
        adapters.put("minecraft-state-id-map-access", new ChunkSectionNbtAdapter(
            ChunkSectionNbtAdapter.Part.ID_MAP));
        adapters.put("minecraft-nbt-gzip-buffer", new ChunkSaveCompressionAdapter(
            ChunkSaveCompressionAdapter.Part.GZIP_BUFFER));
        adapters.put("optifine-reflector-forge-direct-calls", new ForgeBlockStateDirectAdapter(
            ForgeBlockStateDirectAdapter.Part.REFLECTOR_FORGE));
        adapters.put("optifine-blockstate-direct-calls", new ForgeBlockStateDirectAdapter(
            ForgeBlockStateDirectAdapter.Part.STATE_IMPLEMENTATION));
        adapters.put("lycanites-obj-display-list", new LycanitesObjRenderAdapter());
        adapters.put("lycanites-animator-identities", new LycanitesAnimatorAdapter());
        adapters.put("lycanites-part-indexed", new LycanitesModelObjPartAdapter());
        adapters.put("lycanites-frame-dispatch", new LycanitesAnimationFrameAdapter());
        adapters.put("lycanites-lowercase-cache", new LycanitesLowercaseAdapter());
        adapters.put("lycanites-effect-slots", new LycanitesPotionEffectsAdapter());
        adapters.put("mobends-model-part", new MoBendsModelPartAdapter());
        adapters.put("mobends-transform-state-batch", new MoBendsTransformStateBatchAdapter());
        adapters.put("mobends-quaternion-cache", new MoBendsQuaternionAdapter());
        adapters.put("mobends-climbing-shortcut", new MoBendsLivingEntityDataAdapter());
        adapters.put("iceandfire-pose-local", new IceAndFirePoseAdapter());
        adapters.put("iceandfire-particle-args", new IceAndFireSeaSerpentAdapter());
        adapters.put("iceandfire-path-search-cache", new IceAndFireNodeProcessorAdapter());
        adapters.put("orelib-gl-state-cache", new OreLibOpenGlStateAdapter());
        adapters.put("betterfoliage-ao-scratch", new BetterFoliageAoScratchAdapter());
        adapters.put("dynamic-trees-connections", new DynamicTreesConnectionAdapter());
        adapters.put("otg-bo4-runtime", new OtgBo4Adapter());
        adapters.put("otg-bo4-column-layout", new OtgBo4ConfigAdapter());
        adapters.put("otg-bo4-column-order", new OtgBo4ColumnOrderAdapter());
        adapters.put("otg-biome-array-clear", new OtgArrayClearAdapter());
        adapters.put("recurrent-cloud-field", new RecurrentCloudFieldAdapter());
        adapters.put("recurrent-value-workspace", new RecurrentValueWorkspaceAdapter());
        adapters.put("otg-comma-parser", new OtgStringHelperAdapter());
        adapters.put("otg-function-name-cache", new OtgResourcesAdapter());
        adapters.put("otg-bo3-metadata-cache", new OtgBo3MetadataAdapter());
        adapters.put("otg-settings-file-cache", new OtgFileSettingsReaderAdapter());
        adapters.put("otg-configuration-generation", new OtgConfigurationGenerationAdapter());
        adapters.put("otg-structure-compression", new OtgStructureCompressionAdapter());
        adapters.put("otg-structure-save-allocations", new OtgStructureSaveAllocationAdapter());
        adapters.put("player-skull-async", new PlayerSkullLayerAdapter());
        adapters.put("player-skull-renderer", new PlayerSkullRendererAdapter());
        adapters.put("bettercaves-noise-tuple", new BetterCavesNoiseTupleAdapter());
        adapters.put("bettercaves-noise-column", new BetterCavesNoiseColumnAdapter());
        adapters.put("bettercaves-noise-generation", new BetterCavesNoiseGenAdapter());
        adapters.put("bettercaves-threshold-column", new BetterCavesCaveCarverAdapter());
        adapters.put("betterfoliage-optifine-colors", new BetterFoliageOptifineColorAdapter());
        adapters.put("qualitytools-stable-attributes", new QualityToolsAttributeAdapter());
        adapters.put("entity-collision-boxes", new CollisionBoxAllocationAdapter());
        adapters.put("entity-nearest-query", new RltNearestEntityAdapter());
        adapters.put("minecraft-equipment-nbt", new EquipmentNbtAdapter());
        adapters.put("minecraft-nbt-map-access", new NbtCompoundAdapter());
        adapters.put("potioncore-entity-nbt", new PotionCoreNbtAdapter());
        adapters.put("qualitytools-horse-inventory", new HorseArmorAccessAdapter());
        adapters.put("srp-light-candidate-index", new SrpLightCandidateIndexAdapter());
        adapters.put("quark-item-sync-state", new QuarkItemSyncAdapter());
        adapters.put("vanilla-chunk-dispatch-policy", new VanillaChunkRenderAdapter(
            VanillaChunkRenderAdapter.Part.DISPATCH_POLICY));
        adapters.put("vanilla-chunk-vbo-dispatch", new VanillaChunkRenderAdapter(
            VanillaChunkRenderAdapter.Part.DISPATCH_UPLOAD));
        adapters.put("vanilla-chunk-primitive-sort", new VanillaChunkRenderAdapter(
            VanillaChunkRenderAdapter.Part.BUFFER_SORT));
        adapters.put("vanilla-chunk-vbo-access", new VanillaChunkRenderAdapter(
            VanillaChunkRenderAdapter.Part.VERTEX_BUFFER_ACCESS));
        adapters.put("modern-terrain-upload-context", new ModernTerrainAdapter(
            ModernTerrainAdapter.Part.UPLOAD_CONTEXT));
        adapters.put("modern-terrain-container-access", new ModernTerrainAdapter(
            ModernTerrainAdapter.Part.CONTAINER_ACCESS));
        adapters.put("modern-terrain-vbo-emitter", new ModernTerrainAdapter(
            ModernTerrainAdapter.Part.VBO_RENDER_LIST));
        adapters.put("modern-active-render-matrices", new RenderMatrixAdapter());
        adapters.put("modern-visibility-render-global", new PrimitiveTerrainVisibilityAdapter(
            PrimitiveTerrainVisibilityAdapter.Part.RENDER_GLOBAL));
        adapters.put("modern-visibility-render-info", new PrimitiveTerrainVisibilityAdapter(
            PrimitiveTerrainVisibilityAdapter.Part.RENDER_INFO));
        adapters.put("modern-visibility-render-chunk", new PrimitiveTerrainVisibilityAdapter(
            PrimitiveTerrainVisibilityAdapter.Part.RENDER_CHUNK));
        adapters.put("modern-visibility-compiled-chunk", new PrimitiveTerrainVisibilityAdapter(
            PrimitiveTerrainVisibilityAdapter.Part.COMPILED_CHUNK));
        adapters.put("modern-visibility-set-mask", new PrimitiveTerrainVisibilityAdapter(
            PrimitiveTerrainVisibilityAdapter.Part.SET_VISIBILITY));
        adapters.put("modern-gl-state-open-helper", new GlStateTrackingAdapter(
            GlStateTrackingAdapter.Part.OPENGL_HELPER));
        adapters.put("modern-gl-state-manager", new GlStateTrackingAdapter(
            GlStateTrackingAdapter.Part.GL_STATE_MANAGER));
        adapters.put("modern-gl-state-boolean", new GlStateTrackingAdapter(
            GlStateTrackingAdapter.Part.BOOLEAN_STATE));
        adapters.put("modern-renderlib-entity-emitter", new RenderLibRendererAdapter(
            RenderLibRendererAdapter.Part.ENTITY));
        adapters.put("modern-renderlib-tesr-emitter", new RenderLibRendererAdapter(
            RenderLibRendererAdapter.Part.TESR));
        adapters.put("modern-modelrenderer-vbo", new ModelRendererVboAdapter());
        adapters.put("modern-common-model-state-batch", new CommonModelStateBatchAdapter());
        adapters.put("modern-texturedquad-capture", new TexturedQuadCaptureAdapter());
        adapters.put("modern-advancedmodelrenderer-vbo",
            new AdvancedModelRendererVboAdapter());
        adapters.put("modern-particle-manager", new ParticleRenderAdapter(
            ParticleRenderAdapter.Part.MANAGER));
        adapters.put("modern-particle-access", new ParticleRenderAdapter(
            ParticleRenderAdapter.Part.PARTICLE_ACCESS));
        adapters.put("fbp-particle-boundaries", new FbpParticleAdapter());
        adapters.put("fbp-cube-render-util", new FbpRenderUtilAdapter());
        adapters.put("modern-animation-texture-map", new AnimatedTextureAdapter(
            AnimatedTextureAdapter.Part.MAP));
        adapters.put("modern-animation-texture-sprite", new AnimatedTextureAdapter(
            AnimatedTextureAdapter.Part.SPRITE));
        adapters.put("modern-animation-visible-chunks",
            new AnimatedTextureVisibilityAdapter(
                AnimatedTextureVisibilityAdapter.Part.CHUNK_CONTAINER));
        adapters.put("modern-animation-visible-terrain-draw",
            new AnimatedTextureVisibilityAdapter(
                AnimatedTextureVisibilityAdapter.Part.CHUNK_DRAW));
        adapters.put("modern-animation-visible-buffer",
            new AnimatedTextureVisibilityAdapter(
                AnimatedTextureVisibilityAdapter.Part.TESSELLATOR));
        adapters.put("modern-hud-overlay", new HudRenderAdapter(
            HudRenderAdapter.Part.OVERLAY));
        adapters.put("modern-hud-gui", new HudRenderAdapter(
            HudRenderAdapter.Part.GUI));
        adapters.put("modern-hud-font", new HudRenderAdapter(
            HudRenderAdapter.Part.FONT));
        adapters.put("modern-hud-tessellator", new HudRenderAdapter(
            HudRenderAdapter.Part.TESSELLATOR));
        adapters.put("optifine-shader-program-lifecycle",
            new OptifineShaderLifecycleAdapter());
        adapters.put("optifine-vbo-region-observer",
            new OptifineVboRegionAdapter());
        adapters.put("optifine-resolved-shader-source",
            new OptifineShaderSourceAdapter());
        adapters.put("modern-pass-lifecycle",
            new RenderPassLifecycleAdapter());
        adapters.put("optifine-pass-lifecycle",
            new OptifinePassLifecycleAdapter());
        adapters.put("ichun-worldportal-legacy-island",
            new WorldPortalAdapter());
        ADAPTERS = Collections.unmodifiableMap(adapters);
    }

    private OptimizerAdapterRegistry() {
    }

    static OptimizerBytecodeAdapter find(String id) {
        return ADAPTERS.get(id);
    }
}
