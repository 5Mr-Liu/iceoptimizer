# ICE RLCraft Optimizer + ICE Performance Recorder

> **当前稳定版 / Current stable release:** [v1.0.5](https://github.com/5Mr-Liu/iceoptimizer/releases/tag/v1.0.5)

面向 Minecraft 1.12.2、Forge 14.23.5.2860 和 Java 8 的 RLCraft / RLCraft Dregora 性能优化与诊断工具。<br>
Performance optimization and diagnostics for RLCraft / RLCraft Dregora on Minecraft 1.12.2, Forge 14.23.5.2860, and Java 8.

## 项目组成 / Packages

| 文件 / Package | 用途 / Purpose |
| --- | --- |
| `ice-rlcraft-optimizer-*.jar` | 优化器主模组 / Optimizer main mod |
| `ice-rlcraft-optimizer-core-*.jar` | 启动期安全补丁 / Early safe transformers |
| `ice-rlcraft-profiler-*.jar` | 性能记录、命令和报告 / Profiling, commands, and reports |
| `ice-rlcraft-profiler-core-*.jar` | 精确只读探针 / Precise read-only probes |
| `ice-rlcraft-optimizer-bundle-*.zip` | optimizer Main/Core 安装包，先解压 / Optimizer Main/Core bundle; extract before use |

Optimizer 和 Profiler 可以独立安装；同一组件的 Main/Core 必须版本一致。<br>
Optimizer and Profiler can be installed independently; each component's Main/Core versions must match.

## 安装 / Installation

1. 完全退出 Minecraft，并备份实例。<br>Exit Minecraft completely and back up the instance.
2. 从 `mods` 删除所有旧版 ICE Optimizer/Profiler JAR，禁止混装。<br>Remove every older ICE Optimizer/Profiler JAR from `mods`; never mix versions.
3. 仅优化：解压 optimizer bundle，或安装 optimizer Main + Core。<br>Optimizer only: extract the optimizer bundle, or install optimizer Main + Core.
4. 完整诊断：再安装 profiler Main + Core。<br>Full diagnostics: also install profiler Main + Core.
5. 联机时，客户端与服务端必须使用相同版本的 optimizer Main。<br>For multiplayer, client and server must use the same optimizer Main version.

不要安装 `build/devlibs` 中的 combined-dev 产物。<br>
Do not install combined-dev artifacts from `build/devlibs`.

## 主要能力 / Key Features

- **现代渲染 / Modern rendering** — 安全的 Terrain batching、MDI、HZB、上传环与对象/Pass 级兼容回退。<br>Safe terrain batching, MDI, HZB, upload rings, and object/pass-level compatibility fallback.
- **CPU 与世界生成 / CPU and world generation** — 有界工作队列、确定性缓存、OTG/BO3/BO4 解析优化及 NBT 压缩。<br>Bounded work queues, deterministic caches, OTG/BO3/BO4 parsing improvements, and NBT compression.
- **模组适配 / Mod adapters** — 按真实类结构认证 RLCraft 常见模组；单个目标不兼容时只回退该目标。<br>Structural certification for common RLCraft mods; an incompatible target falls back independently.
- **性能取证 / Profiling** — 自动捕获卡顿、帧/Tick/JVM 指标、精确探针、根因聚类和离线 HTML 报告。<br>Automatic hitch capture, frame/tick/JVM metrics, precise probes, root-cause clustering, and offline HTML reports.
- **语义安全 / Semantic safety** — 不删内容、不降画质、不跳 Tick、不异步修改世界；错误时保留原实现。<br>No content removal, quality reduction, skipped ticks, or asynchronous world mutation; failures preserve original behavior.

## 1.0.5 状态 / 1.0.5 Status

- 修复 ChunkAnimator 与 HZB 的动态区块坐标冲突和远处闪烁。<br>Fixed the ChunkAnimator/HZB dynamic-chunk coordinate conflict and distant flicker.
- HZB 遮挡采用双发布确认；GPU 提交失败会事务恢复原可见列表。<br>HZB occlusion uses two-publication confirmation; failed GPU submission transactionally restores the original visible list.
- 最新实机会话确认现代 Terrain、HZB 和 ChunkAnimator 探针正常，无相关运行错误。<br>The latest field session confirmed healthy modern Terrain, HZB, and ChunkAnimator probes with no related runtime errors.
- 密集区域仍受 Terrain 驻留、实体/TESR 提交及 CPU/GPU 混合瓶颈限制；系统性改进进入 [ICE 2.0 方案](docs/ICE-2.0-ARCHITECTURE-PLAN.md)。<br>Dense scenes remain limited by terrain residency, entity/TESR submission, and mixed CPU/GPU bottlenecks; the systemic redesign is tracked in the [ICE 2.0 plan](docs/ICE-2.0-ARCHITECTURE-PLAN.md).

现代路径只在能力、兼容性和正确性认证通过时接管；高 GPU 使用率本身不是目标。<br>
Modern paths take ownership only after capability, compatibility, and correctness certification; high GPU utilization alone is not the goal.

## 使用 / Usage

配置文件 / Configuration:

- `config/ice-optimizer.cfg`
- `config/ice-profiler.cfg`

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

1.0.5 发布构建：194 个测试类、740 项测试、0 failure、0 error；61 skipped 仅对应未提供的可选真实模组 JAR 或运行期样本。<br>
The 1.0.5 release build ran 194 test classes and 740 tests with 0 failures and 0 errors; 61 skips only cover optional real-mod JARs or runtime samples not supplied to the build.

## 文档 / Documentation

- [1.0.5 发布说明 / Release notes](docs/ICE-1.0.5-RELEASE-NOTES.md)
- [ICE 2.0 架构方案 / Architecture plan](docs/ICE-2.0-ARCHITECTURE-PLAN.md)
- [当前架构 / Current architecture](docs/ARCHITECTURE.md)
- [版本历史 / Changelog](CHANGELOG.md)

## 许可证 / License

见 [LICENSE](LICENSE)。<br>
See [LICENSE](LICENSE).
