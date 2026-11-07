# 世界生成、实体 tick 与大 NBT：调查和优化方案

2026-11-06 实施更新见 [NBT 优化记录](NBT-OPTIMIZATION-20261106.md)。实际 JAR 核对发现 QualityTools 玩家 helper 没有整份 ItemStack.copy 路径，本轮改为减少已存在属性的重复解析/对象构造；以下保留原调查时的候选和证据边界。

调查日期：2026-11-05。对象：本仓库 ICE、Forge 1.12.2，以及当前 RLCraft Dregora 实例中的模组和配置。本次只调查并制定方案，没有修改游戏配置、安装包或生产代码。

## 保留的三个渲染方向

1. 透明面重新排序只更新索引，复用未改变的顶点数据。
2. GPU 存储分段分配，减少扩容时复制整个已用缓冲区造成的停顿。
3. 持久保存绘制命令，根据网格、可见性和相机相关变化增量更新。

这些仍是候选，不代表已经实现或经过实机收益验证。旧 ModernRenderer 的带 fence 上传槽不能当作当前 native GL43 路径已有的能力。

## 结论和证据边界

有进一步优化空间，特别是减少反复扫描、对象转成 NBT 再读回、复制未变化的数据，以及无效的全量同步。这些改动有机会同时减少 CPU 工作和临时对象分配；收益取决于当前场景是否频繁进入对应路径。

NBT 的数量、嵌套层数、列表/数组长度、处理频率和数据所属模组，需要一起看。NBT 文本显示很长、磁盘文件很大或 Java 进程占用很高，都不能单独证明 NBT 是当前掉帧主因。压缩后的文件大小也不能代表解码后的对象数量和遍历成本。

世界生成和大部分实体逻辑主要影响服务端 tick。单人游戏中它们与客户端共用 JVM，会通过 CPU 竞争、分配和 GC 等影响帧时间；客户端处理同步、物品模型和界面数据也可能直接变慢。独立服务器上的低 TPS 不必然等于客户端低 FPS，需要同时测量。

现有历史性能记录能说明这些路径值得关注，但不少记录早于后续补丁，不能用旧占比宣布当前瓶颈。此次没有取得新一轮游戏性能录制，也没有确认某个存档正在发生 NBT 无限制增长。

## 已有能力，不计为新增方案

| 范围 | 本地代码或配置已经覆盖的内容 | 仍需区别的部分 |
| --- | --- | --- |
| 装备 NBT | `EquipmentNbtBridge` / `NbtValueComparison` 优化 compound 比较 | 没有全局消除 ItemStack 复制和 Capability 序列化比较 |
| QualityTools | 普通生物的必要字段快照；马铠直接取槽 | `shouldRefresh` 对玩家直接放行，玩家背包/Baubles 分支仍走原处理；马铠另有专门优化 |
| PotionCore | 同一事件内减少重复 NBT 查找 | 不是所有模组的运行时状态都已脱离 NBT |
| 实体查询/寻路 | 已加载区块及 section 查询、候选工作区、寻路工作区、SRP 光源候选和刷怪数据优化 | 复用数组不等于减少每次检查的候选数；不能再次把它包装成新能力 |
| 原版区块保存 | 有界异步 NBT 二进制编码/压缩、压缩器复用及提交协调 | 活实体、方块实体、Capability 构建保存快照仍是另一阶段；不能默认包含 OTG 自己的结构文件 |
| NBT 容器 | `normalasm.cfg` 已启用 compound map 优化、键与字符串规范化、物品 Capability 延迟初始化 | 再叠加同类替换可能冲突，也不能消除高频深复制 |
| AI 和 RC | 已有 AIReducer；RLTweaker 的 RC 数据清理已开启，阈值 100 | 配置开启不等于每个兼容分支都命中；清理过程本身仍可能耗时 |

## 世界生成：从查询和重复准备入手，再评估调度

### 1. 减少随世界变大而增长的查询

OTG BO4 的同类/同组结构距离判定有遍历历史结构记录的路径。按结构类别和空间位置建立索引，先筛选可能影响候选位置的记录，再执行原精确判定。这样减少的是候选数量，不只是把距离计算换个写法。

第一次实现保持现有存档格式，加载后重建索引，生成时同步登记。组间距离规则必须覆盖双方半径，并保留边界舍入。只索引已记录数据，不改变随机数消费和结构尝试顺序。具体证据与设计见 [OTG 调查](OTG-PERFORMANCE-FEASIBILITY-20261105.md)。此项主要适合历史结构记录较多且选址查询占比高的世界。

### 2. 结构模板准备与实际放置分离

把已确认重复的解析、材料解析、旋转和不依赖实时世界的计算，整理为可复用的不可变模板。每次生成只创建必要的可变状态；NBT 若会被 TileEntity 或实体修改，必须拥有独立副本，不能共享可写模板。

当前已有按需加载和缓存，因此先测剩余冷加载与重复准备，不能无条件预读整个预设。模板缓存要有容量边界和文件/配置/依赖变化失效；持久化预编译缓存属于第二阶段。

放置阶段可在一次结构调用中复用当前区块、section 和材料结果，保持原方块写入、回调、光照、通知及随机调用顺序。重入或跨区块时回退。不能通过省掉方块实体初始化或通知获得不等价的结果。

### 3. 单次长任务需要内部改造，队列限额并不能自动解决

把工作拆成请求、基础地形、结构选址、填充、光照和方块实体/NBT 初始化阶段计时，记录级联区块请求的发起者。父子阶段应区分总时间和自身时间，避免将下游模组成本全算到 OTG。

如果一次不可中断的结构填充就耗时数百毫秒，“每 tick 最多生成一个区块”仍会卡。优先降低该任务本身的计算量；只有线程隔离和确定性得到证明，才把纯地形计算、模板解析等移到有界工作队列。真实世界修改和不安全模组回调仍在原所属线程。

跨 tick 分段放置会让其他逻辑看到半成品，也可能改变后续读取和随机序列，因此是更高风险的独立项目。简单给整个 OTG 套线程池、延迟跨区块 setBlock，不能当作安全优化。预生成能提前支付生成成本，但不能解决已生成区域的持续实体/NBT 开销。

### 4. 已核实的 RC 清理 NBT 往返

本机 `RLTweaker-1.12.2-0.7.5.jar` 的 `RecurrentHandler$RCCleanup.wipeRecurrentData` 字节码与公开源码一致：在世界加载事件里调用整个 `WorldSavedData.serializeNBT()`，随后移除 `entries`、`checkedChunks`、`checkedChunksFinal`，再 `readFromNBT`。

候选改动：在严格保持当前清理结果的前提下，避免先构造即将丢弃的数据。例如给对应 RC 数据类型提供专用清理入口，等价重置有关数据及辅助索引，保留其他字段、脏状态和原调用顺序。不能仅清一个 Map 就假设与反序列化完全等价。

周期清理另有复制全部键再逐项删除的路径，可继续检查是否存在安全的批量清理入口。其成本取决于条目数及删除回调；不能仅降低阈值就保证更快。世界加载清理主要影响进世界/加载维度，不能用它解释一直存在的低 FPS。

## 实体 tick：减少同一 tick 内的实际工作

### 1. 专门处理热点查询

当前 ICE 的 nearest 查询已经按已加载 chunk/section 查找，不是每次遍历全世界。下一步是检查大碰撞箱/搜索半径造成的候选膨胀，以及 AI 是否只需要存在性或数量却收集了完整结果。

对经过审计的具体调用点，使用“找到任意一个即返回”“达到数量即停止”或专门的最近目标查询。若范围膨胀显著，再评估维护真实包围盒覆盖的空间索引，同时覆盖移动、变大、跨区块、加入/移除、卸载及多部件实体。

不能全局缩小搜索半径，也不能默认提前返回等价：predicate、包围盒访问和距离回调可能有副作用；同距离目标的选择顺序也必须保留。当前两阶段候选收集就是为保持这些语义。

### 2. 改善玩家装备与饰品处理

本地 `QualityToolsAttributeBridge.shouldRefresh` 对 `EntityPlayer` 返回 true，尚未使用普通生物的跳过刷新路径。建议针对玩家实际依赖的槽位、品质、属性和 Baubles 状态建立专门方案：只有相关输入变化才重算派生属性，避免把整背包或整份物品数据复制作为变化检测。

事件可以标记已知变化，但 1.12.2 的其他模组能直接修改嵌套 NBT。必须覆盖所有相关写入或采用必要字段的轻量核对；不能只监听换装备事件就永久复用结果。还要保留外部属性修改的协调与既有周期复核语义。此处是源码确认的覆盖缺口，尚不是已测定的最大热点。

### 3. AI/寻路只共享能够证明有效的计算

优先移除同一次决策中重复的目标筛选、配置解析、属性派生和方块检查。现有寻路工作区已经复用临时内存，下一步要看是否存在可消除的重复计算。

跨 tick 重用完整路径需要处理目标、地形、门、碰撞和实体能力变化，不是简单加缓存。异步寻路也需要一致输入和应用前验证，验证失败过多反而浪费 CPU。距离远就降低全部实体 tick 频率会改变刷怪、战斗、计时和机器逻辑，应作为明确的玩法折中单独讨论。

事件驱动适合维护索引、脏状态和派生数据；移动、攻击、物理和时间推进仍需要 tick。更现实的目标是让 tick 内只处理必要工作。

## NBT：按产生开销的环节解决

### 1. 高频比较和复制：优先调查

Forge 1.12.x 的 `CapabilityDispatcher.areCompatible` 在需要比较可序列化能力时，对双方调用 `serializeNBT()` 再做 NBT 相等比较。`ItemStack.copy` 的 Forge 补丁也有序列化 Capability 的路径。因此即使没有保存世界，大 NBT 也可能反复生成、复制和遍历；当前整包的实际命中频率还需测量。

方案是沿调用者减少无用比较和复制，给已审计的 Capability 提供与其序列化语义等价的轻量比较/快照。若使用版本号，必须由数据拥有者完整维护，未知类型回退原逻辑。不能把所有 Capability 换成对象引用比较，也不能让不同物品共享可变数据。

全局给 NBT 根对象缓存 hash 或“本 tick 已比较”并不可靠：子 compound、list、数组可以原地改变；同一 tick 也可能改变多次。每次重新求整棵树 hash 又会保留遍历成本。

### 2. 重复读取和运行时转换

对热点模组，将经常使用的派生值保留为普通运行时字段，仅在相关状态变化时重算，在保存/同步边界才转换成 NBT。需要逐个数据拥有者适配，不能把整个模组包所有 NBT 无条件缓存。

客户端若存在模型选择、属性展示或 tooltip 每帧反复解析复杂 NBT，可缓存经过确认的解析结果，并以相关字段变化失效。要包含动态语言、按键、玩家状态等真实依赖；仅凭物品引用相同不能认定显示内容未变。该项目前是待测路径，不是已经确认存在的热区。

### 3. NBT 过多或持续增长：修生产者

如果某个列表每 tick 追加相同记录、把可重建缓存反复写入、存放过期 UUID 或重复结构记录，应修复写入逻辑并明确保留规则；经确认无用的数据才做对应版本的迁移或清理。

需要区分字符串长、条目多、嵌套深和大数组：它们对比较、分配、解析的影响不同。NBT 大小限制的放宽不是性能优化，任意截断列表或删除 `ForgeCaps`、附魔和结构记录会改变数据语义。

### 4. 网络同步：区分完整存档与客户端所需状态

对热点 TileEntity 或自定义数据包，完整 NBT 用于保存；客户端首次进入跟踪范围时收到必要的初始状态，此后只在变化时发送必要字段或合并后的更新。无 GUI 的客户端不一定需要整个服务端库存。接收端仍要处理对象已卸载/替换和状态版本。

Forge 1.12 的 Capability 数据不是统一自动同步；具体模组自己决定同步。物品另有 `getNBTShareTag` / `readNBTShareTag` 路径。必须审查实际网络发送者和客户端用途，不能全局裁剪物品标签或假设全部 ForgeCaps 都在网络上传输。协议变化需要两端对应实现。

### 5. 保存和加载：分清快照与压缩

原版区块的二进制编码/压缩已由 ICE 优化；仍应测量实体、TileEntity、Capability 创建 NBT 快照，以及 OTG 自有文件的保存。压缩线程更多，并不能自动降低快照构建时间。

优先消除重复序列化和重复保存相同状态。对能完整追踪修改的数据拥有者，可复用具备独立所有权的不可变保存快照；未知模组继续实时构建。不能在后台直接遍历活实体或共享可变 NBT。

如自动保存仍有多区块集中快照尖峰，可评估在完整区块之间分摊调度，保留保存代次、脏状态和强制保存/退出屏障。调度变更需要单独验证，不能把一个区块中不同时刻的实体状态随意拼在一起。单个超大区块的快照仍可能阻塞，需要继续优化其中的数据生产者。

## 实施优先级和验收

先用同一次录制把客户端长帧、服务端长 tick、GC、区块生成和 NBT 处理对齐。探针按模组/数据类型汇总调用次数、耗时、分配、节点数/深度和编码字节数；利用已有编码过程计数或有界抽样，不能为了统计每 tick 多序列化一遍整棵树，更不能全量打印 NBT。

| 复现场景 | 优先原型 | 主要收益指标 |
| --- | --- | --- |
| 已生成区域，装备/饰品丰富，持续卡顿 | 玩家 QualityTools/Baubles 路径、热点 ItemStack/Capability 比较与复制 | CPU/分配下降，实体 tick 与帧时间长尾改善 |
| 怪物多或个别大实体出现时卡顿 | 已确认的 AI 查询候选压缩、存在性查询、重复计算消除 | 查询次数与候选数、寻路/AI 耗时 |
| 探索新地形卡顿 | BO4 空间索引、结构模板准备与放置阶段热点 | 生成/填充最大停顿、P99、区块吞吐 |
| 进世界/加载维度卡顿 | RC 清理避免完整 NBT 往返，其他实际加载热点 | 加载耗时和分配峰值 |
| 开容器/看物品/区块同步时掉帧 | 实际 NBT 发送量、客户端应用和派生数据计算 | 每秒字节数、处理耗时、长帧 |
| 保存时卡顿，越玩数据越大 | NBT 增长生产者、快照阶段与重复保存 | 保存停顿、NBT 增长率、分配/GC |

性能比较使用同一存档副本、固定种子/路线/实体配置/物品 NBT，保持视距、JVM、补丁和资源包一致。报告帧时间 P95/P99、长帧次数、MSPT、区块吞吐、分配/GC；不能用降低每 tick 工作量但队列不断积压的结果宣布成功。

正确性验证包括生成结果与重进一致、目标选择和回调顺序一致、装备属性与 Baubles 行为一致、嵌套 NBT 原地修改能被检测、复制后互不污染、保存重载及网络客户端状态正确。此次没有改实现，因此未运行新的性能测试，不能承诺百分比提升。

## 来源和本地入口

- [Forge 1.12.x CapabilityDispatcher](https://raw.githubusercontent.com/MinecraftForge/MinecraftForge/1.12.x/src/main/java/net/minecraftforge/common/capabilities/CapabilityDispatcher.java)：序列化比较。
- [Forge 1.12.x ItemStack 补丁](https://raw.githubusercontent.com/MinecraftForge/MinecraftForge/1.12.x/patches/minecraft/net/minecraft/item/ItemStack.java.patch)：复制、比较与保存的 Capability 路径。
- [Forge 1.12 TileEntity 文档](https://docs.minecraftforge.net/en/1.12.x/tileentities/tileentity/)：tick、持久化和按需同步。
- [Forge 1.12 Capability 文档](https://docs.minecraftforge.net/en/1.12.x/datastorage/capabilities/)：脏标记及由模组负责的同步。
- [Forge 1.12.x PacketBuffer 补丁](https://raw.githubusercontent.com/MinecraftForge/MinecraftForge/1.12.x/patches/minecraft/net/minecraft/network/PacketBuffer.java.patch)：物品 share tag 网络入口。
- [RLTweaker RecurrentHandler](https://raw.githubusercontent.com/Meldexun/RLTweaker/master/src/main/java/com/charles445/rltweaker/handler/RecurrentHandler.java)：清理源码；本次另用 javap 核对本机 0.7.5 的相应字节码。
- [AIReducer 作者仓库](https://github.com/Charles445/AIReducer)：现有 AI 兼容优化。
- [OTG 专项调查](OTG-PERFORMANCE-FEASIBILITY-20261105.md)：对应 v9.7 源码、预设、整包补丁及候选算法。
- `src/main/java/dev/rlcraft/ice/optimizer/compat/entity/QualityToolsAttributeBridge.java`
- `src/main/java/dev/rlcraft/ice/optimizer/compat/entity/NearestEntityQueryBridge.java`
- `src/main/java/dev/rlcraft/ice/optimizer/compat/nbt/EquipmentNbtBridge.java`
- `src/main/java/dev/rlcraft/ice/optimizer/compat/nbt/NbtValueComparison.java`
- `src/main/java/dev/rlcraft/ice/optimizer/compat/save/ChunkSaveCompressionBridge.java`
- `docs/ICE-1.0.23-ENTITY-NBT.txt`：已有实现，避免将早期计划误认成尚未做的优化。

本地实例配置：`D:/Program Files/Mcserver/rlcraftDregora/.minecraft/versions/RLCraft Dregora/config/normalasm.cfg` 与 `rltweaker.cfg`。
