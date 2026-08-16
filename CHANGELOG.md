# Changelog / 更新日志

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
