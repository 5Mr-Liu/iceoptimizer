# ICE 0.10.0 完整实现规格

## 交付边界

ICE 继续交付四个独立 JAR，不合并记录器与优化器：

- `ice-rlcraft-optimizer-0.10.0.jar`
- `ice-rlcraft-optimizer-core-0.10.0.jar`
- `ice-rlcraft-profiler-0.10.0.jar`
- `ice-rlcraft-profiler-core-0.10.0.jar`

优化器负责修改热点实现；记录器保持只读，只负责采样、归因和导出。开发侧完成源码、字节码适配器、自动化测试、分发校验和构建，最终游戏内体验与兼容测试由用户执行。

所有实现必须满足：

- 不预生成世界；
- 不减少、跳过或合并游戏 Tick；
- 不改变实体、方块、Forge 事件、网络包和区块保存的提交顺序；
- 不改变随机数调用顺序；
- 不删减模型、纹理、动画、粒子、动态光、碰撞或 AI 结果；
- 任一优化不兼容、队列已满、结果过期或运行异常时立即执行原实现；
- 整包名称和整 JAR SHA 只用于诊断，不作为兼容开关。

## 运行时架构

### 无锁模块热路径

`OptimizationModule` 使用稳定 ordinal。`OptimizerRegistry` 发布只读的 volatile operational bit mask，注入后的高频调用按位判断模块是否可用，避免字符串查表。

`ModuleCircuitBreaker` 的调用统计使用 `LongAdder`；成功、拒绝等热路径不再进入 synchronized。只有配置、首次激活、失败状态迁移和关闭进入受控状态切换。

### 自适应工作池

客户端和服务端使用独立、有限、可降载的 Worker 运行时：

1. 主线程捕获只读输入和世界/资源/GL 代际；
2. Agrona 有界队列提交纯计算任务；
3. Worker 只写任务私有结果；
4. 结果进入有界提交队列；
5. 原主线程按原序号提交；
6. 代际变化、队列满或模块关闭时丢弃结果并回退原路径。

线程数基于 CPU 数量和实时帧/Tick 压力调节，集成服务器至少为客户端和服务端主线程各保留一个逻辑处理器。后台任务不能无限排队，也不能占用 Minecraft/Forge 公共线程池。

### 内存和性能库

- Agrona：MPSC/MPMC 队列、primitive map、direct buffer 包装；
- Caffeine：有硬上限的跨 Tick/跨帧缓存；
- LZ4：不在当前帧使用的大型冷数据；
- ThreadLocal scratch arena：区块、模型、解析和过滤临时数组；
- Direct/GPU/Heap 三类预算继续独立计算，世界、资源或 GL 代际变化时释放对应缓存。

不增加 JCTools、Disruptor 或另一套缓存库，避免重复实现、包体膨胀和 1.12.2 类加载冲突。

## GPU 和高频绘制

### 动画纹理

通用 `TextureUtil` 单级入口不得为每个 mip 创建 PBO 和 Fence。无法形成批次时直接执行原上传。

0.10.0 不在通用 `TextureUtil` 单级入口建立 PBO；实际 0.9.4 Session 已证明该入口会把每个小纹理变成一次 Fence。只有 FoamFix 已经提供完整 mip 数组的 `uploadTextureMaxMips` 才视为可验证批次：

- 完整 mip 批次至少 256 KiB 才获取一次 PBO 页；
- 同一批次按原调用顺序提交所有纹理级别；
- 一个页只在结束时创建一个 Fence；
- PBO 忙、页容量不足、预算不足或驱动调用异常时，当前批次立即使用原 Direct Buffer 上传；
- 小上传始终执行原 Direct 路径；
- 不调用 `glFinish`，GPU Query 只异步读取。

### 区块 VBO

Worker 只准备顶点字节，渲染线程按原队列顺序上传。相同渲染层的小 VBO 请求可以写入同一暂存页，但 draw/upload 顺序不得变化。槽位忙时使用原版 `glBufferData`。

### 模型

仅缓存经过结构验证的静态顶点、法线和 UV。动画骨骼、矩阵、透明/发光层、纹理绑定及绘制顺序仍逐帧执行。资源重载和模型配置变化立即失效。

## 服务端热点

### SRPMixins 刷怪过滤

替换 `SpawnPotentialsHandler.filterSpawnEntries` 的对象迭代和重复分类：

- 将包装条目编译为保持原顺序的连续数组；
- 预存 parasite ID、原 `SpawnListEntry`、cap 类别和固定 colony 条件；
- 同一次调用中，相同 parasite ID 只读取一次状态；
- colony 总点数和维度 mob cap 每类只读取一次；
- 动态世界状态、配置、玩家数量和最终限制仍每次读取；
- `resetCaches()`、配置重载或源列表身份/代际变化立即失效。

### Lycanites 方块验证

`BlockSpawnLocation.blockIds` 使用可跟踪列表和 membership 索引：

- `loadFromJSON` 后建立索引；
- `add/remove/set/clear` 增加代际；
- 公开字段被其他模组替换时检测新引用；
- 小列表保留线性 contains，大列表使用 primitive/identity 友好的索引；
- 无法证明列表可跟踪时执行原实现。

### SRP 实体范围查询（0.10.0 不启用）

评估后不安装通用缓存。当前整合包中的 RLTweaker、AI Reducer、FoamFix 已分别改写实体容器和范围扫描；继续叠加快照需要覆盖实体加入、移除、跨区块、死亡、乘骑、维度切换及其他 CoreMod 的全部写入口，否则会改变候选顺序或可见集合。没有可证明完整的生命周期 Hook 时保持原查询。

### Capability（0.10.0 不启用）

Forge capability 可在 attach、clone、重生、换世界、实体销毁及第三方 provider/dispatcher 替换时变化。当前采样中的 capability 占比不足以抵消通用生命周期 Hook 的兼容风险，因此不缓存结果；只有未来针对单一 provider、并能验证完整失效入口时才允许增加独立适配器。

## 区块和世界生成

### AO/Forge/OptiFine 调用

每个 `RenderChunk.rebuildChunk` 使用独立 scratch：方块状态、亮度、遮挡属性、AO 数组、BitSet 和 MutableBlockPos。缓存不跨区块任务。

通过结构验证后将 `Reflector.callInt/callBoolean` 替换为 Forge 接口直接虚调用或预链接 MethodHandle，保留覆写、返回值和异常语义。

### Better Caves/OTG

只并行坐标、种子和不可变配置决定的纯噪声列。共享 Random、World 访问、事件、结构放置、TileEntity、生物生成和最终区块提交保持原线程与原顺序。小任务串行，避免提交开销大于计算收益。

## NBT 和区块保存

主线程仍负责从实时 Chunk 创建 NBTTagCompound。完成快照后允许压缩 Worker 并行 Deflate：

- 每个 Worker 复用同参数 Deflater 和缓冲区；
- 压缩结果携带原保存序号；
- FILE_IO 线程按原序号写 RegionFile；
- 世界关闭或代际重置会让已取消任务立即释放 FILE_IO 等待者并执行原压缩，绝不留下排队死锁；
- 队列满、失败或目标结构不兼容时执行原压缩路径。

## 记录器

记录器继续单独发布，并增加：

- 计算、sleep、park/wait、网络、磁盘、GPU 驱动和 GC 分类；
- 世界加载、稳定游戏、保存退出生命周期分段；
- ICE 优化模块包含样本和原生调用归因；
- 持续帧税与单次尖峰分开报告；
- 线程描述符、ID 数组和统计容器复用；
- 深度采样时主线程优先，Worker 分组轮询，限制采样器自身停顿。

`SmoothSync -> Thread.sleep` 必须标为限帧等待；ICE PBO、Fence、队列或 Worker 开销必须归到具体 ICE 模块。

## 字节码和回退

每个替换目标保留原方法，包装入口只选择原实现或优化实现。适配器校验方法描述符、字段数据流、调用数量和控制流。未知结构只关闭当前能力，其余模块继续工作。

高频 Core -> Main 调用使用早期安全 bootstrap 和稳定 delegate，不在每次调用中扫描类名或执行反射查找。

## 本地验证和构建

开发侧执行：

- Java 8 编译；
- 单元测试；
- ASM 结构测试和字节码验证；
- 使用实际 RLCraft/Dregora 目标 JAR 的适配器测试；
- 原实现/优化实现结果一致性测试；
- `test`、分发内容检查和四 JAR 构建；
- 输出完整改动清单、配置项和已知回退条件。

不由开发侧启动或操作用户游戏。最终游戏内 FPS、帧 P95/P99、服务端 Tick、跑图、战斗、动画纹理和保存退出测试由用户执行。

## 0.10.0 实现状态

已完成并进入默认配置：

- ordinal + volatile bit mask 模块门、CAS/LongAdder 熔断器；
- Agrona 有界 MPMC 客户端 Worker 与低分配渲染队列；
- 通用单级纹理 PBO 反优化移除、FoamFix 大批次门与区块 VBO 小上传/Fence 探测保护；
- SRPMixins 刷怪过滤、Lycanites 方块成员索引；
- Konkrete 资源代际反向索引；
- 区块 NBT 并行序列化/Deflate 与原 FILE_IO 顺序写盘；
- OptiFine/Forge 区块光照和 AO 侧面遮挡直调用；
- Profiler 主线程优先轮询、批量 MXBean 统计与新版归因。

明确不进入 0.10.0：

- 通用实体范围查询快照；
- 通用 capability 结果缓存；
- Worker 线程访问实时 World、Entity、TileEntity、Forge 事件或共享 Random；
- 用 GPU 计算会改变浮点、驱动或提交顺序的世界生成、AI、碰撞和 NBT 内容。

这些项目不是“待默认开启的隐藏优化”，而是未满足等价性证明时禁止上线的边界。实机 A/B 测试由用户执行，开发侧不启动或操控游戏。

## 本地构建结果

`2026-08-16` 干净构建执行 176 项测试，0 失败、0 错误、1 项可选运行期样本跳过；重混淆和 `verifySplitJars` 通过。正式产物：

- `ice-rlcraft-optimizer-0.10.0.jar` — `043211F64410E7B995A6A57CDFECEB044FC940C223A91E48B18BBD41FE162DEA`
- `ice-rlcraft-optimizer-core-0.10.0.jar` — `058700EF00467F4BF73876C4F43B7112DF8270497D3143E206315877A369B94C`
- `ice-rlcraft-profiler-0.10.0.jar` — `F47266AF0B8D2643A71021BB9C049EF0FF9772B616BBAE31012D9B5DD73BE817`
- `ice-rlcraft-profiler-core-0.10.0.jar` — `3F9C9D3901A70AB7C3E3FC54CE86ED9D91391F214078E42E7889B50C5EE0030F`
