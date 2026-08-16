package dev.rlcraft.ice.optimizer;

import dev.rlcraft.ice.IceMod;
import net.minecraftforge.common.config.Config;

@Config(modid = IceMod.MOD_ID, name = "ice-optimizer")
public final class OptimizerConfig {
    @Config.Comment("RLCraft-specific client and dedicated-server optimization runtime. Every patch is independently fail-open.")
    public static Settings settings = new Settings();

    @Config.Comment("The optimizer has no normal HUD. This controls its compact F3-only status.")
    public static Display display = new Display();

    private OptimizerConfig() {
    }

    public static final class Display {
        @Config.Comment("Append compact optimizer, chunk-pipeline and queue status lines only while Minecraft's F3 debug screen is open.")
        public boolean showF3Summary = true;
    }

    public static final class Settings {
        @Config.Comment("Enable the RLCraft-specific optimization runtime on this physical client or dedicated server.")
        public boolean enabled = true;

        @Config.Comment({
            "Legacy compatibility option retained so existing configuration files continue to load.",
            "Since 0.8.0 RLCraft/Dregora versions and whole-JAR hashes never block a patch;",
            "each adapter instead validates the target method signatures and instruction graph."
        })
        public boolean strictPackLock = false;

        @Config.Comment({
            "Allow optimizer development artifacts on disk: unknown transformed classes under ice-optimizer/discovery",
            "and the diagnostic component snapshot in ice-optimizer/components-observed.properties. Disabled by default.",
            "Because class discovery runs before normal Forge config loading, changing this value takes effect on the next launch."
        })
        public boolean developmentDiskOutput = false;

        @Config.Comment("Dedicated optimizer worker count. Zero selects CPU count minus two, clamped to 1..6.")
        @Config.RangeInt(min = 0, max = 12)
        public int workerThreads = 0;

        @Config.RangeInt(min = 64, max = 16384)
        public int workerQueueCapacity = 1024;

        @Config.RangeInt(min = 64, max = 16384)
        public int renderQueueCapacity = 2048;

        @Config.RangeInt(min = 100, max = 10000)
        public int renderDrainBudgetMicros = 1500;

        @Config.RangeInt(min = 16, max = 2048)
        public int heapCacheBudgetMiB = 256;

        @Config.RangeInt(min = 16, max = 2048)
        public int directCacheBudgetMiB = 192;

        @Config.RangeInt(min = 16, max = 4096)
        public int gpuCacheBudgetMiB = 256;

        @Config.RangeInt(min = 1, max = 20)
        public int circuitBreakerFailures = 3;

        public boolean srpStaticMesh = true;

        @Config.Comment("Cache vanilla SRP walk-node classifications only for the duration of one PathFinder call.")
        public boolean srpPathNodeCache = true;

        @Config.Comment("Select SRP's nearest non-player target in linear time instead of sorting the whole private list.")
        public boolean srpTargetSearch = true;

        public boolean srpPoseCache = true;
        public boolean srpParticleCollision = true;

        @Config.Comment({
            "Compile SRPMixins spawn wrappers into primitive arrays and memoize repeated pure checks within one call.",
            "Result order and all dynamic SRP state checks remain unchanged."
        })
        public boolean srpSpawnFilter = true;

        @Config.Comment({
            "Reserve CPU capacity for the client and integrated-server main threads instead of letting",
            "vanilla chunk rebuild workers consume every logical processor. The original priority queue",
            "and chunk results are unchanged; only worker and reusable builder counts are bounded."
        })
        public boolean vanillaChunkDispatch = true;

        @Config.Comment({
            "Replace BufferBuilder's boxed stable translucent-quad sort with an equivalent primitive stable sort.",
            "Distances, tie order and final vertex bytes remain identical."
        })
        public boolean vanillaChunkSort = true;

        @Config.Comment({
            "Use a bounded fenced staging ring and GPU buffer copies for chunk VBO uploads through OpenGL core or ARB extensions.",
            "Busy slots, unsupported drivers and any error immediately use vanilla glBufferData."
        })
        public boolean vanillaChunkVboUpload = true;

        @Config.Comment({
            "Publish OptiFine's existing 50 ms dynamic-light update as an immutable primitive snapshot.",
            "Lighting values and update timing are unchanged; chunk workers stop sharing the light-map lock."
        })
        public boolean optifineDynamicLights = true;

        @Config.Comment({
            "Canonicalize Rustic lattice connection states and reuse the 64 exact bounding boxes.",
            "All six neighbor queries and connection rules remain unchanged."
        })
        public boolean rusticLatticeStateCache = true;

        @Config.Comment({
            "During one synchronous full save, index the unchanged scheduled-tick sets once per mutation version.",
            "Chunk order, tick order, NBT content, save timing and all world writes remain on the original server thread."
        })
        public boolean vanillaSaveTickIndex = true;

        @Config.Comment("Cache Lycanites block-state and node-type queries only inside one CreatureNodeProcessor search.")
        public boolean lycanitesPathNodeCache = true;

        @Config.Comment("Remove redundant containsKey plus get probes from Lycanites' hot block/effect registries.")
        public boolean lycanitesRegistryLookup = true;

        @Config.Comment({
            "Reduce temporary maps and duplicate pure block-state reads in the reviewed Lycanites spawn scan.",
            "Unreviewed subclasses, fluid blocks and unusual mutable configuration lists use the original reads."
        })
        public boolean lycanitesSpawnScan = true;

        @Config.Comment({
            "Index the standard Lycanites block-id list while preserving List order and mutation semantics.",
            "Nonstandard replacement lists keep their original contains implementation."
        })
        public boolean lycanitesBlockMembership = true;

        @Config.Comment("Cache stable Lycanites OBJ/VBO render groups in exact OpenGL display lists with bounded GPU accounting.")
        public boolean lycanitesObjRender = true;

        @Config.Comment("Remove identity GL operations, iterator allocation and repeated lowercase work from Lycanites models.")
        public boolean lycanitesModelAnimation = true;

        @Config.Comment("Resolve Lycanites' constant potion-effect names once instead of repeating registry normalization for every living entity tick.")
        public boolean lycanitesEffectCache = true;

        @Config.Comment("Cache Mo' Bends model parent topology and use indexed child traversal without changing transform or draw order.")
        public boolean moBendsModelRender = true;

        @Config.Comment("Reuse Mo' Bends quaternion matrices while all four raw float components remain unchanged.")
        public boolean moBendsQuaternionCache = true;

        @Config.Comment("Avoid Mo' Bends' three extra block-state reads for entities that are not climbing.")
        public boolean moBendsEntityAnimation = true;

        @Config.Comment("Reuse Ice and Fire Tabula pose lookups inside one exact animation pass.")
        public boolean iceAndFirePoseLookup = true;

        @Config.Comment("Reuse immutable empty particle argument arrays in reviewed Ice and Fire sea-serpent paths.")
        public boolean iceAndFireParticleScratch = true;

        @Config.Comment({
            "Use fenced PBO uploads only for large animated-texture batches through FoamFix's batch helper.",
            "Single mip levels, small sprites, busy slots and unsupported drivers keep the original upload path."
        })
        public boolean foamFixTextureUpload = true;
        public boolean xaeroTextureUpload = true;
        public boolean xaeroGpuFence = true;
        public boolean renderLibVisibility = true;
        public boolean oreLibGlState = true;

        @Config.Comment("Reuse Better Foliage's per-worker ambient-occlusion scratch array and BitSet without changing AO calculations.")
        public boolean betterFoliageAoScratch = true;

        @Config.Comment("Reuse Dynamic Trees connection radii across repeated face queries for the same immutable extended block state.")
        public boolean dynamicTreesConnectionMemo = true;

        @Config.Comment({
            "Use primitive Better Caves noise tuples/columns, collapse interpolation temporaries,",
            "reuse duplicate corner-noise evaluations through exact deep copies, and replace per-column threshold HashMaps."
        })
        public boolean betterCavesNoisePipeline = true;

        @Config.Comment("Cache OptiFine's custom-color field lookup used by Better Foliage chunk mesh workers.")
        public boolean betterFoliageOptifineColors = true;

        @Config.Comment("Skip Quality Tools' full modifier teardown/rebuild for unchanged non-player equipment, with periodic verification.")
        public boolean qualityToolsEntityAttributes = true;

        @Config.Comment("Track Quark dropped-item age/lifespan changes without two WeakHashMaps and per-tick boxed Integers.")
        public boolean quarkItemSync = true;

        @Config.Comment({
            "Prevent OTG 9.7 from rewriting parsed BO4 source files during world generation.",
            "Parsing and generated content are unchanged; only the redundant WriteWithoutComments disk output is skipped."
        })
        public boolean otgBo4WriteSuppression = true;

        @Config.Comment("Use allocation-reduced, result-equivalent parsing for OTG configuration function arguments and names.")
        public boolean otgConfigParser = true;

        @Config.Comment("Reuse OTG BO4 block arrays inside one spawn and precompute immutable column offsets while loading a BO4.")
        public boolean otgBo4Layout = true;

        @Config.Comment("Resolve incomplete player-skull profiles away from the render thread with bounded positive, negative and in-flight caches.")
        public boolean skullProfileAsync = true;

        @Config.Comment({
            "Build one resource-generation-scoped reverse index for Konkrete localization values.",
            "Unknown map implementations or reload races execute Konkrete's original reflective scan."
        })
        public boolean konkreteLocaleLookup = true;

        @Config.Comment({
            "Compress completed chunk NBT snapshots on a bounded dedicated worker pool, then write them",
            "from vanilla's file-I/O thread in the original order. Queue pressure or incompatibility falls back immediately."
        })
        public boolean vanillaChunkCompression = true;

        @Config.Comment({
            "Replace reviewed OptiFine Reflector light/AO calls with equivalent Forge virtual calls.",
            "The original reflective methods remain as fallback when a target shape or invocation fails."
        })
        public boolean forgeBlockStateDirectCalls = true;

        @Config.RangeInt(min = 64, max = 8192)
        public int skullProfileCacheEntries = 2048;

        @Config.RangeInt(min = 5, max = 1440)
        public int skullProfilePositiveTtlMinutes = 360;

        @Config.RangeInt(min = 10, max = 3600)
        public int skullProfileNegativeTtlSeconds = 300;

        @Config.RangeInt(min = 16, max = 1024)
        public int skullProfileQueueCapacity = 128;
    }
}
