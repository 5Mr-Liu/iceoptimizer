package dev.rlcraft.ice.optimizer;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Independently switchable RLCraft optimizations. Every module retains a
 * fail-open path to the unmodified game implementation. The boolean flag
 * marks modules reviewed for execution on a physical dedicated server.
 */
public enum OptimizationModule {
    CORE_RUNTIME("core-runtime", "优化运行时", true),
    SRP_STATIC_MESH("srp-static-mesh", "SRP 多模型静态分支批处理"),
    SRP_PATH_NODE_CACHE("srp-path-node-cache", "SRP 单次寻路节点缓存", true),
    SRP_TARGET_SEARCH("srp-target-search", "SRP 最近目标线性选择", true),
    SRP_POSE_CACHE("srp-pose-cache", "SRP Kirin/Heblu 姿态缓存"),
    SRP_PARTICLE_COLLISION("srp-particle-collision", "SRP 粒子碰撞复用"),
    VANILLA_CHUNK_DISPATCH("vanilla-chunk-dispatch", "原版区块工作线程与缓冲调度"),
    VANILLA_CHUNK_SORT("vanilla-chunk-sort", "原版透明区块原始类型排序"),
    VANILLA_CHUNK_VBO_UPLOAD("vanilla-chunk-vbo-upload", "原版区块 VBO GPU 复制流水线"),
    OPTIFINE_DYNAMIC_LIGHTS("optifine-dynamic-lights", "OptiFine 动态光源不可变快照"),
    RUSTIC_LATTICE_STATE("rustic-lattice-state", "Rustic 栅栏连接状态与包围盒缓存", true),
    VANILLA_SAVE_TICK_INDEX("vanilla-save-tick-index", "全量保存计划刻临时索引", true),
    LYCANITES_PATH_NODE_CACHE("lycanites-path-node-cache", "Lycanites 单次寻路缓存", true),
    LYCANITES_REGISTRY_LOOKUP("lycanites-registry-lookup", "Lycanites 注册表单次探测", true),
    LYCANITES_SPAWN_SCAN("lycanites-spawn-scan", "Lycanites 刷怪方块扫描", true),
    LYCANITES_OBJ_RENDER("lycanites-obj-render", "Lycanites OBJ/VBO 分组批处理"),
    LYCANITES_MODEL_ANIMATION("lycanites-model-animation", "Lycanites 模型动画与名称缓存"),
    LYCANITES_EFFECT_CACHE("lycanites-effect-cache", "Lycanites 实体效果槽缓存", true),
    MOBENDS_MODEL_RENDER("mobends-model-render", "Mo' Bends 父链与子模型优化"),
    MOBENDS_QUATERNION_CACHE("mobends-quaternion-cache", "Mo' Bends 四元数矩阵缓存"),
    MOBENDS_ENTITY_ANIMATION("mobends-entity-animation", "Mo' Bends 实体动画查询优化"),
    ICEANDFIRE_POSE_LOOKUP("iceandfire-pose-lookup", "Ice and Fire 姿态局部查询"),
    ICEANDFIRE_PARTICLE_SCRATCH("iceandfire-particle-scratch", "Ice and Fire 粒子参数复用", true),
    FOAMFIX_TEXTURE_UPLOAD("foamfix-texture-upload", "FoamFix / TextureUtil 纹理上传流水线"),
    XAERO_TEXTURE_UPLOAD("xaero-texture-upload", "Xaero 纹理上传合并"),
    XAERO_GPU_FENCE("xaero-gpu-fence", "Xaero GPU Fence"),
    RENDERLIB_VISIBILITY("renderlib-visibility", "RenderLib 方块实体合并"),
    ORELIB_GL_STATE("orelib-gl-state", "OreLib / DS GL 状态快照"),
    CHUNK_MESH_AO("chunk-mesh-ao", "Better Foliage 区块 AO 暂存复用"),
    CHUNK_MESH_DYNAMIC_TREES("chunk-mesh-dynamic-trees", "Dynamic Trees 连接数据复用"),
    BETTER_CAVES_NOISE("better-caves-noise", "Better Caves 原始噪声与列缓存", true),
    BETTER_FOLIAGE_OPTIFINE_COLORS("betterfoliage-optifine-colors", "Better Foliage OptiFine 颜色访问"),
    QUALITY_TOOLS_ATTRIBUTES("qualitytools-attributes", "Quality Tools 稳定装备属性复用", true),
    QUARK_ITEM_SYNC("quark-item-sync", "Quark 掉落物同步状态复用", true),
    OTG_BO4_IO("otg-bo4-io", "OTG BO4 运行时回写抑制", true),
    OTG_CONFIG_PARSER("otg-config-parser", "OTG 配置函数低分配解析", true),
    OTG_BO4_LAYOUT("otg-bo4-layout", "OTG BO4 布局与方块数组复用", true),
    SKULL_PROFILE_ASYNC("skull-profile-async", "玩家头颅资料异步解析"),
    RENDER_SUBMISSION("render-submission", "有界渲染提交后端"),
    // Append-only: injected ordinal call sites depend on all previous ordinals staying stable.
    LYCANITES_BLOCK_MEMBERSHIP("lycanites-block-membership", "Lycanites 方块列表成员索引", true),
    SRP_SPAWN_FILTER("srp-spawn-filter", "SRPMixins 刷怪过滤编译路径", true),
    KONKRETE_LOCALE_LOOKUP("konkrete-locale-lookup", "Konkrete 本地化反向索引"),
    VANILLA_CHUNK_COMPRESSION("vanilla-chunk-compression", "区块 NBT 并行压缩与顺序写盘", true),
    FORGE_BLOCKSTATE_DIRECT_CALLS("forge-blockstate-direct-calls", "区块光照/AO Forge 直调"),
    // Modern renderer modules are runtime-managed. Keep every entry below the
    // append-only boundary: injected ordinal call sites depend on every older
    // value retaining its ordinal.
    MODERN_FRAME_COORDINATOR("modern-frame-coordinator", "现代渲染帧协调器"),
    MODERN_TERRAIN_BACKEND("modern-terrain-backend", "现代地形双后端"),
    MODERN_VISIBILITY_HZB("modern-visibility-hzb", "连续可见性与保守 HZB"),
    MODERN_ENTITY_BACKEND("modern-entity-backend", "现代实体 Packet 后端"),
    MODERN_TESR_BACKEND("modern-tesr-backend", "现代 TESR Packet 后端"),
    MODERN_PARTICLE_BACKEND("modern-particle-backend", "现代粒子实例流"),
    MODERN_TEXTURE_STREAM("modern-texture-stream", "现代动画纹理上传流"),
    MODERN_HUD_STREAM("modern-hud-stream", "现代字体与 HUD 动态流"),
    OPTIFINE_REGION_BACKEND("optifine-region-backend", "OptiFine Region 兼容后端"),
    OPTIFINE_SHADER_BRIDGE("optifine-shader-bridge", "OptiFine ShaderPack 认证桥"),
    LEGACY_GL_ISLAND("legacy-gl-island", "Legacy GL 兼容岛"),
    RENDER_VALIDATION("render-validation", "现代渲染正确性与收益验证"),
    // Separate CPU visibility fuse: HZB failure must never disable the exact
    // RenderGlobal traversal replacement, and vice versa.
    MODERN_VISIBILITY_GRID("modern-visibility-grid", "连续区块可见性与 CPU 层级"),
    // FBP has observable internal Tessellator flushes and therefore owns a
    // separate structural fuse from the standard billboard backend.
    FBP_PARTICLE_ADAPTER("fbp-particle-adapter", "FBP 粒子内部提交边界适配"),
    // Persistent atlas mapping is separately fused: its failure must leave the
    // ordinary PBO animation stream operational.
    MODERN_TEXTURE_PERSISTENT_RING("modern-texture-persistent-ring",
        "动画纹理 Persistent PBO ring"),
    // MDI command streaming has an independent online benefit loop and fuse;
    // failure falls back to the already-certified terrain multi-draw path.
    MODERN_TERRAIN_MDI("modern-terrain-mdi", "地形 MDI 命令 ring"),
    // Persistent Arena writes are measured independently from terrain draw
    // batching; a shared-memory or driver regression keeps subdata active.
    MODERN_TERRAIN_PERSISTENT_MAPPING("modern-terrain-persistent-mapping",
        "地形 Arena Persistent Mapping"),
    // Visibility suppression owns the final ordinal/mask bit and is isolated
    // from both the PBO stream and its persistent mapping strategy.
    MODERN_TEXTURE_VISIBILITY("modern-texture-visibility",
        "动画纹理可见性与同步追帧"),
    // ShaderPack certification is split into four independently trippable
    // domains. A broken compiler/driver path must not disable state capture,
    // image validation, activation rollback, or any unrelated renderer.
    OPTIFINE_SHADER_COMPILE("optifine-shader-compile",
        "OptiFine Shader 临时编译认证"),
    OPTIFINE_SHADER_STATE("optifine-shader-state",
        "OptiFine Shader 状态认证"),
    OPTIFINE_SHADER_IMAGE("optifine-shader-image",
        "OptiFine Shader 离屏图像认证"),
    OPTIFINE_SHADER_ACTIVATION("optifine-shader-activation",
        "OptiFine Shader 安全激活与回滚"),
    // Keep non-renderer additions append-only as well. Runtime renderer
    // classification below is an explicit closed range so later server-side
    // modules cannot accidentally inherit renderer-generation semantics.
    OTG_SYNC_FILE_CACHE("otg-sync-file-cache",
        "OTG 同步配置与 BO3 NBT 安全缓存", true),
    ICEANDFIRE_PATH_NODE_CACHE("iceandfire-path-node-cache",
        "Ice and Fire 单次寻路缓存", true);

    private static final Map<String, OptimizationModule> MODULES_BY_ID;
    private static final Map<String, OptimizationModule> MODULES_BY_ENUM_NAME;

    static {
        OptimizationModule[] modules = values();
        Map<String, OptimizationModule> byId =
            new HashMap<String, OptimizationModule>(modules.length * 2);
        Map<String, OptimizationModule> byEnumName =
            new HashMap<String, OptimizationModule>(modules.length * 2);
        for (OptimizationModule module : modules) {
            OptimizationModule duplicateId = byId.put(module.id, module);
            OptimizationModule duplicateName =
                byEnumName.put(module.name().toUpperCase(Locale.ROOT), module);
            if (duplicateId != null || duplicateName != null) {
                throw new IllegalStateException("Duplicate optimization module identifier: " + module.id);
            }
        }
        MODULES_BY_ID = Collections.unmodifiableMap(byId);
        MODULES_BY_ENUM_NAME = Collections.unmodifiableMap(byEnumName);
    }

    private final String id;
    private final String displayName;
    private final boolean dedicatedServerSupported;

    OptimizationModule(String id, String displayName) {
        this(id, displayName, false);
    }

    OptimizationModule(String id, String displayName, boolean dedicatedServerSupported) {
        this.id = id;
        this.displayName = displayName;
        this.dedicatedServerSupported = dedicatedServerSupported;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean supports(OptimizerRuntimeSide side) {
        return side == OptimizerRuntimeSide.CLIENT || dedicatedServerSupported;
    }

    public boolean isDedicatedServerSupported() {
        return dedicatedServerSupported;
    }

    /**
     * Runtime-managed renderer modules have no single target class whose ASM
     * installation can serve as their readiness gate. Their own capability,
     * output and benefit state machines remain the activation authority.
     */
    public boolean isRuntimeManagedRenderer() {
        return ordinal() >= MODERN_FRAME_COORDINATOR.ordinal()
            && ordinal() <= OPTIFINE_SHADER_ACTIVATION.ordinal();
    }

    public static OptimizationModule byId(String id) {
        if (id == null) return null;
        OptimizationModule module = MODULES_BY_ID.get(id);
        return module != null ? module : MODULES_BY_ENUM_NAME.get(id.toUpperCase(Locale.ROOT));
    }
}
