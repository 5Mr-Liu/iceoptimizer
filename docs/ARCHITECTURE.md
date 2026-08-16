# ICE RLCraft Optimizer / Performance Recorder 完整实现架构

## 1. 设计边界

Profiler 与双端优化器保持包级、模组入口、发布 JAR 和转换器级分离。Profiler 只收集证据；优化器只处理已经由报告确认、并通过物理 side 与目标类结构检查的热点。标准采集路径不取消事件、不改返回值、不重排任务、不写世界数据，也不调整原版或模组线程优先级。

`0.10.0` 构建输出四个互不重叠的正式产物：optimizer main/core 与 profiler main/core。优化器 main 不包含采样、报告、命令、快捷键或 Dashboard；profiler main 不链接优化器状态。两个 core JAR 分别只声明一个 `FMLCorePlugin` 和一个 transformer，构建期会比较全部 class entry 并拒绝重复或越界内容。

客户端优化数据流为：

```text
目标类名 + 方法描述符 + 字段/精确指令结构
                       ↓
        独立 ASM 适配器（逐目标 fail-open）
                       ↓
      纯 CPU 快照 → 有界工作池 → 代际校验
                       ↓
             Agrona MPSC 渲染提交队列
                       ↓
      Minecraft 渲染线程按预算提交/释放 GPU 资源
```

专用服务端数据流不建立渲染队列或后台记录线程：

```text
服务端安全模块 + 目标类精确结构
                       ↓
          双端 ASM 适配器（逐目标 fail-open）
                       ↓
      SRP / Lycanites AI 与寻路、OTG/BO4 同步热路径
                       ↓
        原服务端主线程按原 Tick、RNG 和写入顺序执行
```

类名不在目标目录中时，转换器不猜测也不安装补丁。一个目标类可以注册多个有序、互不绑定的能力适配器；转换器把成功结果串给下一项，某一项结构失配只回退这一项并继续后续能力。目标类 SHA 未见过但结构满足适配器要求时允许转换，并记录“兼容结构验证通过”；结构变化时记录一次兼容性警告并返回该项进入前的字节码。发布配置默认不创建开发目录；只有显式开启 `settings.developmentDiskOutput` 或 `-Dice.optimizer.developmentDiskOutput=true` 后，才把每个结构不兼容目标的样本各保存一次到 `ice-optimizer/discovery`，并在客户端写出 `components-observed.properties`、在专服写出 `components-observed-server.properties`。

启动层不再识别或选择普通 RLCraft / Dregora profile，也不比较 ModContainer 版本或来源 JAR SHA-256。它只按物理 side 选择可加载模块：专服不会启用 Entity Culling、RenderLib、Mo' Bends、RLFoliage 等客户端渲染路径；每个实际出现的目标类随后独立通过字段、方法描述符和调用图验证。旧 `strictPackLock` 字段和相关类型名只用于已有配置/API 二进制兼容，不具备拒绝能力。

数据流为：

```text
Forge/客户端事件 + JVM MXBean + Netty 旁路
                  ↓
          MetricRegistry（每秒聚合）
                  ↓
 ThreadSampler → 栈字典 + 固定环形样本
                  ↓
 TriggerEngine（绝对阈值 + Median/MAD + 倍率）
                  ↓
 前置窗口 + 后置窗口 → RootCauseAnalyzer
                  ↓
 HitchClusterer（相同根因合并、只留最严重代表）
                  ↓
 ICECAP / TXT / CSV / JSON / folded / HTML / ZIP
```

## 2. 常驻低开销层

`MetricRegistry` 使用固定数组、原子计数器和每秒 drain：

- `TimingAccumulator` 保存一个有界时序样本库，并输出 count、average、P50、P95、P99、max。
- 区块事件与网络使用原子增量，每秒 `getAndSet(0)`。
- 世界 Gauge 每秒扫描一次；扫描频率可配置，不逐 Tick 导出。
- `JvmMonitor` 读取标准 MXBean；HotSpot 扩展不可用时返回 `-1`，不阻止游戏。
- GPU 查询采用多查询轮转，只读取已经 available 的结果，不同步等待 GPU。

## 3. 线程采样层

`ThreadRegistry` 显式注册客户端/服务端主线程，并按名称发现 Chunk Batcher、Chunk I/O、文件 I/O、Netty 和常见工作线程。

`ThreadSampler` 在独立低优先级守护线程运行：

- 标准间隔 50 ms，卡顿捕获或深度模式为 10 ms。
- 一次调用 `ThreadMXBean.getThreadInfo(ids, depth)` 批量取栈；深度模式始终包含客户端/服务端主线程，其他 Worker 每批最多四个并轮询。
- HotSpot 一次按 ID 数组批量读取线程 CPU 时间和 allocated bytes；其他 JVM 保留标准逐线程 CPU API，并在不支持分配统计时关闭该证据。
- `StackTraceRepository` 以栈内容为 key 去重，样本只保存 `stackId`。
- `FixedRingBuffer` 达到容量后覆盖最旧引用，不扩容。
- 连续三次采样器内部错误后自动停用采样线程，Forge 主线程继续运行。

内存预算会计算有效上限：

```text
栈字典 <= min(配置, MiB × 256)
滚动样本 <= min(配置, MiB × 400)
详细样本 <= min(配置, MiB × 800)
```

## 4. 触发与会话

`TriggerEngine` 为客户端帧、客户端 Tick、服务端 Tick 分别维护最近分布。有效阈值为：

```text
max(绝对阈值, median + 6 × MAD, median × 2)
```

这样既能发现相对当前环境的异常尖峰，也不会把稳定的 10 FPS 上限持续判为卡顿。

`RecordingSession` 保存：

- 固定时间线环。
- 最多 256 个用户标记。
- 一个正在追加后置样本的活动捕获。
- 已完成的根因聚类与代表捕获。

新触发从线程环复制前置窗口的样本引用；后续采样直到后置窗口结束。合并窗口内的触发追加到同一个捕获，避免一次长卡顿产生几十份重复数据。

默认采用自动事件会话：没有卡顿时只维护全局被动时间线和线程环；触发时以“触发时刻减去前置窗口”为会话起点，回填最近每秒指标和栈样本。后置窗口结束并额外安静 2 秒后，会话自动停止并交给后台报告线程。自动导出的最短间隔为 30 秒，冷却期间的新触发继续进入当前会话并参与聚类，不会丢失。F9 创建的手动会话不受自动停止策略影响，F10 Dashboard 始终可用；这些按键只由可选 profiler 模组注册。

## 5. 根因分析与归属

`RootCauseAnalyzer` 对代表窗口计算：

- 每类热点栈出现次数。
- 最高频可归属类/方法。
- BLOCKED 样本比例。
- CPU 时间与分配字节证据。
- 客户端帧、服务端 Tick、GC 等触发信号。

分类器识别世界生成、区块读写、光照、实体、方块实体、寻路、碰撞、Forge 事件、网络、资源加载、区块渲染、一般渲染、锁等待和 GC。

`ModResolver` 不使用 `Class.forName` 执行热点类。它读取 class 资源 URL/JAR 路径，并与 Forge `ModContainer.getSource()` 建立的来源表匹配；Minecraft、Forge、JVM 和 LWJGL 使用明确内建归属。

置信度由分类支配比例、热点重复次数和直接触发信号组合，限制在 0–98%。报告同时列出原始证据，避免只给一个不可验证的猜测。

## 6. 重复事件聚类

聚类 key 为：

```text
rootCause | modId | hotMethod
```

每个聚类累计次数、总时长、最大时长、首次/末次时间和触发类型计数。代表样本始终保留该类最严重的 N 个；新样本不够严重时只增加统计，不增加详细数据。达到聚类或详细样本上限后仅增加丢弃计数。

## 7. 报告格式

- `summary.txt` 面向玩家和优化开发者，直接给出类别、模组、方法、证据、置信度和建议。
- `timeline.csv` 用于表格/绘图和 A/B 对比。
- `hitches.json` 保存聚类和代表元数据；可选 detailed JSON 才包含逐样本记录。
- `probes.csv` 保存 hooks 聚合。
- `stacks.folded` 面向火焰图工具。
- `.icecap` 使用版本号、gzip 和数字栈 ID，保存可重放的紧凑数据。
- `report.html` 不引用 CDN 或远程脚本。
- ZIP 仅打包本 Session 文件。

写入过程先进入同一报告根目录下的隐藏临时目录，完成后原子移动；清理函数验证 canonical path 必须位于 `ice-profiler/sessions` 内，避免删除越界。

## 8. 可选精确探针

两个 core JAR 与各自主 JAR 分开构建。Profiler core 中的探针配合 `ProbeBridge` 使用预分配的线程本地嵌套数组，入口返回 long token，出口按 `probeId + subject` 聚合 count/total/max。探针 map 按 family 限制 subject 数，多余类折叠到 `<other>`。Optimizer core 则只携带目标目录、审计 SHA-256、结构适配器与早期状态 journal；SHA 不参与执行放行。

ASM transformer 的安全策略：

- 只处理明确类、接口和方法签名。
- 记录输入类 SHA-256 短指纹及成功探针数。
- 为正常返回和异常退出都调用 `ProbeBridge.exit`。
- 使用 `COMPUTE_FRAMES` 重新计算栈帧。
- 任何异常返回原始 `byte[]`。
- `ProbeBridge` 自身吞掉采集异常，永不把异常带回原游戏调用。

Forge 会在普通模组 JAR 可见之前实例化 CoreMod 转换器，因此 hooks 源集在构建期禁止依赖 main 输出。`IceProfilerLoadingPlugin` 只返回 `IceProfilerTransformer`；`IceOptimizerLoadingPlugin` 只返回双端 `IceOptimizerTransformer` 并初始化目标发现策略。Profiler transformer 只保存 `ProbeBridge` 的内部类名和稳定数字 ABI，不在启动期解析该类；optimizer transformer 使用 Core JAR 本地 SHA-256 实现。optimizer CoreMod 的 transformer exclusion 只包含 `dev.rlcraft.ice.hooks.*`；主运行时 `dev.rlcraft.ice.optimizer.*` 必须继续由 LaunchClassLoader 正常解析，否则 FermiumASM 环境会在 preInit 产生 `NoClassDefFoundError`。早期观察到的优化目标和已安装补丁先写入 `OptimizerPatchJournal`，客户端或专服主运行时初始化后通过反射回放到 `OptimizerRegistry`。隔离类加载测试会隐藏全部 main 运行时类，并要求转换器仍能初始化和执行，同时校验 optimizer exclusion 只剩 hooks 包。

## 9. 隐私与兼容

默认报告不需要世界种子、玩家名或精确坐标。绝对游戏/世界路径不进入报告；命令也只显示 Session 名。Netty handler 始终先计数、再调用 `super`，即使计数失败也继续原网络管线。

优化器从 `0.7.0` 起声明为双端必需模组；`0.10.0` 使用 `acceptableRemoteVersions=[0.10.0]` 强制客户端与专用服务端使用同一 ICE 主 JAR 版本。该握手不检查 RLCraft、Dregora 或目标组件版本。两端也都必须安装 optimizer Core JAR 才会真正注入优化。Profiler 没有自定义网络握手协议，仍可按需要单独安装在客户端、单人集成服务器或独立服务端，另一端不要求安装 Profiler。

## 10. 后续优化器准入条件

每个优化器必须拥有独立开关、基准报告和回归测试。只有满足以下条件才进入实现：

1. 同一路线至少两次报告指向同一类别、模组和方法。
2. 代表栈证明耗时位于可优化代码，而不是显卡驱动、磁盘硬件或 JVM 外部停顿。
3. 优化不改变世界生成结果、实体/方块实体 Tick 次数、事件顺序、保存顺序或画面内容。
4. 优化后用报告对比确认 P95/最大值改善，同时触发次数、内存和兼容性没有回归。

## 11. 双端优化运行时

公共启动层先按物理 side 捕获不可变配置、初始化 `OptimizerRegistry`、回放早期 `OptimizerPatchJournal`，再建立只读的结构兼容状态。组件观察失败也不会拒绝适配；Core JAR 缺失时记录一次错误并保持所有字节码优化不生效。

专用服务端由 `OptimizerServerProxy` 启动 `ServerOptimizerRuntime`：不注册 F3、按键、渲染事件、客户端资源监听器或后台记录线程，只放行标记为 dedicated-server-safe 的同步算法优化。`FMLServerStoppedEvent` 会显式关闭共享模块注册表。

客户端额外拥有以下运行时设施：

- `ClientEpochs` 维护帧、客户端 Tick、世界、资源和 GL 上下文代际；异步结果只要依赖代际发生变化，就在提交前丢弃。
- `ClientWorkerRuntime` 使用专用低优先级守护线程和固定容量队列。队列已满时调用方回退原路径，不允许在客户端线程执行拒绝任务。
- `BoundedRenderQueue` 使用 Agrona `ManyToOneConcurrentArrayQueue`；每帧只消耗配置的时间预算，且只执行 ICE 自己生成的命令。
- `CacheBudget` 对堆、Direct 和 GPU 资源分别做原子硬限制。Caffeine 负责带权淘汰，LZ4 只用于字节完全可逆的冷数据。
- `ModuleCircuitBreaker` 只熔断发生错误的模块；核心运行时、SRP、Lycanites 寻路/注册表/模型/效果、Mo' Bends 模型/四元数/实体动画、Ice and Fire 姿态/粒子、OptiFine 动态光、Rustic 栅栏、FoamFix / TextureUtil、Xaero、RenderLib、OreLib、Better Foliage、Better Caves、Quality Tools、Quark 和 Dynamic Trees 状态互不连坐。
- Caffeine、Agrona 和 LZ4 在最终 JAR 中重定位到私有命名空间；Minecraft 自带 Netty、LWJGL、ASM 和旧集合库不会被全局覆盖。
- 优化器不注册常规 HUD 或按键；只有 `Minecraft.gameSettings.showDebugInfo` 为真时，才通过 `RenderGameOverlayEvent.Text` 在 F3 右侧追加两行不可交互摘要。

## 12. FoamFix / TextureUtil 纹理上传适配器

`FoamFixTextureUploadAdapter` 匹配私有静态方法 `uploadTextureMaxMips(I[[IIIIIZZZ)V`；`VanillaTextureUploadAdapter` 仍包装生产 SRG `TextureUtil` 的单级 `(I[IIIIIZZZ)V` 上传入口以保持早期 Core/Main 隔离，但 0.10.0 的单级桥固定返回 `false`。实际 0.9.4 Session 证明单级入口会形成每小纹理一个 Fence 的驱动税，因此只有 FoamFix 已经聚合好的完整 mip 数组才允许进入 PBO 候选。两条适配器都要求唯一方法描述符和原上传调用图；桥返回 `false` 时控制流落回未经删改的原方法。

优化路径保持像素、mip、过滤、clamp、坐标和提交顺序不变：

- 完整 mip 批次至少 256 KiB，才把有效 mip 按原顺序复制到一个有 Direct 硬预算的连续暂存区；小批次不进入桥。
- 四个纹理参数由“每 mip 一次”合并为“每次完整上传一次”。
- 支持 PBO 且具备 OpenGL 3.2 核心 Sync 或 `GL_ARB_sync` 时轮转三个上传槽；Fence 未完成时不调用带等待的同步 API，而是尝试其他槽。
- 三槽都忙、GPU 预算不足或显卡不支持时，使用同一暂存区逐 mip 直接上传。
- FoamFix 三个调用点在完整上传后原本就会执行一次 `checkGLError`，因此桥接方法不再添加逐 mip 或重复的 `glGetError`。
- PBO 绑定在调用后恢复；发生 Java/LWJGL 异常的槽会被隔离，模块按连续错误熔断，并执行原方法。
- GL 槽生命周期使用 `glContextGeneration`，不把普通资源重载误认为显卡上下文重建。

## 13. Xaero World Map 非阻塞 GPU 基准适配器

`XaeroTextureUploadAdapter` 只匹配 `xaero.map.graphics.TextureUploader` 的已审查调用图。原类为 7 类纹理分别执行 512、512、512、256、256、256、256 个上传样本，共 2560 次；每个样本通过 `glFinish` 等待 GPU 完成。适配器保持上传对象、纹理数据、对象池、预算计算、队列和调用顺序不变，只把同步计时器调用替换为 `XaeroGpuTimerBridge`。

- 使用固定 32 对 GPU Timestamp Query，`begin/end` 只提交时间戳，不等待查询结果。
- 仅在 `GL_QUERY_RESULT_AVAILABLE` 为真时读取 64 位时间戳；每次轮询有固定检查上限。
- 单样本仍取 `max(GPU elapsed, CPU submit elapsed)`，并保持 Xaero 原来的样本目标和 1/3/4 ms 默认估计。
- 查询槽全部忙时跳过本次计时样本，不阻塞上传线程；后续样本继续补足原目标数量。
- 不支持 OpenGL 3.3/ARB timer query、模块关闭、反射签名变化或运行时错误时，通过缓存反射调用原 `isFinished/getAverage/pre/post`，原批次前 `glFinish` 也完整恢复。
- 适配器同时校验 `isFinished=8`、`getAverage=6`、`glFinish=1`、`pre=1`、`post=1` 的精确调用图；任何数量变化都返回原类。

## 14. RenderLib 方块实体合并适配器

`RenderLibTileEntityAdapter` 只匹配静态方法 `processTileEntities(World, Consumer)V`。原实现完成监听器回调后，对每个待加入方块实体调用一次 `loadedTileEntityList.contains`，跑图时会形成 `pending × loaded` 的线性扫描。桥接实现完整保留无效实体过滤、`World.addTileEntity`、Chunk 注册、方块更新、待加入列表清空及异常传播顺序。

- 仅当 `loaded >= 64` 且 `pending >= 4` 时尝试建立成员表，小批次直接执行原线性查询。
- 成员表使用可复用 Agrona `ObjectHashSet`，容量按 long 计算并限制在 `1 << 20`，分配前必须取得 Heap 硬预算。
- 只有 `equals` 与 `hashCode` 都继承 `Object` 的方块实体才进入哈希表并使用哈希查询；任何自定义相等语义仍调用原 `List.contains`。
- 每次 `World.addTileEntity` 后验证列表是否按预期尾部追加；大小或追加位置异常时，本次剩余处理立即放弃成员表。
- 静态成员表使用非阻塞重入保护；另一线程或嵌套调用拿不到使用权时，在任何副作用前返回原方法。
- 保留原实现对 `TileEntity.getPos()` 的五次调用和调用顺序，不假定模组子类的 getter 是纯函数。
- 反射字段解析、预算拒绝或缓存内部异常不会重复已经发生的世界副作用；缓存错误可安全降级时继续用原查询，原游戏调用抛出的异常仍按原行为传播。

## 15. SRP 多模型动态关节分支批处理

`SrpKirinStaticMeshAdapter` 保留旧 ABI 名称，但 0.5.0 已成为 13 个 SRParasites 模型共用的结构适配器。目录为 `ModelEsor`、`ModelMudo`、`ModelNuuh`、`ModelJinjo`、`ModelBanoAdapted`、`ModelInfVillager`、`ModelInfEnderman`、`ModelInfHorse`、`ModelInfHuman`、`ModelCruxA`、`ModelAlafha`、`ModelNogla` 与 `ModelKirin` 保留已回归 SHA。真正的门是每个入口的 `ModelRenderer.func_78785_a(scale)` 调用数：Villager 为 5 个顶层调用，Kirin 同时审查 `func_78088_a` 与 `renderC`，其余模型各 1 个；任何额外入口或调用图变化都拒绝转换。

bridge 不再假定整棵模型只有一个静态根，而是自适应寻找动态关节下的稳定分支：

- 第一次渲染执行原路径，让 vanilla 按原 scale 编译每个可见节点自己的 Display List；之后连续观察四次才允许建立批次。
- 每个候选批次根的 offset、rotation point 和旋转角不进入静态快照，渲染时逐实体、逐帧实时读取并按 vanilla 的 offset、rotation point、Z/Y/X 旋转顺序应用。
- 候选根以下所有可见后代必须连续稳定四次，且整个分支至少包含三个可见节点；否则继续精确树遍历，不为小分支增加额外 GPU 对象。
- 外层 Display List 只合并已有节点 Display List 与层级矩阵提交；底层几何、纹理、原列表和子节点顺序不被重建或替换。
- 每次调用在任何批次提交前重新验证后代变换的原始 float 位、显隐、`compiled`、Display List ID、子节点身份/顺序和 scale 原始位。任一漂移会在当前调用释放该批次并精确遍历原树，不会绘制上一帧姿态。
- 失效分支冷却 40 次模型调用后才重新预热，避免战斗动画反复编译；稳定的兄弟分支仍可独立保留自己的批次。
- 每个顶层模型根最多遍历 512 个节点，全局最多记录 96 个顶层根；每个批次按可见节点数估算 GPU 硬预算且单项最少预留 4 KiB。
- GL 上下文代际变化时旧列表 ID 不会在新上下文中删除，只释放 ICE 预算并重新捕获；同一上下文中的失败列表会立即删除。
- `compiled/displayList` 同时接受 MCP 与 SRG 字段名；字段缺失、GL 分配失败、预算拒绝或模块熔断都保持原路径。

## 16. OreLib / Dynamic Surroundings GL 状态快照

`OreLibOpenGlStateAdapter` 为已回归的 `org.orecruncher.lib.gfx.OpenGlState` 保留 SHA-256 `94f22fed…d5047` 作为审计信息。执行时要求私有静态 `getInteger(I)I` 与 `getFloat(I)F` 各自恰好调用一次对应的 LWJGL 查询，只把这两个调用替换为 bridge；构造函数、字段、矩阵栈和 `pop` 的恢复顺序完全不改。

原构造函数同步读取 15 个整数状态和 1 个浮点状态。稳态路径从 `GlStateManager` 已维护的 MCP/SRG 双名字段读取 blend、alpha、depth、cull、lighting、normalize 与 rescale 状态：

- 每个快照开头的 `GL_BLEND` 始终查询驱动并与 Java 缓存比较，因此在使用任何后续缓存值前有一个前置哨兵。
- `GL_BLEND_EQUATION_RGB` 没有可信 Java 缓存，始终执行原查询。
- `GL_TEXTURE_2D` 依赖当前活动纹理单元，始终执行原查询，同时只把 `activeTextureUnit + textureState[]` 用作一致性哨兵。
- 首次快照和之后每 32 次快照完整执行原 16 次查询，并逐项与缓存比较；该快照返回的仍全部是真实驱动值。
- 完整校验通过后，普通快照只保留 3 个原生查询，其余 13 项为只读反射字段访问，不改变任何 GL 状态。
- BooleanState 的 capability 会逐项验证，活动纹理索引必须在数组范围内；字段同时接受 MCP 与 SRG 名称。
- 任一值不一致、字段缺失、空状态或索引越界都会把本模块标记为 `INCOMPATIBLE`，当前及后续调用使用原 `GL11.glGet*`，不会影响其他优化模块。

OreLib、Dynamic Surroundings、Dynamic Surroundings HUDs 与 RLMixins 的版本/JAR SHA 不再作为全局限制；目标类结构和运行时每 32 次完整 GL 真值校验共同防止在不兼容调用方或 Mixin 组合上继续使用该假设。

## 17. 客户端区块网格热点适配器

两台电脑的有效 Chunk Worker 样本中，Better Foliage 路径占 `8814 / 9444`。其中 OctarineCore `AoFaceData.update(Int3, boolean, float)` 每个方向都新建一个 `float[12]` 与 `BitSet(3)`，随后仍调用 vanilla `AmbientOcclusionFace.func_187491_a`。`RendererHolder.modelRenderer` 由 `ThreadLocalDelegate` 提供；一个 `ModelRenderer` 继承 `ShadingContext`，构造六个方向各自独立的 `AoFaceData`，因此暂存对象不会在 Chunk Worker 间共享。

`BetterFoliageAoScratchAdapter` 记录 `AoFaceData` 已回归 SHA-256 `8295031f…45df`，执行门改为同时校验：

- 精确存在一个 `update(Int3, boolean, float)` 与一个 `AoFaceData(EnumFacing)` 构造函数。
- `update` 精确包含一个 `float[12]` 和一个 `BitSet(3)` 分配点。
- 构造函数精确调用一次 `Object.<init>`，适配器只在其后初始化两个私有 final synthetic 暂存字段。
- 模块可运行时使用实例字段并在每次调用前执行 `BitSet.clear()`；关闭、结构不兼容或熔断时保留原两个分配分支。
- AO 方法、方块状态读取、BlockPos 计算、六面遍历、亮度数组、颜色数组和最终四角写入完全不改。

Dynamic Trees 的 `IBakedModel.getQuads` 会由 vanilla 针对一般四边形和可见面重复调用；原 `pollConnections` 每次都分配 `int[6]` 并从同一个不可变扩展状态读取六个连接属性。`DynamicTreesConnectionAdapter` 为 `BakedModelBlockBranchBasic` 保留已回归 SHA `aa9a36d1…a478`，但只要求结构上精确存在一个 `int[6]`、一次虚调用 `getConnectionRadius` 和一个 `ARETURN`，然后在方法入口/出口加入一项每线程 memo：

- Key 为 baked model 身份、已 clamp 的 radius、`IExtendedBlockState` 身份和当前线程；不使用 `equals/hashCode`，也不跨线程共享。
- 第一次调用完整执行原六属性读取；只有后续 key 完全相同的面查询返回同一个只读连接数组。
- 已审查的 Basic 与 Thick 调用方都只读取数组。状态、模型、半径任一变化立即 miss，并覆盖每线程唯一旧项。
- `BakedModelBlockBranchCactus` 是独立实现，且会在渲染调用中改写 `connections[1]`；它没有继承目标方法，也被明确排除，避免把可变数组跨面复用。
- side 与 random seed 不参与 `pollConnections`，且后续模型选取与 `List<BakedQuad>` 组装仍由原方法逐面执行，所以四边形内容、顺序、裁面与随机行为不变。

Better Foliage 与 Dynamic Trees 的真实 JAR/类 SHA 继续用于回归样本标识，不限制其他结构兼容版本。两个适配器均有合成调用图变化拒绝、配置关闭原路径、对象复用语义、真实 JAR、ASM 结构与 JVM 类定义测试。

## 18. SRP 实体 AI 与寻路热点

`SrpParasiteNavigatorAdapter` 为 SRParasites 1.9.11 的 `EntityParasiteBase` 保存已回归 SHA，但执行时确认的是目标类直接继承 `EntityMob` 且原类没有自己的 `func_175447_b(World)`。注入的 `SrpPathNavigateGround` 复制 vanilla `PathNavigateGround.getPathFinder` 的行为：建立 `WalkNodeProcessor`、启用进门并交给 `PathFinder`；唯一变化是节点处理器加入搜索局部缓存。

- `SrpCachingWalkNodeProcessor.init` 开始一次缓存生命周期，`postProcess` 的 `finally` 中清空所有 primitive map；结果不跨寻路调用、Tick、世界或实体共享。
- 原始节点分类与包含实体尺寸/邻域检查的分类使用两个独立 `Long2ObjectHashMap`，坐标键与 vanilla `BlockPos.toLong` 的 26/12/26 位布局一致。
- 单表最多 65536 项；达到上限只停止写入新项，已有结果仍等价。map 异常时当前搜索立即停用缓存、清空并调用原方法。

`SrpTargetSearchAdapter` 要求 `EntityAINearestAttackableTargetStatus.func_75250_a()` 中恰好一次 `Collections.sort` 和一次 `List.get` 的已审查调用图。私有候选列表只读取排序后的第一个元素，因此 `SrpTargetSearchBridge` 对支持随机访问且不少于两个元素的列表执行稳定线性最小值选择；比较结果为零时不替换当前最优，保持稳定排序原本的“最先出现者优先”。配置关闭或列表类型不适合时仍执行原完整排序。

这些逻辑只优化当前 JVM 中真实执行的实体逻辑。单人集成服务器与客户端同进程，所以由客户端安装受益；多人远端 SRP AI/寻路由安装同版 ICE 的专用服务端受益。物理服务端配置会明确关闭模型、OpenGL、区块网格和头颅等所有客户端模块。

## 19. Lycanites 寻路与注册表热点

`LycanitesNodeProcessorAdapter` 为 Lycanites Mobs 2.0.8.9 的 `CreatureNodeProcessor` 保存已回归 SHA，执行时要求一次初始化/结束生命周期、2 个 `getPathNodeTypeRaw` 与 14 个 `IBlockAccess.getBlockState` 调用点。适配器给真实类加入 `LycanitesRawNodeAccessor`，未缓存 trampoline 使用虚调用以保留潜在子类 override 语义。

- `func_186315_a` 调用父类初始化后压入线程局部搜索上下文，`func_176163_a` 开始时弹出；嵌套寻路使用独立上下文栈。
- 原始节点类型和方块状态分别保存在 Agrona `Long2ObjectHashMap`，最多各 65536 项，相同坐标只在本次搜索内复用。
- processor、`IBlockAccess` 或生命周期不匹配时不命中缓存；栈失配会清空当前线程全部上下文并将本模块标记为不兼容。
- map 读取/写入异常只停用当前上下文并回到真实方法，不影响其他优化模块或后续原游戏逻辑。

`LycanitesObjectManagerAdapter` 逐个验证 `getBlock(String)` 与 `getEffect(String)` 中零或一次 `toLowerCase`、一次 `containsKey`、一次 `get` 和两次目标 Map 读取，然后用对应桥接调用替换方法体。普通 RLCraft 2.0.8.9 继续规范化 key；Dregora 2.0.8.10 使用精确、区分大小写的 key。开启时两者都只执行一次 `Map.get`；关闭时桥接函数按各自版本原样执行 `containsKey ? get : null`。

Lycanites Mobs `2.0.8.9` 与 Dregora 参考实例的 `2.0.8.10 - MC 1.12.2` 都作为真实回归样本；测试同时记录类 SHA，并验证精确调用点数量、ASM 变换后接口/生命周期结构和 JVM 类定义。其他版本不因 SHA 不同被拒绝，只需通过同一结构门。

## 20. Lycanites 模型、GPU 与实体效果热点

`LycanitesObjRenderAdapter` 精确匹配 `TessellatorModel` 与 `VBOModel` 的 `renderGroupImpl(ObjObject, Vector4f, Vector2f, VertexFormat)` 结构。原方法重命名保留，公开 wrapper 先调用 `LycanitesObjRenderBridge.tryRender`，任何拒绝或失败都执行原实现。

- 缓存按模型身份、ObjObject 身份、Mesh/indices/vertices/normals 身份与长度、VBO ID、颜色/UV raw float bits 和 `VertexFormat` 身份区分，不合并任何输入不同的提交。
- 一个变体连续观察三次才编译；每组最多 8 个变体、最多 96 个模型、2048 个分组和 1024 个 Display List，避免动态染色造成无界 GPU 对象。
- 每个列表先取得 GPU `CacheBudget` 预留；网格签名改变、资源代际改变或 GL 上下文代际改变时释放对应列表与预算。同一 GL 上下文可安全删除，已丢失的旧上下文只释放账本。
- 反射字段沿继承链解析；异常只熔断 `lycanites-obj-render`。该模块是本轮唯一必须在真实 OpenGL 游戏中额外做画面回归的路径，可用 `settings.lycanitesObjRender=false` 单独关闭而保留其余优化。

Lycanites 动画侧由四类适配器组成：

- `Animator` 的四个 GL 转发方法改由 bridge 执行；只有零角度旋转、零位移和 `(1,1,1)` 缩放被跳过，raw float 判断保留 `-0.0`/NaN 等边界语义。
- `ModelObjPart.applyAnimationFrames` 保留 parent、offset、中心平移、offset 旋转、帧应用和反向中心平移的精确顺序，只把 `Iterator` 改成 `size/get` 索引循环。
- `ModelObjAnimationFrame.apply` 缓存 angle/rotate/translate/scale 类型编号；公开 `type` 字段引用发生替换时立即重新分类，未知类型仍不执行任何分支。
- `ModelCreatureObj`、`ModelItemBase`、`ModelObjOld` 共 10 个无 Locale 参数的 `toLowerCase()` 调用进入最大 4096 项的 Caffeine 缓存；默认 Locale 变化时整个缓存清空，行为与原 Java 调用一致。

`LycanitesPotionEffectsAdapter` 固定 35 个常量 `ObjectManager.getEffect` 调用和 20 个唯一效果名，注入 `AtomicReferenceArray`、名称表和显式 null 哨兵。第一次读取仍调用真实注册表，后续命中不再重复 lowercase/Map 探测；附近实体的 invokedynamic Predicate 按 required class 使用 `ClassValue` 复用。模块关闭时效果查询与 Predicate 均恢复原语义，不缓存实体列表或事件结果。

## 21. Mo' Bends 全模型公共路径

Mo' Bends 的玩家、原版怪物和动物模型最终共用 `goblinbob.mobends.core.client.model.ModelPart`，因此优化这个公共类即可覆盖所有被 Mo' Bends 接管的实体类别，无需逐怪物复制适配器。

- `ModelPart` 保存从当前部件到根的 `IModelPart[]`，每次使用前逐项验证 `getParent()` 身份；拓扑改变立即重建，链深超过 64 时调用接口默认实现。
- 快速路径严格执行 `当前到根 applyPreTransform`，同时保存逐层 scale，再执行 `根到当前 applyLocalTransform`；矩阵重载使用同一顺序和同一 `IMat4x4d`。
- `renderPart` 与 `renderJustPart` 保留显隐、编译、矩阵 push/pop、局部/角色变换、Display List 和 childModels 顺序，仅将 Iterator 改为一次 `size` 加索引 `get`。
- Quaternion 类实现私有 ICE ABI，每实例持有一个 `FloatBuffer(16)`；x/y/z/w 的 raw bits 全部相同才复用并 rewind，任一位变化调用原 `QuaternionUtils.quatToGlMatrix` 重算。`GlHelper.rotate` 在模块关闭或对象未实现 ABI 时执行原静态缓冲路径。
- `LivingEntityData.calcClimbing` 先执行原方法最终一定会检查的 entity/world、`isOnLadder` 与 `isOnGround` guard。非攀爬实体直接返回相同 false，只有可能攀爬时才执行含三个 `World.getBlockState` 的完整原方法。

真实 Mo' Bends 1.2.1 回归会在 Java 8 JVM 中定义 `ModelPart`，验证对接口默认方法的 `INVOKESPECIAL`，并实际调用 Quaternion 缓存两次命中、修改分量后重算。

## 22. Ice and Fire 姿态与粒子暂存

`IceAndFirePoseAdapter` 以 Ice and Fire 1.7.1 为已回归样本，并按 `IceAndFireTabulaModelAnimator.moveToPose` 的方法结构适配。原循环对同一部件名称重复调用 `baseModel.getCube` 与目标 pose `getCube` 共五次；快速路径各读取一次存入局部变量，之后仍调用相同 `isPartEqual`、`distance` 和 `ModelAnimator.rotate`，Map values 的遍历顺序不变。

`IceAndFireSeaSerpentAdapter` 只替换两个已审查分配点：`spawnParticlesAroundEntity` 的 `new int[0]`，以及 `spawnSlamParticles` 中 Ice and Fire 1.7.1 的 `new int[]{0}` 或 Dregora 2.0.9 的 `new int[0]`。零长度数组不可变，单元素数组每次返回前重置为零；粒子类型、数量、位置、速度、调用次数和顺序均由原方法决定。模块关闭时 bridge 按当前版本的原数组形态重新分配。

目标目录在 0.8.0 为 53 个类，每个目标可以保留普通版、Dregora 原始 JAR、生产 SRG 或实际转换后字节码的独立 SHA-256 作为审计样本。真实 Dregora 回归覆盖 SRP 1.9.21、Lycanites 2.0.8.10、RenderLib 1.4.5、RLFoliage 2.4.2、Ice and Fire 2.0.9、Better Caves、Quality Tools、Quark、OTG、生产 SRG `WorldServer`、`ChunkProviderServer`、`LayerCustomHead` 和运行时捕获类；这些版本不构成运行白名单。

## 23. OTG / BO4 按需生成热点

Dregora 参考预设包含 16618 个 `.BO4`（约 1.77 GiB）和 101657 个 NBT 文件，未提供 `.BO4Data`。ICE 不扫描或预载整个预设，也不生成额外旁路缓存文件；实际 OTG JAR SHA-256 `099661c…9d3c` 和四个类指纹只作为回归样本，适配器仍按方法结构放行。

- `BO4.onEnable` 原本会把已经成功读取的对象再次交给 `FileSettingsWriterOTGPlus.writeToFile`。模块可运行时只消费栈上的 writer 参数并跳过该调用；关闭、结构不兼容或桥接不可用时原 writer 原样执行。解析、继承、注册和返回值均不改。
- `BO4.trySpawnAt` 原本连续调用两次 `BO4Config.getBlocks()`，每次都为全部方块新建函数对象。ICE 只在同一次方法调用内让第二处使用第一处局部数组。不会跨 spawn 缓存，因为随机方块选择会修改函数对象的 material/metadata；因此 RNG 次数、选择结果和后续生成之间的隔离保持原样。
- `BO4Config.loadBlockArrays` 每处理一个方块都会调用一次 `getColumnBlockIndex`，而原实现从 `(0,0)` 扫到目标列。桥接按当前线程和精确 `short[][]` 身份建立 256 个 int 前缀值；数组身份改变立即重建，越界或模块关闭执行原扫描。
- `StringHelper.readCommaSeperatedString` 使用两遍索引扫描替代 `toCharArray + LinkedList + 二次数组`，嵌套括号、空字段、空白和括号不平衡结果与原实现逐项回归。`CustomObjectResourcesManager` 的函数名小集合进入最大 128 项 Caffeine 缓存，仍使用无 Locale 参数的 `String.toLowerCase()`；默认 Locale 改变即全部失效。

四个适配器分别归属 `otg-bo4-io`、`otg-bo4-layout` 与 `otg-config-parser`，开关和熔断互不绑定。真实 OTG JAR 回归会记录 JAR/类 SHA，并验证精确调用点、变换后 ASM 结构及 Java 8 JVM 实际类定义。

## 24. 玩家头颅资料异步解析

生产 SRG 与开发映射版 `LayerCustomHead` 分别保留独立完整 SHA-256 审计值。适配器只在结构中恰好存在一次 `TileEntitySkull.updateGameProfile` 时替换，并在 `TileEntitySkullRenderer.renderSkull` 参数提交前再次查询缓存；方法描述符、参数栈和渲染调用次数不变。

- 不完整资料当帧立即返回原 `GameProfile`，所以原版默认皮肤继续绘制；后台完成后后续帧取得同名完整资料。
- 后台只有一个低优先级 daemon worker；队列默认 128、正/负缓存默认各最多 2048 项，同名请求由有界 in-flight map 去重。
- 正缓存默认访问后 360 分钟过期；无结果、断网或队列满进入 300 秒负缓存。联网失败记为拒绝而不是模块错误，不会触发同步联网回退。
- 每次 configure/shutdown 都递增配置代际并清空旧缓存；即使旧 socket 调用忽略中断并晚于新运行时返回，也不能写入新缓存或移除新请求的 in-flight 标记。
- 模块关闭、类结构不兼容或运行时熔断时保留原同步方法。模块运行时从不在渲染线程等待 executor、Future、锁或网络。

## 25. Better Caves、区块颜色与实体状态热点

`0.8.0` 新增七个目标类，来源于最新 Dregora 记录中 Better Caves 噪声/插值、Better Foliage + OptiFine 区块重建、Quality Tools 生物属性刷新和 Quark 掉落物同步热点。四组优化都没有跨世界异步执行，也不改变 RNG 或写入次序。

Better Caves 由四个适配器组成：

- 公共模块 ID 由启动期构建的 O(1) 查找表解析；Better Caves 热路径门缓存稳定的 `ModuleCircuitBreaker` 对象，并在每次进入优化分支前读取其实时状态。它不缓存永久 boolean，所以配置关闭、熔断和结构回退仍立即生效，同时避免普通 RLCraft 洞穴生成循环反复分配枚举数组和比较全部模块名。
- `NoiseTuple` 以 `double[]` 保存热值，同时保留公开 `List<Double>` 的实时兼容视图；复制是深复制，融合方法一次分配目标数组并按原顺序执行 `left * leftScale + right * rightScale`。
- `NoiseColumn` 对正常 0–255 高度使用连续 `NoiseTuple[]`，公开 Map 访问或异常高度才延迟物化后备 Map；缓存模板返回前总是深复制，调用方修改不会污染后续命中。
- `NoiseGen` 使用每实例 64 槽直接映射缓存。槽索引可以碰撞，但命中前必须比较完整 X/Z 位置键和 bottom/top 高度范围键；命中返回 `ice$copy`。四段插值只在 Tuple/Column 的 `ice$blend`、`ice$copy` ABI 都能反射解析时启用，部分适配成功会退回完整原方法而不是产生 `NoSuchMethodError`。
- `CaveCarver` 用 `BetterCavesThresholdMap` 保存连续高度阈值。构造循环保留原 float 运算顺序，测试逐项比较 `Float.floatToIntBits`；Map 的 `get`、`containsKey`、`size` 和迭代键值仍符合只读使用方式。

`BetterFoliageOptifineColorAdapter` 只替换 `OptifineCustomColors` 中逐方块的 `Field.get` 路径。Bridge 通过 `ClassValue<Accessor>` 为每个实际 `GameSettings` 运行时类解析一次 `ofCustomColors`，支持字段位于父类；模块关闭时执行未缓存反射，字段不存在或读取失败时保持原 false 语义。

`QualityToolsAttributeAdapter` 在原七 Tick 生物事件入口加入 `shouldRefresh` guard。玩家还会扫描完整背包/Baubles，马包含盔甲 NBT，因此两类始终执行原逻辑；普通生物保存六个装备槽的 `ItemStack.copy` 弱快照，首次、任一物品/NBT/数量变化、Tick 回绕或距离上次复核 140 Tick 时执行原属性拆装。弱键避免实体卸载后被 ICE 强引用。

`QuarkItemSyncAdapter` 把 `ItemsFlashBeforeExpiring` 原来的 age 与 lifespan 两个 `WeakHashMap<EntityItem, Integer>` 合并到单个 `WeakHashMap<EntityItem, State>`。正常 age 每 Tick +1 时只原地更新 primitive int；age 跳变或 lifespan 与首次值不同返回同步。原 Quark 逻辑在异常 lifespan 后不会更新保存值，因此 Bridge 也保持该状态，使后续 Tick 持续同步；模块关闭或异常时返回 `USE_ORIGINAL` 执行未修改分支。

## 26. 原版同步全量保存计划刻索引

`MinecraftSaveTickAdapter` 协调两个生产 SRG 目标。`ChunkProviderServer.func_186027_a(Z)Z` 的原实现被完整保留在私有 trampoline 中，公开 wrapper 用 catch-all finally 建立/释放线程本地保存作用域；只有 `all=true` 启用索引，普通每 Tick 最多 24 区块的保存路径完全执行原逻辑。

`WorldServer` 注入只读访问器、原 `func_72920_a(Chunk, boolean)` trampoline 和一个 volatile 变更版本。适配器验证三个计划刻集合字段、四个已知写入方法及所有引用这些集合的方法；任何新增未跟踪集合写入都会拒绝转换。调度、执行、移除入口在方法进入时递增版本，允许无实际变化时多做一次安全重建，但绝不漏掉合法写入。

- 索引按原 `TreeSet` 迭代顺序建立，再追加 `pendingTickListEntriesThisTick` 的列表顺序。
- 原版区块查询范围是 X/Z 各向负方向扩两格，因此边界位置仍可进入最多四个相邻区块列表；索引逐项复制这一关系。
- 每次返回新的 `ArrayList`，不会把内部索引容器暴露给 Anvil 或其他调用方。
- 保存事件、实体/方块实体序列化或 Capability 若新增计划刻，版本会在下一个区块查询前变化并触发重建；已经序列化的区块不会被倒序修改。
- 索引只读、同步、线程本地且在原方法 finally 中清空；嵌套非全量保存会遮蔽外层作用域。版本漂移、集合结构变化、容量上限或重入失配均执行原逐区块扫描。

## 27. Lycanites 刷怪方块扫描

`LycanitesSpawnScanAdapter` 只匹配 2.0.8.10 已审查结构：精确一个 `getSpawnPositions`、一个 `isValidBlock`、两个扫描方法方块状态读取、一个验证读取，以及固定的 `HashMap contains/get/put/values` 调用图。原扫描方法保留在私有 trampoline，wrapper 只负责线程本地生命周期，异常仍按原调用传播。

- Y/X/Z 循环、MutableBlockPos、流体百分比、BlockLiquid metadata、候选加入、blockCost/requiredBlockTypes 检查和 `sortSpawnPositions` 调用顺序未改。
- 本地方块计数 Map 在首个有效方块前不分配 backing table，之后使用 primitive int；公开结果与世界状态不缓存。
- 只有运行时类恰为 `BlockSpawnLocation`、World 是已审查原版实现、surface 与 underground 同时开启、blockIds 是同一标准 ArrayList 且当前方块既非 `IFluidBlock` 也非 `BlockLiquid` 时，`isValidBlock` 才复用扫描刚取得的同坐标状态。
- 子类、自定义 World、天光分支、流体、列表身份变化、模块关闭或作用域失配都会调用真实 `World.getBlockState`；最终排序/RNG、刷怪数量、位置和事件不变。

## 28. Profiler 触发区间与线程角色归因

根因分析先取最长触发信号的真实区间 `[timestamp-duration, timestamp]`。客户端帧/Tick 优先客户端主线程，服务端 Tick 优先服务端主线程；对应样本缺失时依次放宽到区间内任一主线程、邻近主线程，最后才使用非主线程。Worker 栈保留在报告中作为旁证，但不能凭采样数量压过实际触发线程。

空闲过滤额外识别 Netty/NIO selector、Windows selector native `poll0`、epoll/kqueue select，以及 `ThreadedFileIOBase` 的 `Thread.sleep`。主线程上的 sleep/park 仍不会被自动忽略，因为它可能属于真实等待链。

## 29. OptiFine 动态光、Rustic 状态与 Fermium 后置策略

`OptifineDynamicLightsAdapter` 为 `DynamicLight` 和 `DynamicLightsMap` 增加只读早期 ABI，并在 `DynamicLights.update/entityRemoved/removeLights/clear` 的原更新边界发布快照。快照只保存坐标、亮度和水下标记；`getLightLevel(BlockPos)` 先读单个 volatile 引用，接口缺失或模块不可运行时调用私有保留方法。少于 96 个光源线性扫描，大于等于 96 个时按 8×8×8 单元建立只读索引，只访问查询单元周围 27 个单元。

`RusticLatticeAdapter` 不缓存邻居或连接判定。`getExtendedState` 的循环仍按 `EnumFacing` 0–5 调用原 `canConnectTo`，Bridge 只按源状态、属性身份和 Boolean 值复用不可变转换结果；表满或探测冲突时直接调用原 `withProperty`。包围盒按 Rustic 的 DOWN、UP、NORTH、SOUTH、WEST、EAST 位序预构建 64 个精确对象，任一属性缺失或类型变化时执行原包围盒方法。

`VanillaChunkRenderAdapter` 在构造器中定位最终 worker 局部变量存储，并在其后调用 `tuneWorkerCount`，因此 Fermium 的 `@ModifyVariable` 结果先执行。builder 限制插在唯一 `field_188249_c` 写入之后；当 Fermium 把写入重定向到自己的 helper 时，限制也位于 helper 的最终赋值之后。CPU 与 JVM 堆只形成上限，不会把前序实现给出的 worker 或 builder 数量增大；结构无法唯一确认时仅关闭线程策略，独立 VBO 上传适配仍继续尝试。

## 30. 0.10.0 无锁模块门与记录器降耗

`OptimizationModule` 采用 append-only ordinal ABI，所有注入桥在编译期保存 ordinal。`OptimizerRegistry` 只发布一个 volatile `long operationalMask`；稳定调用只做范围检查、一次 volatile 读取和位判断。熔断器用 CAS 迁移状态、`LongAdder` 计数，仅配置、目标观察和显式关闭保留同步边界。

Profiler 的线程发现仍按配置周期执行，但发现结果会编译为不可变采样计划并复用描述符和 ID 数组。深度模式每批固定包含已发现的客户端/服务端主线程，并只轮询最多四个其他线程；HotSpot CPU 时间与分配字节分别用一次数组 MXBean 调用读取。这样不会让 10 ms 深度记录暂停全部 Chunk/IO/Worker 线程。

## 31. Konkrete、SRPMixins 与 Lycanites 成员索引

`KonkreteLocaleAdapter` 保留原 `getKeyForString` 为私有 synthetic fallback。Bridge 只接受可稳定遍历的语言 Map，并按精确 Map 身份和资源代际构建反向值索引；使用 `putIfAbsent` 保留重复翻译时源 Map 第一个 key 的语义。Map 身份变化、重载竞争、未知实现或异常都调用原反射扫描。

`SrpSpawnFilterAdapter` 把包装条目编译为保持原序的连续结构，同次调用复用 parasite 状态、colony 总点数和分类 cap；最终 Predicate、动态配置、世界、维度、玩家和 RNG 仍在原线程按原顺序读取。`LycanitesBlockMembershipAdapter` 为可跟踪列表维护代际索引；增删改清、公开字段替换或未知 List 实现会重建或执行原 `contains`。

## 32. 区块 NBT 并行压缩与顺序提交

`ChunkSaveCompressionAdapter` 在 `AnvilChunkLoader` 把完整 NBT 快照放入 pending Map 后提交一个身份绑定任务。Worker 不访问 World/Chunk/Entity，只对该快照执行 `CompressedStreamTools.write` 和 zlib Deflate；线程数按 CPU 与最大堆限制为 1–4，队列和结果总字节都有硬上限。

FILE_IO 线程从原 pending Map 取得同一个 NBT 身份后等待对应任务，随后通过 Core-only `RegionFileRawWriteAccessor` 调用原同步 raw write，因而 pending Map 迭代、RegionFile 写入和磁盘格式都不变。Accessor 缺失、队列拒绝、结果大于 16 MiB、代际变化、压缩错误或取消时 wrapper 调用保留的原 `func_183013_b`。取消任务主动 count down，确保线程池关闭不会让 FILE_IO 永久等待。

## 33. OptiFine / Forge BlockState 直调用

两个独立 target 分别处理 `ReflectorForge` 与 `BlockStateContainer$StateImplementation`。适配器要求唯一对象参数方法、准确返回类型，以及精确一个 `Reflector.callInt` / `callBoolean`；匹配后把原方法重命名保留，公开 wrapper 先调用 `ForgeBlockStateDirectBridge`。Bridge 只执行对应 `IBlockState` 或 `Block` Forge 虚方法，返回专用 fallback sentinel 时调用原反射方法。

普通 Forge 的 StateImplementation 已经没有 Reflector 调用时抛出预期 skip，而不是把模块或其他 target 判为失败。目标类名和完整 JAR SHA 不构成白名单；方法结构变化只回退本能力。目标目录在 0.10.0 为 66 个唯一类、68 个独立能力项。
