# ICE 内存优化方案：兼顾进程占用与帧时间

日期：2026-11-05。用户观察的是 Windows 任务管理器中的 Java 进程内存；本方案以系统 RAM 为主。依据当前工作区源码、Java 8/LWJGL 2 实现及下列第一方资料制定。本轮未修改运行时代码或启动参数，也未取得游戏进程的实际内存快照，因此以下是有代码依据的候选优化点，不是已确认的内存泄漏或节省量。

## 1. 正确衡量占用

任务管理器常用 Memory 列反映私有工作集，Commit size 是另一项指标；二者都不是 Java 堆存活对象量。[Microsoft 的指标说明](https://blogs.windows.com/msedgedev/2021/01/13/investigate-microsoft-edge-memory-usage/)明确区分驻留 RAM 和私有提交量。

需要同时记录：

- Java 堆 used/committed/max、GC 次数与暂停时间，以及自然 GC 后的占用。
- direct/mapped BufferPool 的 memoryUsed、totalCapacity、count。Java 8 的 [BufferPoolMXBean](https://docs.oracle.com/javase/8/docs/api/java/lang/management/BufferPoolMXBean.html)提供这些估计值。
- Java 进程私有提交量和私有工作集；专用/共享 GPU 内存另列，不能直接相加解释该进程的 Memory 列。
- ICE 各类缓冲的使用量/容量、空闲池字节、各缓存估算权重与命中/淘汰、队列保留结果字节、活跃工作线程数。

现有 `src/profiler/java/dev/rlcraft/ice/profiler/jvm/JvmMonitor.java` 主要记录堆和 GC，尚未提供上述完整的 direct/mapped 分项。应把低频统计放在可选 profiler/诊断路径，建议每 1–2 秒聚合一次，不在绘制热循环加日志或扫描对象图。

需要进一步归因时：Java 8 NMT summary + baseline/summary.diff 查 JVM 自身；heap dump 用 Eclipse MAT 的 Dominator Tree、Paths to GC Roots 查哪些对象保留了大数组或旧世界。NMT 不覆盖全部 JDK 类库及第三方 native 分配，也不能当显存统计；Oracle 文档说明有性能开销，因此内存诊断轮与正式 FPS 对照分开。[NMT 文档](https://docs.oracle.com/javase/8/docs/technotes/guides/vm/nmt-8.html)、[MAT 方法](https://help.eclipse.org/latest/topic/org.eclipse.mat.ui.help/reference/findingmemoryleak.html)。

## 2. 第一批：限制历史峰值长期驻留

### 排序、寻路和世界生成工作区

源码证据：

- `compat/chunk/ChunkPrimitiveSortBridge.java:304`：每线程 Scratch 保留 raw、distanceKeys、order、merge、sprites；按倍数扩容，没有缩容。raw 是本次待重排顶点的完整整数副本。
- `compat/otg/OtgBo3Workspace.java:103`：release 清除内容引用，但坐标、材质引用数组及索引数组保留容量。
- `compat/entity/LongObjectScratch.java:38`：clear 清空占用槽的对象引用，keys/values/occupied 数组保留历史容量；`PathSearchWorkspace` 在线程池中复用。

建议：常见尺寸继续复用，超大临时工作区设置保留门槛；根据最近任务的需求，在持续低使用或安全生命周期边界逐步回收过大的空闲数组。维护每线程与全局空闲池预算，避免线程数量把保留量成倍放大。ThreadLocal 的清理由所属线程在任务结束或下一次借用时处理，不能在渲染线程调用一次 remove 就假定所有工作线程已释放。

门槛通过采样确定，不先写死一个很小的上限。不逐帧缩放数组；持续大任务仍复用合适工作区，防止分配/GC 抖动。排序和世界生成输出必须逐字节/逐结果对照。

举例而非实测：100,000 个四边形、28 字节顶点时，raw 有效内容约 10.68 MiB；56 字节布局约 21.36 MiB，尚未计入排序数组、扩容余量及其他线程。实际是否存在这些尺寸需测量。

### 渲染上传暂存区

`render/terrain/NativeTerrainRenderer.java:250` 的 indexUpload 直接缓冲按需倍增；`NativeTerrainGl43.java:159` 的 submissions 及命令缓存也保留高水位。小场景返回后，不会自动恢复到小容量；销毁渲染器可以解除引用，但 direct 内存实际回收时点还受 JVM 管理。

建议：记录每帧实际需求，在持续低使用时降档；世界/资源代际切换时清理不再使用的缓冲。对可拆分的上传采用固定大小暂存页，限制空闲页总量；超大请求不永久污染小请求池。存在异步使用、视图或 GPU 引用的缓冲必须完成所有权交接后才回收，不对原版/其他模组的直接缓冲强制调用内部 Cleaner。

LWJGL 2 的 `BufferUtils.createByteBuffer` 实际调用 `ByteBuffer.allocateDirect`。[LWJGL 2 源码](https://github.com/LWJGL/lwjgl/blob/master/src/java/org/lwjgl/BufferUtils.java)。Java 8 文档说明直接缓冲可位于普通 GC 堆外，分配/释放通常成本较高；降低长期保留量不代表任务管理器数字立即同步下降。[ByteBuffer 文档](https://docs.oracle.com/javase/8/docs/api/java/nio/ByteBuffer.html)。

## 3. 第二批：统一可重建缓存与队列预算

`compat/otg/OtgSynchronousIoBridge.java:55` 的 settings 缓存权重上限为 96 MiB，prepared metadata 为 64 MiB；每个 MetadataMap 的 safe 缓存又各自允许 64 MiB（同文件约 871 行）。这些不是全局共享总额。某些条目可能共享同一个不可变蓝图，不能简单把全部上限相加当作实际内存占用。

建议：优化器拥有的 OTG 不可变蓝图采用可配置总预算，按来源/解析语义安全共享；校正权重中 key、数组及容器开销，保留现有文件失效机制。优先淘汰低复用的大条目，并监测未命中造成的磁盘读取和生成耗时。真实世界数据及外部原始 Map 的兼容语义不因预算改变。Caffeine 权重是估算值，创建/更新后不会自动跟随对象增长，不能把 `maximumWeight` 当成精确 RSS 上限。[Caffeine 官方说明](https://github.com/ben-manes/caffeine/wiki/Eviction)。

`compat/save/ChunkSaveCompressionBridge.java` 已有 128 个任务上限及 32–128 MiB 压缩结果预算，不属于完全无限队列。进一步检查的是被保留输入快照、队列外原版持有关系、替换/取消任务的生命周期。不要为统计字节再复制或序列化一遍所有 NBT，也不能丢弃待保存数据。

`ClientOptimizerRuntime.java:69` 创建通用 CacheBudget，但默认 native terrain 分支不创建使用该预算的 ModernRendererRuntime。Native 地形的主要数组与 GL 存储没有接入这些预留接口。配置的 heap/direct/GPU 256/192/256 MiB 是各通用预算上限，既不是预分配量，也不是整个 ICE 或 Java 进程的总上限；单纯把三个值减半不能解决上述分配。

## 4. 第三批：显存与地形容量，按独立证据决定

GL43 `NativeTerrainStorage.ensure` 约按 1.5 倍扩容；网格 free 归还分配范围，不缩小 GL 存储，增长时存在旧/新存储生命周期交叠。GL21 已有固定页、空页释放，不能概括成全部后端都只增不减。

若实际 GPU/共享内存测量指向 GL43，再评估固定页或按空闲比例低频回收。必须保留多绘制批处理、透明顺序及 Intel 稳定命令存储；分页可能增加绘制调用，迁移/压缩也可能制造新卡顿。因此这一项不作为降低任务管理器 Java Memory 的第一步，不直接将 GL21 绘制方案替换 GL43。

## 5. 启动参数与本轮优化的取舍

尚无机器 RAM、实际 Xms/Xmx 和 GC 信息，不给固定“分配 4G/8G”的结论。先检查是否把 Xms 与很大的 Xmx 设成相同值；可在对照中测试较小初始堆，但不保证游戏高峰后立即返还内存，扩大堆时的延迟也要测。Xmx 是最大堆，不是进程全部内存上限。[Java 8 堆大小文档](https://docs.oracle.com/javase/8/docs/technotes/guides/vm/gctuning/sizing.html)。

不建议靠周期性 System.gc、工作集清理器或很小的 MaxDirectMemorySize 压数字。OpenJDK 8u 的 Bits.reserveMemory 在直接内存额度不足时会尝试引用清理、System.gc 和退避等待，最终仍可能报 Direct buffer memory；压限额可能加剧跳帧。[OpenJDK 8u 源码](https://raw.githubusercontent.com/openjdk/jdk8u/master/jdk/src/share/classes/java/nio/Bits.java)。

上一轮 frame-submission 优化有明确空间换时间：新增大小端索引表共 256 KiB，加每个历史最大绘制记录 36 字节的命令副本及少量面块元数据。4096 条命令副本为 144 KiB；它本身不能据此解释数 GiB 占用。保留已测得的帧时间收益，对其高水位容量纳入上述统一回收。

## 6. 实施顺序与验收

1. 低频分项统计，建立同一存档/视距/材质/光影/路线的基线。分别记录稳定场景、持续跑图、返回原处和退出/重进世界。
2. 首轮只改 Scratch/空闲工作区和上传暂存区的保留策略；不压缩活跃数据、不降低画质、不减少实体或世界 Tick。
3. 第二轮做 OTG 全局缓存预算与失效后引用清理；按真实占用决定是否调整压缩队列。
4. 显存方案和 JVM 参数分别试验，避免同时改变多项后无法归因。

每轮验收同时看：自然 GC 后堆占用、direct 容量、进程私有提交/工作集、分配速率、GC 暂停、帧时间 P95/P99/最大值以及跑图加载进度。重复路线后占用应形成可解释的稳定平台；内存下降但帧时间或加载速度明显退步，则调整或撤回该策略。泄漏证据是不可达资源仍被强引用等生命周期问题，单次高占用截图不构成证明。具体可节省多少 MiB/GiB 留待基线与修复后对照确认。
