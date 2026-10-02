# ICE 1.0.12 实际运行性能检查

检查日期：2026-10-02。数据为当天 18:41–18:46 的六份自动会话，用户确认在持续移动、探索地形。本次读取日志、记录和源码，未修改模组、配置或存档。

安装的 Optimizer 与 Profiler 均为 1.0.12，SHA-256 与本地构建一致；mods 内只有两个单包，没有旧 Core。FBP 立方体、TextureUtil 参数及模型补丁已安装。实际客户端栈出现 `FbpCubeRenderBridge.drawCube → LwjglFbpCubeRenderer.setupArrays`，证明独立 FBP 快路径实际执行；日志不提供它的总体命中比例。

## 观察结果

六份时间线包含重叠的前窗口。按 epoch 去重后为 260 点，覆盖 18:41:49–18:46:11；52 个重复点的指标一致。详细 ICECAP v4 均解析到 EOF，六份记录的栈字典溢出、前缀合并及丢弃均为零。重叠捕获中的原始 88128 个样本去重为 71309 个。触发按类型、时间去重为 252 次：服务端 Tick 230、客户端帧 14、客户端 Tick 6、GC 计数增量 2。

| 移动时段 | FPS 中位数 | 平均帧耗时的中位数 | 异步 GPU Query 的中位数 |
| --- | ---: | ---: | ---: |
| 18:42:01–18:43:59 | 139 | 7.21 ms | 2.12 ms |
| 18:44:00–18:45:59 | 74 | 13.44 ms | 6.58 ms |

场景持续变化，两段不能用于计算版本提升幅度。GPU Query 是异步返回值，不从帧耗时中相减推算 CPU 独占时间。后段加载实体中位数为 844、方块实体为 8385；这是世界加载数量，不是实际渲染数量。

初入世界的 38.19 秒帧/Tick 包含进入世界与加载界面调用，不能按 `SmoothSync` 的报告标题推断它主动睡眠了 38 秒。3756 ms GC 触发值是一次采集间隔内 11 次 GC 的累计 collection-time 增量，不能视为一次连续停顿。后续探索没有记录到 50 ms 以上的 GC 触发。

## 剩余卡顿

| 触发结束时间 | 类型与时长 | 实际区间证据 |
| --- | --- | --- |
| 18:42:21.222 | 服务端 3150.85 ms | 触发已记录，但该区间的主线程详细样本未保留；不能据邻近栈指定根因 |
| 18:42:09.454 | 服务端 3091.05 ms | 同上，属于初始加载后的探索阶段 |
| 18:45:46.017 | 服务端 860.31 ms | 75 个服务端样本，56 个包含 OTG settings 加载链，49 个落在 `WindowsNativeDispatcher.CreateFile0 → Files.newInputStream → OtgSynchronousIoBridge.loadSettingsEntry` |
| 18:45:49.848 | 服务端 751.14 ms | 区块保存、NBT 方块状态序列化及 OTG 结构 Deflater 压缩；区间 CPU 增量约 718.75 ms，分配约 137.63 MiB，当秒 GC 增量 24 ms |
| 18:46:00.308 | 服务端 601.18 ms | 53 个服务端样本，38 个包含 settings 加载链，其中 34 个在 Windows 文件打开 |
| 18:45:31.174 | 客户端帧 198.92 ms | 实际区间 17 个客户端样本涉及首次打开背包/配方书、JEI 和其他 GUI 初始化、类加载及转换，不能单独归因于地形提交 |

样本 CPU 和分配增量仅为线程旁证，覆盖上一采样间隙，不能直接算成某个方法的独占耗时；本机 CPU 计数还存在时间量化。

860 ms Tick 所在时间线点的客户端帧最大值仅 36.08 ms，601 ms Tick 对应点的客户端帧最大值为 76.70 ms，两点 GC 增量均为零。内置服务端停顿会使世界更新、实体响应和区块供应停顿，不一定同时出现等长的客户端渲染冻结。

`loadSettingsEntry` 是 ICE 包装的配置加载入口。大量样本的真正栈顶在系统文件打开，因此现有摘要“ICE 优化模块开销”不能证明大部分耗时是 ICE 重复检查；也不能证明移除缓存会加速。实际 OTG 原 JAR 的 `FileSettingsReaderOTGPlus` 本来就会打开文件。这里的采样数不等于文件打开次数，同一慢操作可连续出现在多个样本中，栈也不包含文件路径参数。下一步应确认缓存未命中的原因，并减少可避免的重新打开与重解析。

## 可以保留功能的优化点

### 1. 修复地形后端无法恢复的等待状态

最后五份快照的 `published_frame=17980` 未变化，`quiescent=true`、`frame_infrastructure_active=false`；Terrain 的原因是 `model qualification awaits newly captured geometry`。

`AdaptiveBackendController.prepareProfiledWorkload` 同时用于地形和模型。`advanceFrame` 对所有 profiled workloads 的输出验证超时都进入等待模型数据的状态；进入等待后 `mayInspectOrRetestCandidate` 返回 false。唯一的 `candidateDataPublished` 调用位于 `ModernRendererRuntime.acceptModelMesh`，只唤醒实体/TESR。地形上传入口又依赖有效帧和候选接收状态，无法提供对应的唤醒证据。

这形成地形特有的恢复断路。应将模型等待策略限定到模型后端，或让冷端区块上传/新所有权发布提供明确、有界的地形恢复事件。恢复后仍执行输出验证、覆盖条件与 ABBA 收益测量。此次实体、TESR、MDI 的测量未达到收益条件，不能据此强制开启它们。

地形 Arena 曾占有最后可见网格的 29.27%，约 128.2 MB 已使用、134.2 MB 已提交；大量网格无法进入 Arena。容量与候选覆盖也需要处理，但仅增大预算无法修复等待状态，并且不能保证 MDI 变快。

### 2. 停止休眠状态下不再使用的动画可见性扫描

`AnimatedTextureVisibilityBridge.tracker` 仅判断 tracker 是否存在。后端休眠后，`terrainChunk` 仍反射获取 compiled chunk、读取 animated sprites；`terrainDraw` 又遍历容器重复读取。最后会话的客户端 folded 栈有明确的这些调用，属于持续的渲染循环工作。

可在真正休眠、没有延后动画需要补传且无需候选观察时直接返回，避免区块反射和第二次遍历；有 pending 动画、正在验证或需要恢复时保留观察及追赶更新。不能只用 `LEGACY` 一个状态删掉全部可见性工作。

### 3. 减少 OTG 配置缓存的无关失效与重复读盘

优先审查 `OtgSynchronousIoBridge.loadSettingsEntry` 的实际 miss 原因，而不是删除文件变更和 reload 语义。已有缓存为 96 MiB 加权上限、单文件上限 64 MiB，无定时过期，也没有每个区块都清空缓存；热命中已经不 stat、不 toRealPath、不 hash 内容。3 次文件认证是遇变动时的重试上限，正常只走一次，冷读取后的认证也没有再读整份内容。原 OTG 成功加载的对象会留在它自己的对象缓存中。

当前目录 watcher 对同目录任意 create/delete/modify 都推进整目录序列；这可能使未改变的配置也失效。将文件内容失效与目录/路径别名失效区分，可以保留热配置、减少再次打开与解析。实际配置 WorldObjects 与保存 StructureData 位于不同目录，不能把结构保存当作本次已证明的自失效。现场没有逐文件 hit/miss/stale/eviction 指标，无法判断这些打开属于首次加载、无关失效还是容量淘汰。

可先做不依赖 miss 原因的小改：直接从输入流解析 settings，保留读取前后的文件/变更认证，省去 `ByteArrayOutputStream`、整份 `toByteArray` 复制和二次流包装。它减少冷加载的分配与复制，不能保证消除系统文件打开的等待。有限的纯 settings 预热也可研究，但不得执行对象 onEnable、生成结构或改变 RNG/世界更新顺序，且需先测清新资源与重复 miss。

751 ms 保存事件提供了另一条 CPU/分配热点。65 个实际服务端样本中，32 个落在原版 AnvilChunkLoader 的区块 NBT 构建，其中 14 个叶子为 `IdentityHashMap.get → GameData BlockCallbacks → BlockStateContainer.func_186017_a` 的方块状态写出。OTG 结构保存链为 11 个样本；Deflater 样本共 16 个，其中 7 个属于 OTG，9 个是标准 GZIP 世界元数据压缩，不能全部记为 OTG。

应先检查区块 section 序列化中的重复注册表 lookup、遍历、临时数组与 NBT 复制，再处理 OTG 保存的空位置坐标分配、已有分组时丢弃的新 ArrayList、压缩 scratch/输出容量和整份复制。优化必须保留 Forge 注册表语义、序列化格式、真实变更落盘与同步顺序，不能用少保存、丢数据或异步修改世界来替代。

### 4. 减少 Profiler 重复工作，并修正诊断信息

`ProfilerRuntime.onTrigger` 每次调用 `sampler.recentSince`，复制和过滤滚动窗口；但 `RecordingSession.trigger` 已有活动捕获时只延长窗口，不使用传来的 preSamples。只在新建捕获时获取前窗口可以消除这部分分配和遍历，并保留记录内容。

报告的 `primaryTrigger` 选择所有角色中最长的一个触发，再仅按该角色/区间归因，导致初入世界的客户端长帧遮住同一捕获中 168 次服务端触发。`classifyTrace` 又因完整栈中存在 ICE compat 包装者而把系统打开文件归为 ICE 自身开销。应按角色和实际触发区间归因，区分叶子操作、包装者和原请求来源。

此外，230 次服务端触发中只有 89 次区间保留详细样本，141 次没有；长捕获的中部被有界样本选择淘汰。应优先保留真正触发区间的主线程样本，再分配前/后旁证额度，保持现有内存上限。这是诊断质量改进，不能直接作为 FPS 提升结论。

## 数据与限制

只读快照和解析脚本位于 `.refactor-backups/1.0.12-live-performance-audit`；`overview.json` 为会话概要，`hitch/findings.json` 保存详细区间检查，`render/render-review.md` 保存后端与渲染分析，`overhead/brief.md` 保存 OTG/Profiler 源码审查和实际 JAR 字节码证据。没有同位置、同视角的版本对照，不能从本次记录量化 1.0.12 的 FPS 增幅。此次也没有足够证据把 Profiler 的总体自开销认定为主要卡顿来源。
