# ICE RLCraft Optimizer

<p align="center">
  <img src="docs/images/cover.png" alt="ICE RLCraft Optimizer cover" width="512">
</p>

<p align="center">
  <a href="#中文">中文</a> · <a href="#english">English</a> ·
  <a href="https://github.com/5Mr-Liu/iceoptimizer/releases/latest">Releases</a>
</p>

---

## 中文

ICE RLCraft Optimizer 是面向 Minecraft 1.12.2 RLCraft 系整合包的客户端与专用服务端性能优化模组。它针对实际卡顿热点提供精确的字节码适配、受预算约束的缓存与工作队列，并在目标代码结构不兼容时保留原实现。

本仓库只包含优化器，不包含性能记录、采样、Session、报告导出或 F8/F9/F10 功能。

### 运行环境

| 项目 | 要求 |
| --- | --- |
| Minecraft | 1.12.2 |
| Forge | 14.23.5.2860 |
| Java | Java 8 |
| 当前版本 | 0.8.0 |
| 模组 ID | `iceoptimizer` |
| 运行端 | 客户端与服务端 |
| 已重点验证 | RLCraft 2.9.3、RLCraft Dregora 1.1.2b / DregoraRL 3.9 |

0.8.0 起不再按整个整合包版本或 JAR SHA-256 阻止优化。每个适配器会独立检查目标字段、方法描述符和精确指令结构；结构不匹配、桥接能力不完整或运行时异常时，只回退对应目标，不影响其他模块。

这并不代表任意修改版整合包都受到正式支持。出现问题时请先在上述已验证环境中复现。

### 安装

从 [GitHub Releases](https://github.com/5Mr-Liu/iceoptimizer/releases/latest) 下载同一版本的两个文件：

```text
ice-rlcraft-optimizer-0.8.0.jar
ice-rlcraft-optimizer-core-0.8.0.jar
```

将两个文件一起放入实例的 `mods` 目录。Core JAR 是必需组件，不是可选依赖。

- 单人游戏：安装到客户端实例。
- 多人游戏：客户端和专用服务端都必须安装两个文件。
- Forge 握手要求客户端与服务端的 ICE Optimizer 主 JAR 版本完全相同。
- 升级前请删除旧版 `ice-rlcraft-runtime-*`、旧 optimizer 主 JAR 和旧 optimizer core JAR。

启动后打开原版 F3 调试界面，右侧会显示两行紧凑的 `ICE Opt` / `ICE Q` 状态。普通游戏画面没有额外 HUD。

### 主要优化

- **SRParasites**：热点模型静态分支批处理、单次寻路节点缓存、最近目标线性选择及部分姿态/粒子路径。
- **Lycanites Mobs**：寻路节点缓存、注册表单次探测、OBJ/VBO 稳定分组、动画与效果热路径。
- **Mo' Bends / Ice and Fire**：父链、四元数、姿态查询及低分配粒子参数路径。
- **FoamFix / Xaero**：纹理上传暂存、PBO/Fence 和非阻塞 GPU 计时路径。
- **RenderLib / OreLib / Better Foliage / Dynamic Trees**：方块实体合并、GL 状态快照、AO 暂存和连接数据复用。
- **Better Caves**：原始类型噪声存储、深复制列缓存、插值临时对象收敛和连续阈值表。
- **Quality Tools / Quark**：稳定装备属性复用与掉落物同步状态的低分配实现。
- **Open Terrain Generator / BO4**：冗余源文件回写抑制、低分配配置解析、方块数组和列偏移复用。
- **玩家头颅**：将不完整资料的 Authlib 网络解析移出渲染线程，并使用有界缓存与队列。

具体模块可以在 `config/ice-optimizer.cfg` 中独立关闭。

### 行为与安全边界

优化器的目标是减少重复计算、分配、同步等待和高频容器开销。它不会故意：

- 删除实体、粒子、模型或游戏内容；
- 跳过游戏 Tick 或降低 AI 更新频率；
- 修改掉落、随机数调用、世界生成结果或写入顺序；
- 把世界或存档写入移动到未经验证的异步线程；
- 在普通运行中生成性能采集 Session。

每个模块拥有独立状态和连续错误熔断。缓存、Heap、Direct、GPU、CPU 队列和渲染队列均设有硬上限；任何校验、预算或生命周期条件不满足时都会使用原路径。

### 隐私与磁盘写入

默认 `settings.developmentDiskOutput=false`。正常安装只会产生 Forge 配置和 Minecraft/Forge 的常规日志；不会导出目标类、整合包组件快照或性能采集报告。

只有开发新适配器时才应启用 `developmentDiskOutput`。提交 Issue 前请自行检查并删除日志中的用户名、本机路径、服务器地址或其他不希望公开的信息。

### 从源码构建

要求 Java 8：

```powershell
./gradlew.bat clean test build
```

Linux/macOS：

```bash
./gradlew clean test build
```

正式产物位于：

```text
build/libs/ice-rlcraft-optimizer-<version>.jar
build/libs/ice-rlcraft-optimizer-core-<version>.jar
```

部分真实目标 JAR 回归测试需要通过 Gradle 属性提供本地测试夹具；缺少这些可选夹具不会影响普通源码构建和核心单元测试。

### 许可证

项目使用 [MIT License](LICENSE)。最终优化器主 JAR 私有重定位了 Agrona、Caffeine 和 lz4-java；详情见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

---

## English

ICE RLCraft Optimizer is a client and dedicated-server performance mod for Minecraft 1.12.2 RLCraft-family packs. It applies narrowly scoped bytecode adapters, bounded caches, and bounded worker queues to measured hot paths, while preserving the original implementation whenever a target is structurally incompatible.

This repository contains the optimizer only. It does not include performance recording, sampling, sessions, report export, or F8/F9/F10 features.

### Runtime requirements

| Item | Requirement |
| --- | --- |
| Minecraft | 1.12.2 |
| Forge | 14.23.5.2860 |
| Java | Java 8 |
| Current version | 0.8.0 |
| Mod ID | `iceoptimizer` |
| Environment | Client and server |
| Primary test targets | RLCraft 2.9.3 and RLCraft Dregora 1.1.2b / DregoraRL 3.9 |

Since 0.8.0, pack versions and whole-JAR SHA-256 values no longer gate optimizations. Each adapter independently validates target fields, method descriptors, and the exact instruction graph. If the structure, bridge capability, or runtime state is incompatible, only that target falls back to its original implementation.

This does not make every modified pack an officially supported target. Please reproduce issues on one of the primary test environments first.

### Installation

Download both files with the same version from [GitHub Releases](https://github.com/5Mr-Liu/iceoptimizer/releases/latest):

```text
ice-rlcraft-optimizer-0.8.0.jar
ice-rlcraft-optimizer-core-0.8.0.jar
```

Place both files in the instance `mods` directory. The Core JAR is required; it is not an optional dependency.

- Single player: install both files in the client instance.
- Multiplayer: install both files on every client and on the dedicated server.
- The Forge handshake requires the ICE Optimizer main JAR to have the exact same version on both sides.
- Remove old `ice-rlcraft-runtime-*`, optimizer, and optimizer-core JARs before upgrading.

Open the vanilla F3 debug screen after startup to see the compact `ICE Opt` and `ICE Q` lines. No regular HUD is displayed.

### Main optimization areas

- **SRParasites**: hot model branch batching, per-search path-node caching, stable linear target selection, and selected pose/particle paths.
- **Lycanites Mobs**: path-node caching, single registry probes, stable OBJ/VBO grouping, animation, and effect hot paths.
- **Mo' Bends / Ice and Fire**: parent topology, quaternion matrices, pose lookup, and low-allocation particle arguments.
- **FoamFix / Xaero**: texture upload staging, PBO/Fence paths, and non-blocking GPU timing.
- **RenderLib / OreLib / Better Foliage / Dynamic Trees**: tile-entity merging, GL state snapshots, AO scratch reuse, and connection memoization.
- **Better Caves**: primitive noise storage, deep-copy column caching, collapsed interpolation temporaries, and contiguous threshold maps.
- **Quality Tools / Quark**: stable equipment-attribute reuse and lower-allocation dropped-item synchronization state.
- **Open Terrain Generator / BO4**: redundant source rewrite suppression, lower-allocation parsing, and per-spawn block-array/layout reuse.
- **Player skulls**: bounded off-render-thread Authlib profile resolution for incomplete profiles.

Individual modules can be disabled in `config/ice-optimizer.cfg`.

### Behavioral and safety boundaries

The optimizer reduces repeated computation, allocation, synchronization stalls, and hot-container overhead. It is not intended to:

- remove entities, particles, models, or game content;
- skip ticks or lower AI update rates;
- change drops, random-number calls, world-generation results, or write ordering;
- move world/save writes to unverified asynchronous threads;
- create performance-capture sessions during normal operation.

Every module has independent state and a consecutive-error circuit breaker. Heap, direct-memory, GPU, CPU-queue, and render-queue usage is bounded. Failed validation, rejected budgets, or lifecycle mismatches use the original path.

### Privacy and disk output

`settings.developmentDiskOutput=false` by default. A normal installation only creates the Forge configuration and regular Minecraft/Forge logs; it does not export target classes, component snapshots, or profiling reports.

Only enable `developmentDiskOutput` while developing a new adapter. Before attaching logs to an issue, review and remove usernames, local paths, server addresses, or any other information you do not want to publish.

### Building from source

Java 8 is required.

Windows:

```powershell
./gradlew.bat clean test build
```

Linux/macOS:

```bash
./gradlew clean test build
```

Release artifacts are written to:

```text
build/libs/ice-rlcraft-optimizer-<version>.jar
build/libs/ice-rlcraft-optimizer-core-<version>.jar
```

Some real-target regression tests accept local fixture JARs through Gradle properties. Those optional fixtures are not required for the normal source build and core unit tests.

### License

The project is licensed under the [MIT License](LICENSE). The optimizer main JAR privately relocates Agrona, Caffeine, and lz4-java; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for details.
