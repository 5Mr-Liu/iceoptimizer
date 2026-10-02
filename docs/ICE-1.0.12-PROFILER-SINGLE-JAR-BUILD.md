# ICE 1.0.12 Profiler 单 JAR 构建

日期：2026-10-02。本地测试构建，未发布到 GitHub。

Profiler 的安装文件合并为 `ice-rlcraft-profiler-1.0.12.jar`。记录、报告、快捷键、命令、Dashboard 和精确字节码探针均由该文件提供；它可独立使用，也可与 `ice-rlcraft-optimizer-1.0.12.jar` 一起安装。

## 实现边界

- `profilerRuntimeJar` 只包含 profiler 源集，独立重混淆后仍使用原来的 Minecraft/Forge 运行时名称。
- `profilerHooksJar` 只包含 `IceProfilerLoadingPlugin`、`IceProfilerTransformer`、`ProbeProtocol` 及其内部类，保留原始启动字节码。
- `profilerJar` 合并两者，只生成一个 manifest 和一份 `mcmod.info`；使用 `FMLCorePluginContainsFMLMod: true`，让 Forge 同时发现普通模组入口和启动插件。
- 内部 JAR 位于 `build/intermediates/profiler`。`build/libs` 不再输出 Profiler Core 安装文件。

Profiler 的运行逻辑、配置与报告格式、探针 owner/descriptor 保留。此次修改没有增加逐帧检查、反射、计算或采样任务；沿用 1.0.11 渲染优化，不将包装合并宣称为 FPS 提升。

## 安装

完全退出游戏后，从 `mods` 移除旧 Profiler Main 和 Core，放入 `build/libs/ice-rlcraft-profiler-1.0.12.jar`。只安装这一份 Profiler 文件即可。配置 `config/ice-profiler.cfg` 和报告目录 `ice-profiler` 可继续使用。

需要优化器时，使用 `ice-rlcraft-optimizer-1.0.12.jar`；联机客户端和服务端的优化器版本需一致。仅使用 Profiler 不要求安装优化器。

`tools/deploy-profiler.ps1` 默认只操作 workspace 测试目录。旧双包升级、重复部署和精确回滚可用 `tools/test-profiler-singlejar-deployment.ps1` 在临时 fixture mods 中验证。本次不向真实游戏目录部署。

## 验证结果

Java 8 / ForgeGradle 离线 `clean build optimizerBundleZip exportEntityClasspath` 成功，59 秒，27 个任务全部执行。普通回归 216 类、891 项：868 通过、23 跳过；最终产物 3 类、10 项全部通过。合计 901 项，878 通过，23 跳过，零失败或错误。跳过项为未提供的可选真实模组输入及 Windows 链接权限，不包含此次最终包验证。

最终 `build/libs` 仅有以下三个文件，没有 Profiler Core 安装包；优化器 ZIP 内嵌 JAR 与对应独立 JAR 字节完全一致。

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `ice-rlcraft-profiler-1.0.12.jar` | 255550 | `70F3AC3225D6FB7CFFF13F8DE1AAC0A8E6EDD0EAAC03008A428AAC2399261787` |
| `ice-rlcraft-optimizer-1.0.12.jar` | 4238345 | `C0C055AEA065071027A6FB1914A82ACC5920895A7702DA93BFD930DC49E9F55C` |
| `ice-rlcraft-optimizer-bundle-1.0.12.zip` | 3769010 | `D1C4FE5250B8FC5CFCE6489FB3AC1B27F85D3B642052EC14E5EAC8BAC52881D5` |

最终包测试覆盖 CLIENT/SERVER 的优化器独装、Profiler 独装和同装六种组合；使用真实 Forge 2860 的插件发现和转换器包装，验证普通模组候选保留、启动插件注册次数，以及原类经过转换链后的探针数量。隔离加载测试检查 Profiler 启动层无需解析优化器或晚期记录器。

独立归档审计确认：最终 Profiler 包含 152 个 class，其中 18 个启动 class 与原始 hooks 编译输出逐字节一致；所有内部类齐全，目标匹配字符串保留，记录器的 Minecraft 方法调用已转为生产 SRG 名称。未发现硬优化器依赖、缺失项目类或与优化器重叠的 class。

Profiler 迁移夹具通过：旧 Main/Core/单包文件集合升级后仅保留新 Profiler；重复安装返回 `AlreadyCurrent`；精确回滚恢复原文件名与 SHA-256，新安装的回滚恢复空集合。缺入口、不完整 manifest、版本/哈希不符以及受损备份在修改安装文件前被拒绝，暂存文件移动失败后原文件恢复。每步比对整个 fixture，优化器、其他模组、配置、报告和存档保持一致。原优化器迁移夹具同样通过。

日志与记录位于 `.refactor-backups/1.0.12-build-final.log`、`1.0.12-final-verification.json`、`1.0.12-profiler-deployment.log`、`1.0.12-optimizer-deployment.log` 和 `1.0.12-profiler-singlejar-audit`；改动前源码与安装文件保留在 `.refactor-backups/1.0.11-before-profiler-singlejar`。

这些检查验证打包与早期启动边界。完整 Dregora 游戏启动、采样和报告仍需在游戏内验证。
