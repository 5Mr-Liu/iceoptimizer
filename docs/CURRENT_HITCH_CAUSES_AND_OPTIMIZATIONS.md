# RLCraft 当前卡顿原因与 ICE 优化对应表

更新时间：2026-08-16
对应版本：ICE RLCraft Optimizer `0.10.0`

## 1. 统计结论

本文基于目前收集的普通 RLCraft、联机服务器和 RLCraft Dregora Session，以及 ICE `0.10.0` 的实际目标目录整理。

- 已确认 5 大类卡顿。
- 当前优化器包含 47 个独立模块 ID、66 个唯一目标类、68 个目标能力。
- 另有 9 类问题仍是部分缓解或尚未处理。
- Session 中的 folded stack 数值表示热点出现频率。不同模式可以命中同一条调用栈，因此不能相加为百分比或直接等同于 CPU 时间。
- 最新 0.9.4 反优化证据来自 Dregora `20260816-213345-630` 与 `20260816-213429-790`；其中动画纹理链出现数百次 `glFenceSync`，区块保存仍包含同步 NBT/Deflate，Konkrete 与 OptiFine Reflector 也进入主线程样本。0.10.0 针对这些实际路径修正。

## 2. 最新记录热点分布

| 热点 | 加权样本 | 当前状态 |
| --- | ---: | --- |
| 区块读取、保存、NBT 序列化 | 3255 | 已处理计划刻重复扫描，并将完成快照后的 NBT 序列化/Deflate 放入有界 Worker；快照构造仍保持主线程 |
| 区块重建和渲染 | 2538 | 部分处理 |
| FoamFix 纹理上传 | 1876 | 已移除通用单级 PBO 反优化；仅完整大 mip 批次尝试 PBO |
| Better Foliage | 1513 | 已优化 |
| 实际 OpenGL 纹理上传 | 1277 | 小纹理和 GPU 落后时保留原路径；区块 VBO 仅大上传尝试 staging |
| Better Caves | 1194 | 已优化 |
| 地形 VBO/绘制调用 | 1161 | 部分处理 |
| Lycanites | 1080 | 多条热路径已优化 |
| Dynamic Trees | 907 | 已优化 |
| Quark 掉落物同步 | 818 | 已优化 |
| Ice and Fire | 789 | 部分热路径已优化 |
| RenderLib | 714 | 已优化 |
| OreLib / Dynamic Surroundings | 646 | 已优化 |
| Fancy Block Particles | 611 | 未处理 |
| RLTweaker 范围实体查询 | 359 | 未处理 |
| ItemPhysic | 298 | 未处理 |
| Mo' Bends | 180 | 多条热路径已优化 |
| Quality Tools | 89 | 已优化 |
| SRP | 28 | 战斗高实体数量时会放大，已优化核心路径 |

## 3. 跑图、地形与区块加载

| 原因 | 为什么卡 | ICE 的处理 |
| --- | --- | --- |
| Better Caves 噪声 | `ArrayList<Double>`、Double 装箱和链式插值产生大量临时对象 | 改为 `double[]`、连续列数组和一次 blend |
| Better Caves 重复角点计算 | 相邻插值重复生成完全相同的噪声列 | 使用 64 槽完整坐标/高度缓存，命中返回深复制 |
| Better Caves 洞穴阈值 | 每列创建 `HashMap<Integer, Float>` | 改为连续 float 阈值 Map，保持逐 bit 相同 |
| OTG BO4 重复写盘 | 已经成功读取的 BO4 又被完整写回源文件 | 跳过冗余 `WriteWithoutComments`，不改变解析结果 |
| OTG 重复方块数组 | 同一次生成连续调用两次 `getBlocks()` | 只在当前生成调用内复用第一份数组 |
| OTG 列偏移扫描 | 每个方块都从 `(0,0)` 扫描到目标列 | 改为一次生成 256 项前缀表 |
| OTG 配置解析 | `toCharArray`、LinkedList、数组和 lowercase 分配频繁 | 使用低分配解析器和有界名称缓存 |
| 大量区块同时初始化 | 进世界或高速跑图时短时间加载数百区块 | 通过 Better Caves、OTG 和区块网格优化降低单区块成本，但不减少区块数量 |
| 全量保存计划刻 NBT | 每个脏区块都从头扫描 WorldServer 全局计划刻 `TreeSet` | 一次同步全量保存内按变更版本建立临时只读索引，条目与保存顺序不变 |
| 实体/方块实体 NBT | 主线程同步构造大量 Compound/List 和模组 Capability 数据 | 保留快照构造；完整快照生成后才异步序列化/Deflate，FILE_IO 按原顺序写盘 |
| 方块状态反序列化 | `BlockStateContainer → GameData → IdentityHashMap` 在主线程执行 | 当前尚未直接优化 |

### 3.1 Better Caves

记录中的核心路径包括：

```text
MapGenBetterCaves
→ CaveCarverController.carveChunk
→ CaveCarver.carveColumn
```

以及：

```text
NoiseGen.interpolateNoiseCube
→ NoiseTuple.times
→ NoiseTuple.plus
```

ICE 使用四个结构适配器：

1. `NoiseTuple` 的热存储改为 primitive `double[]`，同时保留公开 `List<Double>` 实时兼容视图。
2. `NoiseColumn` 的正常 0–255 高度使用连续数组，公开 Map 仅在需要时延迟物化。
3. `NoiseGen` 对重复角点列使用 64 槽直接映射缓存，命中必须比较完整位置和高度范围。
4. `CaveCarver` 使用连续阈值 Map，float 计算结果逐 bit 保持。

普通 RLCraft 的新记录还暴露了 ICE 旧模块门本身的开销：Better Caves 每次构造、复制和融合都会把字符串模块 ID 交给线性枚举扫描。`0.9.3` 将规范 ID 改为 O(1) 表查询，并让 Better Caves 四个适配器共享缓存的熔断器引用；实时开关与失效开放语义保留，但热循环不再执行 `OptimizationModule.values()` 或 `String.equalsIgnoreCase`。

Tuple、Column 任一结构 ABI 未成功安装时，NoiseGen 自动执行完整原方法，不会因为部分转换造成链接错误。

### 3.2 OTG / BO4

ICE 不扫描或预加载整个 Dregora 预设，不生成 `.BO4Data`，也不跨结构生成复用会被随机方块逻辑修改的对象。

- 文件、结构方块顺序不变。
- RNG 调用次数和顺序不变。
- 世界写入顺序不变。
- 只消除冗余写盘、重复对象数组、重复列扫描和解析分配。

### 3.3 原版区块 I/O

`20260816-124855-915` 在 12:49:11 记录到约 520 ms 服务端 Tick。相关主线程样本主要位于：

```text
MinecraftServer.saveAllWorlds
→ WorldServer.saveAllChunks
→ ChunkProviderServer.saveChunks(true)
→ AnvilChunkLoader.writeChunkToNBT
→ WorldServer.getPendingBlockUpdates
```

这次约 484 ms CPU 和 92 MiB 分配主要发生在服务端主线程同步构造 NBT，而不是等待磁盘。ICE 只消除同一次全量保存中对未变化计划刻集合的重复扫描；每次计划刻增删都会递增版本，后续区块查询先重建索引。`SelectedSelectionKeySetSelector.select` 和 `ThreadedFileIOBase.sleep` 是旁路线程空闲，不再参与主根因投票。

0.10.0 进一步只移动“快照完成之后”的纯工作：主线程仍执行所有 Chunk、实体、方块实体、Capability 和事件序列化逻辑并生成同一 `NBTTagCompound`；有界 Worker 对该不可变快照执行二进制写出和 zlib Deflate，FILE_IO 线程按原 pending Map 顺序写 RegionFile。队列满、结果过大、关闭取消、世界代际变化或目标结构不兼容时使用原压缩流。

## 4. 客户端区块重建

| 原因 | ICE 的处理 |
| --- | --- |
| Better Foliage 每个面分配 `float[12]` 和 `BitSet` | 每个 Chunk Worker 的六面 AO 对象复用私有暂存区 |
| Better Foliage 每个方块反射查找 OptiFine `ofCustomColors` | 使用 `ClassValue`，每个运行时类只解析一次字段 |
| Dynamic Trees 每个可见面重复读取六个连接属性 | 同一模型和不可变状态的六面查询复用 `int[6]` |
| Chunk Worker 大量对象分配导致 GC | 上述数组、Map、Double 和反射优化共同降低分配 |
| 主线程上传区块 VBO | ICE 有有界提交设施，但原版实际 `glBufferData` 仍必须执行 |
| 实际地形 `glDrawArrays` | 不减少可见方块或绘制内容，只降低绘制前 CPU 开销 |

Better Foliage + OptiFine 的原始热点为：

```text
RenderChunk.rebuildChunk
→ Better Foliage RenderLeaves.render
→ OptifineCustomColors.getBlockColor
→ Reflection.reflectField
→ Class.getDeclaredField
```

`0.8.0` 将逐方块字段查找改为按运行时类缓存。

## 5. 纹理、GPU 与渲染状态

| 原因 | ICE 的处理 |
| --- | --- |
| 通用纹理 PBO Fence 风暴 | 单级 `TextureUtil` PBO 固定关闭，执行原上传 |
| FoamFix 大 mip 批次 | 仅总量不少于 256 KiB 时使用有硬预算的三槽 PBO |
| 区块 VBO staging 固定成本 | 小于 256 KiB 走原 `glBufferData`，每次最多探测两个 Fence 槽 |
| Xaero 初始化执行约 2560 次 `glFinish` | 改为 32 对异步 GPU Timestamp Query |
| OreLib 每次读取 16 个驱动 GL 状态 | 稳态降为 3 个真实查询，其余读取 `GlStateManager` 缓存 |
| RenderLib 方块实体合并为 `pending × loaded` 扫描 | 大列表使用 Agrona 成员表，使查找接近线性 |
| 实际 `glTexSubImage2D`、`glDrawArrays` 和交换缓冲 | 只能减少调用前准备与同步，不能取消真正 GPU 工作 |
| 云、粒子和 FBP 大量绘制 | FBP 与云渲染尚未针对性优化 |

### 5.1 FoamFix

最新记录中 FoamFix 的主要栈为：

```text
FastTextureAtlasSprite.uploadTextureMaxMips
→ TextureUtil
→ glTexSubImage2D
```

0.9.4 的实际 Session 显示通用单级桥把小纹理变成数百次 `glFenceSync`，在部分驱动上反而成为主要渲染税。0.10.0 因此让单级入口始终返回原路径；只有 FoamFix 已聚合的完整 mip 批次达到 256 KiB 才尝试 PBO，显卡不支持、槽位忙或预算不足时执行未修改的 FoamFix 方法。

### 5.2 Xaero

Xaero World Map 原初始化基准通过大量 `glFinish` 强制 CPU 等待 GPU。ICE 使用非阻塞 Timestamp Query，只在驱动报告结果可用后读取。

### 5.3 OreLib / Dynamic Surroundings

原 `OpenGlState` 快照每次同步读取 16 个驱动状态。ICE 稳态读取 13 项 Minecraft Java 状态，仅保留三个真实驱动哨兵，并在首次和每 32 次快照执行完整真值校验。任何不一致都会让本模块回退原 16 次查询。

## 6. 怪物、实体与战斗

### 6.1 SRP

已确认问题：

- 复杂寄生虫模型拥有深层 `ModelRenderer` 子树和大量 `glCallList`。
- 寻路重复读取同一坐标的节点/方块分类。
- 目标 AI 完整排序候选列表后只读取第一个。

ICE 的处理：

- 13 个热点模型使用自适应稳定分支批处理。
- 每帧重新验证姿态、显隐、子节点顺序、原 Display List 和 scale。
- 寻路缓存只存在于一次 `PathFinder` 生命周期，不跨 Tick 或实体。
- 目标选择改为稳定线性最小值，相等候选保持原列表先后。

当前配置中的 `srpPoseCache` 和 `srpParticleCollision` 是保留项，没有对应的目标目录适配器，不计入已完成优化。

### 6.2 Lycanites

已优化路径：

- 寻路中相同坐标方块状态的重复读取。
- `ObjectManager.containsKey + get` 双 Map 探测。
- `BlockSpawnLocation.getSpawnPositions` 中无条件 HashMap、装箱计数及普通方块的重复只读状态读取。
- OBJ/VBO 稳定网格的重复提交。
- Animator 恒等 GL 调用。
- `ModelObjPart` Iterator 分配。
- 动画帧字符串分派和模型名称 lowercase。
- 35 次固定药水效果查询和重复 Predicate 创建。

不缓存实体列表、完整路径或事件结果，不降低 AI、事件或效果检查频率。

### 6.3 Mo' Bends

ICE 已处理：

- 模型部件重复遍历父链。
- 子模型 Iterator 分配。
- Quaternion 重复生成 16-float 矩阵。
- 未攀爬实体无意义读取三个方块状态。

尚未处理的 Mo' Bends 热点包括部分实体范围查询、落地/碰撞扫描和高层动画控制器逻辑。

### 6.4 Ice and Fire

ICE 已处理：

- 姿态插值中同一部件重复五次 `getCube`，收敛为两个局部查询。
- 海蛇粒子路径重复创建零长度或 `{0}` 数组，改为等价复用。

尚未处理：

- LLibrary capability/property 的重复查询。
- 部分实体 AI 范围扫描。
- 大型模型实际绘制和发光 Layer 绘制。

### 6.5 Quality Tools

原逻辑会让大量普通生物每七 Tick 拆除并重新安装全部属性修饰，即使装备完全没有改变：

```text
CommonEventHandler.onLivingUpdate
→ ModifiableAttributeInstance
→ remove/apply modifier collections
```

ICE 保存六个装备槽的弱快照：

- 首次处理执行原逻辑。
- 任意物品、数量或 NBT 变化时执行原逻辑。
- 装备不变时跳过属性拆装。
- 每 140 Tick 强制执行一次原检查。
- 玩家与马始终保持原调用频率。

### 6.6 Quark

原 `ItemsFlashBeforeExpiring` 使用两个 `WeakHashMap<EntityItem, Integer>` 检查 age 与 lifespan。实体多、掉落物多时产生大量弱表查询和 Integer 装箱。

ICE 合并为单个：

```text
WeakHashMap<EntityItem, State>
```

正常 age 增长只更新 primitive int；age 跳变或 lifespan 变化时仍返回同步。原 Quark 在异常 lifespan 后持续同步的状态机保持不变。

### 6.7 ItemPhysic

最新记录中存在：

```text
EntityItem.tick
→ ServerPhysic.updatePost
→ SortingList.canPass
→ InfoStack.isInstanceIgnoreSize
```

当前 ICE 尚未为 ItemPhysic 的规则表匹配、掉落物物理更新或大量掉落物合并编写适配器。

### 6.8 RLTweaker 范围实体查询

多个模组通过 RLTweaker 的 `WorldRadiusUtil` 执行范围实体查询，在怪物密集、碰撞范围大时容易放大：

```text
HookWorld.getEntities...
→ WorldRadiusUtil.getEntities...
```

当前尚未直接优化。后续必须确认查询结果顺序、实体筛选和碰撞语义后才能安全处理。

## 7. 联网与线程阻塞

当前存在两个性质不同的联网问题。

### 7.1 玩家头颅 Authlib：已处理

`LayerCustomHead` 不再在渲染线程同步联网：

- 使用一个低优先级 daemon worker。
- 使用有界正缓存、负缓存、队列和 in-flight 去重。
- 未完成时当帧继续使用原资料或默认皮肤。
- 失败不会回到渲染线程同步联网。

### 7.2 Trinkets VIP 配置：尚未处理

最严重事件位于早期服务器记录：

```text
Trinkets.serverStarting
→ VIPHandler.loadJsonFromUrl
→ HTTPS
→ SocketInputStream.socketRead0
```

该路径曾造成客户端约 44 秒冻结。客户端采样中的 `Unsafe.park` 是在等待被同步联网堵住的集成服务器，真正根因是服务端启动线程进行阻塞 HTTPS 读取。

当前 optimizer 尚未修改 Trinkets。后续安全方案应为：

1. 本地缓存最后一次成功数据。
2. 启动时立即使用本地缓存或空结果。
3. 后台有界线程刷新。
4. 设置严格连接/读取超时。
5. 下载失败不得阻塞服务端主线程。

## 8. GC 与内存分配

记录中出现过：

- GC 暂停 50–113ms。
- 最严重启动事件 GC 暂停约 3.431 秒。
- 部分长会话目标线程累计分配达到数 GiB 至十几 GiB。

ICE 没有替换 JVM GC，而是从源头减少：

- Double/Integer 装箱。
- 临时数组、BitSet、HashMap 和 Iterator。
- 反射对象与字符串 lowercase。
- 重复模型矩阵和方块连接数组。
- 重复 OTG 方块函数对象。

因此 GC 属于整体缓解，并不会完全消失。仍需要通过相同路线的优化前后 Session 比较 GC 次数、暂停总量、最大暂停和每秒分配量。

## 9. 不能直接视为根因的栈

以下调用经常只是线程空闲或采样容量标记：

| 调用 | 实际含义 |
| --- | --- |
| `Unsafe.park` | 工作线程等待任务、锁或主线程条件；必须继续向上寻找真正阻塞者 |
| `Thread.sleep` | 帧率限制、服务器节拍等待或后台线程休眠 |
| `SelectedSelectionKeySetSelector.select` / Windows selector `poll0` | Netty 事件线程等待网络事件 |
| `ThreadedFileIOBase + Thread.sleep` | 原版文件 I/O 队列当前没有待写任务 |
| `ice.profiler.dictionaryOverflow` | Profiler 唯一调用栈字典达到上限，不是游戏代码热点 |

只有游戏主线程明确在等待这些线程、锁或网络结果时，它们才是实际冻结链的一部分。

## 10. 当前仍需优化的项目

按照收益和风险排序：

1. 区块内实体、方块实体、Capability 自身的 NBT 构造与异常大数据定位。
2. 大批量区块载入和方块状态反序列化。
3. Trinkets VIP 同步 HTTPS。
4. Fancy Block Particles 的立方体粒子绘制。
5. ItemPhysic 掉落物规则表和大量掉落物更新。
6. RLTweaker 大范围实体查询。
7. Ice and Fire / LLibrary capability 与剩余 AI 查询。
8. 真正的地形、实体、方块实体和粒子 GPU 绘制量。
9. 全局 GC、堆配置、显存和显卡驱动瓶颈。

## 11. 当前优化边界

ICE 不通过以下方式换取性能：

- 不删除内容。
- 不减少粒子数量。
- 不降低模型质量。
- 不跳过实体或方块实体 Tick。
- 不改变掉落、AI、生成或世界写入结果。
- 不异步执行 Minecraft 1.12.2 世界生成。
- 不改变随机数调用次数和顺序。

目标类的已知 SHA-256 只用于审计，不作为运行白名单。每个适配器按照字段、方法描述符和调用图验证结构；结构不兼容时只保留该目标的原字节码，不影响其他目标或游戏启动。

## 12. 后续验证方式

安装 `0.10.0` 的 optimizer Main/Core 后，可按相同存档、路线、视距和 JVM 参数重新记录；需要取证时再独立安装 profiler Main/Core。重点比较：

- 客户端帧 P95、P99 和最大值。
- 服务端 MSPT P95、P99 和最大值。
- 区块生成和区块重建队列峰值。
- 每秒分配量。
- GC 次数、暂停总量和最大暂停。
- `glFenceSync`、FoamFix/TextureUtil、NBT/Deflater、Konkrete 和 Reflector 栈样本数量。
- 卡顿触发次数和持续时间。

只有完成同条件复测，才能确定各优化在实际机器上的最终收益。
