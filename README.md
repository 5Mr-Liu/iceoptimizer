# ICE RLCraft Optimizer + ICE Performance Recorder

> **最新预发布 / Latest pre-release:** [v1.20.3 — 地形重建卡顿修复与精简优化](https://github.com/5Mr-Liu/iceoptimizer/releases/tag/v1.20.3)
> **当前稳定版 / Current stable release:** [v1.0.13](https://github.com/5Mr-Liu/iceoptimizer/releases/tag/v1.0.13)

当前源码为 **ICE 1.20.3 地形重建卡顿修复与精简优化版**，以用户已实机确认效果的 `slim-20261107` 候选为基础。GL43 网格替换恢复旧显存区间复用，撤下新增 Capability NBT 结果比较、Trinkets 标量缓存和差量同步的生产接入，保留候选的其他优化与兼容修复。**1.20.3 是模组版本，Minecraft 仍为 1.12.2。** 本次版本更新不增加运行功能，见 [1.20.3 说明](docs/ICE-1.20.3-RELEASE-NOTES.md)。

Current source: ICE 1.20.3, based on the slim candidate approved after user playtesting. GL43 mesh replacement reuses retired allocations. The new Capability NBT result comparator, Trinkets scalar cache and delta transport are disconnected from production startup. Other candidate optimizations and compatibility fixes are retained. Minecraft remains 1.12.2; broader hardware validation remains open.

变更与验证记录：[1.20.3 发布说明](docs/ICE-1.20.3-RELEASE-NOTES.md)、[精简候选记录](docs/ICE-SLIM-PLAYTEST-20261107.md)、[功能与卡顿审查](docs/ICE-1.20.1-TO-LOCAL-AUDIT-20261107.md)、[1.20.2 发布说明](docs/ICE-1.20.2-RELEASE-NOTES.txt)。历史实验记录中的功能不一定在当前版本启用。

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
3. 仅优化：将 `ice-rlcraft-optimizer-1.20.3.jar` 放入 `mods`；升级时先移出旧 optimizer 和旧 Core，避免重复安装。<br>Optimizer only: put `ice-rlcraft-optimizer-1.20.3.jar` in `mods`; remove the previous optimizer and old Core from mods when upgrading.
4. 本次 Release 仅包含 Optimizer，不包含新构建的 Profiler；分析器可单独管理。<br>This release contains Optimizer only; no newly built Profiler is attached.
5. 联机时，客户端与服务端必须使用相同版本的 optimizer。<br>For multiplayer, client and server must use the same optimizer version.

下载本次 Release 的 JAR，或解压 `ice-rlcraft-optimizer-bundle-1.20.3.zip` 后取出 JAR。源码和构建证据 ZIP 供开发排查使用。自行构建的安装文件位于 `build/libs`，`build/intermediates` 是内部产物。<br>
Download the release JAR or extract the bundle ZIP. The source-and-build ZIP contains development evidence. Local builds place installation files in `build/libs`; `build/intermediates` contains internal artifacts.

自有地形在图形上下文创建完成后自动选择一次：支持 **OpenGL 4.3** 时使用原后端，否则使用 **OpenGL 2.1/gl4es 兼容后端**，低于 2.1 明确报错。旧 `nativeTerrainOpenGl21` 配置不再参与选择。兼容后端使用共享固定容量缓冲页，新增页不读回或搬动旧网格；全局 VBO 判断保持原样，仅接管 ICE 地形入口。该调整已纳入 1.20.3，详见 [实现与验证说明](docs/OPENGL-21-COMPATIBILITY.md)。

保留整合包已有的 RenderLib。使用 ICE 自有地形时移出 Nothirium，关闭 OptiFine Smart Animations、Fast Render 和 Render Regions。OptiFine G5 光影继续使用完整扩展顶点和原光影程序；光影包自身的硬件要求保持。若安装 Nothirium，ICE 保持现有外部渲染器所有权规则。兼容性与性能需要在目标机器确认，当前尚无 Android/gl4es 实测结果。

## 主要能力 / Key Features

- **自有地形 / Native terrain** — 常驻网格、有序索引和批量间接绘制，分摊区块重建/上传工作。<br>Resident geometry, ordered indices, indirect draws and scheduled chunk rebuilding/uploads.
- **CPU 与世界生成 / CPU and world generation** — 有界工作队列、确定性缓存、OTG/BO3/BO4 解析优化及 NBT 压缩。<br>Bounded work queues, deterministic caches, OTG/BO3/BO4 parsing improvements, and NBT compression.
- **模组适配 / Mod adapters** — 按真实类结构认证 RLCraft 常见模组；单个目标不兼容时只回退该目标。<br>Structural certification for common RLCraft mods; an incompatible target falls back independently.
- **性能取证 / Profiling** — 自动捕获卡顿、帧/Tick/JVM 指标、精确探针、根因聚类和离线 HTML 报告。<br>Automatic hitch capture, frame/tick/JVM metrics, precise probes, root-cause clustering, and offline HTML reports.
- **内容保留 / Content preservation** — 不通过删除内容、降低画质、跳过 Tick 或异步修改世界取得提升。<br>No content removal, quality reduction, skipped ticks or asynchronous world mutation is used to obtain gains.

## 1.20.3 状态 / 1.20.3 Status

- 用户已实机测试精简候选并确认效果，1.20.3 保留其运行逻辑；没有新增功能或新的整局 FPS 测量。
- GL43 重建复用旧区间；Trinkets 缓存/差量及 Capability NBT 结果比较不接入生产路径，旧配置不会重新启用撤下的 Trinkets 功能。保留原装备 NBT 和已知 Capability 状态比较。
- 相对远程 1.20.2 纳入 OpenGL 2.1 兼容、多重纹理支持，以及局部 NBT、索引和命令上传优化。
- 保留双截棍物品动画、世界切换、透明排序、光影兼容及空 VBO 修复。其他设备的接口注入失败仍需对应启动日志，不在已确认修复范围。
- F6 切换会重建地形，约 2ms 的上传时间片不保证单帧耗时上限；不同显卡、驱动、光影及 Android/gl4es 仍需实测。

The user approved the slim candidate after playtesting. This version preserves that behavior; it does not include a new FPS benchmark. F6 rebuilds terrain during switching, and upload slices do not guarantee a frame-time ceiling.

## 使用 / Usage

配置文件 / Configuration:

- `config/ice-optimizer.cfg`
- `config/ice-profiler.cfg`

1.20.2 新增 OptiFine 兼容：关闭“地形动画”时，仍更新 `items/`、`item/` 路径下的武器及物品贴图动画，地形贴图遵循原设置。这是启动兼容补丁，不随 F6 暂停；尚未进行具体武器的实机验证，范围见 [物品动画修复记录](docs/ICE-1.20.1-OPTIFINE-ITEM-ANIMATIONS.txt)。

默认情况下，ICE 不会联网补全缺少纹理的玩家头颅资料：方块、物品栏/JEI、快捷栏、手持/掉落及佩戴头颅均在进入 `SkinManager`/CustomSkinLoader 前应用同一策略；已有 `textures` 数据照常渲染，不完整资料直接使用默认皮肤。将 `settings.allowIncompletePlayerSkullProfileLookup=true` 可恢复资料解析，再由 `settings.skullProfileAsync` 选择有界异步或原版同步模式；更改后需要重启客户端。<br>
By default, ICE does not resolve incomplete player-skull profiles online: placed, inventory/JEI, hotbar, held/dropped, and worn heads all apply the same policy before entering `SkinManager`/CustomSkinLoader; existing `textures` data still renders, while incomplete profiles use the default skin. Set `settings.allowIncompletePlayerSkullProfileLookup=true` to permit resolution, then use `settings.skullProfileAsync` to choose bounded asynchronous or vanilla synchronous lookup; restart the client after changing it.

只安装 Optimizer 时，原版 F3 右侧显示简要状态；可通过 `display.showF3Summary=false` 关闭。<br>
With Optimizer only, a compact status appears on the vanilla F3 screen; disable it with `display.showF3Summary=false`.

Optimizer 默认快捷键为 `F6`：暂停/恢复所有可即时切换的 ICE 优化。在“选项 → 控制 → ICE 优化”中改键，键位由游戏保存；暂停状态仅本次运行有效，不改模块配置。切换会重建地形，等区块重新加载稳定后再比较 FPS；F3 的 `PAUSED` 表示暂停。单人集成服一同切换，远程服务器不受客户端按键影响。已加载启动补丁和已建立的缓存/对象保留，因此暂停不等于卸载 ICE。<br>
Optimizer hotkey: `F6` pauses/resumes runtime-switchable ICE optimizations. Rebind it under Options → Controls → ICE Optimizer. The game saves the binding; pause is session-only and preserves module settings. Wait for terrain rebuilding to settle before comparing FPS. F3 shows `PAUSED`. The integrated server shares the switch; remote servers are unaffected. Loaded startup patches and existing caches/objects remain, so pausing is not equivalent to uninstalling ICE.

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

1.0.16 同装时自动记录 F6 切换事件；时间线新增 `optimizer_runtime_state`（`1` 开启、`0` 暂停、`2` 本采样区间发生切换、`-1` 无状态来源）。FPS 按实际采样时间计算，同时保存 `frame_count` 和 `sample_interval_ns`；详细二进制格式升级为 ICECAP v5。状态记录只订阅切换事件，不参与优化启用判断。<br>
When installed together, 1.0.16 records F6 state changes and labels timeline intervals (1 unpaused, 0 paused, 2 changed within the interval, -1 unavailable). This describes the runtime switch, not each module's active coverage. FPS is normalized by elapsed sample time, with raw frame counts and nanosecond intervals retained. Binary captures use ICECAP v5. Recording observes switch events and never controls optimization admission.

## 构建与验证 / Build and Verification

需要 Java 8。<br>
Java 8 is required.

```powershell
.\gradlew.bat clean build optimizerBundleZip --no-daemon
```

1.20.3 发布使用 `build optimizerBundleZip verifyArtifactStartup`，覆盖常规回归、最终制品启动检查、版本元数据和包隔离。具体统计、源码提交和 SHA256 见 Release 的 `BUILD-INFO.txt`、`SHA256SUMS.txt` 及源码/构建证据包。用户已实测精简候选，此次打包不重新进行游戏内 FPS 测量。<br>
The 1.20.3 release runs the regular and final-artifact startup suites, metadata checks and package isolation checks. See the release build evidence for counts, source commit and hashes. User playtesting covers the slim candidate; packaging does not include a new in-game FPS benchmark.

项目目录、发布产物与历史归档的组织方式见 [目录说明](docs/PROJECT-LAYOUT.txt)。历史实验结论和被否定的方案保留在 `docs/`，继续开发前应先阅读对应工作记录。

## 文档 / Documentation

- [1.20.3 发布说明 / Release notes](docs/ICE-1.20.3-RELEASE-NOTES.md)
- [1.20.2 发布说明 / Release notes](docs/ICE-1.20.2-RELEASE-NOTES.txt)
- [1.20.1 发布说明 / Release notes](docs/ICE-1.20.1-RELEASE-NOTES.txt)
- [透明面排序修复 / Translucent sort fix](docs/ICE-1.20-TRANSPARENT-SORT-FIX.txt)
- [项目目录与归档 / Project layout](docs/PROJECT-LAYOUT.txt)
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
