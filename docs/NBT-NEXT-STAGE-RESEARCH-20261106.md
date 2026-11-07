# NBT 下一阶段优化调查

日期：2026-11-06。对象：当前 ICE、Forge 1.12.2、RLCraft Dregora 本机模组版本。本次在线查阅作者源码、官方文档，并核对本机模组字节码；只形成方案，没有实现新补丁或进行游戏性能测试。

## 结论

比继续微调 NBT equals/copy 更值得优先验证的，是消除具体模组中的重复解析、整树重建和重复同步。此次新增了两个可定位到实际安装版本的目标：Lycanites 装备部件读取、Trinkets and Baubles 的能力持久化与同步。

当前 Capability 补丁仍执行双方原有的 serializeNBT，然后优化比较。这保证了兼容性，但无法省掉构造 NBT 的主要成本。前轮合成探针中，4 个 provider 每次构造独立快照的场景只从约 116 μs 降到 109 μs，说明这类工作负载还需要向数据产生端优化；该结果不是当前游戏掉帧原因的证明。前轮详情见 [已交付记录](NBT-OPTIMIZATION-20261106.md)。

| 顺序 | 候选 | 主要覆盖场景 | 证据与限制 |
| --- | --- | --- | --- |
| 1 | Lycanites 单次操作内只解析一次部件 | 装备提示信息、属性计算 | 本机确认重复调用；收益局限于实际进入这些方法的场景 |
| 2 | Trinkets 字段更新与同步去重 | 魔力/能力变化、饰品同步 | 本机确认整树写入和重复状态序列化；需要处理状态时序 |
| 3 | 热点 Capability 直接状态比较/复制 | 大量物品比较、复制 | 成熟架构可参考，尚需选定并审计具体 provider |
| 4 | 已知库存的槽位变更通知 | 库存检查、自动化和同步 | 可减少未变化时的工作，但必须完整覆盖修改入口 |
| 5 | 选择性读取、直接二进制写入 | 特定读取或保存尖峰 | 场景受限，Forge 保存事件限制通用替换 |

## 1. Lycanites：消除一次计算中的重复部件反序列化

本机版本 `lycanitesmobs-1.12.2-2.0.8.10.jar`。`ItemEquipment.getEquipmentPartStacks` 每次建立 20 槽列表，再调用 `ItemStackHelper.loadAllItems` 从 NBT 重建部件物品。各个上层 getter 又分别调用它。

正常锋利度大于零时，单次 `getAdditionalDescriptions` 的既有调用路径可展开为 15 次部件解析：锋利度 1、魔力 1、名称 1、伤害 2、攻速 1、击退 2、范围 1、横扫 2、四种说明 4。单次主手 `getAttributeModifiers` 为 3 次。重复在上层调用之间，单独 `getFeaturesByType` 的内部 feature 循环并非每轮重建。

建议引入仅活跃于本次计算的装备视图，把部件列表传给各项计算，分别以 15→1、3→1 为原型目标。这会同时减少 NBT 读取、ItemStack 创建及其初始化，而不只是加速标签查询。操作结束就释放视图，不保留跨 tick 缓存。

实施前必须审计 feature 回调和物品初始化：若某些路径会修改部件、依赖每次新对象或产生外部副作用，就不能直接共享它们；应限定受支持类型或仅复用可证明只读的数据。保持计算顺序。目标次数尚未通过改造后的程序验证，不是已取得的整帧收益。

来源：[Lycanites 作者固定版本源码](https://gitlab.com/Lycanite/LycanitesMobs/-/blob/a9036151d9752a61c1bcc1600a90a7b3a7c7e25e/src/main/java/com/lycanitesmobs/core/item/equipment/ItemEquipment.java)。方法和次数另以本机 JAR 的 javap 输出核对；网页抓取存在访问限制。

## 2. Trinkets and Baubles：减少整树写入及重复同步

本机版本 `Trinkets and Baubles-0.32.5.jar`，作者对应提交 `4016caddbe961898cdb9acd33b218fc53d91d5b0`。

`setStoredMana` 在数值变化时调用 `saveToNBT(getTag())`，重建整个 `xat:Trinket` 子树，连带写入制作人、槽位、能力、计时器及元素属性等状态。已有 `sync` 脏标记，不能把“增加 dirty flag”当成新优化。

可选方案是由拥有者维护字段变更，只更新已审计的相关字段，或在明确的读写边界集中产生快照。需要检查原整树写入是否顺带提交了计时器、元素等其他可变对象的状态。其他调用者能直接读 stack NBT，因此不能简单把全部更新延迟到存盘，也不能只改 mana 而遗漏原来一并刷新的状态。缺失标签时仍需正确初始化。

[作者 TrinketProperties 源码](https://github.com/XzeroAir/Trinkets/blob/4016caddbe961898cdb9acd33b218fc53d91d5b0/main/java/xzeroair/trinkets/capabilities/Trinket/TrinketProperties.java)

另一个明确目标是 BAUBLES 同步分支：先构造独立 capability tag，包编码写入该 tag，然后写整个物品；物品 share tag 又序列化同一 capability。接收时 share tag 已加载能力，包处理还会执行装备初始化和独立 tag 加载。重复的是同一状态类型的处理和携带，不保证两份字节完全相同。

建议先让本次同步使用同一份独占快照，再考虑省略重复表示，最后才做字段增量协议。独立 tag 在创建包时采样，share tag 在编码时采样，直接删一个会改变两次采样语义。还需保留装备初始化、首次观察、物品替换和未知物品类型的处理，两端协议必须配套。尚未实测发送频率，不能认定每 tick 都发生。

来源：[同步包源码](https://github.com/XzeroAir/Trinkets/blob/4016caddbe961898cdb9acd33b218fc53d91d5b0/main/java/xzeroair/trinkets/network/SyncItemDataPacket.java#L55)、[物品 share tag](https://github.com/XzeroAir/Trinkets/blob/4016caddbe961898cdb9acd33b218fc53d91d5b0/main/java/xzeroair/trinkets/items/base/AccessoryBase.java#L275)、[Forge 1.12 PacketBuffer 补丁](https://github.com/MinecraftForge/MinecraftForge/blob/1.12.x/patches/minecraft/net/minecraft/network/PacketBuffer.java.patch)。

网络设计的同版本参考是 Forge 官方建议和 CoFHCore：保存完整数据，按客户端用途提供初始、方块更新、GUI 等不同数据；状态变化时只发送必要内容，不用全量序列化后深比较来判断是否变化。

[Forge 1.12 同步文档](https://docs.minecraftforge.net/en/1.12.x/tileentities/tileentity/#synchronizing-the-data-to-the-client)、[CoFHCore 1.12 TileCore](https://github.com/CoFH/CoFHCore-1.12-Legacy/blob/1.12/src/main/java/cofh/core/block/TileCore.java)

## 3. Capability：比较和复制运行时状态

现代数据组件把实际 Java 状态与序列化分开；不可变值可以共享，少变组件可缓存编码结果。可借鉴这一架构，为精确已知的热点 provider 实现与原 NBT 语义等价的字段比较和复制，避免运行时对象→NBT→比较/反序列化的往返。保留 1.12 的存档格式和外部接口，未知 provider 使用原路径。

这是当前通用比较补丁之上的下一层优化。必须证明原 serializeNBT 没有需要保留的副作用或动态输入，直接复制仍保持独立所有权；两个对象修订号相同不代表内容相同。长期快照或编码缓存要求嵌套集合/数组没有可变引用泄露，不能把缓存 NBT 当共享可写对象返回。

参考：[NeoForge 20.5 架构说明](https://neoforged.net/news/20.5release/)、[Data Components 与 cacheEncoding](https://docs.neoforged.net/docs/1.21.1/items/datacomponents/)。这是借鉴设计，不是建议在 1.12 安装现代组件系统。

## 4. 库存：用变更通知减少没有必要的处理

Lithium 的库存实现通过修改计数、槽位订阅与失效通知，避免重复执行未变化的库存工作，并在漏斗优化中保留比较器等副作用。1.12 的 ItemStackHandler 也有 onContentsChanged(slot)，可以作为已知库存的接入点。

但该回调不覆盖外部直接修改返回的 ItemStack、嵌套 NBT/Capability，也不意味着读档逐槽通知。需补齐数量变化、对象替换、加载和数据内部变更后，才能维护变更槽位集合，减少快照、复制和同步。不能据此全局跳过 Container.detectAndSendChanges。

来源：[Lithium hopper 设计](https://github.com/CaffeineMC/lithium/blob/develop/common/src/main/java/net/caffeinemc/mods/lithium/mixin/block/hopper/README.md)、[LithiumStackList](https://github.com/CaffeineMC/lithium/blob/develop/common/src/main/java/net/caffeinemc/mods/lithium/common/hopper/LithiumStackList.java)、[Forge ItemStackHandler](https://github.com/MinecraftForge/MinecraftForge/blob/1.12.x/src/main/java/net/minecraftforge/items/ItemStackHandler.java)。

## 5. 读写层：只用于条件合适的场景

选择性读取：若某个入口只需要结构尺寸、版本或索引，可借鉴 FastNBT 的跳过值机制，只解析必要字段，不为无关子树创建对象。压缩流仍需解压/扫描；如果随后立即读取完整数据，反而可能重复工作。它适合特定加载入口，不解决驻留 NBT 的高频比较。

[FastNBT 作者反序列化实现](https://github.com/owengage/fastnbt/blob/master/fastnbt/src/de.rs)

直接二进制写入：C2ME 有快照直接写入 NbtWriter 的实现，能省去部分中间 NBT 对象树，与 ICE 已有的异步编码压缩不同。但 Forge 1.12 保存区块时会把完整 NBT 交给 ForgeChunkManager 和 ChunkDataEvent.Save，监听器能读取和修改它。跳过完整树会破坏这一接口，重建完整树又抵消主要收益。

因此先限定在拥有完整控制权的私有写入路径；全局替换区块 writer 排在最后。保持原格式和一致快照，后台不访问活世界。现代版本还有 1.12 不具备的编码层，不能直接搬用其收益数字。

[C2ME ChunkDataSerializer](https://github.com/RelativityMC/C2ME-fabric/blob/dev/26.3.0/c2me-rewrites-chunk-serializer/src/main/java/com/ishland/c2me/rewrites/chunk_serializer/common/ChunkDataSerializer.java)、[Forge 1.12 保存补丁](https://github.com/MinecraftForge/MinecraftForge/blob/1.12.x/patches/minecraft/net/minecraft/world/chunk/storage/AnvilChunkLoader.java.patch)

## 验收方式

先做 Lycanites 原型，比较同装备的 tooltip、属性、战斗结果和 NBT 修改即时可见性，记录部件解析次数、分配量与耗时。再测 Trinkets 的魔力变化、装备/卸下、多人追踪、重连、存档重载，记录快照次数、编码字节和客户端处理耗时。

实际游戏使用同存档副本、同路线和装备，测帧时间 P95/P99、长帧次数、MSPT 与分配/GC；先确认这些入口占比，再决定是否扩大改造。代码路径已确认不等于主瓶颈已确认，调用次数下降不等于 FPS 同比例上升。

不把已有字符串规范化、NBT backing map 替换和异步压缩再次列为新增收益；不采用全局根引用/hash 缓存或任意截短存档 NBT。未发现、也未声称已经修复某个存档的无限增长问题。
