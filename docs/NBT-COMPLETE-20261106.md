# NBT 完整实现：nbt-complete-20261106

针对本机 RLCraft Dregora 的实际模组版本完成读取、写入、Capability 比较/复制编码和网络传输四条路径。沿用 ICE 的 adapter → bridge → 模块配置结构，没有新增通用状态管理框架、逐帧整树检测、后台世界修改或独立保存线程池。

这是当前工作区的本地候选，包含此前的 NBT 和渲染修改。游戏版本仍为 Minecraft 1.12.2，模组版本仍为 ICE 1.20.2；构建标识为 `nbt-complete-20261106`。候选未发布，也未覆盖游戏实例的安装包。

## 实际实现

| 路径 | 实现 | 配置项（默认开启） |
| --- | --- | --- |
| Lycanites 装备属性和说明 | 一次操作共用部件读取视图，返回后释放，保持原计算与 feature 顺序 | `lycanitesEquipmentRead` |
| Trinkets 饰品、元素、计时器写入 | 读取即时状态，复用未改变的标量编码，可变 NBT 容器仍由本次写入独立创建 | `trinketsStateNbt` |
| 飞轮能量和箭袋 Capability | 直接比较与原序列化结果等价的运行态，减少临时 NBT；复制过程中复用不可变叶子 | `capabilityStateNbt` |
| Trinkets 远程物品同步 | 每个连接维护独立基线，传输原消息的差量，接收端重建完全相同的消息字节 | `trinketsSyncDelta` |

前轮的 Capability NormalTagMap 比较、QualityTools 属性复用和 Recurrent Complex 清理优化继续保留，见 [前轮记录](NBT-OPTIMIZATION-20261106.md)。装备读取的局部性能数据和原行为对照见 [装备阶段记录](NBT-EQUIPMENT-20261106.md)。

### 饰品状态与数据所有权

`TrinketsStateAdapter` 覆盖 TrinketProperties、ElementalAttributes、Counter 和 TickHandler 的原 writer。缓存挂在所属对象上，只记录每个固定字段的当前标量标签；值变化便替换该条记录，不积累历史。保留所有原 getter、动态 Map Entry 读取和完整写入顺序。浮点数按原始位比较，保留正负零和 NaN 载荷。

完整子树会被其他模组获取并修改，计时器和元素也不只通过 mana setter 更新。因此没有把它们复制成另一份权威状态，或仅凭 mana 版本跳过整个保存。原先替换子树、清理旧键和保持旧引用独立的语义继续成立。这是原设计中“只复用可证明不变的数据”的实际落地；没有声称实现不受限制的字段级延迟保存。

### Capability 比较与复制

目标是 Bountiful Baubles 0.1.8 的飞轮能量 provider 和 Spartan Weaponry 1.6.1 的箭袋 provider。整个 dispatcher 的所有 writer 都符合已适配合同才使用直接比较。混合未知 provider 继续走原始的“左侧全部序列化，然后右侧全部序列化”路径。

箭袋比较覆盖槽位、数量的 byte 投影、损伤的 short 投影、注册名、标签缺失和内容；保留原 serializer 初始化所属物品空根标签的副作用。未知嵌套能力、NormalASM 尚未初始化的 capNBT 都由原实现处理。不会为比较强行初始化延迟能力。

复制仍经过原 ItemStack 构造、能力挂载和反序列化。飞轮缓存一个能量整数标签，ItemStackHandler 复用常用 Slot/Size 整数标签；原 writer 的容器创建、物品标签引用关系和回调继续执行。这里减少的是复制过程的重复编码分配，不是绕开模组生命周期的对象克隆。

### 差量同步

仅替换 Trinkets 0.32.5 NetworkHandler 的两个发送边界，针对 SyncItemDataPacket。原消息构造时独立 tag 的采样，以及编码时 share tag 的采样都保留；向多个观察者发送时仍只编码一次。

接收者通过 Forge 自带 REGISTER 通告 `ice_nbt_v1` 通道。支持的远程连接各有独立基线；其他连接使用原 `xat` 消息。单人集成服的 LocalChannel 直接走原协议，避免在内存传输中增加没有带宽收益的差量工作；局域网加入者的远程连接仍可使用差量。缓存随 NetworkManager 通道生命周期释放，每端最多 64 条、2 MiB 的有效载荷，按相同规则淘汰。首次消息和缺少基线时使用完整表示，普通更新按变动字节段或共同前后缀生成差量。70 字节及以下的小消息、超过缓存单包限制的消息沿用原通道并清除发送基线；后续重新发送完整基线。

接收端恢复原消息字节后进入原 xat codec 和原 ThreadSafePacket。物品卸下、移槽、更换、相同状态的重复消息仍逐次交付；原来的两次 NBT 应用、装备回调和主线程调度均保留。暂停优化只停止新的差量发送，仍可接收在途消息。连接重建或重新注册后重新建立完整基线。

协议基于原连接的可靠有序传输，保留解码所需的长度、范围和版本检查；没有增加逐包 CRC、确认应答或额外轮询。差量降低网络字节量，仍需要原 NBT 编码/解码，并增加字节比较与有限基线存储；收益不能等同于减少了同等比例的客户端 tick 或渲染工作。

## 验证与构建

统一执行入口为 `tools/build-nbt-candidate.ps1`，定向验证加 `-Targeted`。使用本机实际模组 JAR 与 Forge SRG JAR，证据保存在 `build/nbt-complete-20261106/`。测试中对必要的游戏启动依赖使用夹具，并执行原 writer、原 packet 编解码和真实 Forge 网络组件；这些验证不能替代完整游戏会话。

`build optimizerBundleZip verifyArtifactStartup` 全部成功。常规测试 1418 项：1323 通过、95 项可选跳过、零失败；另 10 项最终制品启动检查全部通过。本阶段新增 36 项测试全部通过、无跳过；其中网络验证覆盖原 packet 契约、差量算法、真实 Forge 收发、不同观察者、本地连接、小包、断线和开关恢复。Trinkets writer 另在实际 NormalTagMap 下通过 7 项独立测试。

字节码修补仅对改动的饰品 writer 计算新栈帧，完整原类中其他方法的帧保持不变，并有专项回归。测试结果快照位于 `validation/`，汇总位于 `test-summary.json`。

最终 Capability 探针结果如下，包含反射与夹具共同开销；时间为单次诊断样本，分配由 ThreadMXBean 计量，不作为稳定的加速比例：

| 操作 | 原分配 → 优化分配 | 原耗时 → 优化耗时 |
| --- | --- | --- |
| 12 槽 ItemStackHandler 复制用快照 | 6184 → 5976 B/次 | 2.38 → 2.03 μs/次 |
| 飞轮能力比较 | 896 → 16 B/次 | 0.50 → 0.20 μs/次 |
| 12 槽箭袋能力比较夹具 | 12048 → 16 B/次 | 6.63 → 0.66 μs/次 |

前者执行实际 Forge ItemStackHandler writer，后两者执行真实 provider/dispatcher 方法；箭袋的物品和 inventory 载体由夹具提供。飞轮与箭袋比较的结果差异、原副作用、未知 provider、延迟 capNBT 和可变标签别名均有行为对照。详细测量条件见 `capability-state-probe.md`。

独立饰品 writer 探针执行原 0.32.5 方法：普通 Map 稳定分配约 1784 → 1496 B/次；实际 NormalTagMap 的两个 JVM 进程分别少分配 128 和 256 B/次。后者 CPU 耗时大致持平，证明的是分配下降，不是稳定的 writer 加速。完整条件见证据目录的 `trinkets-state-probe.md`。

网络探针使用合成饰品状态，经实际 0.32.5 SyncItemDataPacket 编码器生成原消息，每种序列 5000 包，包含首次完整包和 45 字节差量包头：

| 状态序列 | 原消息 → 新协议平均载荷 | 额外发送/接收算法耗时 | 两端合计额外分配 |
| --- | --- | --- | --- |
| 两份采样均保持不变 | 2772 → 54.55 B/包 | 1.06 / 0.70 μs/包 | 约 9.18 KB/包 |
| 两处整数变化 | 2772 → 72.58 B/包 | 1.02 / 0.49 μs/包 | 约 9.22 KB/包 |
| 每份采样的 30 个字符串均变化 | 2672 → 2467.43 B/包 | 3.78 / 0.92 μs/包 | 约 23.84 KB/包 |

时间取七轮中位数。以上载荷是 Minecraft 连接压缩前的数据，不是最终 socket 带宽；额外耗时/分配没有省去原 NBT 编码解码。变化较少时传输量下降明显，频繁大改则收益较小且付出额外 CPU/分配。新完整包自身比原消息多一个包头，不能承诺所有状态序列都省流量。结果与限制保存在 `transport-probe.log`，可通过 `TrinketsSyncPacketContractTest.main` 复现。

尚无此次候选的实机 FPS、帧时间 P95/P99 或 MSPT 对照。局部优化量化的是对应路径的时间、分配或传输量；世界生成、实体逻辑和渲染负载仍需要分别判断。

## 候选制品

安装包：`build/nbt-complete-20261106/delivery/ice-rlcraft-optimizer-1.20.2-nbt-complete-20261106.jar`，4,834,763 字节。

SHA256：`93DC1E396F8C57BD7B22C6688F23949083D0A5E89F0D6ED1338172AC0253E539`。

最终包已检查包含新增 bridge、协议、adapter 和早期访问接口，IceMod 内的构建标识一致。安装时用该文件替换现有 ICE Optimizer，避免同时保留两个 optimizer JAR。远程连接双方均使用本候选时可启用差量；仅一端更新时沿用原协议。单人模式自动使用原本地传输。
