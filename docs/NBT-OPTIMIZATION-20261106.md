# NBT 处理优化：nbt-20261106

日期：2026-11-06。版本：ICE 1.20.2，本地候选构建标识 `nbt-20261106`。本轮针对实际代码中的重复处理，保留完整数据和存档格式；没有确认或修复某个具体存档的数据无限增长问题。

## 实现范围

### Capability NBT 比较

`CapabilityNbtAdapter` 只替换 Forge `CapabilityDispatcher.areCompatible` 中两个序列化结果之间的比较。所有左侧 provider 先序列化、再序列化右侧 provider，次数、顺序、异常以及动态增删 provider 的行为不变。

新 `CapabilityNbtBridge` 仅在双方都是精确的原版 `NBTTagCompound`、已经安装 NBT 访问接口，且双方 backing map 都是精确 `NormalTagMap` 时启用已有的直接遍历比较。普通 HashMap、未知容器、子类和关闭模块时调用原 `equals`。限定容器是性能测量的结果：普通 HashMap 的新遍历路径在探针中明显更慢。

比较每次读取当前数据，没有跨 tick 缓存、根引用相等缓存或 hash 缓存。嵌套 compound、list 和数组原地修改仍然可见。已有装备 NBT 比较算法没有扩大或重写。

### QualityTools 已有属性保留

实际 QualityTools 1.0.7 的玩家 helper 没有整份 `ItemStack.copy` 热点。本轮处理它在每次协调属性时构造 UUID 和 AttributeModifier、随后发现属性已存在又丢弃的路径。

在原 `applyAttributesForSlot` 的属性循环里插入小范围快路径，因此装备、背包和 Baubles 调用都能使用。仅在标准 list/compound、标准 HashMultimap、字段有效且待处理修饰器总数不超过 4 时，用 UUID 的两个 long 查找并保留已有实例。其他情况继续执行原方法。

每 7 tick 的属性协调仍执行。没有跨 tick 缓存，不共享新增属性对象；其他模组移除属性后仍会由原逻辑补回。同 UUID 但 Amount/Operation 改变时保留原 QualityTools 的处理规则，不擅自修正其语义。非法字段、自定义 NBT/list/属性类继续走原解析、回调和日志路径。

适配器精确校验将被跳过的指令、调用和分支目标；未知修改拒绝接管。候选数量上限避免把原先的哈希查询变成随修饰器数量增长的扫描。

### Recurrent Complex 世界加载清理

RLTweaker 0.7.5 原 `wipeRecurrentData` 先序列化 RC 数据，再移除 `entries`、`checkedChunks`、`checkedChunksFinal`，最后执行原 `readFromNBT`。

新适配器从经过严格匹配的 RC writer 中保留 `customEntries` 的原始序列化语句，省掉即将删除的三组标签的构建。原 removeTag、readFromNBT、辅助索引重建、锁、脏状态及 setData 流程保留。正常保存和周期 removeEntry 清理不经过此快路径。

运行时还检查实际数据类型、集合、条目、包围盒和变换类型；未知或无效数据回退原序列化。它主要减少进世界或加载维度时的无效分配，不能当作持续 FPS 优化。

原 StructureEntry writer 对 instanceData 使用引用，因此这项改动省掉的是结构条目的 NBT 包装、列表和坐标数组构建，不能表述为省掉整份 instanceData 深复制。

## 配置与兼容边界

- Capability 比较沿用 `vanillaEquipmentNbt` 开关。
- QualityTools 沿用 `qualityToolsEntityAttributes` 开关。
- RC 清理新增 `recurrentCleanupNbt=true`，支持客户端集成服务器和独立服务器。
- 模块暂停或禁用时回退原路径；原来的三个渲染候选仍保存在前次方案中，本轮没有实现它们。

校验过的可选模组为 QualityTools 1.0.7、RecurrentComplex 1.4.8.6、RLTweaker 0.7.5、IvToolkit 1.3.3。新目录项包含对应模组原始 class 指纹；适配器另外检查实际收到的字节码形状。

## 验证方法

使用 `tools/build-nbt-candidate.ps1` 完成编译、测试、单 JAR 打包和最终制品启动检查，向可选测试传入本机真实模组 JAR 和 Forge SRG JAR。日志及测试证据保存在 `build/nbt-20261106/`。

新增回归执行实际安装模组的 helper/writer，验证：

- Capability 序列化调用顺序、提前发现不同仍执行全部原调用、异常、动态 provider 变化及嵌套数据修改。
- QualityTools 的饰品槽位、同 UUID 属性、外部删除后的补回、非法输入和自定义 NBT/list 回调。
- RC 清理前后数据及读回索引一致，自定义条目保留，修改即时可见，关闭开关恢复正常序列化。
- 实际字节码变换后的栈校验，以及未知指令/分支/清理合同的拒绝处理。

RC 夹具中，原路径对两个即将丢弃的结构条目调用 writer 两次；快路径调用零次。自定义条目在两条路径中都调用一次，读回结果一致。

`tools/run-capability-nbt-probe.ps1` 在独立 JVM 中安装生产 NBT/Capability 适配器；NormalASM 模式使用实际 `NormalTagMap(5, true, true)`，包含新建 compound 的构造链。分别测纯比较、provider 返回现有标签、provider 每次构造独立快照，交错开关并核对序列化次数。它不是 Minecraft 整局测试。

QualityTools 的局部分配/时间测量使用同一对实际 helper，四个场景分别交错预热并取七轮中位数。时间波动不会作为测试失败条件；分配测量只用于确认命中路径确实少建对象，不能换算为游戏 FPS。

最终 `build optimizerBundleZip verifyArtifactStartup` 通过：常规测试 1,373 项，其中 1,275 通过、98 项可选跳过，零失败；另 10 项最终制品启动检查全部通过。新增的 29 项回归均执行通过。结果快照为 `build/nbt-20261106/test-summary.json` 和 `validation/`。

QualityTools 实际 helper 的七轮中位数如下，单位是每次调用，包含测试驱动的共同开销：

| 场景 | 原分配 → 候选分配 | 原耗时 → 候选耗时 |
| --- | --- | --- |
| 已有属性稳定命中 | 304 → 136 B | 244 → 197 ns |
| 4 个候选 | 304 → 136 B | 217 → 162 ns |
| 32 个其他修饰器，回退原查询 | 304 → 304 B | 219 → 217 ns |
| 真正新增属性 | 1,072 → 1,072 B | 455 → 454 ns |

最终 Capability 候选在两个独立 JVM 中测得下列范围；每个 JVM 内使用交错采样中位数。这里测的是完整 `areCompatible` 调用，包括原有 provider 序列化。

| NormalTagMap 输入 | 原耗时 → 候选耗时 | 原分配 → 候选分配 |
| --- | --- | --- |
| 1 个 provider、32 个子项、每子项 4 字段，返回现有 tag | 3.45–3.89 → 2.35–2.51 μs | 6,000 → 5,136 B |
| 同上，但 provider 每次生成独立快照 | 15.48–15.94 → 13.00–14.54 μs | 35,728 → 34,864 B |
| 4 个 provider、各 32 个子项、每子项 6 字段，返回现有 tag | 19.74–20.62 → 12.63–13.81 μs | 28,360 → 19,520 B |
| 同上，但 provider 每次生成独立快照 | 115.86–115.96 → 108.51–110.43 μs | 235,336 → 226,496 B |

小 NBT 有明确取舍：4 个 provider 各含一个 4 字段子项时，返回现有 tag 的耗时从 0.60–0.62 降到 0.39–0.41 μs，但分配从 656 增至 1,088 B；使用独立快照时分配从 4,944 增至 5,376 B。不能宣称本路径对所有输入都减少分配。普通 HashMap 的最终候选恢复原比较，探针分配量与基线一致；微小耗时差仍有运行波动。

记录在 `build/nbt-20261106/capability-probe/normal-{1,2}.log` 与 `vanilla-{1,2}.log`。未限定容器的初始实验记录保存在 `capability-ungated/`，不作为最终实现成绩。

## 未交付的通用深复制实验

尝试过把 compound.copy 改成 entry 遍历，消除每个节点的二次键查找。两轮测试显示普通 HashMap 多数场景更慢，NormalTagMap 的某些布局增加临时分配，因此已撤掉所有生产接入、配置和测试类，没有放入候选 JAR。

实验源文件快照在 `build/nbt-20261106/rejected-copy/`，测量在 `build/nbt-20261106/probe/copy-1.log`、`copy-2.log`。该实验仅替换输入的 NormalTagMap，输出 compound 使用默认构造器；只用于否决本候选，不据此宣传整包优化幅度。

## 交付与使用范围

候选基于当前工作区，包含此前已有的渲染改动；它不是只包含 NBT 补丁的独立插件。构建产物没有自动替换游戏实例的 JAR，也没有启动游戏或改动存档。手动测试时使用候选替换旧 optimizer，保持只有一个 optimizer；多人游戏两端使用一致候选。

安装用候选：`build/nbt-20261106/delivery/ice-rlcraft-optimizer-1.20.2-nbt-20261106.jar`

SHA256：`CB9CE93099FD179FD6BB774769D133EB0D7064B19E0A4B110CFDBB398329FA70`

需要同存档、同路线、同装备/容器状态的游戏录制，才能判断这些改动是否解决用户实际遇到的跳帧。大 NBT 的持续增长、网络全量同步或保存快照仍可能是其他模组中的独立问题；本轮没有盲目裁剪数据或更改所有模组的同步协议。
