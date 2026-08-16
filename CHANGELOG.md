# Changelog / 更新日志

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
