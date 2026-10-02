# ICE RLCraft Optimizer + ICE Performance Recorder

> **当前稳定版 / Current stable release:** [v1.0.13](https://github.com/5Mr-Liu/iceoptimizer/releases/tag/v1.0.13)

> **当前源码 / Current source:** 1.0.13，修复地形后端休眠后的恢复和无效动画扫描，减少 OTG 配置读取、区块保存与 Profiler 的重复工作。Optimizer / Profiler 各一个安装 JAR，正式安装文件见上方 Release。<br>
> 1.0.13 fixes terrain recovery and dormant animation scans, reduces OTG loading and save work, and corrects hitch capture overhead and attribution. Optimizer and Profiler each use one installation JAR. Installation files are available in the release linked above.

> [!IMPORTANT]
> **兼容范围 / Compatibility scope:** 从 1.0.5 起，ICE 仅支持 RLCraft Dregora，不再支持原版 RLCraft。原版 RLCraft 用户请继续使用 1.0.4 或更早版本；由原版 RLCraft 模组组合引起的启动或注入冲突不在 1.0.5 及后续版本的支持范围内。<br>
> Starting with 1.0.5, ICE supports RLCraft Dregora only and no longer supports standard/original RLCraft. Standard RLCraft users should stay on 1.0.4 or earlier; startup or injection conflicts caused by the standard RLCraft mod set are outside the support scope of 1.0.5 and later releases.

面向 Minecraft 1.12.2、Forge 14.23.5.2860 和 Java 8 的 RLCraft Dregora 性能优化与诊断工具。<br>
Performance optimization and diagnostics for RLCraft Dregora on Minecraft 1.12.2, Forge 14.23.5.2860, and Java 8.

## 项目组成 / Packages

| 文件 / Package | 用途 / Purpose |
| --- | --- |
| `ice-rlcraft-optimizer-*.jar` | 优化器，已包含启动补丁 / Optimizer including startup transformers |
| `ice-rlcraft-profiler-*.jar` | 性能记录、命令、报告和精确探针 / Profiling, commands, reports, and precise probes |
| `ice-rlcraft-optimizer-bundle-*.zip` | 一个优化器 JAR 和安装说明，先解压 / One optimizer JAR and installation instructions; extract before use |

Optimizer 和 Profiler 可以独立安装，各只需一个 JAR。自 1.0.12 起不再安装 Profiler Core 文件。<br>
Optimizer and Profiler can be installed independently, each with one JAR. Starting with 1.0.12, no separate Profiler Core is installed.

## 安装 / Installation

1. 完全退出 Minecraft，并备份实例。<br>Exit Minecraft completely and back up the instance.
2. 从 `mods` 删除所有旧版 ICE Optimizer/Profiler JAR，禁止混装。<br>Remove every older ICE Optimizer/Profiler JAR from `mods`; never mix versions.
3. 仅优化：将 `ice-rlcraft-optimizer-1.0.13.jar` 放入 `mods`；升级时也删除旧 optimizer Core。<br>Optimizer only: put `ice-rlcraft-optimizer-1.0.13.jar` in `mods`; remove the old optimizer Core when upgrading.
4. 完整诊断：安装 `ice-rlcraft-profiler-1.0.13.jar`；升级时删除旧 profiler Main/Core。Profiler 也可单独安装。<br>Full diagnostics: install `ice-rlcraft-profiler-1.0.13.jar`, removing older profiler Main/Core JARs. Profiler can also be installed alone.
5. 联机时，客户端与服务端必须使用相同版本的 optimizer。<br>For multiplayer, client and server must use the same optimizer version.

正式安装文件位于 `build/libs`。`build/intermediates` 是内部构建产物。<br>
Installation files are in `build/libs`. `build/intermediates` contains internal build artifacts.

## 主要能力 / Key Features

- **现代渲染 / Modern rendering** — 安全的 Terrain batching、MDI、HZB、上传环与对象/Pass 级兼容回退。<br>Safe terrain batching, MDI, HZB, upload rings, and object/pass-level compatibility fallback.
- **CPU 与世界生成 / CPU and world generation** — 有界工作队列、确定性缓存、OTG/BO3/BO4 解析优化及 NBT 压缩。<br>Bounded work queues, deterministic caches, OTG/BO3/BO4 parsing improvements, and NBT compression.
- **模组适配 / Mod adapters** — 按真实类结构认证 RLCraft 常见模组；单个目标不兼容时只回退该目标。<br>Structural certification for common RLCraft mods; an incompatible target falls back independently.
- **性能取证 / Profiling** — 自动捕获卡顿、帧/Tick/JVM 指标、精确探针、根因聚类和离线 HTML 报告。<br>Automatic hitch capture, frame/tick/JVM metrics, precise probes, root-cause clustering, and offline HTML reports.
- **语义安全 / Semantic safety** — 不删内容、不降画质、不跳 Tick、不异步修改世界；错误时保留原实现。<br>No content removal, quality reduction, skipped ticks, or asynchronous world mutation; failures preserve original behavior.

## 1.0.13 状态 / 1.0.13 Status

- 优化器与 Profiler 各一个安装 JAR，启动补丁、早期接口与普通模组入口合并打包，运行时和 hooks 保持独立编译/重混淆边界。<br>Optimizer and Profiler each use one installation JAR containing their startup patches, early interfaces, and ordinary mod entry, while preserving the runtime/hooks compilation and remapping boundary.
- 地形后端在出现新的区块上传后恢复原验证与测量，休眠时跳过动画反射/扫描；待上传纹理仍在绘制前追赶。<br>Terrain retries resume from new chunk uploads; dormant animation reflection/scans stop while pending textures still catch up before drawing.
- 精简模型/粒子提交、纹理参数调用、OTG 文件解析与结构保存；区块 NBT 按本次保存的调色板复用注册 ID，压缩格式及写入顺序保留。<br>Model/particle submission, texture parameter calls, OTG parsing, and structure saving use less work; chunk NBT resolves local palette IDs per save while preserving compression formats and write order.
- Profiler 合并触发不再复制无用预窗口，固定预算内优先保留卡顿区间证据；按客户端/服务端角色归因，明确标出缺样尖峰。<br>Merged Profiler triggers reuse the active capture, retain hitch evidence within the fixed budget, attribute client/server work separately, and mark peaks with missing samples.

现代路径只在能力、兼容性和正确性认证通过时接管；高 GPU 使用率本身不是目标。<br>
Modern paths take ownership only after capability, compatibility, and correctness certification; high GPU utilization alone is not the goal.

## 使用 / Usage

配置文件 / Configuration:

- `config/ice-optimizer.cfg`
- `config/ice-profiler.cfg`

默认情况下，ICE 不会联网补全缺少纹理的玩家头颅资料：方块、物品栏/JEI、快捷栏、手持/掉落及佩戴头颅均在进入 `SkinManager`/CustomSkinLoader 前应用同一策略；已有 `textures` 数据照常渲染，不完整资料直接使用默认皮肤。将 `settings.allowIncompletePlayerSkullProfileLookup=true` 可恢复资料解析，再由 `settings.skullProfileAsync` 选择有界异步或原版同步模式；更改后需要重启客户端。<br>
By default, ICE does not resolve incomplete player-skull profiles online: placed, inventory/JEI, hotbar, held/dropped, and worn heads all apply the same policy before entering `SkinManager`/CustomSkinLoader; existing `textures` data still renders, while incomplete profiles use the default skin. Set `settings.allowIncompletePlayerSkullProfileLookup=true` to permit resolution, then use `settings.skullProfileAsync` to choose bounded asynchronous or vanilla synchronous lookup; restart the client after changing it.

只安装 Optimizer 时，原版 F3 右侧显示简要状态；可通过 `display.showF3Summary=false` 关闭。<br>
With Optimizer only, a compact status appears on the vanilla F3 screen; disable it with `display.showF3Summary=false`.

Profiler 快捷键 / Profiler hotkeys:

- `F8`：标记一次事件 / mark an event
- `F9`：开始或停止手动录制 / start or stop manual recording
- `F10`：打开 Dashboard / open the dashboard

常用命令 / Common commands:

```text
/iceprofiler status
/iceprofiler start [note]
/iceprofiler stop
/iceprofiler export
/iceprofiler compare <sessionA> <sessionB>
/iceprofiler deep <on|off>
```

客户端命令使用 `/iceprofilerclient`，别名为 `/iceclient` 和 `/icec`。<br>
Client-side commands use `/iceprofilerclient`, with aliases `/iceclient` and `/icec`.

报告目录 / Report directory:

```text
ice-profiler/sessions/<session-id>/
```

每个 Session 包含摘要、时间线、卡顿聚类、探针、折叠栈、HTML 报告和导出 ZIP。默认不记录玩家名、世界种子或精确坐标。<br>
Each session contains a summary, timeline, hitch clusters, probes, folded stacks, an HTML report, and an export ZIP. Player names, world seeds, and exact coordinates are not recorded by default.

## 构建与验证 / Build and Verification

需要 Java 8。<br>
Java 8 is required.

```powershell
.\gradlew.bat clean build optimizerBundleZip --no-daemon
```

1.0.13 干净构建通过。普通回归与最终产物共 950 项测试：928 通过、22 跳过、零失败；真实 Forge 检查覆盖 CLIENT/SERVER 的独装、同装六种组合，最终单包隔离加载与探针单次注入通过。覆盖渲染恢复、休眠扫描、实际 OTG 加载/保存、NBT 字节等价及 Profiler 旧记录回放；游戏内 FPS 增幅仍需同场景实测。<br>
The clean 1.0.13 build passed 928 of 950 regression and final-artifact checks, with 22 skipped and no failures. Actual Forge discovery passed all six client/server installation combinations, alongside final-JAR isolation and single probe insertion. Validation covers renderer recovery, dormant scan bypass, actual OTG loading and saving, NBT byte equivalence, and old-session replay. In-game FPS gains still require a matched-scene comparison.

## 文档 / Documentation

- [1.0.13 发布说明 / Release notes](docs/ICE-1.0.13-RELEASE-NOTES.md)

- [1.0.13 探索场景性能修复与验证 / Exploration performance fixes](docs/ICE-1.0.13-LIVE-PERFORMANCE-FIXES.md)
- [1.0.12 实际游玩性能分析 / Live performance audit](docs/ICE-1.0.12-LIVE-PERFORMANCE-AUDIT.md)

- [1.0.12 Profiler 单 JAR 构建与验证 / Profiler single JAR build and validation](docs/ICE-1.0.12-PROFILER-SINGLE-JAR-BUILD.md)
- [1.0.11 渲染提交优化与验证 / Render submission fixes and validation](docs/ICE-1.0.11-FPS-RENDER-BUILD.md)
- [1.0.10 现场卡顿优化与验证 / Live hitch fixes and validation](docs/ICE-1.0.10-LIVE-HITCH-BUILD.md)
- [1.0.9 模型提交优化与验证 / Model submission and validation](docs/ICE-1.0.9-MODEL-SUBMISSION-BUILD.md)
- [1.0.8 重构与验证 / Refactor and validation](docs/ICE-1.0.8-REFACTOR-BUILD.md)
- [单 JAR 构建与升级 / Single JAR build and upgrade](docs/ICE-SINGLE-JAR-BUILD.md)
- [分阶段结构调整方案 / Refactoring plan](docs/ICE-SINGLE-JAR-REFACTOR-PLAN.md)
- [1.0.7 构建说明 / Build notes](docs/ICE-1.0.7-RELEASE-NOTES.md)
- [渲染器测试包及接入范围 / Renderer test build and coverage](docs/ICE-1.0.7-RENDERER-TEST-BUILD.md)
- [实体渲染开销精简 / Entity render cleanup](docs/ICE-ENTITY-RENDER-CLEANUP.md)
- [1.0.6 发布说明 / Release notes](docs/ICE-1.0.6-RELEASE-NOTES.md)
- [1.0.5 发布说明 / Previous release notes](docs/ICE-1.0.5-RELEASE-NOTES.md)
- [ICE 2.0 架构方案 / Architecture plan](docs/ICE-2.0-ARCHITECTURE-PLAN.md)
- [当前架构 / Current architecture](docs/ARCHITECTURE.md)
- [版本历史 / Changelog](CHANGELOG.md)

## 许可证 / License

见 [LICENSE](LICENSE)。<br>
See [LICENSE](LICENSE).
