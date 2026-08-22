# ICE 1.0 完整最优实现与源码审计

日期：2026-08-21

目标环境：Minecraft 1.12.2、Forge 14.23.5.2860、Java 8、RLCraft / RLCraft Dregora 及结构兼容衍生包
实测来源：`ice-profiler/sessions/20260816-235120-766` 至 `20260816-235618-558`

## 1. 最终结论

这批卡顿不是单一“线程太少”或“显卡不够”造成，而是六类成本叠加：客户端主线程同步联网、世界生成重复解析与高复杂度扫描、数 GiB 级短期分配与 GC、区块保存序列化/Deflate 突发、区块编译生产速度超过渲染上传消费速度，以及旧 Profiler 把 ICE wrapper 下游的真实模组方法误归给 ICE。

1.0 的实现不读取 CPU/GPU 型号，不识别 P/E 核，不按逻辑处理器数、核显/独显、集成服/远程服建立档位，也不硬绑核。并发由运行时闭环决定：从一个有效并发开始，以帧 P95、服务端 Tick P95、GC 暂停占比、可用时的进程 CPU、编译/上传队列、等待者和完成吞吐作为反馈，压力时乘性回退，有余量时一次加一，并验证扩容是否真的提升吞吐。

所有结果敏感逻辑仍保持同步、原 RNG 次数和原写入顺序。只有完全完成的不可变快照、可证明的纯计算、可验证的文件缓存以及渲染线程最终提交前的 CPU 准备允许离开原线程。任何结构失配、预算不足、队列满、代际变化、驱动异常或校验失败都会执行保留的原方法。

## 2. 八个实测 Session 的定量根因

表中的 Frame/Tick P95/P99 为各 Session `timeline.csv` 每秒窗口值的平均，峰值根因来自对应 `summary.txt`。它们是修改前基线，不是 1.0 的性能承诺。

| Session | Frame P95 / P99 | Tick P95 / P99 | 主要证据 | 1.0 对应处理 |
|---|---:|---:|---|---|
| `235120-766` | 177.12 / 2168.40 ms | 137.20 / 149.00 ms | Mojang `performGetRequest` 阻塞 37683.82 ms；GC 3643 ms | 集成服 Session Profile 异步；头颅资料继续使用独立有界异步缓存 |
| `235150-289` | 13.57 / 19.16 ms | 210.61 / 213.93 ms | 服务端最长 Tick 4099.05 ms；RC maze 1962.60 ms；OTG settings 623.84 ms；BO3 NBT 323.12 ms | Roguelike 设置 single-flight；OTG 解析/BO4 缓存；RC maze 保持原逻辑并列为结果敏感热点 |
| `235321-287` | 12.05 / 14.36 ms | 25.73 / 32.31 ms | OTG settings 100.28 ms；LibrarianLib unload 100.20 ms | 复用 OTG/Roguelike 设置；LibrarianLib 证据不足，不做猜测性变换 |
| `235358-370` | 18.14 / 21.49 ms | 58.61 / 59.85 ms | `LayerBiomeBorder.getInts` 1347.57 ms，目标线程分配约 752.1 MiB | OTG ArraysCache 线程本地租约池与按请求前缀清零 |
| `235415-482` | 15.31 / 20.06 ms | 354.33 / 359.47 ms | `BO4Config.readResources` 12842.54 ms，分配约 3939.1 MiB | 首次原解析、随后强校验持久 BO4Data 命中；布局与解析低分配路径 |
| `235520-916` | 23.66 / 30.14 ms | 28.40 / 38.70 ms | `ArraysCacheManager.releaseCache` 125.88 ms | 释放从扫描历史 backing arrays 改为 O(1) 游标复位，清零推迟到实际请求前缀 |
| `235532-518` | 38.78 / 47.26 ms | 67.84 / 78.66 ms | Chunk save 1198.31 ms；约 187.3 MiB 分配；保存峰值 1241/s；上传队列峰值 37 | NBT 完成快照后的有界并行 Deflate；FILE_IO 原序提交；保存域身份去重 |
| `235618-558` | 46.30 / 56.69 ms | 41.68 / 51.52 ms | render queue 峰值 71、upload queue 峰值 34；旧报告把 Lycanites 下游状态读取归到 ICE | Chunk permit 闭环、VBO Fence 退役；StackAttribution 按真实业务帧归属 |

八个 Session 的 GC 暂停总量依次约为 3643、590、138、128、455、165、475、423 ms。由此可见，单纯增加 Worker 会在 BO4/OTG 大分配、保存突发或上传队列已经积压时加重尾延迟；必须同时观察吞吐和压力反馈。

## 3. 硬件无关的闭环并发控制

### 3.1 Chunk 编译

ICE 保留其他 CoreMod 最终创建的 `ChunkRenderWorker`、`RegionRenderCacheBuilder` 和队列，不重写数量。每个后台编译任务在真正进入 `processTask` 前取得公平 permit：

1. 世界、资源或 GL 代际变化后从 1 permit 重新学习。
2. 每 500 ms 计算最近 60 帧 P95，并读取集成服 Tick P95、GC 暂停比、进程 CPU、compile/upload queue、in-flight、等待者和完成数。
3. 帧尾、Tick、GC、CPU 或上传队列持续有压力 750 ms 时，permit 近似减半。
4. 连续四个窗口有余量、队列确实饱和且有完成吞吐，并满足 2.5 秒驻留时只增加 1。
5. 扩容进入 2 秒探针。需求仍存在时，若吞吐增益不足 5%，或帧尾恶化超过 15%/2 ms，则回滚。
6. 模块关闭时立即打开门，不让已经从 vanilla 队列取出的任务丢失；被中断的任务先进入原 `finish()` 终态。

因此，少核心、低功耗、共享内存核显、独显、高核心数或后台负载变化只会反映为不同的实时反馈，不需要任何硬件表。

### 3.2 区块保存压缩

服务端主线程仍构造完整 `NBTTagCompound`。专用低优先级池只做 `CompressedStreamTools.write` 与 zlib Deflate，从 1 个 active worker 起步；绝对资源上限为 16，并按 `max(1, min(16, maxHeap / 64 MiB))` 连续工作集预算进一步收紧。这里没有核心数、CPU 型号或服务器类型分档。

控制器每秒观察待压缩队列、active worker、可用结果吞吐、FILE_IO fallback、完成延迟、三秒 Tick P95/max，以及由低优先级线程采样的进程 CPU/GC 快照。Tick/CPU/GC/fallback 压力时近似减半；扩容必须连续三个窗口证明真实队列饱和且已有完成样本，一次只增加 1，再执行 2.5 秒收益探针。吞吐增益不足 5%、fallback rate 增加 25 个百分点或完成延迟恶化超过 15%/2 ms 即回滚，失败探针冷却 5 秒。长期空闲只清除过期置信度和未完成探针，保留最近验证的工作点。FILE_IO 线程仍按 vanilla pending Map 顺序写同一个 RegionFile；队列满、结果过大、总结果预算不足、Accessor 缺失、代际变化或异常时调用保留的原压缩流。

### 3.3 GPU 提交

GPU 能力不按厂商判断，只按实际 OpenGL 能力与 Fence/Query 状态判断：

- 小于 256 KiB 的纹理/VBO 上传直接走原路径，避免固定 Fence 税。
- 槽位只做非阻塞探测；未 ready、超时、GL error、预算不足或 context 变化立即 fallback/retire。
- Fence、PBO、Buffer、Display List 和 Timestamp Query 分别记账并 best-effort 释放，异常也必须回收预算。
- 通用单 mip PBO 永久关闭；仅 FoamFix 已聚合的完整 mip 批次可进入 PBO 候选。
- Xaero 查询使用显式 `IDLE → STARTED → IN_FLIGHT → IDLE/RETIRED` 状态机，begin/end 必须严格配对。

## 4. 完整实现清单

### 4.1 OTG / BO4

- 持久 BO4Data：首次仍执行 OTG 原解析。成功后单个低优先级 daemon worker 把同一 `BO4Config.writeToStream` 结果压缩并写入 `config/ice/cache/otg-bo4`。
- 热命中校验：canonical 源路径哈希、源大小/mtime/完整 SHA-256、WorldConfig/Fallbacks/version/OTG.ini 与目录域摘要、OTG data version、BO4Config class hash、data 长度和 SHA-256 必须同时一致。
- 原子写：data/meta 各写同目录临时文件，flush 和 `FileDescriptor.sync` 后 `ATOMIC_MOVE + REPLACE_EXISTING`。半写、损坏或不支持原子替换只造成 cache miss。
- 路径安全：缓存名只含 64 位十六进制 SHA-256；root、临时文件和目标文件均做 canonical containment；不会写入预设目录或源 `.BO4`。
- 代际安全：shutdown/start 递增 generation；排队任务、正在序列化的旧任务和旧 pending owner 不能提交到新运行时。
- 运行期冗余写抑制：只抑制已审查的 `WriteWithoutComments`；显式 `WriteAll` 始终执行并重新计算源指纹。
- `trySpawnAt` 只在单次调用内复用第一份 blocks 数组，不跨 spawn 保存会被随机选择修改的函数对象。
- `loadBlockArrays` 把逐方块的 16×16 前缀扫描变成同一 `short[][]` 身份下的 256 项前缀表。
- 配置解析器用两遍索引扫描取代 `toCharArray + LinkedList + 二次数组`；函数名缓存最多 128 项并随默认 Locale 变化失效。

### 4.2 OTG ArraysCache

- 四组全局锁 cache 改为可嵌套 ThreadLocal pool，每线程最多保留四个 cache。
- 本地 release 只复位四个 next 游标、`isFree` 和 `OutputType.FULL`，不扫描全部数组。
- `getArray(n)` 只清 `[0,n)`；OTG 原池对象仍执行原完整 release。
- 全局弱身份 LeaseRegistry 使外线程释放成为线性化撤销；同一 cache 不可能同时发布到 ICE 本地池和 OTG 全局池。

### 4.3 Roguelike SettingsResolver

- 同一 loader、resolver class 和目录 generation 共享一个 `FutureTask`，消除重复解析。
- 低优先级 monitor 每 2 秒检查 metadata，最多每 30 秒做一次内容审计；最多 4096 项、64 层、64 MiB JSON 内容，避免后台扫描失控。
- 首次目录基线建立时若 resolver 已发布，强制使其失效，修复“旧解析 A、首次 monitor 把新状态 B 当基线”的 TOCTOU。
- monitor 在首个可缓存基线前建立递归 WatchService；每个候选绑定解析前 `mutationSequence`，验证前后 drain 并等待 100 ms。modify/create/delete、OVERFLOW、WatchKey 失效或覆盖变化都会推进序号并驱逐候选，因此等长 A→B→A 且恢复 mtime/fileKey 也不能错误发布。
- shutdown 清空 entry、反射根和 ThreadLocal，递增 monitor epoch；迟到 monitor 不能发布。

### 4.4 Mojang Session Profile 与玩家头颅

- 集成服 Session Profile：客户端主线程立即继续 UUID/名称登录；后台使用新建查询 `GameProfile`，只把属性复制为不可变 `PropertyValue[]`，绝不在 worker 修改原对象。
- ASM 同时跳过紧邻的 `Session.setProperties`；空属性绝不写入 Session，避免空 `PropertyMap` 把 `hasCachedProperties()` 永久置真。
- 非空结果只在客户端 Tick 发布；每个仍有效的 Session 都得到新的防御性 `PropertyMap` 副本，不与 worker、缓存或集成服共享可变容器，并保留 multimap key 与 `Property.name` 可以不同的 Authlib 语义。
- 同身份 single-flight、最多 8 个 key、每 key 最多 8 个弱目标、完成队列最多 8 项；正缓存 6 小时、负缓存 30 秒。
- 客户端 Tick 的主动预取至少间隔 30 秒；cache hit、completion、shutdown 均与 generation 线性化。
- executor 若因第三方 HTTPS 忽略中断而未退出，不创建第二个永久卡住线程；本次保留无属性身份。
- 玩家头颅使用独立的有界正/负缓存和单 worker，渲染帧永不等待网络。

### 4.5 世界保存

- 全量保存计划刻：一次 `saveChunks(true)` 按 mutation version 建立临时索引，代替每个脏区块重复扫描全局集合；范围扩两格和 TreeSet/List 顺序保持原样。
- Chunk 压缩任务 key 包含保存器/保存域身份、ChunkPos 和 NBT 快照身份，避免不同维度相同坐标互相取消。
- 任务状态机保证排队取消、超时 fallback、shutdown 和迟到 worker 只完成一次；每条路径释放结果字节预算和 FILE_IO 等待者。

### 4.6 区块网格与 Forge 状态

- Chunk rebuild coalescer 只合并同一 `RenderChunk` 当前 in-flight rebuild 的重复 dirty 通知；位置变化、资源销毁和立即重建清除 deferred 状态。
- 透明四边形从 boxed object sort 改为稳定 primitive sort；距离、NaN、tie order 与最终顶点字节不变。
- Forge fluid extended state 使用每 worker 256 槽精确身份缓存；`property.isValid` 只调用一次，非法值异常文本与 Forge 相同。
- Better Foliage AO 复用每 worker 的 `float[12]` 与 `BitSet`；Dynamic Trees 只缓存同一不可变 extended state 的六面连接数组。
- OptiFine dynamic lights 发布原 50 ms 更新边界的不可变 primitive snapshot；大于等于 96 个光源才建立 8 方块空间索引。

### 4.7 GPU 与模型资源

- Chunk VBO、FoamFix PBO、Lycanites/SRP Display List、Xaero Query 全部绑定 GL/resource generation。
- 禁止嵌套 `glNewList`；编译必须在实际 GL 调用成功且 error scope 通过后才标记完成。
- context 切换、benchmark 重启、模块中途关闭、Fence/Query 超时和任何创建异常都会退役对象并回收预算。
- SRP 与 Lycanites 的动态关节/颜色/UV/scale/子节点身份逐次验证，只有稳定静态分支进入 GPU cache。

### 4.8 其他实测热点

- SRP 最近目标：O(n log n) 全排序改为稳定 O(n) 最小选择。
- RenderLib pending 合并：O(pending × loaded) 改为有预算成员表的 O(pending + loaded)，自定义 equals 或重入时回原 contains。
- Better Caves：boxed `Double`/Map 改 primitive tuple、连续 column、64 槽精确坐标缓存和一次 blend。
- Lycanites：单次寻路节点/方块状态缓存、刷怪扫描低分配计数、注册表单探测、效果槽缓存、方块成员索引。
- Konkrete：资源代际反向索引，重复翻译保留首 key。
- Quality Tools、Quark、Rustic、Ice and Fire 与 SRPMixins 保持结果等价优化和独立熔断。
- Mo' Bends 复用已验证父链数组和每实例矩阵缓冲，但 `childModels` 继续使用原 `List.iterator()`，保留自定义 List、Iterator 副作用和 fail-fast 语义。
- OreLib 的 Java GL 状态缓存已删除；bridge 始终返回本次 `GL11.glGet*` native 真值，模块默认关闭，不再把无法完整证明的状态快照当作优化。

## 5. 算法复杂度变化

| 热点 | 原复杂度/成本 | 1.0 |
|---|---|---|
| SRP 取最近目标 | O(n log n) 排序 + 列表分配 | O(n)，tie 保持首项 |
| RenderLib 合并 | O(p × l) `List.contains` | 常规 O(p + l)，异常语义回原路径 |
| 保存计划刻 | O(c × t) | O(t + 输出条目)，每 mutation version 重建 |
| BO4 列偏移 | 每方块最多 O(256) | 每数组 O(256) 预处理，随后 O(1) |
| OTG cache release | O(历史已分配总数组长度) | release O(1)，acquire O(本次请求前缀) |
| Konkrete value→key | 每次 O(n) 反射扫描 | 每资源代际 O(n)，命中 O(1) |
| Rustic 状态组合 | 重复 property transition | 64 个精确连接状态 O(1) |
| BO4 冷/热加载 | 每次文本解析与多 GiB 短期分配 | 首次原解析；强校验命中走二进制加载 |
| Chunk 并发选择 | 静态数量，无法响应运行时压力 | O(window log window) 的 2 Hz 小窗口反馈，任务热路径仅公平 permit |

## 6. 内存、队列与生命周期边界

- 所有 CPU、渲染、网络、压缩和 cache writer 队列固定有界；拒绝发生在调用方仍能执行原路径时。
- Heap、Direct、GPU 三类预算分别记账；结果预算按实际 backing capacity 而非逻辑长度计算。
- 缓存 key 使用完整身份或完整值比较，不把可能碰撞的 hash 当作结果等价证明。
- 世界、资源、GL context 和运行时 generation 变化会使旧任务/资源失效。
- ThreadLocal 在 worker 退出、作用域 finally 或 shutdown 清理；大 backing table 不允许跨世界长期驻留。
- 后台线程均为 daemon、低优先级、按需创建，并尽量清空 context class loader。
- BO4 writer 队列最多 64 项，持久 cache ledger 最多 32768 对/4 GiB；reader snapshot 弱身份表最多 4096 项，弱 payload 回收后重新加载仍核对原 SHA、原始长度与压缩长度。
- OTG ArraysCache 的进程级 retained budget 为 64 MiB；Lycanites 寻路同时限制单搜索、嵌套总项数和上下文深度，超过边界只停用当前可选缓存。
- 客户端 CPU worker 按 `busy + queued` 需求扩容，15 秒空闲退役；关闭超时的旧线程进入 retired gate，完全退出前新 runtime fail-open，避免重载叠加线程池。
- worker 槽采用 `ACTIVE / RETIRE_PENDING / RETIRE_COMMITTED`；submit 可取消 pending，committed 尾部真正死亡前不接受无人可服务的新任务。shutdown 的 interrupt denial 不阻止队列取消、completion 释放和 retired ownership 登记。
- JvmMonitor、ThreadSampler、Chunk SystemPressure、Roguelike monitor、BO4 writer/sweeper 与 Session/Authlib executor 均有进程级 retired gate；旧线程未死亡时宁可停用可选优化，也不叠加同类后台池。
- `BudgetedCache.get` 使用 Caffeine 原子 mapping 返回唯一 canonical value；replacement/eviction 以 Caffeine 权威 weighted size 为准，避免已由 removal listener 释放的竞争失败值仍被调用方使用。

## 7. 漏洞、竞态与隐形 bug 审计

本次已修复的高风险问题包括：

- OTG 数组 cache 外线程释放后，获取线程仍保留租约，导致同一对象进入两个池；改为全局弱身份原子撤销。
- BO4 列偏移模块关闭/熔断后仍读取旧 ThreadLocal cache；模块门移到任何缓存访问之前。
- BO4 cache 路径逃逸、源文件哈希期间变化、data/meta 半写、显式 WriteAll 后旧指纹以及 shutdown 后迟到提交；全部增加 canonical containment、前后指纹、原子文件对验证和 generation 门。
- Chunk save 只按 ChunkPos 去重会让不同 `AnvilChunkLoader` 保存域互相取消；key 增加保存域身份。
- Session Profile worker 修改原 `GameProfile` 或共享 `PropertyMap` 会与客户端/LocalChannel 竞争；改为 worker 私有 query、不可变属性快照和客户端 Tick 防御副本。
- 空 Session 属性仍调用 setter 会永久污染 `hasCachedProperties()`；ASM 同时替换 setter，空结果不写入 Session。
- Roguelike 首次 monitor 基线可能吞掉解析后的文件变化；首次基线若已有 entry 必须 invalidate。
- Roguelike 端点指纹无法识别解析窗口内 A→B→A；增加 WatchService mutation generation，任一事件或迟到事件都推进 cache generation，watch 初始化/扫描失败清缓存并 fail-open。
- Probe 的 calls/total/maximum 分别 `getAndSet` 会把并发 record 拆到不同采样窗口；每个 accumulator 改为同一同步线性化区，drain 复用 primitive snapshot，不在 record 热路径分配。
- Chunk permit 探针虽计算 `demandRemains` 却未参与判定；需求消失现在必回滚扩容，短突发不能逐级抬高 permit。
- Client Worker 判断空闲准备退出时，新 submit 可能看到旧 `liveWorkers` 而不拉起 replacement；pending/committed 状态机关闭该永久滞留窗口，并覆盖 shutdown interrupt denial。
- BO4 sweeper interrupt 被拒绝后仍创建 replacement 会形成两个清扫所有者；旧线程存活即进入 retired gate，新 sweeper 构造/start 失败也保持 owner 终态一致。
- Forge 断线事件可在 Netty 线程触发，直接 `gpuTimer.shutdown()` 会在无 GL context 线程调用 `glEndQuery/glDeleteQueries`；事件现在只发布 handoff，请求由 client/render Tick 串行清理。Query 删除 fatal 延迟到全部 ID/状态终态后传播。
- RenderLib 静态成员表清空元素但保留大 backing array/预算跨世界驻留；增加并发安全 `releaseCaches()` 与 pending release。
- GL Fence/Buffer/Query 异常或超时后对象未摘除、预算未释放；所有退役路径统一 best-effort 清理与预算归还。
- GL context/resource generation 切换后迟到对象重新挂回；发布前再次检查 generation。
- Lycanites/SRP 嵌套 `glNewList`、编译失败仍标记成功、清理失败泄漏新旧批；加入 GL compile scope、error 验证和双侧销毁。
- Xaero begin/end 异常、模块中途关闭、二次 `isFinished` 与 benchmark 重启导致 Query 悬挂；改显式状态机。
- RenderChunk 同位置 `setPosition` 误删 deferred rebuild；只有真实位置/生命周期变化才清理。
- Profiler 看到 ICE wrapper 后把下游 `World.getBlockState` 等真实模组业务归为 ICE；归因改为穿透 bridge 选择真实业务帧。
- Profiler 报告任务与 sampler control task 可在异常压力下堆积；报告队列固定为 4，每代 control task 最多 16，probe subject 也执行硬 clamp。
- 报告根或中间目录可被 Windows junction/reparse point 重定向；固定可信 real/canonical root 与 file key，并在 create/write/move/zip/retention/`.writing` 删除前逐级 `NOFOLLOW` 复核。
- Konkrete 语言 Map 内容同尺寸替换时旧索引 miss 会误报 `null`；miss 回原完整扫描并按当前内容重建。
- SRP 编译缓存与 scratch 可能让长寿命 Chunk Worker 保留历史峰值；增加单列表、每线程总条目/字节硬预算，过大输入回原路径，大 scratch 用后移除。
- 非 ICE RegionFile 线程的 ThreadLocal `Deflater` 会长期保留 native zlib 状态；仅专用压缩 worker 复用，外部租约关闭时必定 `end()`。
- 新枚举项、未知配置和越界数值可能改变运行门；增加 append-only ordinal 回归、全模块显式映射、未知项默认关闭和统一 clamp。
- `catch(Throwable)` 吞掉 `ThreadDeath`/`VirtualMachineError`；优化与记录热路径统一重新抛出 fatal error。
- fatal 检测原先只遍历 16 层 cause；现改为完整、身份环安全的 cause / `ExceptionInInitializerError` / suppressed 图遍历。Optimizer Main、Profiler Main、Optimizer Core 和独立 Profiler Core 边界都覆盖深层包装，fatal 不进入遥测或熔断。
- `getCause()` / EIIE accessor 自身抛出的普通包装异常也作为同优先级分支继续遍历；清理合并中的 `addSuppressed` 自身若抛 fatal，不再被普通 `catch(Throwable)` 吞掉。
- Chunk 压缩在 operation 抛 fatal 的同时发生 cancel/epoch/interrupt 时会把 fatal 当预期取消吞掉；现保留 primary wrapper，任务先进入终态、释放 FILE_IO 等待者，再传播 fatal。
- `CompressedStreamTools.write` 抛 fatal 后，旧 finally 中的 flush/close 取消异常可能覆盖原 fatal；现使用 try-with-resources，cleanup 只作为 suppressed，Deflater lease 仍必定释放。
- Skull executor 初始化失败后，`shutdownNow()` 的 `SecurityException` 可能覆盖原 fatal；现合并 cleanup failure，并由完整 throwable 图规则保证 primary fatal 优先。

安全边界：ICE 不从网络接收可执行代码，不反序列化 Java Object，不执行外部进程，不下载或替换模组。BO4 cache 和报告只写固定游戏子目录，所有生成路径都做 containment；本地用户已拥有同等文件权限，因此恶意本地模组/配置不被视为隔离边界。

## 8. 明确不做的结果敏感修改

- Recurrent Complex `MazeComponentConnector.connect` / `WorldScriptMazeGenerator.getPlacedRooms` 的约 1.96 秒主体是迷宫连接算法，不是 executor 创建。并行化、超时截断或改变搜索顺序会改变 RNG 消耗、房间选择或生成结果；没有逐世界结果等价证明前保持原实现。
- 1.12.2 世界生成、实体 Tick、Forge 事件、Capability attach/clone 和 Chunk/TileEntity 快照构造不异步执行。
- BO3 NBT 解析、LibrarianLib unload 等仅有少量样本且生命周期副作用未完全证明的热点不做猜测性缓存。
- 不通过降视距、删粒子、减模型、跳 Tick、减少刷怪或预生成世界来制造基准提升。

## 9. 实机 A/B 验证口径

使用同一存档副本、路线、视距、JVM 参数、材质/光影、后台程序和采样时长，对关闭 ICE 与开启 1.0 各跑至少三次。必须同时比较：

- Frame P95 / P99 / max，而不是只看平均 FPS。
- 集成服或专服 Tick P95 / P99 / max。
- Chunk compile queue、upload queue、render queue、in-flight 与 permit 数随时间变化。
- permit 扩容探针的参考/观察吞吐、回滚次数。
- GC pause ratio、每秒分配量和堆高水位。
- Chunk save 提交、完成、fallback、FILE_IO 等待与保存吞吐。
- BO4 cold parse、hot hit、校验 miss、写队列拒绝和 cache 体积。
- GPU fallback、Fence/Query timeout、retired slots 与预算占用。
- Roguelike cache hit/invalidate、Session Profile 后台完成/拒绝。

判定标准不是“线程越多越好”，而是在相同结果下 Frame/Tick P95/P99 下降、队列不发散、GC 比例不升高、吞吐有实测收益，并且 fallback/timeout 不持续增长。

## 10. 发布与部署边界

正式输出保持四个互不重叠的 Java 8 JAR：optimizer main/core 与 profiler main/core。optimizer 不包含采样/报告，profiler 不包含优化实现；Main/Core 必须同版，联机双方 optimizer main 必须同为 1.0。

构建完成后必须通过全量测试、真实 Dregora JAR 结构契约、ASM 变换、HotSpot Java 8 define、`verifySplitJars`、重混淆和 SHA-256 固化。部署脚本只有被显式调用且目标目录确实命名为 `mods` 时才复制产物；optimizer-only 脚本只处理 optimizer Main/Core，客户端四包脚本同时处理 optimizer/profiler Main/Core。两者都先验证源哈希并备份旧包，不触碰无关模组、存档或用户 cache。

最终干净验证已于 2026-08-21 完成。先前完整普通基线与 Dregora 组合矩阵为 181 个测试类、674 项测试、0 failure、0 error、1 skipped；后续现代渲染零命中和喝药探针修复后，使用当前机器可用的真实 OptiFine G5、OTG 9.7、Dregora 只读依赖、Minecraft client/Forge SRG 与映射输入执行 182 个测试类、682 项测试、0 failure、0 error、8 skipped。8 项只对应缺失的 Xaero/Better Foliage 运行时样本和六项普通 RLCraft 旧版精确基线；Dregora 专用契约均已执行。发布任务链全部成功。连续两次独立 clean 构建的六个产物逐字节一致；重复 entry、四组类交集、21 组早期 ABI、依赖 relocation 和 bundle 内嵌哈希均通过独立审计。用户批准后，四个新 JAR 已部署到真实 Dregora，旧四包保存在 `rollback/client-Dregora-before-1.0-20260821-234005394`。

| 发布产物 | SHA-256 |
|---|---|
| optimizer main | `3E4217D2657560B5472AEF2CCD2B624DBA3BA3A6DE862B5678589057A50F9571` |
| optimizer core | `280DA95BA947D38C0644A3225D155E6525C434A2E3956A70D90DAE62D36E164F` |
| profiler main | `91C21399B7570B124DE79B8ED222ABC2E84C480E9AD49D5BF4E447FC3407A094` |
| profiler core | `DF95108CAC4CD7C1A270F81BB34A594EF5C3C1734AB3B98CE0747283B70A67C5` |
| optimizer bundle | `9EB94A339E5140003A347CBD9AB83377A91217C102FFDE8F12C2FBB039DCF952` |
| reobfuscated combined-dev | `5A6E4257A767E3940DED072C9D5D8A6EFF37588E3FCA87C6C3ACAF75D8A42826` |

自动验证没有覆盖真实 OpenGL 画面 A/B、ShaderPack 实机图像、随机化 ABBA、new-chunk Frame/Tick/GPU P95/P99/1% low 或长时间 Fence/Query/RAM/VRAM soak，这些仍必须在真实游戏中另行验收。
