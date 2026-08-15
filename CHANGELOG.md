# Changelog / 更新日志

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
