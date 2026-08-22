# ICE 完整自适应优化实现与源码审计

日期：2026-08-21

目标版本：ICE RLCraft Optimizer / Performance Recorder `1.0`
运行环境：Minecraft 1.12.2、Forge 14.23.5.2860、Java 8

## 1. 最终实现结论

当前实现不再维护任何按 CPU 型号、P/E 核、逻辑处理器数量、GPU 厂商、核显/独显、集成服务器或远程服务器划分的静态性能表。

所有机器使用同一套运行时策略：

1. 保留其他 CoreMod 最终创建的线程和缓冲拓扑，不猜测哪一种硬件应当使用多少 Chunk Worker。
2. 后台线程降低一个 Java 优先级，不绑定 CPU 核，不依赖 Java 8 无法可靠提供的 P/E 拓扑信息。
3. 用公平 permit 控制“同时真正执行”的区块编译数，而不是销毁或重建原线程池。
4. 从最保守的单并发开始，只依据当前运行中测得的帧尾、Tick、GC、进程负载、队列、等待者和实际吞吐闭环探测。
5. 增加并发后必须通过收益探测；吞吐没有改善或帧尾恶化时自动回滚。
6. GPU 路径只看当前 OpenGL 能力、Fence 是否完成、资源预算和本次上传大小，不识别显卡品牌或“核显/独显”标签。
7. 所有缓存、队列、任务、线程、堆、Direct、GPU 对象和磁盘输出都具有硬边界、代际失效和原实现回退路径。

源码目标目录当前包含 48 个稳定模块 ID、74 个唯一目标类和 77 个独立能力项；其中 44 个模块直接拥有字节码目标，其余为核心运行时、渲染提交后端或保留兼容项。`OptimizationModule` 继续保持 append-only，已注入 ordinal 不会因本次实现改变。

## 2. 实测卡顿根因

本节直接对应：

`D:\Program Files\Mcserver\rlcraftDregora\.minecraft\versions\RLCraft Dregora\ice-profiler\sessions`

| Session | 实测事件 | 结论 |
| --- | ---: | --- |
| `20260816-235120-766` | 客户端长帧 `37683.8169 ms` | `Minecraft.launchIntegratedServer → fillProfileProperties → Authlib HTTPS/SSL → socketRead0`。实际触发区间内客户端主线程样本 619 个，`HttpAuthenticationService.performGetRequest` 328 个；同期 GC `3527 ms` 是伴随压力，不是 37.68 秒冻结的主因。 |
| `20260816-235150-289` | 服务端 Tick `4099.0539 ms` | 世界生成主线程同步打开配置/NBT 文件。`FileInputStream.open0` folded 权重合计 1166；Roguelike `SettingsResolver → SettingsContainer.loadFiles` 的最大单链为 324，OTG/BO3/BO4 文件链占其余主体。 |
| `20260816-235415-482` | 服务端 Tick `12842.5422 ms` | `BO4Config.readResources` 是主根因；归因使用 593 个触发区间服务端主线程样本，最高方法 278 次，窗口内目标线程分配约 `3939.1 MiB`。 |
| `20260816-235532-518` | 区块保存 Tick `1198.3058 ms` | 会话 56.03 秒内 `chunk_data_saves` 合计 2465，峰值单秒 1241；热点位于同步区块 NBT/保存链。 |
| `20260816-235618-558` | `208.4603 ms` / `100.7519 ms` | 旧报告把 Lycanites 的 ICE 包装桥当成根因。原 folded 栈的真实叶子继续进入 `World.getBlockState`、`getChunk/loadChunk`；新版归因将 `ice$scan$` 和已进入底层业务调用的 ICE 委托层视为透明，不再把包装方法本身算作计算根因。 |

因此卡顿不是单一的“线程太少”或“显卡不够快”，而是五类问题叠加：

- 主线程同步网络；
- 世界生成过程中重复磁盘读取和解析；
- BO4/BO3/Roguelike 配置重复装载；
- 区块保存的同步 NBT 构造、序列化和 Deflate；
- 区块网格并发过量、对象分配、上传同步和真实 GPU 工作。

## 3. 硬件无关的区块编译闭环

实现：`ChunkRenderPolicyBridge`、`VanillaChunkRenderAdapter`。

### 3.1 拓扑与准入

- 不改写 `ChunkRenderDispatcher` 最终 worker 数。
- 不改写 `RegionRenderCacheBuilder` 数量。
- Fermium、NormalASM 或其他前序转换器的最终结果保持不变。
- 每个实际 Chunk Worker 启动时登记，线程优先级降低到最多 `NORM_PRIORITY - 1`。
- 每个 `processTask` 外层执行公平、可中断的 permit acquire，并在 `finally` 中释放。
- 若等待 permit 时线程被 shutdown 中断，先把已经从原队列取出的 `ChunkCompileTaskGenerator` 标为完成，再传播中断，避免任务同时从队列和 RenderChunk 状态机中消失。
- 模块关闭或结构失配时打开 permit 门，保留原执行方式。

### 3.2 采样信号

控制器每 500 ms 最多评估一次：

- 最近 180 个帧样本；
- 低负载基线使用 P20；
- 帧尾使用最近 60 帧 P95；
- 最近 120 个集成服 Tick 的 P95；
- GC collection time 在墙钟窗口中的占比；
- JVM 可提供时的进程 CPU 使用率；
- 编译队列、上传队列、permit 等待者、in-flight 和完成任务数；
- 扩容前后的真实任务/秒。

基线只在单 permit 或真正空闲窗口学习。繁忙期间不会把已经被并发拖慢的帧时间重新学习为“正常”，避免控制器逐步接受越来越差的延迟。

### 3.3 减载

任一条件成立即认为存在压力：

- 近期帧 P95 高于 `max(低负载参考 + 2 ms, 参考 × 120%)`；
- 至少 8 个 Tick 样本且 Tick P95 高于 55 ms；
- GC 暂停占比至少 10%；
- 进程 CPU 至少 97% 且编译队列仍有积压；
- 上传队列超过 `max(4, permit × 3)` 且仍在增长。

满足 750 ms 降载驻留后，permit 使用 `max(1, (当前 + 1) / 2)` 近似减半。已在执行的任务不强杀，只阻止更多后台任务同时进入。

### 3.4 扩容与回滚

只有同时满足以下条件才累计稳定窗口：

- 帧 P95 不高于 `max(参考 + 2 ms, 参考 × 115%)`；
- Tick 样本不足，或 Tick P95 不高于 50 ms；
- 进程 CPU 不可读，或不高于 90%；
- GC 暂停占比低于 5%；
- 上传队列不高于 `max(2, permit)`；
- 编译队列/等待者存在、in-flight 已达到 permit，且本窗口确实完成了任务。

连续四个合格窗口并满足 2.5 秒扩容驻留后，只增加一个 permit。随后进入 2 秒探测：

- 仍有需求时，吞吐增益不足 5%则回滚；
- 帧尾相对扩容前恶化超过 15%或 2 ms则回滚；
- 通过探测才保留新并发。

世界切换、资源生命周期重置时清空学习结果并从单 permit 重新探索。这样同一台电脑在空世界、复杂整合包、单人集成服和远程服中的工作点可以不同，但不需要任何硬件型号分支。

## 4. 区块保存完整实现

实现：`SaveTickIndexBridge`、`ChunkSaveCompressionBridge`、`MinecraftSaveTickAdapter`、`ChunkSaveCompressionAdapter`。

### 4.1 全量保存计划刻索引

- 仍由服务端主线程执行同步 `saveChunks(true)`。
- 只在一次完整保存作用域内，为未变化的 pending tick 集合建立按 ChunkPos 的临时只读索引。
- 每次计划刻增删都增加版本；版本不一致立即重建或执行原扫描。
- 非全量查询不创建空 ThreadLocal 状态；最外层作用域退出执行 `ThreadLocal.remove()`。
- 不移除条目，不改变条目、区块、NBT、事件或保存顺序。

### 4.2 快照后并行压缩

- 主线程仍完整构造原 `NBTTagCompound`，包括实体、方块实体、Capability 和模组事件产生的数据。
- `AnvilChunkLoader` 把快照放入原 pending Map 后，才把纯 `CompressedStreamTools.write + zlib Deflate` 提交到专用池。
- Worker 不读取 World、Chunk、Entity、TileEntity、事件总线或共享 Random。
- 原 FILE_IO 线程最多等待对应结果 100 ms；超时、拒绝或失败即执行原压缩流。
- 原 FILE_IO 线程仍按 pending Map 的原顺序写 RegionFile；世界写入顺序不变。

任务键使用“`AnvilChunkLoader`/保存器对象身份 + ChunkPos”，不会让两个维度或两个存档中相同坐标互相取消。快照本身继续用对象身份关联，避免相同内容误合并。

### 4.3 压缩池闭环和边界

- 初始 worker：1。
- 绝对安全上限：16；再由 `max(1, min(16, Runtime.maxMemory / 64 MiB))` 连续工作集预算收紧。这是资源保险丝，不是硬件档位。
- 队列容量：64。
- 同时跟踪任务：最多 128。
- 单个压缩结果：最多 16 MiB。
- 结果总预算：`max(64 KiB, min(128 MiB, Runtime.maxMemory / 32))`。
- 控制周期：1 秒。
- 扩容要求队列饱和、active worker 已满、已有可用结果和延迟样本、Tick/CPU/GC 有余量且本窗口没有 FILE_IO fallback；连续三个合格窗口并满足 3 秒驻留后只加 1。
- 新 worker 进入 2.5 秒探针；吞吐增益不足 5%、fallback rate 增加 25 个百分点或平均完成延迟恶化超过 15%/2 ms即回滚，失败后冷却 5 秒。
- Tick P95 高于 55 ms、单次 Tick 至少 100 ms、CPU 达到 97%、GC 比例达到 10%或 fallback 占终态至少 25%时近似减半；减载驻留 750 ms。
- 空闲 10 秒只清除旧置信度与未完成探针，保留最近已经验证的 worker 工作点；短保存突发不必每次从 1 重新学习。
- Worker 使用低优先级 daemon 线程；退出时 `Deflater.end()`、清除 ThreadLocal 和 context classloader 引用。

取消、shutdown、世界代际变化、同坐标新快照覆盖、结果消费和预算释放都采用明确状态机；等待者在取消时立即释放，不依赖后台压缩函数最终返回。压缩流使用 try-with-resources，关闭阶段的取消/清理异常只能作为 suppressed，不能覆盖原 primary fatal；operation 即使与 cancel/epoch/interrupt 同期发生，任务也会先进入终态再传播 `ThreadDeath`/`VirtualMachineError`。

## 5. 网络与配置 I/O

### 5.1 集成服 Session Profile

`SessionProfileAsyncBridge` 不把启动链已经返回、随后会经 LocalChannel 与集成服务器共享的 `GameProfile` 交给后台线程。ASM 同时替换启动链中的 `fillProfileProperties` 和紧邻的 `Session.setProperties`：worker 只针对私有 query `GameProfile` 执行 Authlib 请求，再把 multimap key、`Property.name`、value 和 signature 复制为不可变快照。

- 主线程立即按 UUID/名称继续登录，不等待 Mojang HTTPS。
- 空属性结果绝不调用 `Session.setProperties`，因此不会用空 `PropertyMap` 永久污染 `Session.hasCachedProperties()`，后续节流预取和重试仍可发生。
- 非空结果只在客户端 Tick 上提交，并为每个仍注册、代际仍有效的 `Session` 新建防御性 `PropertyMap` 副本；worker、Session 与集成服之间不共享可变属性容器。
- 同身份 single-flight；正缓存 6 小时、负缓存 30 秒；最多 8 个 key、每 key 8 个 Session 目标和 8 个待提交 completion。
- 客户端 Tick 最多每 30 秒预取一次；shutdown、cache hit 和 completion 都与 generation 线性化，旧请求不能写回新运行时。

### 5.2 玩家头颅

`SkullProfileBridge` 使用单 worker、有界队列、正负缓存和 in-flight 去重。后台请求只读取渲染线程创建的 `GameProfile` 快照，不跨线程遍历可变 `PropertyMap`。

重配置使用 generation + accepting 线性化：

- shutdown 后旧调用不能重建 executor；
- 旧请求不能写回新缓存；
- 最多保留两个未终止的退役 executor；
- 若 Authlib/socket 忽略 interrupt 并连续卡死，不再无限创建线程，而是保持优化关闭；旧线程真正终止后，下一次显式 configure 可恢复；
- Worker 为 daemon、低优先级，并清空 context classloader。

### 5.3 Roguelike 设置

`RoguelikeSettingsCacheBridge` 将 `SettingsResolver.getInstance` 变为 single-flight：同一代际只有一个线程扫描/解析设置，其余调用复用结果。文件树指纹包含规范路径、元数据和内容边界；有深度、条目数和总内容字节上限，拒绝循环与根目录外路径。资源变化或 shutdown 后旧加载结果不能发布。

真实 `RoguelikeDungeonsFnarEdition-1.12.2-2.4.6.jar` 的 `SettingsResolver` SHA-256 已记录为 `7f94c8f0b0c32b32358891ae661acf7105ead22bc59b739b5f2f06e17c087e2a`，并加入真实 JAR 转换/定义回归。

### 5.4 OTG / BO4

BO4 不做整预设预加载，不生成全局可变方块对象缓存，也不异步访问世界。实现包括：

- 按需 BO4 源解析结果的持久缓存；
- 相同源文件 single-flight；
- 源文件规范路径 containment；
- 版本、长度、mtime 和内容 hash 校验；
- 临时文件写入后原子移动；
- 缓存损坏、格式变化、预算不足或 I/O 失败时回原解析；
- shutdown generation 防止迟到任务重新挂回；
- 同一次生成中的方块数组复用和 256 项列前缀表；
- OTG biome `ArraysCache` 改为嵌套安全的线程本地池，租赁结束和线程退出清理。

源 BO4 内容、结构方块顺序、RNG 调用次数和世界写入顺序保持不变。

## 6. 区块网格、分配和缓存算法

- `ChunkRebuildCoalescer` 合并同一个 RenderChunk 的冗余 rebuild 请求，不吞掉 terminal 状态；资源/世界代际变化全部失效。
- `ChunkPrimitiveSortBridge` 用 primitive 稳定归并排序替代 `Integer[] + Comparator + TimSort`；`Float.compare`、NaN、相等顺序和顶点位逐 bit 保持。ThreadLocal 数组扩容先取得 Heap 预算，拒绝时不修改缓冲并执行原排序；worker 结束释放数组和预算。
- `ForgeBlockStateDirectBridge` 只在精确 Reflector 调用图匹配时改为等价 Forge 虚调用；普通 Forge 已经没有目标调用时是预期 skip。
- `FluidExtendedStateTransitionBridge` 只缓存同一 Chunk Worker 上不可变状态的精确 property/value 转移，仍执行 property 有效性检查和原异常语义。
- `DynamicTreesConnectionBridge` 只复用同一模型、半径、不可变扩展状态的六面连接数组；Cactus 等会修改数组的模型不进入缓存。
- Better Foliage AO、OptiFine 颜色、OptiFine 动态光、Rustic 栅栏、Better Caves primitive pipeline 均以完整输入身份/数值和生命周期为键；任一不变量不成立立即回原路径。
- Forge 流体转移缓存只有在原 state、property 和 value 实例都完全相同时才复用结果；两个仅 `equals()` 相同但身份不同的 Float/属性值不会共享返回 Map，避免暴露第一次调用的对象身份。
- Mo' Bends 的 `childModels` 继续执行原 `List.iterator()`，保留自定义 List、Iterator 副作用和 fail-fast；只复用逐次验证的父链数组和每实例矩阵缓冲。
- OreLib 的 Java GL 状态缓存已删除。bridge 每次返回当前 `GL11.glGet*` native 真值，模块默认关闭；局部哨兵不能证明其他模组没有直接修改其余状态，因此不再接受这条伪优化。

所有 Chunk Worker ThreadLocal 在 worker 退出时清理；主线程生命周期也在 world/resource/GL/stop 边界主动清理，避免线程长期存活时保留旧世界、模型、状态或大型数组。

## 7. GPU 与 OpenGL 生命周期

GPU 优化不识别显卡厂商，只根据实际能力和反馈运行。

### 7.1 区块 VBO

- 只有上传量至少 256 KiB 才尝试 staging。
- 支持 OpenGL 3.1 核心 copy 或 `GL_ARB_copy_buffer`，同步支持核心 Sync 或 `GL_ARB_sync`。
- 每次最多检查两个槽，不轮询全部槽，不调用等待型 Fence API。
- Fence 未完成、槽位忙、预算不足、能力缺失、上下文变化或异常时立即执行原 `glBufferData`。
- 异常/超时槽先从可见槽表摘除，再分别 best-effort 删除 Fence 和 Buffer，最后释放预算；一个删除失败不阻止其他资源清理。

### 7.2 FoamFix

通用单 mip PBO 保持关闭，因为实测数百次 `glFenceSync` 会形成反优化。只有 FoamFix 已经聚合的完整 mip 批次达到 256 KiB 才尝试三槽 PBO；其他条件执行原 FoamFix 路径。

### 7.3 Lycanites / SRP Display List

- 只允许渲染线程创建和删除 GL 对象。
- 检测嵌套 `glNewList`，不在未知 Display List 作用域中编译。
- 编译前后建立 GL error scope；只有实际绘制调用成功且错误检查通过才标记 renderStarted/发布批次。
- 反射原方法按精确实例、`void` 返回值和完整参数签名匹配。
- 新批次发布失败、子批清理失败、资源或 GL 代际变化时清理新旧资源并释放预算。

### 7.4 Xaero Query

`XaeroGpuTimerBridge` 使用：

`IDLE → STARTED → IN_FLIGHT → IDLE/RETIRED`

并显式记录本次配对为 `NONE / GPU / ORIGINAL`。重复 `isFinished`、未闭合 begin/end、benchmark 切换、上下文切换、模块中途关闭、Query timeout 和 shutdown 都有终止路径。两个 Query ID 独立删除；一个删除失败不泄漏另一个，也不阻止反射缓存清空。

## 8. 有界异步运行时

`ClientWorkerRuntime` 与 `BoundedRenderQueue` 的端到端边界：

- CPU 队列固定容量；拒绝时调用方仍能执行原路径。
- 接收 CPU 计算前先预留一个渲染 completion 槽，避免计算完成后因渲染队列已满而静默丢结果。
- 预留使用 Semaphore 统一管理直接提交和 completion；拒绝、失效、异常、取消和 shutdown 均释放一次且只释放一次。
- 渲染队列只有一个消费者，并用锁串行化 drain/discard。
- 每执行一条命令后重新检查时间预算，昂贵命令不会再连带执行更多命令越过本帧预算。
- world/resource/GL epoch 不匹配的任务丢弃结果，不把旧世界对象提交到新生命周期。
- shutdown 对全部 worker 共用一个 250 ms 总 deadline，不按 worker 数量重复等待 250 ms。

## 9. 字节码适配与失败回退

每个 adapter 验证：

- 目标类名；
- 方法名和完整 descriptor；
- 字段类型与访问方式；
- 唯一调用数量；
- 必需的控制流和栈形状；
- 已存在 ICE wrapper/接口时拒绝重复安装。

SHA-256 只记录已审查样本，不是运行白名单。未知 SHA 只要结构完全满足仍可转换；结构不满足时返回该能力进入前的原字节码，同一类的其他独立能力继续尝试。

Transformer、bootstrap、runtime bridge 和优化热路径中的 `catch(Throwable)` 必须先传播 `ThreadDeath` 与 `VirtualMachineError`；普通兼容异常才记录到单模块熔断。fatal 查找遍历完整 cause、`ExceptionInInitializerError` 与 suppressed 图，并用身份集合终止环，不再受 16 层包装上限影响。Optimizer Main、Profiler Main、Optimizer Core 和独立 Profiler Core 均有自包含实现；单模块连续失败不会关闭其他模块。

## 10. 路径、磁盘与报告安全

### 10.1 Profiler 报告

- Session ID 只允许字母数字、点、下划线和连字符；拒绝空值、`..`、目录分隔符和超过 128 字符的 ID。
- 精确查找和前缀查找都重新执行 canonical containment 与 symlink 检查。
- zip、目录大小扫描和 retention 删除不跟随 symlink。
- 删除报告目录内的 symlink 只删除链接本身，不递归到链接目标。
- zip 只加入报告根目录内的普通文件。

### 10.2 BO4 / Roguelike

- 所有磁盘候选先规范化并验证仍位于允许根目录。
- 不信任文件名、相对路径、缓存头或旧缓存元数据。
- 单文件、目录深度、条目数、内容字节和缓存总量均有上限。
- 写缓存使用临时文件和原子替换；无法保证时回退原解析，不覆盖源配置。

## 11. 本次源码审计已修问题

| 问题 | 风险 | 修复 |
| --- | --- | --- |
| 普通 SaveTick 查询创建空 ThreadLocal | 服务端主线程长期保留无用状态 | 改为惰性创建；完整作用域退出 `remove()`。 |
| 区块压缩只用 `(x,z)` 取消旧任务 | 不同维度/保存器相同坐标互相取消 | 键加入保存器对象身份。 |
| 压缩结果预算固定至少 32 MiB | 小堆环境保留过多结果 | 改为 64 KiB～128 MiB、受 `maxHeap/32` 限制。 |
| primitive sort 大数组未计入预算 | 长寿命 Chunk Worker 可驻留大堆数组 | 扩容前预留 Heap，worker/lifecycle 结束释放。 |
| Session profile worker 修改已返回的 `GameProfile` / 共享 `PropertyMap` | LocalChannel 与客户端线程竞态，属性遍历可能看到半更新状态 | worker 使用私有 query profile，只发布不可变属性快照；客户端 Tick 给每个 Session 创建防御副本。 |
| 空 profile 仍调用 `Session.setProperties` | `hasCachedProperties()` 被空 Map 永久置真，后续无法预取或重试 | ASM 一并替换紧邻 setter；空结果从不写入 Session。 |
| Skull executor 在旧 socket 不响应 interrupt 时可不断重建 | daemon 线程泄漏和网络请求堆积 | generation/accepting 线性化，退役 executor 最多两个，达到上限关闭优化。 |
| Worker 先算完再发现渲染队列满 | 结果静默丢失，调用方已无法回原路径 | 接受计算前预留 completion 槽。 |
| 多 worker shutdown 每个等待 250 ms | 关闭时等待随 worker 数线性增长 | 全部 worker 共用 250 ms 总 deadline。 |
| GL poisoned 槽仍留在池中 | 重复异常、Fence/Buffer/Query 泄漏 | 先摘槽，再独立删除每个对象并释放预算。 |
| Xaero begin/end/isFinished 配对不完整 | Query 悬挂、错误读取或上下文泄漏 | 明确四态状态机和 GPU/ORIGINAL 配对。 |
| Display List 编译未验证嵌套与 GL error | 破坏外层 GL 状态、发布空/错误列表 | 渲染线程门、嵌套检查、错误作用域、成功后发布。 |
| 报告 ID/前缀可形成目录越界候选 | 路径遍历、错误压缩或删除 | 严格 ID 语法、canonical containment、symlink 不跟随。 |
| 报告根或中间目录被 symlink/junction/reparse point 重定向 | zip、retention、`.writing` 清理可能离开可信目录 | 固定可信 real/canonical root 与 file key；每级 `NOFOLLOW` 复核，Windows junction 按 reparse point 拒绝。 |
| Konkrete Map 内容同尺寸替换 | 旧反向索引 miss 会错误返回 `null` | 索引 miss 回到原完整扫描，并按当前内容重建。 |
| SRP 每线程编译缓存和 primitive scratch 无硬预算 | 长寿命 Chunk Worker 可保留大列表与历史峰值 backing table | 单列表 2048 项、每线程 4096 项/256 KiB；超预算严格回原路径，大 scratch 用后移除。 |
| 外部 RegionFile 线程复用 ThreadLocal `Deflater` | 长寿命线程长期保留 native zlib 状态 | 仅 ICE 压缩 worker 复用；外部线程每次 disposable，流关闭必定 `Deflater.end()`。 |
| 新模块枚举或损坏配置绕过边界 | ordinal 漂移、未知模块误开启、极端队列/预算值 | `OptimizationModule` append-only ordinal 回归；所有模块显式映射，未知项默认关闭，数值统一 clamp。 |
| Profiler 控制任务和报告写入积压 | 采样线程/内存压力反过来制造卡顿 | 每代最多 16 个 control task；报告队列固定容量 4，满时拒绝而不在游戏线程等待。 |
| Profiler 把 ICE 委托包装层当根因 | 报告误导优化方向 | self/inclusive 分离；进入底层业务调用后的包装层透明。 |
| 压缩任务 fatal 与 cancel/epoch/interrupt 同期发生 | fatal 被误当预期取消吞掉，JVM 可能继续运行损坏状态 | 保留 primary failure，任务先进入终态并释放等待者，再传播完整链中的 fatal。 |
| 压缩 write 抛 fatal 后 close/flush 又抛取消异常 | cleanup 覆盖 primary fatal | try-with-resources 保留 primary，cleanup 作为 suppressed，Deflater lease 仍释放。 |
| Skull 初始化失败后 `shutdownNow()` 抛 `SecurityException` | cleanup 覆盖原初始化 fatal | 合并 cleanup failure；完整 throwable 图优先传播 primary fatal。 |
| fatal 只检查 16 层 cause | 深层反射/EIIE/suppressed 包装中的 fatal 被漏检 | 完整、环安全 throwable 图遍历，并覆盖 Main/Core/Profiler 边界。 |
| Probe calls/total/max 分拆 drain | 并发 record 可被拆入不同窗口，出现 calls 与耗时错配 | 同一 accumulator 临界区线性化 record/drain，复用 primitive snapshot。 |
| Chunk 扩容后需求已消失 | 短突发可让 permit 棘轮式上升 | `!demandRemains` 直接回滚到 probe 前 limit。 |
| Roguelike A→B→A 且恢复元数据 | 从 B 解析的结果可能被 A 的端点指纹错误验证 | WatchService mutation sequence 绑定候选，事件/OVERFLOW/迟到事件均推进 generation。 |
| Client Worker idle-retire/submit 竞态 | 任务和 completion reservation 永久滞留 | pending/committed 槽状态机；commit 尾部存活时 fail-open。 |
| OTG sweeper 中断拒绝后叠加 replacement | 两代线程并发扫描/删除 cache | 100 ms 有界等待 + retired sweeper gate；旧线程死亡前不建新 owner。 |
| Netty 断线线程直接释放 GPU Query | 无 GL context 调用、与 render begin/end 并发 | 原子 handoff 到 client/render Tick，全部 Query 生命周期单线程串行。 |
| `ReportWriter` 发布失败后 ZIP/stream close 再失败 | cleanup 覆盖 primary，或 fatal 被普通 I/O 异常吞掉 | 发布、原子移动和清理统一合并失败；保留 primary/suppressed，完整 throwable 图中的 fatal 优先传播。 |
| Capability/renderer 只给总结果 | 无法判断是准备、认证、提交还是状态恢复失败 | 每项使用 typed result，`CapabilityReport` 与 `optimizer-renderer.txt` 输出逐能力生命周期和明确 fallback reason。 |
| FBO/MDI/FBP/HZB 只恢复常见 GL 状态 | draw buffers、PBO、固定功能纹理单元、alpha/fog/stencil 等泄漏到原渲染器 | 私有 VAO/资源和完整状态沙箱；状态数量超过可精确捕获上限时拒绝现代候选，生产 HZB 在 `glPopAttrib` 前恢复原 FBO。 |
| LWJGL 多值 `glGet*v` 只按逻辑结果分配 2/4 元素 | 包装器要求至少 16 个剩余元素，FBO 或启动/HUD 状态捕获被隔离，Arena/MDI 永远零命中 | integer/boolean/float 查询均使用互不重叠的 16 元素视图；真实 LWJGL 2.9.4 字节码和容量回归共同约束。 |
| Timer Query 沿用通用 8 ms 自测等待 | 已在 GPU 队列后的 Timestamp 正常异步退休仍被误报 timeout | Timer Query 单独使用 250 ms 有界轮询并 `glFlush`，其他能力仍保留原 8 ms 上限。 |
| ShaderPack 未完成认证或 HUD 返回空提交 | 错误接管自定义顶点/Program，或丢失 HUD 绘制 | Shader program/permutation、顶点格式、状态与输出全部认证后才接管；空提交/提交前安全失败执行原路径，提交不确定时禁止重复重放。 |
| Profiler 栈字典满或窗口只冻结热点 | 字典索引越界，报告线程看到半冻结统计 | 超限样本稳定折叠到有界项；窗口冻结完整统计字典和计数，不只冻结热点视图。 |
| OTG 缓存只看 size/mtime，或每次命中重新认证路径/属性/整文件 SHA-256 | A→B 同元数据、反复读盘/秒级 Tick、旧代结果复活或异常语义变化 | 首次/变更后以 canonical path、file key、size、mtime、SHA-256、目录变更序列与配置代际完整认证；受监视热命中只做逻辑路径查找、排空 WatchService 并比较内存令牌，零 canonical/toRealPath/readAttributes/file-open；稳定缺失/失败负缓存，命中深复制，反射 fatal 传播，发布前同锁再次认证代际；监视不可用时拒绝复用并 fail-open。 |
| 区块卸载保存与计划刻来源未分开 | churn 报告无法定位增量卸载保存风暴 | 加载/卸载/保存来源独立计数，并覆盖增量卸载保存使用的计划刻索引路径。 |

## 12. 无法由本模组完全消除的外部风险

以下内容不应通过不安全的“全异步”或结果删减来伪装成优化：

- 其他模组仍可能在主线程执行未适配的 HTTPS、磁盘读取或 JSON 解析。
- Java 8 中被底层 socket/native 调用忽略的 interrupt 无法强制终止；ICE 只能限制新线程数量、放弃发布旧结果并让 daemon 随 JVM 退出。
- Minecraft 1.12.2 世界生成、实体/方块实体 NBT 构造、Forge 事件和共享 Random 不能安全搬到通用后台线程。
- Recurrent Complex、BO3/BO4 首次真实解析、未命中 Roguelike 设置和真实磁盘缓存未命中仍有不可消除的首次成本。
- 实际 `glTexSubImage2D`、`glBufferData`、`glDrawArrays`、粒子和模型绘制必须执行；ICE 只能减少准备、同步、重复提交和驱动等待。
- 显卡驱动丢失上下文或 JVM 进入 OOM/ThreadDeath 时，正确行为是传播致命错误，而不是继续运行损坏状态。
- 任何优化收益必须以相同存档、路线、视距、JVM 参数和模组配置做 A/B Session；闭环能自动寻找安全工作点，但不能让不同内容负载得到相同 FPS/MSPT。

## 13. 配置原则

- 不提供 CPU/GPU 型号预设表。
- `workerThreads` 只作为通用纯计算池的统一安全上限；`0` 使用 6，不代表推荐某一种 CPU 使用 6。运行时从单 worker 起步，只按实时 busy+queue 积压逐个扩容，空闲自动退役；旧 runtime 超时未退出时，新池直接 fail-open，避免重载叠加线程。
- Heap/Direct/GPU budget 是“最多允许占用”的账本，不会在启动时预分配；用户显式配置仍是硬上限。
- 队列满、预算不足、Fence 忙、结构失配、代际变化或熔断时都应执行原实现，不在主线程排队等待“优化资源”。
- 开关按模块独立；排障时只关闭报告点名的模块，不使用全局硬件 profile。

## 14. 完整验证命令

在工程根目录执行：

```powershell
$env:GRADLE_USER_HOME = (Resolve-Path '.gradle-sandbox-home').Path

.\gradlew.bat compileJava compileHooksJava compileTestJava --no-daemon

.\gradlew.bat test --no-daemon `
  -PotgJar="D:\Program Files\Mcserver\rlcraftDregora\.minecraft\versions\RLCraft Dregora\mods\OpenTerrainGenerator-1.12.2-v9.7.jar" `
  -ProguelikeJar="D:\Program Files\Mcserver\rlcraftDregora\.minecraft\versions\RLCraft Dregora\mods\RoguelikeDungeonsFnarEdition-1.12.2-2.4.6.jar" `
  -PminecraftSrgJar="D:\Program Files\pycharmProject\minecraftMode\ice\.gradle-sandbox-home\caches\forge_gradle\minecraft_user_repo\net\minecraftforge\forge\1.12.2-14.23.5.2860\forge-1.12.2-14.23.5.2860-srg.jar"

.\gradlew.bat verifySplitJars --no-daemon
.\gradlew.bat build --no-daemon
```

最终验证已于 2026-08-21 完成。后续现代渲染零命中和喝药探针修复后的当前真实输入矩阵为 182 个测试类、682 项测试、0 failure、0 error、8 skipped；8 项仅对应当前机器缺失的 Xaero/Better Foliage 运行时样本和不能由 Dregora 新版 JAR 冒充的六项普通 RLCraft 旧版精确基线。真实 OptiFine G5、OTG 9.7、Dregora 专用只读 JAR、Minecraft client/Forge SRG、ASM/JVM define、`verifySplitJars`、三个重混淆任务和 optimizer bundle 全部通过。

连续两次独立 `clean + build/reobf/bundle/verify` 的六个产物逐字节一致。独立审计确认六个归档无重复 entry，四组 optimizer/profiler 与 Main/Core class 交集为 0，21 组早期注入 ABI 只在 optimizer Core，Agrona 308/Caffeine 696/LZ4 103 个重定位 entry 均无原包 entry 或内容引用，bundle 内 Main/Core 哈希等于外部产物。四包客户端部署脚本已在隔离 `mods` 夹具执行空目录安装、同名旧包升级、备份保真与第二次幂等验证；用户批准后，真实 Dregora 已部署新四包，旧四包保存在 `rollback/client-Dregora-before-1.0-20260821-234005394`。

| 产物 | SHA-256 |
|---|---|
| optimizer main | `3E4217D2657560B5472AEF2CCD2B624DBA3BA3A6DE862B5678589057A50F9571` |
| optimizer core | `280DA95BA947D38C0644A3225D155E6525C434A2E3956A70D90DAE62D36E164F` |
| profiler main | `91C21399B7570B124DE79B8ED222ABC2E84C480E9AD49D5BF4E447FC3407A094` |
| profiler core | `DF95108CAC4CD7C1A270F81BB34A594EF5C3C1734AB3B98CE0747283B70A67C5` |
| optimizer bundle | `9EB94A339E5140003A347CBD9AB83377A91217C102FFDE8F12C2FBB039DCF952` |
| reobfuscated combined-dev | `5A6E4257A767E3940DED072C9D5D8A6EFF37588E3FCA87C6C3ACAF75D8A42826` |

这些自动证据不覆盖真实 OpenGL 画面 A/B、ShaderPack 实机图像认证、随机化 ABBA、new-chunk Frame/Tick/GPU P95/P99/1% low 或长时间 Fence/Query/RAM/VRAM soak；上述项目仍属于外部实机验收。
