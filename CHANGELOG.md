# Changelog / 更新日志

## 0.9.3 — 2026-08-16

### 中文

- 普通 RLCraft 新采样确认稳定阶段约 248–313 FPS、GPU 用时约 1.66–2.14 ms，主要卡顿位于单人集成服务器的世界生成与保存链路，而不是持续 GPU 饱和。
- 修复 ICE 自身的热路径回退：`OptimizationModule.byId` 不再为每次开关检查分配 `values()` 数组并逐项执行 `equals/equalsIgnoreCase`，改为启动期构建的 O(1) 精确 ID 表与兼容枚举名表。
- Better Caves 的 `NoiseTuple`、`NoiseColumn`、`NoiseGen` 和 `CaveCarver` 共享门缓存稳定的 `ModuleCircuitBreaker` 引用。每次仍读取实时状态，配置关闭、结构不兼容或运行时熔断会立即回退原实现；没有缓存永久 boolean。
- 新增字节码性能回归，禁止模块查询恢复线性扫描，也禁止 Better Caves 热路径重新调用字符串版 `OptimizerBridge.isEnabled`。这次不改变洞穴噪声、随机数、区块内容、保存顺序或画面结果。
- 公开优化器工程使用普通 RLCraft 与 Dregora 真实目标 JAR、OptiFine G5、Rustic 1.1.7、Better Caves 2.0.4 和 Forge 1.12.2 SRG 执行 130 项测试：0 失败、0 错误，1 项仅因缺少可选运行期 Better Foliage 单类样本而跳过；重混淆、主/Core 分包和 bundle 校验通过。

### English

- New standard-RLCraft captures show roughly 248–313 FPS and 1.66–2.14 ms GPU time during stable play; the major stalls are integrated-server world generation and saves, not sustained GPU saturation.
- Fixed an ICE regression in the hot gate: `OptimizationModule.byId` no longer allocates `values()` arrays and linearly runs `equals/equalsIgnoreCase` for every module check. Canonical IDs now use a startup-built O(1) table while legacy enum-name lookup remains compatible.
- The shared Better Caves gate for `NoiseTuple`, `NoiseColumn`, `NoiseGen`, and `CaveCarver` caches the stable `ModuleCircuitBreaker` reference but reads its live state on every entry. Configuration changes, structural incompatibility, and runtime trips still fall back immediately; no permanent boolean is cached.
- Added bytecode performance regressions that forbid reintroducing linear module scans or the string-based `OptimizerBridge.isEnabled` call in the Better Caves hot gate. Cave noise, RNG, chunk contents, save order, and rendering results are unchanged.
- The public optimizer project ran 130 tests against real standard-RLCraft and Dregora targets, OptiFine G5, Rustic 1.1.7, Better Caves 2.0.4, and Forge 1.12.2 SRG: zero failures, zero errors, and one skip only for an optional runtime-transformed Better Foliage class fixture. Reobfuscation, main/Core separation, and bundle verification passed.

## 0.9.2 — 2026-08-16

### 中文

- 新增 OptiFine 动态光不可变 primitive 快照；仍在原 50 ms 更新边界刷新，96 个以上光源自动使用 8 方块空间索引。距离、亮度、水下衰减和 Clear Water 语义保持一致。
- 新增 Rustic 栅栏六方向状态与 64 个精确 AABB 复用；连接判定仍执行 Rustic 原方法，渲染、碰撞、粒子和寻路结果不变。
- Fermium/NormalASM 环境不再整体跳过区块线程限制；ICE 在其最终 worker/builder 改写后只做硬件与堆内存自适应上限，不替换 Fermium 策略。
- FoamFix 动画纹理 PBO 增加可靠的原版 `TextureUtil` 单级入口，并同时支持 OpenGL 3.2 核心 Sync 和 `GL_ARB_sync`。能力缺失、槽位忙、预算不足或异常时立即执行原上传。
- 目标目录增至 61 个唯一类、62 个独立能力项；新增真实 OptiFine G5、Rustic 1.1.7、FoamFix 0.10.15、Forge SRG TextureUtil 与 Fermium 后置策略回归。

### English

- Added immutable primitive snapshots for OptiFine dynamic lights, refreshed on the original 50 ms boundary, with an exact 8-block spatial index above 96 lights. Distance, brightness, underwater attenuation, and Clear Water behavior are preserved.
- Added Rustic lattice six-direction state reuse and 64 exact AABBs. Rustic's original connectivity checks still run, preserving rendering, collision, particles, and pathfinding.
- Fermium/NormalASM no longer disables the dispatcher policy wholesale. ICE clamps only the final worker/builder values after Fermium has applied its own strategy, using hardware-and-heap tiers without replacing that strategy.
- Added a reliable vanilla `TextureUtil` entry for FoamFix animated-texture PBO uploads and support for both OpenGL 3.2 core Sync and `GL_ARB_sync`. Missing capabilities, busy slots, budget rejection, or errors immediately use the original upload.
- The catalog grows to 61 unique classes and 62 independent capability entries, with real OptiFine G5, Rustic 1.1.7, FoamFix 0.10.15, Forge SRG TextureUtil, and post-Fermium regression fixtures.

## 0.9.1 — 2026-08-16

### 中文

- 同一个目标类现在可以注册多个独立能力并依次转换；一项结构不兼容只回退这一项，后续能力继续安装。Fermium 改写 `ChunkRenderDispatcher` 线程/构建器策略时，不再连带关闭兼容的 VBO 上传 wrapper。
- 区块线程适配移除固定 `j * 10` 片段和 `availableProcessors()` 后 16 条指令距离限制，改为验证必要字段写入与初始化关系。类 SHA-256 仍只用于审计，不作为运行白名单。
- Chunk Worker 改为 CPU 与 JVM 堆双重分档：低配机从 1 个起，24–31 逻辑线程平台可到 12 个，32+ 可到 16 个，同时永远不超过原实现算出的数量。
- 区块 VBO staging 新增 `GL_ARB_copy_buffer` 后端，并继续支持 OpenGL 3.1 核心 copy、OpenGL 3.2 核心 sync 或 `GL_ARB_sync`。F3 区分 `GL31-COPY` 与 `ARB-COPY`。
- F3 新增 `MISS`，明确显示已经观察到但至少一项独立结构能力未安装的模块。目标目录为 56 个唯一类、57 个独立能力项。
- 优化器公开工程使用 Forge SRG、Dregora 捕获类和当前真实模组夹具执行 113 项测试：0 失败、0 错误、6 项可选旧版/Xaero 夹具跳过；重混淆、主/Core 分包与 bundle 校验通过。

### English

- A target class may now host multiple ordered, independent capabilities. A structural mismatch falls back only that capability and later adapters continue. Fermium's dispatcher policy no longer disables the otherwise compatible VBO-upload wrapper.
- Removed the fixed `j * 10` pattern and the 16-instruction distance limit after `availableProcessors()`. The dispatcher adapter now validates only the required field write and initialization relationship. Whole-class SHA-256 remains audit metadata, not a runtime allowlist.
- Chunk workers now use CPU-and-heap tiers: one worker on the smallest systems, up to 12 on 24–31 logical CPUs, and up to 16 on 32+ systems, never exceeding vanilla's computed value.
- Added a `GL_ARB_copy_buffer` VBO staging backend alongside OpenGL 3.1 core copy and core/ARB sync combinations. F3 reports `GL31-COPY` or `ARB-COPY`.
- Added F3 `MISS` for observed modules with at least one unmatched independent capability. The catalog now contains 56 unique classes and 57 capability entries.
- Ran 113 optimizer tests in the public project against Forge SRG, captured Dregora classes, and current real-mod fixtures: zero failures, zero errors, and six optional legacy/Xaero fixture skips; reobfuscation, main/Core separation, and bundle verification passed.

## 0.9.0 — 2026-08-16

### 中文

- 新增跨整合包通用的原版区块渲染流水线优化：按处理器规模为客户端与单人集成服务器保留 CPU，Chunk Worker 最多 8 个，可复用 `RegionRenderCacheBuilder` 由原版每 Worker 十个收敛为四个。原优先级、任务和结果不变。
- 将透明区块的 `Integer[] + Comparator + TimSort` 替换为 Chunk Worker 私有、可复用的原始类型稳定归并排序；距离、`Float.compare`/NaN、相等项顺序和最终顶点位保持一致。
- 在支持 OpenGL 3.1 与 Sync 的客户端上，为区块 VBO 增加六槽 Fence staging 与 GPU buffer copy。槽位忙、驱动不支持、数据过大、预算不足或异常时立即使用原 `glBufferData`，不会等待 GPU。
- F3 新增 `ICE Chunk`，并把 `PATCH`（补丁已安装）与 `HIT`（优化路径实际执行）分开；同时显示 Core JAR、Worker/Builder、已排序四边形、GPU 后端及上传/回退计数。缺少 Core JAR 时进入世界会提示一次。
- 新增同时包含主 JAR、Core JAR 和中英文安装说明的 bundle ZIP，减少只安装主 JAR而实际未启用底层补丁的情况。
- 修正模块状态：仅安装字节码补丁不再提前标记为已命中。目标目录由 53 个增至 56 个，并为新增的 `BufferBuilder` / `VertexBuffer` CoreMod ABI 增加分包和隔离加载校验。

### English

- Added a pack-wide vanilla chunk-render pipeline: reserve CPU capacity for the client and integrated server, cap Chunk Workers at eight, and reduce reusable `RegionRenderCacheBuilder` instances from vanilla's ten per worker to four. Priority, task, and result ordering are unchanged.
- Replaced translucent-chunk `Integer[] + Comparator + TimSort` with a reusable worker-local primitive stable merge sort while preserving distance calculations, `Float.compare`/NaN behavior, tie order, and final vertex bits.
- Added a six-slot fenced staging ring and GPU buffer copies for chunk VBO uploads on OpenGL 3.1 + Sync. Busy slots, unsupported drivers, oversized data, rejected budgets, or errors immediately use the original `glBufferData` path without waiting for the GPU.
- F3 now shows `ICE Chunk` and distinguishes `PATCH` (installed bytecode) from `HIT` (the optimized path actually ran), including Core-JAR presence, worker/builder counts, sorted quads, GPU backend, uploads, and fallbacks. A missing Core JAR produces one in-game warning.
- Added a bundle ZIP containing the main JAR, Core JAR, and bilingual installation instructions to reduce ineffective one-JAR installations.
- Corrected module state so installing a patch no longer counts as a runtime hit. The reviewed catalog grows from 53 to 56 classes, with split-package and isolated-loading checks for the new `BufferBuilder` / `VertexBuffer` CoreMod ABI.

## 0.8.1 — 2026-08-16

### 中文

- 新增原版全量保存计划刻临时索引，保持 TreeSet/当前列表顺序、区块边界重叠、同步保存时序及原始回退路径。
- 新增 Lycanites 刷怪位置扫描低分配计数器和严格受限的重复方块状态读取复用，不减少扫描体积、候选项或随机数调用。
- 修复 `PendingTickAccessor` 仅位于普通 Mod JAR 时导致 Forge 启动阶段 `NoClassDefFoundError` 的双 JAR 类加载问题。
- 新增 CoreMod 隔离加载和主/Core JAR 重复类回归校验；目标目录扩展至 53 个已审查类。

### English

- Added a temporary scheduled-tick index for vanilla full saves while preserving TreeSet/current-list order, chunk-boundary overlap, synchronous save timing, and the original fallback path.
- Added a low-allocation Lycanites spawn-position counter and strictly guarded duplicate block-state read reuse without reducing scan volume, candidates, or RNG calls.
- Fixed the split-JAR startup `NoClassDefFoundError` caused by keeping `PendingTickAccessor` only in the regular mod JAR during the early Forge CoreMod phase.
- Added isolated CoreMod loading and main/core duplicate-class regression checks; expanded the reviewed target catalog to 53 classes.

## 0.8.0 — 2026-08-15

### 中文

- 兼容性放行从整合包版本与整包 SHA-256 改为逐目标结构验证；不兼容目标独立回退。
- 新增 Better Caves 原始类型噪声存储、列缓存、插值临时对象收敛与连续阈值表。
- 新增 Better Foliage + OptiFine 颜色访问器缓存。
- 新增 Quality Tools 稳定装备属性复用和 Quark 掉落物同步状态优化。
- 目标目录扩展至 50 个已审查类。
- 客户端与服务端仍必须安装同一版本的优化器主 JAR，并同时安装 Core JAR。
- 本公开仓库与 Release 只发布优化器，不发布记录器。

### English

- Replaced pack-version and whole-JAR SHA gating with per-target structural validation and isolated fallback.
- Added primitive Better Caves noise storage, deep-copy column caching, collapsed interpolation temporaries, and contiguous threshold maps.
- Added cached Better Foliage + OptiFine color accessors.
- Added stable Quality Tools equipment-attribute reuse and optimized Quark dropped-item synchronization state.
- Expanded the reviewed target catalog to 50 classes.
- Clients and servers must still use the exact same optimizer main-JAR version and install the matching Core JAR.
- This public repository and its Releases publish the optimizer only; the recorder is not distributed.
