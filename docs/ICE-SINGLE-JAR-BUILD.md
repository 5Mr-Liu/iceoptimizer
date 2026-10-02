# ICE 单 JAR 构建与升级

日期：2026-10-02。版本：1.0.13。

优化器的唯一安装文件是 `build/libs/ice-rlcraft-optimizer-1.0.13.jar`，包含 `IceMod`、启动 Plugin/Transformer、早期注入 ABI、优化器运行时和已重定位的缓存/压缩库。可选 Profiler 的唯一安装文件是 `build/libs/ice-rlcraft-profiler-1.0.13.jar`，包含记录、报告、命令、Dashboard 和启动探针。两者可独立安装；Core 文件均不再单独安装。

## 源码层次

| 源集 | 物理目录 | 编译边界 |
| --- | --- | --- |
| early | `src/early/java` | 注入 ABI/早期状态源文件；只使用 Java、Forge/Minecraft 编译类型 |
| hooks | `src/hooks/java`、`src/hooks/resources` | 启动插件、ASM 适配器、启动 journal；依赖 early，不链接优化器运行时 |
| main | `src/main/java`、`src/main/resources` | `IceMod` 与优化器运行时；依赖 early |
| profiler | `src/profiler/java`、`src/profiler/resources` | Profiler 入口、采样、报告、命令、代理；不链接优化器运行时 |
| test | `src/test/java` | 行为及实际模组 JAR 契约测试；编译时可见全部源集 |

Java 包名和注入 owner/descriptor 均保持不变。现有反射协议无需迁移。渲染循环中没有因分层而增加查表、反射或资格检查。

## 构建与正式产物

根 `build.gradle` 仅组合五个职责脚本：`forge.gradle` 配置依赖和源集，`tests.gradle` 映射真实 JAR 参数，`packaging.gradle` 打包，`archive-normalization.gradle` 修复重混淆时间戳，`artifact-verification.gradle` 校验最终产物。

`optimizerJar` 依次使用 `reobfShadowJar` 生成的重混淆运行时和 `optimizerHooksJar` 生成的原始启动/early 字节码，合并成安装 JAR。中间文件放在 `build/intermediates`。启动字节码仍沿用既有处理方式，不额外重混淆。最终只生成一份 manifest：

```text
FMLCorePlugin: dev.rlcraft.ice.hooks.IceOptimizerLoadingPlugin
FMLCorePluginContainsFMLMod: true
Implementation-Version: 1.0.13
```

`profilerJar` 同样合并 `reobfProfilerRuntimeJar` 和 `profilerHooksJar`；两份内部文件位于 `build/intermediates/profiler`。只有记录器运行时重混淆，启动匹配规则与注入的 ProbeBridge owner/descriptor 保持原始字节码。其唯一 manifest 使用 `FMLCorePlugin: dev.rlcraft.ice.hooks.IceProfilerLoadingPlugin`、`FMLCorePluginContainsFMLMod: true` 和 `Implementation-Version: 1.0.13`，保证同一文件被 Forge 识别为启动插件和普通模组。

`optimizerBundleZip` 保留下载便利性，ZIP 内仅一个优化器 JAR 和安装说明。`verifyArtifacts` 实际枚举每个 ZIP entry 并拒绝重复，不再先转成 Set 后忽略重复；同时检查 early 源集的全部 class、普通模组与启动入口、版本、资源元数据、依赖重定位和 Profiler 隔离。

`verifyArtifactStartup` 使用最终 JAR 作为子类加载器唯一 ICE 来源，隐藏所有晚期运行时类，初始化实际 Plugin/Transformer 并解析 early ABI，校验最终 `@Mod` 版本和握手范围。`ForgeSingleJarDiscoveryTest` 直接调用本地真实 Forge 2860 的 `CoreModManager.discoverCoreMods`，由真实 `FMLPluginWrapper.injectIntoClassLoader`、`ASMTransformerWrapper` 和 `LaunchClassLoader.registerTransformer` 注册转换器，确认每个安装 JAR 仍在普通模组候选列表中，且各转换器只注册一次。烟测覆盖 CLIENT/SERVER 的优化器独装、Profiler 独装与两者同装六种组合，并通过转换链检查探针只插入一次。测试使用 workspace 内 fixture mods，不加载存档或创建 GL 上下文；启动用静态 fixture 数据在 finally 恢复。

普通回归测试中的 `StartupRegistrationTest` 验证“类存在”以及只完成一半启动都不会报告为已注册。依赖正式产物的测试只由 `verifyArtifactStartup` 执行，避免普通 `test` 在产物不存在时产生无意义跳过。Profiler 隔离测试从最终单 JAR 加载所有启动类，隐藏优化器和晚期记录器，检查真实类转换与异常时原字节码回退。校验在构建阶段执行，不进入逐帧路径。

启动状态由真实 `injectData` 生命周期和 Transformer 实例构建共同记录，普通运行时初始化时读取一次。已有状态对象的 `isCoreModPresent` 方法名保留兼容，但其含义现在是启动补丁已注册。F3 的 `CORE INACTIVE` 与启动日志反映这一状态，不再提示缺少第二个 JAR。

## 迁移和回滚

关闭游戏/服务端，移除升级组件的旧 Main/Core，安装对应单 JAR；客户端与独立服务端的优化器版本需一致。Profiler 单包升级需同时移走旧 `ice-rlcraft-profiler-*.jar` 和 `ice-rlcraft-profiler-core-*.jar`，避免旧转换器重复注册。现有配置、报告和存档可继续使用。

通用脚本 `tools/deploy-optimizer.ps1` 默认只操作 workspace 内的 `build/deploy-fixtures/manual/mods`。真实实例需要显式传入 `-ModsDirectory`，没有预置游戏路径。脚本校验单包 manifest/必需 entry，将匹配的旧 optimizer 文件备份并验证 SHA-256，暂存新 JAR 后替换。相同单包已安装时直接返回 `AlreadyCurrent`。

```powershell
# 默认只在本项目测试目录安装当前版本。
.\tools\deploy-optimizer.ps1

# 真正升级需指定实例的 mods 路径；可同时提供发布 SHA-256。
.\tools\deploy-optimizer.ps1 -ModsDirectory 'D:\实例\mods' -ExpectedSha256 '<发布 SHA-256>'

# 使用安装结果中的 RollbackManifest 恢复原来的 Main/Core 或单包。
.\tools\deploy-optimizer.ps1 -RollbackManifest '<workspace\rollback\...\deployment.json>'
```

回滚先验证备份中的原始文件，额外备份当前已安装的 optimizer，再恢复原来的文件集合。Profiler 和其他模组不会被部署脚本选择。历史按版本脚本仍保留原来的双包语义。

`tools/deploy-profiler.ps1` 提供同样的单包升级、幂等及回滚流程，只选择 Profiler 文件。默认目标也在 workspace；可使用 `-Archive`、`-ModsDirectory`、`-ExpectedSha256` 和 `-RollbackManifest`。Optimizer、其他模组、配置、报告与存档不属于其操作对象。

可在实际产物构建后运行迁移夹具；它仅创建 workspace 下的临时 mods，检查错误哈希拒绝、双包迁移、Profiler 保留、重复部署和精确回滚，不部署游戏：

```powershell
.\tools\test-singlejar-deployment.ps1 -Archive '.\build\libs\ice-rlcraft-optimizer-1.0.13.jar'
.\tools\test-profiler-singlejar-deployment.ps1 -Archive '.\build\libs\ice-rlcraft-profiler-1.0.13.jar'
```

最终 JAR 隔离测试证明包内容和早期依赖边界，不能代替实际 Forge 游戏启动与游戏内画面测试。
