# ICE RLCraft Optimizer + ICE Performance Recorder

本工程面向 **Minecraft 1.12.2 / Forge 14.23.5.2860 / Java 8 的 RLCraft 系整合包**，包括普通 RLCraft、RLCraft Dregora 以及结构兼容的旧版和衍生版本。从 `0.4.0` 起工程拆成两个可独立安装和维护的模组：`ICE RLCraft Optimizer` 负责客户端、单人集成服务器和远程专用服务端热点优化，`ICE Performance Recorder` 负责自动卡顿取证、采样和报告导出。优化器针对实测确认的 SRP、Lycanites、Mo' Bends、Ice and Fire、OptiFine、Rustic、FoamFix、Xaero、RenderLib、OreLib / Dynamic Surroundings、Better Foliage、Better Caves、Quality Tools、Quark、Dynamic Trees 与 OTG 热路径提供分侧能力选择、独立熔断和可回退的字节码适配框架。

当前版本：`1.0.5`

发布说明：[ICE 1.0.5 Release Notes](docs/ICE-1.0.5-RELEASE-NOTES.md)

下一代完整方案：[ICE 2.0 数据驱动性能架构](docs/ICE-2.0-ARCHITECTURE-PLAN.md)

它不删内容、不减粒子、不降模型、不跳 Tick，也不异步写世界。优化运行时只允许缓存完全相同输入的结果、把纯 CPU 准备工作交给专用线程，以及在渲染线程按原顺序提交 GPU 工作。`0.8.0` 不再按 RLCraft、Dregora 或单个模组的版本/JAR SHA-256 拒绝运行；每个目标类都由适配器检查所需方法签名和精确指令结构，结构不兼容时只放弃该目标并保留原字节码。

## 1.0.5 模组边界

- `ice-rlcraft-optimizer-1.0.5.jar`：双端优化器主运行时、高性能库和客户端 F3 状态。
- `ice-rlcraft-optimizer-core-1.0.5.jar`：双端共享 transformer、结构适配器、早期安全桥和审计指纹目录。
- `ice-rlcraft-profiler-1.0.5.jar`：独立记录器、F8/F9/F10、命令、指标、分析和报告。
- `ice-rlcraft-profiler-core-1.0.5.jar`：只包含只读性能探针 transformer。

两套主 JAR 和两套 Core JAR 均经过构建期重复 class 与越界内容检查。只安装优化器时不会创建采样线程、不会注册 F8/F9/F10、不会导出 Session，也不会显示常规 HUD。

`1.0.5` 要求联机双方安装同一版 optimizer 主 JAR，以避免客户端和服务端运行时协议不一致；这只是 ICE 自身的 Forge 握手要求，不限制 RLCraft 或其他模组的版本。专服只启用可在物理服务端安全执行的 SRP AI/寻路与刷怪过滤、Lycanites 寻路/注册表/刷怪/效果、Ice and Fire 单次寻路缓存与粒子暂存、区块 NBT 压缩、Rustic 栅栏状态、Better Caves、Quality Tools、Quark 和 OTG/BO4 模块；模型渲染、OptiFine、OpenGL、区块网格、Xaero、头颅联网及客户端工作池在物理服务端均保持关闭。

`1.0.5` 修复 HZB 与 ChunkAnimator 1.2.1 的动态坐标冲突：尚在动画中的区块强制保持可见并通过真实 `ChunkRenderContainer.preRenderChunk` 绘制，稳定区块仍参与 Arena multi-draw/MDI。普通遮挡必须由两个连续、独立的深度发布确认；Arena 在 GPU 提交前拒绝时会事务恢复完整 Legacy 列表。确认表会按堆预算缩容而不让整个现代渲染器回退，报告同时给出原始遮挡、确认延迟、动画绕过、事务回滚和容量退化。

`1.0.4` 针对 1.0.3 实机会话修复现代渲染资格链：Terrain 认证阶段优先让 SOLID 占用影子 Arena，TESR 不再因无关 FBO/PBO 状态缺口被误隔离，粒子首次验证不再链接合成可选依赖，HZB 将区块几何代际与相机稳定历史分离并直接采纳已通过源深度 oracle 的发布结果。Entity/HUD 的实测候选路径仍明显更慢，因此继续由收益门保留原渲染；这不是兼容性回退，也不会为了提高“现代命中率”而强制启用反优化。

`1.0.1` 在 Fermium 最终改写 worker 与 builder 数量之后再施加固定安全上限，不替换 Fermium 策略；Chunk Worker 不按逻辑处理器、最大堆、CPU 型号或核类型分档。区块 VBO 只有达到 256 KiB 才尝试 GPU staging，每次最多探测两个 Fence 槽；小上传、GPU 落后、预算不足或能力缺失直接执行原 `glBufferData`。动画纹理的通用单级 PBO 已停用，避免不同显卡驱动上出现每 mip 一个 Fence 的反优化；FoamFix 只有完整 mip 批次达到 256 KiB 时才尝试 PBO。

普通 RLCraft 的 Better Caves 会在单个区块生成中极高频调用 NoiseTuple/NoiseColumn 门。`1.0.1` 将所有模块热路径统一为稳定 ordinal + 单次 volatile bit-mask 读取；关闭、熔断或结构失配仍会立即刷新 mask 并回退原逻辑，不会把优化固定为开启。

`1.0.1` 保留动画纹理入口的早期类加载隔离：Core 改写后的 `TextureUtil` 只依赖 Core 内自包含引导桥；主 optimizer 尚未进入 Forge pre-init 时无条件执行原上传，运行时就绪后才安装无逐次反射开销的 MethodHandle 委托。因此不会因为主 JAR 类尚不可见而在帧缓冲初始化阶段崩溃。

F3 状态不再把“字节码已安装”误写成“实际生效”：`CORE` 显示 Core JAR 是否存在，`PATCH` 表示结构补丁已安装，`HIT` 只统计真正执行过优化分支的模块，`MISS` 表示已观察到但至少一项结构能力未安装；区块行同时显示原版/有效 Worker、构建器数量、已排序四边形、`GL31-COPY` / `ARB-COPY` 后端和上传/回退次数。独立的 `ICE Terrain` 行显示 Arena/Legacy 实际绘制、multi-draw/MDI 提交及当前最高频回退原因，因此不能再把“适配器已安装”误当成现代地形已经接管。Core JAR 缺失时还会在进入世界后发送一次红色提示。

2026-08-21 的后续真实 Session 证明此前 Arena/MDI 为零并非 GPU 本身不支持，而是 LWJGL 2 对多值 `glGet*v` 缓冲统一要求至少 16 个剩余元素，旧状态捕获只分配了逻辑结果所需的 2 或 4 个。`1.0.1` 现在同时修复 FBO 沙箱和启动/HUD 状态工作区的 integer/boolean/float 查询，并把 Timer Query 自测的异步退休窗口单独放宽到有界 250 ms。OTG 设置与 NBT 缓存首次或目录变更后才做真实路径、属性和 SHA-256 认证；稳定热命中只使用规范化逻辑路径、WatchService 内存序列和配置代际，不再调用 canonical/toRealPath/readAttributes 或打开文件。文件变化、同大小同 mtime 重写、配置换代或监视不可用仍会失效或 fail-open。

本版本根据 0.9.4 新采样继续处理实测反优化和主线程热点：Konkrete 本地化值查询由逐次反射加全 Map 扫描改为资源代际反向索引；区块 NBT 仍由主线程生成完全相同的快照，只把序列化与 Deflate 交给 1–4 个有界专用 Worker，原 FILE_IO 线程按原 Map 顺序写 RegionFile；OptiFine 的区块光照与侧面遮挡 Reflector 调用在结构匹配时改为等价 Forge 虚调用。任一队列、目标结构、世界代际、内存预算或调用异常不满足时执行保留的原方法。

`0.6.5` 针对 Dregora 跑图卡顿新增 OTG/BO4 精确优化：阻止已解析 BO4 在生成期间被无意义地整文件回写；同一次 `trySpawnAt` 复用第一份方块对象数组；把 `loadBlockArrays` 中每个方块一次的 16×16 前缀扫描改为同数组身份下的 256 项前缀表；配置函数参数改用结果等价的低分配解析器，函数名 lowercase 使用最大 128 项、默认 Locale 变化即清空的 Caffeine 缓存。优化器不会生成 `.BO4Data`、不会预读约 1.9 GiB 预设，也不会跨结构生成缓存会被随机方块逻辑修改的 `BO4BlockFunction` 对象。

同一版本把 `LayerCustomHead` 中不完整玩家头颅资料的 Authlib 联网移出渲染线程：画面先按原版默认皮肤继续绘制，单线程有界队列在后台取得资料，后续帧自动使用结果。正/负缓存、队列和 in-flight 去重均有硬上限；重配置代际会丢弃尚未结束的旧请求，失败只进入短期负缓存，不会退回渲染线程同步联网。

`0.6.4` 修复 RenderLib 版本间的泛型语义差异：1.2.8 的 `processTileEntities` 接收 `Consumer<List<TileEntity>>`，而 Dregora 1.4.5 的同名方法接收 `Consumer<TileEntity>` 并委托给内部 `processTileEntityList`。适配器现在通过精确调用图选择各自入口；Dregora 路径从 `ITileEntityHolder.getTileEntities()` 取得原可见列表，外层逐方块实体 Consumer、处理标志、待加入列表与通知顺序保持原样。

`0.6.3` 修复 Dregora 的 Lycanites OBJ/VBO JVM 栈帧校验冲突：适配器不再重算未修改方法的 `StackMapTable`，而是逐字保留现有 CoreMod/Mixin 转换链生成的精确类型，只为新增缓存包装方法写入自己的确定帧。真实回归现在会让 HotSpot 定义并校验全部已捕获 Dregora 目标类，而不再只检查 ASM 文件结构。

`0.6.2` 修复 Dregora 启动阶段的 Forge 1.12 `LaunchClassLoader` 负缓存冲突：CoreMod 只在内存中记录早期补丁状态，直到优化器主运行时显式就绪后才回放；主注册表会先通过正常模组类路径初始化。所有注入桥接也增加失效开放边界，运行时类尚不可见时直接执行目标模组原逻辑，不再让 Lycanites 或其他已适配模组因优化器状态查询而中止启动。

## 当前优化实现状态

已经完成并通过测试的底层能力：

- 每模块独立开关、状态机和连续错误熔断。
- 不限制 RLCraft、Dregora 或目标模组版本；普通版与衍生版统一按目标类结构判断。
- 类 SHA-256 仅作为已验证样本的审计信息，不参与运行放行；未知 SHA 只要结构满足同样可以转换。
- 专用低优先级工作线程池、固定容量 CPU 队列和 Agrona MPSC 渲染队列。
- `frameId/clientTickId/worldGeneration/resourceGeneration/glContextGeneration` 代际取消。
- 堆、Direct、GPU 三类硬预算，以及 Caffeine 带权缓存、Agrona 原始类型表和 LZ4 无损冷存储。
- 常规画面完全隐藏优化器界面；仅在原版 F3 调试画面右侧显示 Core、实际命中、区块流水线和有界队列摘要。

普通 RLCraft 与 Dregora 的真实 JAR 仍用于回归测试和记录已知指纹，但不再形成四套整合包 profile，也不会因名称、版本或 JAR SHA 不同而全局关闭优化。运行时先按物理 side 禁用不可能安全加载的模块，再由每个目标适配器独立验证方法描述符、字段和调用图；某一个类发生结构变化只会让该类 fail-open，不影响其他可兼容目标：

- 原版区块渲染：根据逻辑处理器数量和 JVM 最大堆为客户端/集成服务器保留处理能力，低配机从 1 个 Worker 起，24–31 线程平台最多 12 个，32+ 平台最多 16 个，且永远不超过原实现；默认构建器池从原版每 Worker 十个收敛为四个。透明四边形以与 `Arrays.sort(Object[], Comparator)` 相同的稳定降序重新排列，但距离、NaN 比较、相等顺序和顶点位完全相同。VBO 上传只在渲染线程提交，支持核心与 ARB copy/sync 组合，GPU staging Fence 不等待，任何不支持或繁忙状态执行原上传。
- SRParasites 13 个热点模型：`ModelEsor`、`ModelMudo`、`ModelNuuh`、`ModelJinjo`、`ModelBanoAdapted`、`ModelInfVillager`、`ModelInfEnderman`、`ModelInfHorse`、`ModelInfHuman`、`ModelCruxA`、`ModelAlafha`、`ModelNogla` 与 `ModelKirin` 均使用自适应分支批处理。每个关节自身的 offset、旋转点和旋转角始终逐实体、逐帧实时读取；只有连续稳定至少四次、包含至少三个可见节点的后代分支才编译为有 GPU 硬预算的外层 Display List。每次提交前重新校验后代变换、显隐、原 Display List、compiled 状态、子节点身份/顺序和 scale 原始位；任一变化当次立即精确遍历原树，并冷却 40 次调用后才重试。
- SRParasites 寻路：`EntityParasiteBase` 使用行为等价的 `PathNavigateGround`，只在一次 `PathFinder` 生命周期内用 Agrona 原始类型表复用 vanilla `WalkNodeProcessor` 的原始/邻域节点分类；`postProcess` 后立即清空，不跨寻路、不跨 Tick，也不缓存路径结果。
- SRParasites 目标搜索：`EntityAINearestAttackableTargetStatus` 中“完整排序后只读取第一个”的私有候选表改为稳定线性最小值选择。比较器相等时仍选择原列表中最先出现者，实体筛选、可见性、仇恨和最终目标设置逻辑不变。
- 原版全量保存：`WorldServer.getPendingBlockUpdates(chunk, false)` 不再为每个脏区块重复遍历全局计划刻集合；一次同步 `saveChunks(true)` 内按变更版本建立临时只读索引。计划刻、区块、NBT、事件和保存顺序不变，不移除条目、不异步访问世界；任何集合变更、重入失配或结构异常都会重建或执行原逐区块扫描。
- 原版区块压缩：服务端主线程仍完整构造 `NBTTagCompound` 快照；1–4 个按 CPU/堆自适应的有界专用 Worker 只执行 NBT 序列化和 zlib Deflate，原 FILE_IO 线程等待对应结果并按原 pending Map 顺序写 RegionFile。队列满、结果超过 16 MiB、世界代际变化、Accessor 缺失、关闭取消或压缩错误时立即使用原压缩流；取消任务会释放等待者，不会在世界关闭时卡死。
- Lycanites 寻路：`CreatureNodeProcessor` 的 2 个原始节点分类调用和 14 个方块状态调用只在当前一次寻路内按坐标复用，支持嵌套搜索；生命周期或缓存异常时当前上下文立即停用并走原方法。
- Lycanites 注册表：`ObjectManager.getEffect/getBlock` 从 `containsKey + get` 两次 `HashMap` 探测改为一次 `get`。普通 RLCraft 2.0.8.9 保留原 `toLowerCase()`；Dregora 2.0.8.10 保留原精确、区分大小写 key，配置关闭时两者都恢复各自原双探测路径。
- Lycanites 刷怪扫描：`BlockSpawnLocation` 保留原 Y/X/Z 遍历、流体高度、方块白/黑名单、候选顺序与最终排序/RNG；方块种类计数表首次需要时才分配。只有精确已审查基类和无中间可见副作用的普通方块路径会复用同一坐标的只读状态，子类、流体、自定义 World 或可变列表立即使用原读取。
- Lycanites OBJ/VBO：`TessellatorModel` 与 `VBOModel` 的稳定分组以模型、网格身份、颜色原始位、UV 原始位和 `VertexFormat` 为完整 key，连续观察三次后才进入受 GPU 硬预算约束的 Display List；资源或 GL 代际变化、网格身份/长度/VBO 变化、变体过多、预算拒绝或异常都会立即走原渲染方法。
- Lycanites 模型动画：四个 Animator GL 转发方法跳过数学上完全等价的恒等旋转、平移和单位缩放；`ModelObjPart` 保留父级、offset、中心变换和帧应用顺序，只把 Iterator 改成索引循环；动画帧类型以公开 `type` 字段身份校验后走 `tableswitch`；三个模型基类的 10 个 `toLowerCase()` 调用进入最大 4096 项、Locale 变化即清空的 Caffeine 缓存。
- Lycanites 实体效果：`PotionEffects` 中 35 个常量 `ObjectManager.getEffect` 调用、20 个唯一效果名进入原子槽缓存；公开注册表第一次仍真实解析，空值也显式编码。附近实体筛选 Predicate 按目标类型复用，事件、实体 Tick 和效果判定次数不变。
- Mo' Bends 通用模型：优化公共 `ModelPart`，因此覆盖它接管的玩家、原版怪物和动物，而不是只针对某一种怪物。父链拓扑逐次验证后复用，pre/local transform 的先后和 scale 传播完全保持；子模型仍按原列表顺序绘制，只去掉 Iterator 分配。
- Mo' Bends 四元数与攀爬：每个 Quaternion 持有自己的 16-float 矩阵缓冲，四个分量以 raw float bits 校验，任一变化立即重算；非梯子或已落地实体在三个方块状态读取前返回与原方法相同的 false，真正攀爬时仍完整执行原判定。
- Ice and Fire：Dregora 2.0.9 的 `ExperimentalWalkNodeProcessor` 在一次同步 `PathFinder` 生命周期内用 primitive 坐标表复用方块状态和原始节点分类；缓存不跨搜索、不异步读取世界，并按处理器与 `IBlockAccess` 身份隔离。Tabula `moveToPose` 每个部件把重复五次的 `getCube` 收敛为两个局部值，旋转差值、部件遍历和提交顺序不变；海蛇粒子适配同时覆盖 1.7.1 的 `int[0] + int[]{0}` 与 Dregora 2.0.9 的两个 `int[0]` 调用图，不减少粒子。
- FoamFix `FastTextureAtlasSprite.uploadTextureMaxMips`：0.9.4 记录证明通用单级 PBO 的固定 Fence 成本会形成 42–45% 渲染线程税，因此单 mip `TextureUtil` 入口现在始终走原上传。只有完整 mip 批次达到 256 KiB 才允许使用三槽 PBO；小批次、槽位仍忙、GPU 预算不足或不支持 PBO 时立即执行未修改的 FoamFix 路径，不等待 GPU。
- Xaero World Map `TextureUploader`：保留原上传对象、池、预算、队列与顺序，把初始化阶段 7 类纹理共 2560 个 `glFinish` 同步基准替换成 32 对非阻塞 GPU Timestamp Query。查询结果只有在驱动报告 available 后才读取，样本仍采用 GPU 时间与 CPU 提交时间的较大值，并保留原来的 512/256 样本目标与默认估计。
- RenderLib `TileEntityUtil.processTileEntities`：在已加载方块实体不少于 64、待合并不少于 4 时，使用受 Heap 硬预算限制的可复用 Agrona 成员表替代 `pending × loaded` 线性扫描。只有保持 `Object.equals/hashCode` 身份语义的对象才使用哈希查询；自定义相等语义、预算不足、列表发生非预期变化或重入时逐项回退原 `List.contains`。
- OreLib `OpenGlState`：原构造函数每次为 Dynamic Surroundings 粒子集合、弹出文字、语音文字、极光、天气渲染、HUD 与生成纹理同步读取 16 个驱动状态。ICE 从 Minecraft `GlStateManager` 的同一 Java 状态机读取 13 项，始终保留真实 `GL_BLEND`、`GL_BLEND_EQUATION_RGB` 与当前活动纹理单元 `GL_TEXTURE_2D` 三个查询。首次及每 32 次快照执行一次完整真值校验；任何缓存失配、映射变化或反射错误都会在当前快照使用真实值，并让本模块在本次启动永久回退原查询。
- Better Foliage / OctarineCore `AoFaceData.update`：保留原 `AmbientOcclusionFace.func_187491_a`、六个方向、亮度、AO 倍率和结果复制顺序，只把每个面新建的 `float[12]` 与 `BitSet(3)` 改为对应 Chunk Worker 私有 `ModelRenderer` 内六个 `AoFaceData` 实例的暂存字段；每次调用前清空 BitSet，模块关闭时仍执行原分配。
- Better Caves：`NoiseTuple` 的装箱 `ArrayList<Double>` 热存储改为 `double[]` 并提供实时兼容视图；`NoiseColumn` 的正常 0–255 高度改为连续数组；`NoiseGen` 用 64 槽精确位置缓存复用重复角点列并以深复制隔离调用方，四段链式 `times + times + plus` 合并为一次等价 blend；`CaveCarver` 每列阈值 `HashMap<Integer, Float>` 改为保持相同 float 位结果的连续 Map。缓存命中比较完整坐标和高度范围，不使用可能碰撞的散列替代身份，也不跨不安全生命周期复用。
- Better Foliage + OptiFine：`OptifineCustomColors` 不再在每个区块网格方块上通过 Kotlin/Java 反射读取同一布尔字段，改为按运行时类缓存字段访问器。字段缺失、访问失败或结构不兼容时保持原路径。
- Quality Tools：普通非玩家、非马生物只在装备槽内容实际改变时重新拆除并安装 Quality Tools 属性修饰；首次观察和每 140 Tick 强制执行原检查，玩家与马始终保持原调用频率，装备变化当 Tick 生效。
- Quark：`ItemsFlashBeforeExpiring` 的 age/lifespan 状态从两个装箱 `WeakHashMap<EntityItem, Integer>` 合并为一个可变弱状态，保持 Quark 在 age 或 lifespan 异常变化后持续请求同步的原状态机，不改变闪烁、寿命或网络语义。
- Dynamic Trees `BakedModelBlockBranchBasic.pollConnections`：首次面查询仍按原顺序读取六个 `IUnlistedProperty<Integer>` 并执行相同 clamp；同一 Chunk Worker 随后对同一模型、半径和不可变 `IExtendedBlockState` 查询其他面时，复用这一个只读 `int[6]`。状态、模型、半径或线程任一变化都重新执行原方法。独立的 Cactus 模型会修改返回数组，因此明确不进入该缓存。
- Open Terrain Generator / BO4：Dregora 实际预设含 16618 个 BO4、约 1.77 GiB，且没有预生成 `.BO4Data`。ICE 只优化当前按需读取和生成路径：关闭运行时 BO4 源文件回写、复用同一次生成中的重复方块数组、把每方块列偏移扫描收敛为一次前缀表，并降低配置函数解析分配；文件内容、结构方块顺序、随机数调用和世界写入顺序均不改。
- 玩家头颅：`LayerCustomHead` 不再在实体/刷怪笼或光影阴影渲染途中同步等待 HTTPS。最大 2048 项正/负缓存、128 项队列和单工作线程负责最终资料解析；缓存未命中、断网或队列满时当帧继续使用原输入资料/默认皮肤，不阻塞画面。
- SRPMixins 刷怪过滤：把保持原顺序的包装条目编译为连续数组，同一次过滤中的 parasite 状态、colony 点数和分类 mob cap 只读取一次；动态配置、玩家数量、世界状态和最终限制仍逐次读取，缓存重置或源列表变化立即失效。
- Lycanites 方块成员判断：`BlockSpawnLocation.blockIds` 的大列表使用可跟踪成员索引；列表被增删改清空或整个字段被其他模组替换时增加代际并重建，小列表、未知列表结构和异常路径保持原 `contains`。
- Konkrete：`LocaleUtils.getKeyForString` 按当前语言 Map 身份建立一次反向索引，重复翻译仍返回源 Map 遍历遇到的第一个 key；资源重载、未知 Map、竞争或异常时执行保留的原反射扫描。
- Forge / OptiFine 区块光照：只有 `ReflectorForge.getLightValue`、`StateImplementation.getLightValue` 和 `doesSideBlockRendering` 的参数与 Reflector 调用图完全匹配时，才调用相同 Forge 虚方法；普通 Forge 已经直调的类会跳过，原反射方法始终保留为 fallback。

目标目录目前包含 66 个唯一类、68 个独立能力项；同一个 `ChunkRenderDispatcher` 可依次尝试线程策略和 VBO 上传，两者互不连带。类名只用于找到明确目标，真正的执行门是每个适配器内部的字段、方法描述符和调用图检查；完整 SHA-256 只标记“已审查样本”。输入异常、单项结构变化、预算不足、未知显卡能力、生命周期失配、GL 缓存真值不一致或运行时熔断时只会保留对应能力的原实现，后续独立能力仍继续验证；未在目录中的类不会被猜测适配。

SRP、Lycanites 与 Ice and Fire AI/寻路优化只会作用于当前 JVM 实际运行的逻辑：单人游戏由客户端进程内的集成服务器受益；多人游戏由安装了同版 ICE 的远程专用服务端受益。SRP、Lycanites、Mo' Bends 与 Ice and Fire 的模型渲染优化仍只在客户端生效，服务端不会加载任何客户端渲染模块。

## 优化器磁盘写入边界

`0.6.1` 起，`settings.developmentDiskOutput=false` 是默认值。只安装优化器时不会建立 Session、不会持续采样、不会写世界或 `saves`，也不会创建或更新 `ice-optimizer/discovery`、`ice-optimizer/components-observed.properties` 或服务端的 `components-observed-server.properties`。Forge 自己仍会维护很小的 `config/ice-optimizer.cfg`，Minecraft 也仍会写正常的 `logs/latest.log`；未知目标最多在每次启动记录一条兼容性警告，不会逐帧或逐 Tick 输出。

已有旧版 `ice-optimizer` 目录不会被自动删除，也不会继续增长。只有开发适配新结构时才应把 `config/ice-optimizer.cfg` 中的 `settings.developmentDiskOutput` 改为 `true` 并重启，或添加 JVM 参数 `-Dice.optimizer.developmentDiskOutput=true`。开启后才会导出未知类样本和一次诊断组件清单；该清单只帮助开发定位来源，不会成为运行限制。

## 为什么数据不会失控

ICE 不逐实体、逐 Tick 写日志。运行时数据采用四级压缩：

1. 普通指标每秒聚合成一个时间点。
2. 原始线程样本只保存在固定容量环形缓冲中，写满后覆盖最旧数据。
3. 完全相同的调用栈只保存一次，样本只引用一个数字 ID。
4. 同一根因的重复卡顿会聚类，只保留最严重的少量代表样本。

单次捕获还会把总样本预算分成触发前保护区和触发后保护区：触发前最多使用 40%，且只保留最接近触发时刻的部分；剩余容量用于触发后窗口。每个阶段再按线程角色加权，客户端/服务端主线程不会被大量 Worker 挤出。报告会写出实际前后覆盖时间和丢弃数量，便于判断证据是否完整。

默认硬边界：

| 数据 | 默认上限 |
| --- | ---: |
| Profiler 目标内存预算 | 64 MiB |
| 每秒时间线 | 3600 点 |
| 滚动线程样本 | 20000 个 |
| 唯一调用栈 | 16384 个 |
| 根因聚类 | 32 类 |
| 每类代表捕获 | 3 个 |
| 全会话详细样本 | 50000 个 |
| 单会话报告软上限 | 25 MiB |
| 磁盘保留会话 | 20 个 |

内存预算调低时，调用栈、滚动样本和详细样本上限会同步收紧。报告超过软预算前会先取消可选的详细 JSON；旧 Session 自动按保留数量清理。因此用户默认面对的是一份直接结论，而不是海量原始日志。

## 采集内容

### 客户端

- 帧时间与客户端 Tick 的平均值、P50、P95、P99、最大值。
- FPS、区块重建队列、GPU timer query（显卡支持时异步读取）。
- 客户端已加载区块、实体、方块实体。
- 客户端主线程、Chunk Batcher、I/O、Netty 和工作线程调用栈。
- 网络包数量及能够在 Netty 管线看到的压缩后字节数。

### 服务端

- MSPT 的平均值、P50、P95、P99、最大值。
- 每个维度的区块、实体、方块实体 Gauge。
- 区块 Load/Unload、NBT Load/Save 每秒计数。
- 服务端主线程、区块 I/O、文件 I/O、Netty 与工作线程采样。

### JVM

- 堆已用、已提交、最大值。
- GC 次数与暂停时间增量。
- 进程 CPU 使用率。
- JVM 支持时的目标线程 CPU 时间和分配字节增量。

MXBean 与原生进程 CPU 查询由独立最低优先级线程执行；Minecraft 客户端和服务端线程只读取最近一次缓存快照。

## 自动捕获和根因结论

默认绝对触发阈值为客户端长帧 `80 ms`、服务端长 Tick `75 ms`、GC 暂停 `50 ms`。同时使用“中位数 + MAD + 相对倍率”的自适应阈值，稳定的低 FPS 限制不会被每帧误判为卡顿。

每次触发保留触发前 `15 秒`、触发后 `5 秒` 的调用栈窗口。三秒内的连续触发合并为同一事件。分析器按调用栈、线程角色、阻塞状态、CPU/分配增量和触发类型归类：

处于 `WAITING/TIMED_WAITING` 且停在 park、空队列 take/poll、select 或 Chunk Worker 等待入口的后台线程会被标记为空闲，不参与热点根因排名；其原始样本仍保存在 `.icecap` 中。

默认不需要手动按 F9。ICE 平时只维护有界的被动缓冲；发现卡顿后会自动建立事件会话、回填前 15 秒、记录后 5 秒，再等待额外 2 秒确认没有连续卡顿，随后自动关闭并在后台导出。第一份报告通常在最后一次触发约 7 秒后开始生成；自动报告最短间隔为 30 秒，间隔内的新卡顿仍会继续记录和聚类，避免卡顿密集时产生大量小报告。

- 世界生成、区块读取、区块保存、光照。
- 实体 Tick、方块实体 Tick、AI/寻路、碰撞。
- Forge 事件监听器、网络解包、资源加载。
- 区块网格构建/上传、客户端渲染。
- GC、CPU 饱和、线程阻塞/锁竞争。

最终报告按照下面的顺序直接给出结论：

```text
类别 → 模组 → 类/方法 → 证据 → 置信度 → 针对性建议
```

类到模组的归属通过 Forge ModContainer 来源和 class 资源所在 JAR 进行，只读取来源，不加载或执行被分析类。

## 安装

### 推荐：客户端与服务端都安装优化器 1.0.5

将下面两个文件同时放入 RLCraft 客户端实例和对应专用服务端的 `mods` 目录：

```text
ice-rlcraft-optimizer-1.0.5.jar
ice-rlcraft-optimizer-core-1.0.5.jar
```

Forge 握手要求客户端与服务端的 ICE optimizer 主 JAR 同为 `1.0.5`；Core JAR 不参与模组列表握手，但两端都必须同时安装，否则对应端不会获得字节码优化。必须先移除所有旧版 optimizer/profiler Main/Core JAR，避免重复模组入口和 transformer；不能混装不同版本。

关闭 Minecraft 后也可在工程目录运行固定校验 SHA-256、会先备份旧 ICE JAR 的部署脚本：

```powershell
.\tools\deploy-client-1.0.5.ps1 -Pack Dregora
# 专用服务端只部署 optimizer：
.\tools\deploy-optimizer-1.0.5.ps1 -Target Server -ModsDirectory "D:\path\to\server\mods"
# 普通版客户端使用：-Pack RLCraft
```

客户端脚本会统一替换 optimizer/profiler Main/Core 四包，适合从重复安装或混装状态恢复；专服脚本只替换两个 optimizer JAR。两者都不删除 `ice-optimizer` 旧采集文件，也不接触配置、历史 Session 或任何存档。若客户端不需要记录器，可改用 optimizer 脚本的 `-Target Client`，但必须自行确认 `mods` 中至多保留一组同版本 profiler Main/Core。

### 可选：安装独立记录器

需要重新采集卡顿证据时，再额外安装：

```text
ice-rlcraft-profiler-1.0.5.jar
ice-rlcraft-profiler-core-1.0.5.jar
```

记录器可以单独维护和升级。主 JAR 提供有界采样、自动触发、根因分析、F8/F9/F10 和报告；core JAR 只提供精确只读计时探针。普通自动记录不依赖 profiler core，但深度探针需要两者一起安装。

### 深度探针模式

安装记录器的 profiler core JAR 后，执行 `/iceprofiler deep on` 或 `/iceprofilerclient deep on`，可按类/监听器聚合：

- 实体更新包装调用。
- 实现 `ITickable` 的方块实体 `update`。
- `IChunkGenerator` 的生成与 populate。
- Forge `ASMEventHandler` 的具体监听器。
- 区块保存与客户端区块重建。

喝药水完成边界属于低频常驻精确探针，不需要开启全局深度模式。完成一次饮用后，`probes.csv` 会分别出现 `item_use_finish`、`potion_item_finish`，以及该边界内实际执行的 Forge 事件监听器；探针只计时，不跳过或异步执行药水逻辑。

两个 core JAR 不混装转换器：profiler core 只包含 `IceProfilerTransformer`，optimizer core 只注册双端 `IceOptimizerTransformer`（旧类名仅保留为开发兼容入口）。每个优化变换必须通过对应物理 side 和适配器的结构检查；类 SHA 或整合包版本不参与放行。任一结构条件不满足都执行原路径。未知目标默认只警告并保留原字节码；只有显式开启 `developmentDiskOutput` 才会在 `ice-optimizer/discovery` 中保存开发样本。

优化器不再提供 F10 面板。F3 显示 Core 是否存在、实际命中/安装补丁数量、区块 Worker/排序/GPU 后端、现代地形 Arena/Legacy/MDI 实际提交及 CPU/渲染队列；适配器安装与结构兼容性拒绝仍写入 `logs/latest.log`。`INCOMPATIBLE`、`TRIPPED` 或 `DISABLED` 都会保留对应目标的原实现。

## 快捷键和界面

只安装优化器时：

- 普通游戏画面没有 ICE HUD。
- F8、F9、F10 不由优化器占用。
- 只有打开原版 F3 时，右侧追加 `ICE Opt`、`ICE Chunk`、`ICE Terrain` 和 `ICE Q` 摘要；`ICE Terrain` 的 Arena/Legacy/MDI 计数来自真实提交点，不是启动期能力推测。`config/ice-optimizer.cfg` 的 `display.showF3Summary=false` 可全部关闭。

以下快捷键只属于可选的独立记录器：

- `F8`：可选备注；即使自动阈值没有触发，也会建立一次会自动关闭的事件捕获。
- `F9`：仅在想主动录制一整段路线时使用；再次按下后停止并导出。
- `F10`：打开记录器 Dashboard，查看客户端、服务端、JVM 和根因聚类。

F8 和 F9 都不是日常自动诊断的必需操作。安装记录器后，它的 HUD 显示当前录制/捕获状态、帧 P95、MSPT P95、GC 和最近根因；F10 Dashboard 不暂停游戏。

自动卡顿捕获默认静默运行，不会在聊天框发送“捕获完成”消息。HUD、F10 Dashboard 和后台报告导出不受影响。需要恢复聊天提示时，可在 `config/ice-profiler.cfg` 中将 `client.silentAutomaticRecording` 改为 `false`。

## 命令

服务端或单人集成服务器：

```text
/iceprofiler status
/iceprofiler start [说明]
/iceprofiler stop
/iceprofiler mark [说明]
/iceprofiler export
/iceprofiler list
/iceprofiler compare <会话A> <会话B>
/iceprofiler deep <on|off>
/iceprofiler reload
```

`status` 和 `list` 可普通查询；改变录制状态、深度模式和配置需要管理员权限。

纯客户端命令：

```text
/iceprofilerclient status|start|stop|mark|export|dashboard
/iceprofilerclient deep <on|off>
```

客户端别名为 `/iceclient` 和 `/icec`；服务端/集成服务器命令别名为 `/iceperf` 和 `/iceprofile`。

## 报告

报告写入实例目录：

```text
ice-profiler/sessions/<session-id>/
```

每个 Session 包含：

- `summary.txt`：中文直接结论与建议。
- `optimizer-renderer.txt`：若同一实例装有 optimizer，则记录现代渲染生命周期、Arena/Legacy 上传与绘制、multi-draw/MDI、HZB、模型捕获/逐原因回退、Heap/Direct/GPU 预算、ResourceLedger，以及 Legacy/ICE Native/OptiFine Region 的累计 CPU/GPU 归因；optimizer 不存在时该文件明确标记不可用。
- `timeline.csv`：每秒客户端、服务端、JVM、世界、区块、渲染和网络指标。
- `hitches.json`：聚类、置信度、证据和代表捕获元数据。
- `probes.csv`：可选精确探针的类/监听器聚合。
- `stacks.folded`：可直接用于火焰图的代表调用栈。
- `<session>.icecap`：gzip 压缩的版本化二进制捕获。
- `report.html`：无需联网的自包含报告。
- 同名 `.zip`：便于发送给开发者的导出包。

默认不记录世界种子、玩家名和精确坐标；报告也不写绝对游戏/世界路径。

## 从诊断到优化

建议用同一条跑图路线做两次录制：第一次确定类别、模组和方法；只为这个方法编写保持语义的优化；第二次用 `/iceprofiler compare` 比较 P95、最大值、堆占用和触发次数。独立优化器必须遵守：

- 不删除内容、不跳过原有 Tick、不改变掉落/生成/AI 结果。
- 只缓存纯函数或只读结果；世界状态写入仍在正确主线程。
- 任何异步化都必须证明线程安全和保存顺序不变。
- 每个优化器可单独关闭，并用 ICE 报告验证收益和回归。

详细实现见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 构建与验证

要求 Java 8：

```powershell
.\gradlew.bat clean test
.\gradlew.bat build
```

如需同时对真实目标 JAR 执行审计指纹、结构适配、ASM 注入、调用图和 JVM 类定义验证：

```powershell
.\gradlew.bat clean build `
  -PoptifineJar="D:\path\to\OptiFine_1.12.2_HD_U_G5.jar" `
  -PrusticJar="D:\path\to\rustic-1.1.7.jar" `
  -PfoamfixJar="D:\path\to\foamfix-0.10.15-1.12.2.jar" `
  -PxaeroWorldMapJar="D:\path\to\xaeroworldmap-forge-1.12.2-1.44.2.jar" `
  -PrenderLibJar="D:\path\to\RenderLib-1.12.2-1.2.8.jar" `
  -PsrpJar="D:\path\to\SRParasites-1.12.2v1.9.11.jar" `
  -PlycanitesJar="D:\path\to\lycanitesmobs-1.12.2-2.0.8.9.jar" `
  -PmoBendsJar="D:\path\to\MoBends_1.12.2-1.2.1-19.12.21.jar" `
  -PiceAndFireJar="D:\path\to\iceandfire-1.7.1-1.12.2.jar" `
  -PllibraryJar="D:\path\to\llibrary-1.7.20-1.12.2.jar" `
  -PoreLibJar="D:\path\to\OreLib-1.12.2-3.6.0.1.jar" `
  -PbetterCavesJar="D:\path\to\bettercaves-1.12.2-2.0.4.jar" `
  -PbetterFoliageJar="D:\path\to\BetterFoliage-MC1.12-2.3.3.jar" `
  -PbetterFoliageRuntimeClass="D:\path\to\mods_octarinecore_client_render_AoFaceData-4b797522a5a1c268.class" `
  -PdynamicTreesJar="D:\path\to\DynamicTrees-1.12.2-0.9.29.jar" `
  -PqualityToolsJar="D:\path\to\QualityTools-1.0.7_for_1.12.2.jar" `
  -PquarkJar="D:\path\to\Quark-r1.6-179.jar"
```

Dregora 参考实例的真实兼容回归可额外传入。这里列出的版本只是测试样本，不是运行限制；旧版或其他衍生版会使用同一套结构门：

```powershell
.\gradlew.bat test `
  -PoptifineJar="D:\path\to\mods\OptiFine_1.12.2_HD_U_G5.jar" `
  -PrusticJar="D:\path\to\mods\rustic-1.1.7.jar" `
  -PfoamfixJar="D:\path\to\mods\foamfix-0.10.15-1.12.2.jar" `
  -PdregoraModsDir="D:\path\to\RLCraft Dregora\mods" `
  -PdregoraDiscoveryDir="D:\path\to\RLCraft Dregora\ice-optimizer\discovery" `
  -PdregoraSrpJar="D:\path\to\mods\SRParasites-1.12.2v1.9.21.jar" `
  -PdregoraLycanitesJar="D:\path\to\mods\lycanitesmobs-1.12.2-2.0.8.10.jar" `
  -PdregoraRenderLibJar="D:\path\to\mods\RenderLib-1.12.2-1.4.5.jar" `
  -PdregoraFoliageJar="D:\path\to\mods\RLFoliage-MC1.12-2.4.2.jar" `
  -PdregoraIceAndFireJar="D:\path\to\mods\Ice and Fire-2.0.9.jar" `
  -PbetterCavesJar="D:\path\to\mods\bettercaves-1.12.2-2.0.4.jar" `
  -PbetterFoliageJar="D:\path\to\mods\RLFoliage-MC1.12-2.4.2.jar" `
  -PqualityToolsJar="D:\path\to\mods\QualityTools-1.0.7_for_1.12.2.jar" `
  -PquarkJar="D:\path\to\mods\Quark-r1.6-179.jar" `
  -PotgJar="D:\path\to\mods\OpenTerrainGenerator-1.12.2-v9.7.jar" `
  -PminecraftSrgJar="D:\path\to\forge-1.12.2-14.23.5.2860-srg.jar"
```

四个可安装 JAR 都位于 `build/libs`。合并开发产物位于 `build/devlibs`，不可安装到正式实例。`verifySplitJars` 会检查主 JAR 与 core JAR 的类集合互不重叠、模组入口和 transformer 不越界。工程保留了 ForgeGradle 3.0.197 在 Windows/Java 8 下对完整 mapped JAR 的类路径补充。

### 1.0.5 发布验证（2026-08-22）

针对 Dregora 远处区块在 `modernVisibilityHzb=true` 时闪烁、关闭后恢复稳定的实机证据，使用真实 `ChunkAnimator-1.12.2-1.2.1.jar` 执行 ABI 测试，并连续两次独立执行 `clean build optimizerBundleZip`。发布前本地干净复验的 20 个任务全部实际执行；194 个测试类、740 项测试、0 failure、0 error，61 个 skipped 只对应当前命令未传入的可选真实模组 JAR/运行期单类样本。六个归档保持固定哈希：

| 产物 | SHA-256 |
| --- | --- |
| optimizer main | `FFCEBDDDCDBFDA20E04DE102B17DCB861D52188B14051419BDC0815521919EC4` |
| optimizer core | `8F082BE7DFC7E0A4FDC03EBF86F9E4A32BC8C0FFA65A28CA0D6CDFE5FD65BF31` |
| profiler main | `8CB4B2B145A3215750F49C2EA8EB89FE3476DBF5D524A85403FF8D60F525B2E9` |
| profiler core | `6FE9D528874BD7C788B9C0775133C510FFBC1CEE9ACE3C20138C27BF3E64CA01` |
| optimizer bundle | `5F440E1CB47B983FDB777C7DDACEDD81338561668E2835802898EE11D0F3301E` |
| combined-dev（不可安装） | `5D62E686147205DCE2402418D984912EF0D52CE46EBFFF8A9BE526C6092B3E63` |

`tools/deploy-client-1.0.5.ps1` 与 `tools/deploy-optimizer-1.0.5.ps1` 固定校验上述安装包哈希。两者均在工作区隔离 `mods` 夹具中验证了旧版替换、完整备份、非 ICE/profiler 文件保持不变、无 `.deploying` 遗留及第二次幂等复验；夹具和测试回滚目录已清理。1.0.5 随后部署到真实 Dregora 客户端，并在 `modernVisibilityHzb=true` 下完成 Session 验证：区块生成与远处闪烁恢复正常，ChunkAnimator 探针和 HZB 列表事务均无运行失败。当前 HZB 尚未测试真实候选、Terrain 所有权仍受固定 Arena 限制，后续改进进入独立的 ICE 2.0 架构方案。

### 1.0.4 发布验证（2026-08-22）

针对 `20260822-113130-969` 实机会话完成 Terrain 认证容量、TESR 模型状态、粒子首次链接和 HZB 资格链修复后，连续两次独立执行 `clean build optimizerBundleZip`。每轮 20 个任务全部实际执行；189 个测试类、724 项测试、0 failure、0 error，60 个 skipped 只对应未传入的可选真实模组 JAR/运行期单类样本。六个归档逐字节一致：

| 产物 | SHA-256 |
| --- | --- |
| optimizer main | `E58F057C3E3A9985F8E922BCD98C7BEF8A414F11F9707DBD7A1902282B9F62CB` |
| optimizer core | `6A4E1022E81B5A0BA53DDE83D3A736B937F0853C1E2A9AF6A3452317F5CC8DE4` |
| profiler main | `3977D5F8824AAA3DD90BF0A5A14DE2D893541951251D37520101B90F9BB786C0` |
| profiler core | `68E87F35D77A7E888154EA0FFC660F94FB5E3017FCDB5DE8D7D3FC85B31206AB` |
| optimizer bundle | `BEF6A2D6E75FC0821A0091E7BF17024C422FAE4FBA36112D91E8934D0C7569AD` |
| combined-dev（不可安装） | `94A65C17AC94DC054541E2C24E1D8C0036B3622D805D973A3D8B6B86E0770C6C` |

`tools/deploy-client-1.0.4.ps1` 与 `tools/deploy-optimizer-1.0.4.ps1` 固定校验上述安装包哈希。两者已用真实 Dregora 1.0.3 四包的工作区隔离副本验证：首次执行完整备份并只留下目标 1.0.4 包，第二次执行命中幂等校验且不再新建回滚目录，也没有遗留 `.deploying` 文件。测试夹具及其测试回滚目录已清理；1.0.4 尚未部署到真实 Dregora 客户端或任何服务端。

### 1.0.3 发布验证（2026-08-22）

真实崩溃包 `minecraft-exported-crash-info-2026-08-22T09-20-45` 显示，启动画面的 `GlStateManager.matrixMode` 调用了只存在于普通 Main JAR 的 `EarlyMatrixStateTracker`，当时 LaunchWrapper 尚不能解析该类，直接触发 `NoClassDefFoundError`；实例同时保留 1.0.1 与 1.0.2 的 optimizer/profiler Main/Core 八个 JAR，两个 transformer 还会重复改写同一目标。1.0.3 将矩阵跟踪器及四个内部类全部迁入 optimizer Core 并从 Main 排除，同时移除了它对 Main `FatalErrors` 的链接。

Core 隔离回归会完全隐藏 Main 运行时，在子加载器中定义并实际执行改写后的合成 `GlStateManager.matrixMode`；发布分包任务另行要求五个矩阵类全部且只存在于 Core。连续两次空目录 `clean build optimizerBundleZip` 均为 20 个任务实际执行：186 个测试类、701 项测试、0 failure、0 error，60 个 skipped 仅对应未传入的可选真实模组 JAR/运行期单类样本。六个归档逐字节一致。

| 产物 | SHA-256 |
| --- | --- |
| optimizer main | `D73581DE9A59B92791408F984F70D4CDB12D32FE03994CF39D12D5DC8BB2CD6F` |
| optimizer core | `6C24636949D585D9A77926CED42995E0E3441BC1527A6A1A77F710165C217F2A` |
| profiler main | `8EDFD98FC2D102D2DB964978F733BB6D5724B80334358AB51D146A890793EB5A` |
| profiler core | `4F4214EDA17E8427B3131C71C68CDFDBA6ADE838910B78523AB9024DF9BDF8F3` |
| optimizer bundle | `89C0FE8259E57CB78F634DFFB0A1BD37D2EAD96C0A2D166ED32472F1C744D625` |
| combined-dev（不可安装） | `21494A7F6982DAE767BC44A6B26264DDA6A476F8E9A0D69ABEA9A895E92CC94F` |

`tools/deploy-client-1.0.3.ps1` 已用真实混装八包的隔离副本验证：旧八包完整进入回滚目录，目标 `mods` 最终只剩四个 1.0.3 JAR；第二次执行为幂等复验，不新建回滚或遗留 `.deploying`。`tools/deploy-optimizer-1.0.3.ps1` 为专服或不安装记录器的客户端只替换 optimizer Main/Core。两者均固定校验上述 SHA-256。经用户明确授权，四个 1.0.3 JAR 已于 2026-08-22 09:45 部署到真实 Dregora 客户端；四个 1.0.2 原件保存在 `rollback/client-Dregora-before-1.0.3-20260822-094517283`，其他模组、配置、Session 和存档未修改。

### 1.0.2 发布验证（2026-08-22）

从空构建目录连续执行两次 `clean build optimizerBundleZip`，第二轮实际执行 186 个测试类、701 项测试、0 failure、0 error；60 个 skipped 均对应本轮没有传入的可选真实模组 JAR 或运行期单类样本。20 个 Gradle 任务全部实际执行，重混淆、Main/Core 分包和 `verifySplitJars` 均通过。两轮 optimizer/profiler Main、Core、optimizer bundle 与 combined-dev 六个归档逐字节一致；combined-dev 只供开发使用，不能安装到游戏实例。

| 产物 | SHA-256 |
| --- | --- |
| optimizer main | `467388F241529E6AAE3DF57DAFFD55A0688723A9D7F121A3F397677732F267F0` |
| optimizer core | `47EF6357E75001C33F8103FBB3565E84F767681485C5DD532BE1EDBBBA99EB0E` |
| profiler main | `0CA288DF05C3C2009C147AD1A995198663DA206EEDA3042B724E73AAB87E3B2D` |
| profiler core | `C3770E6BF8852D68D93B5580D295E1D42C014B7F8E8541083E0EF8AAF972A323` |
| optimizer bundle | `B5AB92517670BDAC8C0B5EE6FE37619CCC4442B4EA0081CEEF7A5FF16BA60109` |
| combined-dev（不可安装） | `79E006FEF650145E33393C7B7D535B7751C9F203B6F34A20CAC422DBD7F1EC74` |

`tools/deploy-optimizer-1.0.2.ps1` 固定校验两枚 optimizer 发布 JAR 的 SHA-256，在关闭 Java 游戏进程后才允许替换；它先备份已安装的 optimizer JAR，通过同目录 `.deploying` 文件完成替换，并在失败时恢复备份。脚本不会替换 profiler、配置、历史 Session 或存档。

### 1.0.1 发布验证（2026-08-22）

从空构建目录执行真实 Ice and Fire 2.0.9 参数下的 `test`、`build`、`optimizerBundleZip` 与 `verifySplitJars`：185 个测试类、695 项测试、0 failure、0 error。57 个 skipped 均为本轮未传入的其他可选真实 JAR/运行时样本；Ice and Fire 新适配器、Dregora 原始 JAR、缓存生命周期和 Core-only ABI 用例均实际执行且无跳过。

| 产物 | SHA-256 |
| --- | --- |
| optimizer main | `E405E889C7BFC5B7B7721619916F043BA430619A55022CD033CB884C56572F42` |
| optimizer core | `2D15D4416E3FEB0EAA636E895642EDC66E511860C75CF297E8688B1EF754C297` |
| profiler main | `30E540526F19081D652E7FE3605F68424D0C4314368356594C617580054557AE` |
| profiler core | `AE9024181646E5D4798B7E68755F751E66B8C175686B1ECB46F107D5969DF8F1` |
| optimizer bundle | `B8617CE0030758685834FAB3A950C644182F12F9DBE19F32D155A1DB3753D585` |

`tools/deploy-optimizer-1.0.1.ps1` 固定校验两枚 optimizer 哈希，先备份所有已安装的旧 optimizer JAR，再通过同目录 `.deploying` 文件替换；失败时恢复备份。脚本的空 `mods` 首次安装与第二次幂等复验均已在工程内隔离夹具通过；它不会触碰 profiler、存档、配置或历史 Session。

### 1.0 发布验证（2026-08-21）

完整真实输入矩阵使用 OptiFine G5、OTG 9.7、Forge SRG、Minecraft client/notch→SRG 映射，以及 Dregora 的 SRP、Lycanites、RenderLib、RLFoliage、Ice and Fire 等只读样本：181 个测试类、674 项测试、0 failure、0 error、1 skipped。唯一跳过项对应未额外提供的 Better Foliage 运行期改写单类样本；Dregora 专用 SRP/RenderLib 样本只通过 `-PdregoraSrpJar` / `-PdregoraRenderLibJar` 传入，不能同时冒充普通基线参数。

现代渲染零命中与喝药精确探针修复后的当前实例回归为 182 个测试类、682 项测试、0 failure、0 error、8 skipped。8 项只对应本机未安装的 Xaero/Better Foliage 运行时单类，以及六个不能由 Dregora 新版 JAR 冒充的普通 RLCraft 旧版精确基线；Dregora 专用变换、OTG 9.7、OptiFine G5、Forge/Minecraft 字节码均已执行。

本次收尾覆盖 `ReportWriter` 发布/ZIP cleanup 的 fatal 等价、逐能力 renderer 诊断、FBO/FBP/HZB/MDI 状态沙箱、LWJGL 2 多值状态查询容量、Timer Query 异步退休、MDI/HZB/实体/TESR 零命中修复、模型延迟发布与生命周期、ShaderPack 安全认证、HUD 空提交回退、Profiler 栈字典边界与喝药精确探针、OTG BO3/BO4 的强文件身份/摘要热复用/代际/深复制/稳定负缓存，以及区块 churn 与增量卸载保存的计划刻归因。发布任务链成功；连续两次独立 `clean` 构建的六个产物逐字节一致。独立归档审计确认 6 个 ZIP/JAR 无重复 entry、4 组 Main/Core 类交集均为 0、21 组早期注入 ABI 只存在于 optimizer Core、Agrona/Caffeine/LZ4 没有未重定位 entry 或字节引用，bundle 内两枚 JAR 与外部产物哈希相同。

| 产物 | SHA-256 |
| --- | --- |
| optimizer main | `3E4217D2657560B5472AEF2CCD2B624DBA3BA3A6DE862B5678589057A50F9571` |
| optimizer core | `280DA95BA947D38C0644A3225D155E6525C434A2E3956A70D90DAE62D36E164F` |
| profiler main | `91C21399B7570B124DE79B8ED222ABC2E84C480E9AD49D5BF4E447FC3407A094` |
| profiler core | `DF95108CAC4CD7C1A270F81BB34A594EF5C3C1734AB3B98CE0747283B70A67C5` |
| optimizer bundle | `9EB94A339E5140003A347CBD9AB83377A91217C102FFDE8F12C2FBB039DCF952` |
| reobfuscated combined-dev | `5A6E4257A767E3940DED072C9D5D8A6EFF37588E3FCA87C6C3ACAF75D8A42826` |

部署脚本已固定上述哈希，并在隔离 `mods` 夹具中覆盖空目录首次安装、同名旧四包升级、备份保真和第二次幂等校验；夹具与烟测回滚副本随后删除。经用户明确批准，本轮四个新 JAR 已部署到真实 Dregora 客户端，旧四包保存在 `rollback/client-Dregora-before-1.0-20260821-234005394`；其他模组、存档、配置和历史 Session 未修改。

需要将本轮 optimizer 与包含喝药探针的 profiler 四包一起更新时，可在完全退出 Minecraft 后运行 `tools/deploy-client-1.0.ps1 -Pack Dregora`。脚本先校验四个源产物，备份现有 ICE JAR，以 `.deploying` 暂存并逐包复验；任一步失败都会恢复备份，且不会修改其他模组、存档、配置或已有 Session。

自动验证不等于实机验收：当前没有宣称通过真实 OpenGL 画面 A/B、ShaderPack 实机图像认证、随机化 ABBA、new-chunk Frame/GPU P95/P99/1% low 或长时间 Fence/Query/RAM/VRAM soak。
