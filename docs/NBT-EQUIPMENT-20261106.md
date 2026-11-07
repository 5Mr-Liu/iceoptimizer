# 装备 NBT 单次解析：nbt-equipment-20261106

本轮落实 [架构设计](NBT-ARCHITECTURE-DESIGN-20261106.md) 的第一阶段：Lycanites 装备单次读取视图。对应实际版本 `lycanitesmobs-1.12.2-2.0.8.10.jar`，没有修改存档或安装到游戏实例。

后续状态、Capability 和同步阶段已合入新的 [完整候选](NBT-COMPLETE-20261106.md)；本文件保留装备阶段单独的制品与测量记录。

## 实现

新增 `LycanitesEquipmentReadAdapter` 和 `LycanitesEquipmentReadBridge`。适配器复用原方法体，为只读调用链生成带显式 View 参数的私有方法；没有重新手写伤害、攻速或说明算法。主手 `getAttributeModifiers` 和 `getAdditionalDescriptions` 各自创建独立视图，第一次到达原 `getEquipmentPartStacks` 调用时解析，之后的读取使用本次结果。

视图只保存一个部件列表引用，方法返回后即可释放。没有跨 tick 缓存、线程局部状态、全树哈希、全局版本追踪或逐部件扫描。feature 激活判断、等级读取、说明计算的原调用顺序保留。非主手属性、ItemEquipment 子类、关闭或暂停模块时走原方法体。

配置项 `lycanitesEquipmentRead=true`，模块 `lycanites-equipment-read` 支持集成服务器和独立服务器。目标目录沿用现有类指纹机制；新增类指纹为 `adacf20daead76247bfdc9fabcb7a69a896e81c94eb3464b7bd75c42eb8ed32c`。必要结构匹配只在类变换时执行。

本机 ItemEquipmentPart 的等级、锋利度、魔力读取，以及参与计算的 feature 回调已检查为只读。减少部件 ItemStack 重建也会减少相应构造及 Capability 初始化；这不等于保证任意其他模组依赖构造回调次数的行为不受影响。当前适配范围是已核对的 Dregora 模组版本。

## 后续阶段

Trinkets 的 mana 保存同时完整刷新 timers、elements、slot 等状态，并替换旧子树。直接改成写一个字段会遗漏原有更新，或改变旧引用的可见性；此次没有加入该改动。也没有为仅减少一个空 compound 的分配增加新的模块。

Capability 运行时直接比较、跨操作版本追踪和新同步协议仍是后续独立工作，本候选没有宣称实现整份架构设计。

## 验证与交付

构建与测试由 `tools/build-nbt-candidate.ps1` 执行，证据目录为 `build/nbt-equipment-20261106/`。局部验证使用安装版本的 ItemEquipment 方法与可观测测试依赖；实机帧时间仍需实际游戏录制。

9 项新增回归全部通过、无跳过。使用原版 ItemStackHelper.loadAllItems 解析真实物品 NBT，保留安装版本的装备计算方法；部件、feature 和翻译依赖由可观测夹具提供。验证说明文本、属性数值和回调轨迹一致，正锋利度说明路径解析 15→1，主手属性 3→1（零锋利度 2→1）；并覆盖独立调用间修改 NBT、模块关闭、异常后恢复、嵌套调用、装备子类覆盖、多部件及缺少 Items 的空装备。

完整 `build optimizerBundleZip verifyArtifactStartup` 成功：常规测试共 1,382 项，1,287 通过、95 项可选跳过、零失败；另 10 项最终制品启动检查全部通过。测试结果快照保存在证据目录的 `validation/` 和 `test-summary.json`。

`equipment-probe.log` 是独立 Java 8 JVM、512 MiB 堆、7 轮交替运行的局部探针。使用一个测试部件，默认 NBT 容器；每轮 3,000 次调用。这里包含反射驱动与夹具的共同成本，不是实际游戏录制：

| 场景 | 原分配 → 候选分配 / 次 | 原耗时 → 候选耗时 / 次 |
| --- | --- | --- |
| 主手属性 | 约 2,498 → 1,528 B | 约 3.89 → 2.09 μs |
| 装备说明 | 约 20,544 → 14,142 B | 约 11.90 → 7.16 μs |

调用次数由行为测试确定，分配与时间取探针七轮中位数。仍然执行的说明拼接、属性创建及 feature 逻辑占据剩余成本，所以 15→1 不代表整个方法或游戏快 15 倍。具体 FPS、P99 帧时间与真实模组 Capability 初始化成本需要实机确认。

此候选包含当前工作区之前的 NBT 补丁及渲染改动，不是只含本次装备补丁的独立插件。候选不会自动覆盖游戏目录中的模组。

安装用候选：`build/nbt-equipment-20261106/delivery/ice-rlcraft-optimizer-1.20.2-nbt-equipment-20261106.jar`。

SHA256：`E9EEC2B6C275D9D60F3A3ABF96B616FFE7FA5526158CDB0C31688F8443A53E14`。
