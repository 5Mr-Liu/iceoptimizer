# ICE 单 JAR 与代码结构重构方案

日期：2026-10-02。基于 1.0.7 冗余清理后的源码审查。A/B/C 已在 1.0.8 实施：优化器单 JAR、源码/构建分层，以及诊断、组件图/工厂、Shader 与测量抽取。实现及验证结果见 [1.0.8 重构构建记录](ICE-1.0.8-REFACTOR-BUILD.md)。下文的当前结构和基线行数记录重构前状态，D/E 为后续独立批次。

## 目标与范围

优化器发布一个 `ice-rlcraft-optimizer-<version>.jar`，直接放入 mods，包含普通模组入口、启动转换器、早期注入接口和运行时。启动层和运行时保持独立编译及初始化顺序。Profiler 保持可选工具；本方案的单 JAR 指优化器 Main/Core 合并。Profiler 自身如需合包，可后续沿用相同方式。

结构调整优先解决职责混合、状态重复和构建清单重复。现有功能、配置字段、动画和绘制语义保持一致；不将单 JAR 或拆文件本身当作 FPS 提升。

## 当前结构与问题

源码有 328 个 main、99 个 hooks 生产 Java 文件，25 个超过 700 行。下列行数包括空行和注释。

| 文件 | 行数 | 结构问题 | 优先级 |
| --- | ---: | --- | --- |
| `ModernRendererRuntime` | 6,515 | 生命周期、Shader、模型、地形、HZB、测量和诊断同时持有状态 | 第一优先 |
| `LwjglTerrainArena` | 2,142 | 网格发布事务、GPU 存储、间接命令槽和资源退役交织 | 渲染门面拆分后 |
| `OtgSynchronousIoBridge` | 1,367 | 设置解析、文件身份与变更追踪、NBT 解析和元数据缓存混合 | 后续独立批次 |
| `LwjglCapabilitySelfTest` | 1,299 | 多项 GPU 能力自测聚集 | 后续按能力整理 |
| `HudRenderBridge` | 1,284 | 多类 HUD/字体入口和状态恢复聚集 | 后续按提交职责整理 |
| `LwjglDepthHistory` | 1,278 | 捕获调度、PBO、深度帧投影和列表事务共处 | 渲染门面拆分后 |
| `PrimitiveTerrainVisibilityAdapter` | 1,140 | 同时改写五种不同目标类 | 后续启动层整理 |
| `LwjglConservativeDepthReducer` | 1,053 | GPU 缩减、资源和输出认证聚集 | 后续按资源所有权整理 |
| `LwjglShaderImageCertification` | 1,048 | 离屏认证流程和 GPU 状态处理聚集 | Shader 协调抽取后 |
| `ReportWriter` | 1,002 | 多种格式、汇总、ZIP、原子发布、保留清理和对比 | Profiler 独立批次 |
| `CorrelatedRenderProfiler` | 783 | CPU/profile 聚合与 GPU Query 环混合 | 测量模块整理后 |
| `build.gradle` | 755 | Forge、测试参数、分包清单、重混淆和验证混在一起 | 单包阶段整理 |

`ClientOptimizerRuntime` 302 行、`OptimizerRegistry` 115 行、`OptimizerAdapterRegistry` 159 行、`ProfilerRuntime` 288 行，职责已经相对集中，保留即可。`OptimizerConfig` 是优化器配置；`IceConfig` 是 Profiler 配置，两者不合并。

当前根本耦合是：main 源集同时编译优化器、Profiler 和早期接口，构建脚本再从同一份输出正反筛选。早期接口在 Main 排除、Core 包含、校验任务检查三处维护，容易漏改。渲染器则同时在内部 `ComponentGraph` 和外部字段持有同一批组件，`publish()` 复制、`clearPublishedGraph()` 再清空，状态归属不清晰。

## 单 JAR 的实现方式

本地 Forge `1.12.2-14.23.5.2860` 官方源码 `CoreModManager` 明确支持：先把 CoreMod JAR 加入 LaunchClassLoader；存在 `FMLCorePluginContainsFMLMod` 属性时，同一文件还会进入普通模组扫描。因此保留当前 Forge/Java/Gradle 版本即可实现单包。

最终 manifest 同时声明：

```text
FMLCorePlugin: dev.rlcraft.ice.hooks.IceOptimizerLoadingPlugin
FMLCorePluginContainsFMLMod: true
Implementation-Version: <重构版本号>
```

最终包同时含 `IceMod`、LoadingPlugin、Transformer、全部早期 ABI、`optimizer-targets.properties` 和一份 `mcmod.info`。Forge 的属性判断是“是否存在”，当前 Core 的 `false` 也会进入候选扫描；新包明确使用 `true`，无需另加 ForceLoadAsMod。

首阶段采用最终合包任务：先完成现有 Runtime 重混淆和依赖重定位，再合入当前启动输出及早期 ABI，生成一个正式 JAR。这样可保持启动字节码的处理方式。内部中间产物移到 `build/intermediates/`，`build/libs/` 只出现正式优化器单 JAR。合并时只生成一份 manifest，拒绝重复 class entry，并继续固定时间戳和 entry 顺序。

此后通过源集分层消除手工 ABI 清单。无需嵌套 JAR、自建 ClassLoader 或同时迁移 Gradle 多子项目。

| 当前位置 | 修改内容 |
| --- | --- |
| `build.gradle` 的 `shadowJar`、`optimizerCoreJar` | 转为内部输出，最终由单包任务汇总；后续按源集直接组成 |
| `optimizerBundleZip` | 若保留 ZIP，包含一个优化器 JAR 和说明；JAR 成为主要安装文件 |
| `verifySplitJars` | 改为 `verifyArtifacts`；检查单包内容、版本、无重复 entry、依赖重定位和 Profiler 隔离 |
| `CommonOptimizerBootstrap` | 从“类存在”改成初始化时读取实际 Plugin/Transformer 注册状态 |
| `ClientOptimizerRuntime`、相关提示 | “缺少 Core JAR”改为实际启动补丁状态 |
| `packaging/optimizer-install.txt`、README | 单 JAR 安装；升级时移除旧 Main/Core |
| 部署工具 | 新建通用单包迁移工具；保留历史版本脚本的原语义，覆盖旧双包升级、备份和回滚 |

合包后 `Class.forName(OptimizerPatchJournal)` 总会成功，不能继续作为 Core 已注册的证据。由真正执行的 Plugin/Transformer 生命周期记录启动状态，普通运行时初始化时读取一次；不增加逐帧探测。

仍保留 `hooks` 的 transformer exclusion、早期 journal 和延迟 delegate。单包中的类可见，不代表配置、Minecraft 或渲染器已经初始化。exclusion 继续限定 `dev.rlcraft.ice.hooks.`，不能扩展到全部 optimizer。客户端/专服入口仍由 SidedProxy 分开加载。

## 源码与构建目录

第一轮按编译阶段调整物理源目录，保持 Java package 和注入 ABI 名称：

```text
src/
  early/java/          注入 Accessor、EarlyGl/EarlyMatrix 等早期接口与状态
  hooks/java/          LoadingPlugin、Transformer、ASM adapter、bootstrap
  main/java/           IceMod 和优化器运行时
  main/resources/      优化器资源
  profiler/java/       Profiler 入口、运行时、采样、报告、命令和代理
  profiler/resources/  Profiler 资源
  test/java/           按代码域组织现有行为测试

gradle/
  forge.gradle         Forge、Java、依赖与重混淆配置
  tests.gradle         可选真实 JAR 参数和测试 classpath
  packaging.gradle     单包、Profiler 包、manifest、归一化和产物验证
```

early 可使用 Forge/Minecraft 的编译类型，不能依赖优化器运行时、Profiler 或缓存库。hooks 可依赖 early，main 可依赖 early；hooks 不依赖 main。Profiler 保持对优化器诊断的既有反射协议。hooks 内 Profiler 的少量启动类仍按组件归属筛选，避免为了合包引入复杂共享模块。

根 Gradle 脚本只组合上述配置。测试参数采用静态映射表，合并重复的 `minecraftSrgJar` 设置。保留现有 ForgeGradle 3、Shadow 4、Java 8、mapped JAR 兼容处理及重混淆顺序。

第一轮不同时重命名所有包。manifest、ASM owner 字符串、SidedProxy、journal/registry 反射和 Profiler 诊断读取都依赖这些名称。后续包迁移按域集中改引用和测试。hooks 有大量 package-private 类，不能直接移动到子包后把全部工具随意改成 public。

## 拆分 ModernRendererRuntime

保留它对现有 hooks/bridge 的公开入口，变为生命周期与帧协调门面。具体状态只放在负责该职责的组件中，不在旧类留镜像字段。新组件使用具体 final 类直接调用，资源在初始化时创建。

| 提取类 | 职责与状态 | 迁移顺序 |
| --- | --- | --- |
| `RendererDiagnosticsWriter` | 报告构建、格式化、120 帧节流；在渲染线程读取计数，发布不可变字符串 | 1 |
| `RendererComponents` / `RendererComponentFactory` | 候选组件图、成功发布、资源所有权、部分初始化失败清理、context loss | 2 |
| `OptifineShaderRuntime` | 源码队列及预算、验证队列、身份/ID 双索引、program 激活与恢复 | 3 |
| `RenderMeasurementRuntime` | 后端与 HZB 的 CPU/GPU 样本关联、两种完成次序、未闭合 scope 清理 | 4 |
| `ModelRenderController` | 模型准入、验证、即时/批次绘制选择、重认证失败节流和统计 | 5 |
| `TerrainRenderController` | 上传策略、shadow 预算、arena/legacy 选择、覆盖率、当前层提交事务 | 6 |
| `DepthVisibilityController` | 捕获时机、历史复用、独立发布确认、输出认证推进和 HZB 测量 | 与 6 配合 |
| `TrackedGlStateSynchronizer` | 实际共享的查询与软件镜像发布，复用当前查询工作区 | 按依赖提取 |

现有 `ModernRendererDiagnostics` 仅是 38 行的字符串发布槽，约 576 行的报告构建仍在 Runtime，需要抽出。组件图完整构建成功后统一发布；context 有效时销毁、丢失时 abandon，清理和 Shader 恢复顺序沿用原行为。

地形列表过滤、arena 提交、提交前回退与过滤 rollback/commit 必须由同一调用流程协调，不能分散后失去事务边界。Terrain controller 可直接调用 Depth controller；后者不反向依赖地形 controller。

主类最终只保留初始化、帧/Pass 生命周期、失效协调和原公开入口转发，预期约 600–1,000 行，按真实职责调整，不将行数作为硬门槛。`RenderBackendSample` 和 `OptifineRegionDrawSample` 已被外部桥引用，先保留名称及调用协议。

## 其他长文件

| 当前类 | 调整方案 | 保留的所有权边界 |
| --- | --- | --- |
| `LwjglTerrainArena` | 提取 `TerrainGpuStorage`、`TerrainIndirectSubmission` | Arena 保留 mesh 身份、allocator、上传 publication/rollback、range retirement 和已提交判定；复用现有命令生成器与 binding sandbox |
| `LwjglDepthHistory` | 提取 `PublishedDepthFrame`、`LwjglDepthReadback`；再评估 `ChunkDepthFilter` | 顶层唯一持有 scene/capture/publication 状态机；复用既有 readback ring、确认 gate 和列表事务 |
| `PrimitiveTerrainVisibilityAdapter` | 按 RenderGlobal、RenderInfo、RenderChunk、CompiledChunk、SetVisibility 五个目标拆适配器，共用小型 ASM helper | 启动期显式注册，保留目标 key/描述符和逐目标失配回退 |
| `OtgSynchronousIoBridge` | 按文件变更追踪、设置缓存/解析、NBT 解析、元数据缓存拆，原桥保留注入入口 | 世代、文件身份和缓存失效的唯一来源；不更改同步 IO/RNG/世界写入顺序 |
| `ReportWriter` | 提取 `ReportFiles`、`ReportSummaryBuilder`、`IcecapV4Writer`、`SessionTextWriter`、`HtmlReportWriter`、`ReportComparisonReader` | 导出门面协调原子发布；汇总只算一次供多格式使用，保持 ICECAP v4 与输出格式 |
| `CorrelatedRenderProfiler` | 将 GPU Query ring 提为 package-private 具体类 | 外层保留 CPU/profile 聚合及公开 scope API，沿用原始类型数组和同步边界 |

`LwjglCapabilitySelfTest` 按能力把自测实现归到对应代码域；`HudRenderBridge` 按字体/顶点提交和状态恢复整理。先审查实际共有状态，再决定抽取范围，避免拆成互相访问全部字段的空壳。

## 重构中的性能要求

- 模型预检后直接使用已认证 Entry，每批一次统计；不重新增加逐部件全套资格检查。
- 姿态对象、矩阵数组、DirectBuffer、Projection scratch 和原始类型计数继续复用。
- HZB 每幅图首次验证后复用，独立发布确认与动态视图匹配保持一致。
- 不为每次 draw/部件创建 context、snapshot 或结果 DTO；不增加事件总线、运行时反射扫描或通用服务定位器。
- Shader/组件工厂、诊断格式化等冷路径可以增加清晰的类边界。热路径按批次或 run 调用具体组件，验证耗时和分配量没有回退。

## 分阶段实施和验收

| 阶段 | 交付结果 | 必须完成的验证 |
| --- | --- | --- |
| A：单包 | 一个正式优化器 JAR；manifest、一次启动状态、安装/升级说明正确 | 最终 JAR 同时提供 Plugin/Transformer/ABI/@Mod；隐藏 Runtime 的早期隔离测试；旧双包升级、幂等和回滚夹具 |
| B：源集和构建 | early/main/profiler 清晰分开；移除重复 ABI 清单，根构建脚本变短 | 单包 class 无重复；hooks 不链接 main；Profiler 依赖边界；重混淆与依赖重定位；连续干净构建可复现 |
| C：渲染冷路径 | 诊断、组件图/工厂、Shader 与测量依次抽取 | 初始化失败、context loss/reload、Shader 双索引发布异常与恢复、CPU/GPU 完成次序 |
| D：绘制控制 | 模型、地形与深度控制独立；再拆底层资源类 | 提交前回退/提交后不重放；模型统计与数组恢复；HZB 新证据/复用/资源失效；离屏像素等价和局部性能基准 |
| E：其他域 | IO、报告、Query、可见性 ASM 按职责拆分 | 真实模组 JAR 契约、报告格式/ZIP/发布一致性、原配置和注入入口兼容 |

每一步先移动方法与状态、保持算法不变，再删除旧实现，不同时改变包名、算法和启动方式。现有依赖 Runtime 私有字段的反射测试，随状态迁到真实持有者；不为旧测试保留重复影子字段。

基线为当前完整构建 810 项测试：780 通过、30 项缺少对应可选依赖而跳过，零失败。沿用真实 Forge/client、OptiFine G5、LLibrary、RenderLib、Mo’ Bends、Lycanites 契约检查。新增最终单 JAR 的实际 Forge 启动烟测：Dregora 客户端与专服、Profiler 安装/未安装均覆盖。

重构发布使用独立的新版本号，建议 1.0.8，并同步模组握手、manifest、产物和安装文档。保留 1.0.7 基线以便游戏对照。先交付 A/B 和 C，再按验证结果推进 D/E；源码结构改善与游戏 FPS 收益分别记录。

## 主要源码入口

- [构建入口](<D:/Program Files/pycharmProject/minecraftMode/ice/build.gradle>)
- [单 JAR 打包](<D:/Program Files/pycharmProject/minecraftMode/ice/gradle/packaging.gradle>)
- [普通模组入口](<D:/Program Files/pycharmProject/minecraftMode/ice/src/main/java/dev/rlcraft/ice/IceMod.java:12>)
- [启动插件](<D:/Program Files/pycharmProject/minecraftMode/ice/src/hooks/java/dev/rlcraft/ice/hooks/IceOptimizerLoadingPlugin.java:6>)
- [当前启动状态判断](<D:/Program Files/pycharmProject/minecraftMode/ice/src/main/java/dev/rlcraft/ice/optimizer/common/CommonOptimizerBootstrap.java:26>)
- [渲染器协调与状态](<D:/Program Files/pycharmProject/minecraftMode/ice/src/main/java/dev/rlcraft/ice/optimizer/client/ModernRendererRuntime.java:136>)
- [报告构建](<D:/Program Files/pycharmProject/minecraftMode/ice/src/main/java/dev/rlcraft/ice/optimizer/client/RendererDiagnosticsWriter.java>)
- [组件图](<D:/Program Files/pycharmProject/minecraftMode/ice/src/main/java/dev/rlcraft/ice/optimizer/client/RendererComponents.java>)
- [组件工厂](<D:/Program Files/pycharmProject/minecraftMode/ice/src/main/java/dev/rlcraft/ice/optimizer/client/RendererComponentFactory.java>)
- [地形资源与提交](<D:/Program Files/pycharmProject/minecraftMode/ice/src/main/java/dev/rlcraft/ice/optimizer/render/terrain/LwjglTerrainArena.java>)
- [深度历史](<D:/Program Files/pycharmProject/minecraftMode/ice/src/main/java/dev/rlcraft/ice/optimizer/render/visibility/LwjglDepthHistory.java>)
- [报告导出](<D:/Program Files/pycharmProject/minecraftMode/ice/src/profiler/java/dev/rlcraft/ice/profiler/report/ReportWriter.java:69>)
