# Changelog / 更新日志

## Unreleased — 2026-08-16

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
