# Changelog

## 1.0.5 ChunkAnimator-safe HZB visibility - 2026-08-22

- 修复 Dregora `ChunkAnimator-1.12.2-1.2.1` 与 HZB 静态区块边界的坐标冲突：`timeStamps` 中仍在动画的区块强制保持可见，Arena 将其从 multi-draw/MDI run 拆出并调用真实 `ChunkRenderContainer.preRenderChunk`，再补偿 region-local 原点；稳定区块继续批处理。模组不存在时零影响，实际 ABI 或运行探针异常时 fail-open。
- 普通 HZB 遮挡必须在连续两个独立深度发布中获得同一区块身份见证，同一发布的 SOLID/CUTOUT 多层不会重复累计；场景/几何换代会清空证据。确认表使用固定预算、无逐区块分配的身份表，预算不足时逐级缩容或只停止剔除，不再使整个现代渲染器初始化失败。
- HZB 对真实 `VboRenderList` 的压缩现在具有提交事务：只有 Arena 已接管或 GPU 提交已经开始才 commit；所有提交前拒绝和异常均恢复原对象身份、顺序与长度。新增回滚快照预算、异常契约与 `hzb_filter_rollbacks`、`hzb_filter_transaction_deferrals/failures` 等诊断。
- 新增真实 ChunkAnimator 1.2.1 JAR ABI 回归、动画兼容绘制字节码契约、稳定遮挡见证与列表事务测试。optimizer/profiler Main/Core 和 Forge 精确握手版本提升为 `1.0.5`，禁止与 1.0.4 混装。
- 连续两次 `clean build optimizerBundleZip` 的六个归档逐字节一致；每轮 20 个发布任务实际执行，194 个测试类、740 项测试为 0 failure、0 error。1.0.5 四包/optimizer 两包固定哈希脚本通过 PowerShell 语法、隔离旧版升级和第二次幂等复验。随后四包部署到真实 Dregora 客户端并以 `modernVisibilityHzb=true` 完成新 Session 验证：区块生成与远处闪烁恢复正常，ChunkAnimator 探针、运行失败和 HZB 列表事务失败均为零；实测同时确认旧 HZB 尚未测试真实候选、Terrain 所有权受固定 Arena 限制，后续进入独立 ICE 2.0 架构重构。

## 1.0.4 renderer qualification recovery and actionable diagnostics - 2026-08-22

- 修复真实 1.0.3 Session 中 Terrain 资格认证被固定 128 MiB Arena 饥饿的问题：无 ShaderPack 时只有认证工作负载 `SOLID` 能建立 Legacy twin 并竞争影子 Arena，CUTOUT/TRANSLUCENT 不再先占满预算；ShaderPack 顶点布局认证仍保留全部实际图层的严格双副本。新增 `terrain_shadow_qualification_skips` 以区分策略跳过与容量拒绝。
- 修复 TESR 被错误永久隔离：模型重认证只需要固定管线模型绘制状态和矩阵状态，旧代码却用还要求无关 FBO/PBO 全局状态的完整 snapshot 检查发布，导致三次合法重认证被误报为 `model GL state publication incomplete`。实体/TESR 现在按真实模型提交契约认证，仍不放松 draw-time 状态等价要求。
- 移除首次实机粒子遍历中会链接整套可选 CoreMod 依赖的合成 `Particle` 子类；运行期验证只检查真实实例提交所需的 BufferBuilder/VBO 字节边界，完整 vanilla billboard 等价性移入隔离测试。报告新增粒子直接异常及根异常的类型与紧凑消息，若仍有可选类缺失会给出具体类名而非只有 `NoClassDefFoundError`。
- 修复 HZB 几何代际与视图稳定性误耦合：区块上传仍严格淘汰旧/在途深度并以 scene serial 拒绝陈旧完成，但不再清空独立的连续精确视图历史。带源深度 oracle 的 GPU 缩减一旦成功发布便按每帧一次推进输出认证，不再额外等待下一帧相机与几何身份完全相同。新增 oracle 发布量及 `FIRST_OBSERVATION/VIEW_CHANGED/DUPLICATE_FRAME/FRAME_GAP/CAPTURE_ALLOWED` 逐原因计数。
- Entity 与 HUD 在本次实机中分别慢 11.483% 与 30.888%，因此保留收益门主动 Legacy，没有通过降低正确性或强制 GPU 接管掩盖反优化。版本提升为 `1.0.4`，禁止与已部署 1.0.3 使用相同版本号混用不同字节码。
- 连续两次 `clean build optimizerBundleZip` 的六个归档逐字节一致；每轮 20 个发布任务实际执行，189 个测试类、724 项测试为 0 failure、0 error。1.0.4 四包/optimizer 两包固定哈希脚本均通过 PowerShell 语法、真实 1.0.3 输入的隔离升级和第二次幂等复验；未部署到真实客户端或服务端。

## 1.0.3 early matrix Core ABI and mixed-version recovery - 2026-08-22

- 修复 1.0.2 启动画面崩溃：Core transformer 已经把 `GlStateManager` 的矩阵包装器改为调用 `EarlyMatrixStateTracker`，但该类仍只存在于尚未暴露给 LaunchWrapper 的普通 Main JAR，首次 `matrixMode` 因而触发 `NoClassDefFoundError`。矩阵跟踪器及全部内部类现在只位于 optimizer Core，并移除对 Main `FatalErrors` 的早期链接依赖。
- 新增真正隐藏 Main 运行时的 Core 隔离回归：在子加载器中定义并执行改写后的合成 `GlStateManager.matrixMode`，同时由发布分包检查硬性要求矩阵 ABI 的五个类只存在于 Core。版本提升为 `1.0.3`，禁止与已发布的 1.0.2 相同版本号混用不同字节码。
- 真实崩溃包还确认实例同时安装了 1.0.1 与 1.0.2 的 optimizer/profiler Main/Core 八个 JAR，造成重复模组入口和 transformer。1.0.3 客户端部署脚本会统一备份并替换全部 ICE 四包，避免只覆盖新文件却保留旧版本。
- 两次独立 `clean build optimizerBundleZip` 的六个归档逐字节一致；每轮 20 个发布任务实际执行，186 个测试类、701 项测试为 0 failure、0 error。真实混装八包的隔离部署、旧包备份、最终四包哈希和第二次幂等复验均通过。经用户明确授权，四个 1.0.3 JAR 已部署到真实 Dregora 客户端，四个 1.0.2 原件保存在 `rollback/client-Dregora-before-1.0.3-20260822-094517283`；其他模组、配置、Session 和存档未修改。

## 1.0.2 load-profiled renderer recovery and HZB preflight - 2026-08-22

- 实体与 TESR 收益认证不再由启动早期一个普通场景永久决定：按实体/方块实体数量建立 8 个对数负载桶，同一桶连续暖机 30 帧后才进入 ABBA；已拒绝桶保持 Legacy，只允许未见过且更高的负载桶在 600 帧冷却后重测，单代际最多 3 次。安全收益阈值仍为中位数至少提升 5%、p95 回退不超过 2%，没有把实测更慢的路径强行打开。
- Legacy/隔离后的 RenderLib 遍历不再让每个 ModelRenderer 部件进入缓存、状态重认证与逐原因计数；终态在遍历入口发布零分配快速门。Terrain 父后端回退时，MDI 与 Persistent Mapping 子认证立即暂停为明确 Legacy，避免长期饥饿与无效样本。
- HZB 在任何同步 GL 状态补查前先执行纯 CPU 连续视角预检；已有历史、捕获待完成或移动视角均不会查询驱动。地形上传失效在同帧且捕获边界之前安全合并，捕获后的变化仍严格推进 scene serial；新增几何变化、合并、场景失效及过期完成量诊断。
- Particle 实例着色器为支持 `GL_EXT_gpu_shader4` 的兼容驱动增加 GLSL 1.20 变体，避免 NVIDIA 对带旧固定管线 varyings 的 vertex-only GLSL 1.30 程序在链接阶段拒绝；最终是否启用仍由离屏像素等价自测决定。着色器/程序日志保留上限由 256 字符扩展为 4096，失败继续独立隔离。
- optimizer/profiler Main 与 Core、Forge 精确握手和发布文件统一提升为 `1.0.2`。连续两次空目录 `clean build optimizerBundleZip` 的六个归档逐字节一致；第二轮 186 个测试类、701 项测试为 0 failure、0 error，重混淆、Main/Core 分包、bundle 与固定 SHA-256 部署夹具均通过。

## 1.0.1 entity-density recovery and bounded path reuse - 2026-08-22

- 修复真实实体密集 Session 暴露的二次瓶颈：HZB 只有连续两个相同视角帧才捕获并在 Legacy terrain 下也能推进输出验证，避免移动时反复 reduction/readback；实体/TESR 将 GL 与矩阵状态分开重认证，同帧同失效序号只探测一次，提交前瞬时认证失败不再立即永久切回 Legacy；TESR 的 ABBA 指纹允许实体与方块实体计数的自然小幅抖动，但仍严格锁定维度、区域、视角、分辨率、天气、配置代际与纹理负载。
- 新增 `iceandfire-path-node-cache`：对 Dregora Ice and Fire 2.0.9 的 `ExperimentalWalkNodeProcessor` 严格验证 2 个 raw-node 与 12 个 block-state 调用点，只在一次同步 PathFinder 生命周期内用 primitive long key 复用结果；处理器/世界身份不匹配、调用图变化、缓存异常或生命周期异常均独立 fail-open，不跨 Tick、不缓存整条路径，也不把服务端权威世界读取异步化。
- 新增 Ice and Fire 合成调用图、搜索生命周期、嵌套处理器、世界身份隔离、实机 2.0.9 JAR 与 Core-only ABI 回归；注入接口只位于 Core，运行期桥只位于 Main，分包任务硬性禁止缺失或重复。optimizer 精确握手版本提升为 `1.0.1`，避免已部署 `1.0` 与本次不同字节码被误判为同版；两次独立 clean 发布构建逐字节一致，固定哈希部署脚本的首次与幂等夹具通过。

## 1.0 modern OpenGL hybrid renderer and audited release pipeline - 2026-08-21

- 将正式发布版本从已使用过的 `0.10.0` 提升为 `1.0`；optimizer/profiler Main 与 Core、Forge 精确握手范围、Gradle 产物名、部署脚本和安装文档统一使用同一版本，禁止 0.10 与 1.0 混装。
- 完整接入 FrameCoordinator、PassGraph、Legacy GL Island、能力自测、输出验证、在线 ABBA/收益回归状态机，以及 `OF_COMPAT_REGION`/`ICE_NATIVE` 双地形后端、保守 HZB、实体/TESR、粒子、动画纹理、HUD/字体与 ShaderPack 认证路径。
- hooks fatal、提交状态不确定时禁止 Legacy GL 重放、Context-loss 资源 abandon、Fence/Query/VBO/PBO/Display List 所有权和 ChunkSave Deflater native 生命周期均完成故障注入与尾审。
- 完成中断后的发布尾审：`ReportWriter` 的目录发布、ZIP/流关闭和原子移动现在保留 primary failure，并在 cleanup 中出现 `ThreadDeath`/`VirtualMachineError` 时正确提升 fatal；Profiler 统计窗口冻结完整字典，栈字典达到上限时稳定折叠而不越界。
- `CapabilityReport` 与 `optimizer-renderer.txt` 改为逐能力记录准备、认证、提交、回退及明确原因；FBO、MultiDraw/MDI、FBP、HZB 与生产 HZB 路径隔离并恢复 draw/read framebuffer、draw buffers、VAO/PBO、固定功能纹理单元、alpha/fog/stencil/dither/sRGB/rasterizer 等状态，无法精确恢复时拒绝候选路径。
- 修复真实 Session 暴露的现代渲染零命中：LWJGL 2 的 `glGetIntegerv/glGetBooleanv/glGetFloatv` 生成包装器统一要求缓冲区至少剩余 16 个元素；FBO 状态沙箱与启动/HUD 状态工作区现在为三类查询提供相互隔离的 16 元素视图，不会在 viewport、颜色掩码或颜色值捕获阶段被错误隔离。
- 修复能力自测通过后仍无法实际下放的三条链：MDI 的瞬时失败不再永久封死后续提交，HZB 在固定功能状态镜像缺口时进行有界重新认证，实体/TESR 的 `ModelRenderer` 捕获在后端完成决策前允许有界延迟发布；两个模型后端都不可用时立即丢弃待发布网格，避免只增加 CPU/内存而 GPU 零命中。
- 模型 VBO 缓存明确按资源/GL 上下文而非世界代际归属：换世界或维度保留暖缓存，资源包重载和 Context loss 清除。`optimizer-renderer.txt` 新增模型捕获/逐原因拒绝、Heap/Direct/GPU 预算、ResourceLedger 存活/退休/超时，以及 Legacy、ICE Native、OptiFine Region 的累计 CPU/GPU 时间与查询覆盖量。
- Profiler Core 新增低频常驻的首次喝药精确边界，无需开启全局深度模式即可分别记录 `item_use_finish`、`potion_item_finish` 及其间每个 Forge 事件监听器；它只观测原版同步语义，不把药水效果、实体状态、事件或网络同步错误地异步化/GPU 化。
- Timer Query 能力自测保留通用 8 ms 上限，但 Timestamp Query 单独允许最多 250 ms 让已排队 GPU 工作退休；轮询仍有严格边界，避免把正常异步结果误报为 `timer query timeout`。
- ShaderPack 只有在 program/permutation、顶点格式、状态捕获和输出验证全部认证后才接管；fatal 验证路径先释放 retained prepared state。HUD 的空提交或提交前安全失败回到原提交，提交结果不确定时禁止重复 Legacy 重放。
- OTG BO3/BO4 同步解析缓存以 canonical path、file key、size、mtime、SHA-256 和配置代际共同认证文件；首次或目录变更后才解析真实路径、读取属性与摘要，稳定热命中只规范化逻辑路径、排空 WatchService 并比较内存中的目录序列/配置代际，不再执行 canonical/toRealPath/readAttributes 或打开文件。缺失/稳定解析失败具有负缓存，命中返回深复制，反射构造 fatal 继续传播，发布前在同一代际锁内再次认证；WatchService 不可用时拒绝复用摘要并保留原 Map fail-open 行为。
- 区块 churn 报告补齐加载、卸载、保存和计划刻来源归因；增量卸载保存覆盖计划刻索引路径，避免把保存风暴笼统归为区块生成。
- ChunkSave 固定从一个安全 Worker 启动，只按实际队列、FILE_IO 等待与结果压力迟滞调节；不按处理器数、最大堆、CPU/GPU 型号或核类型静态分档。
- 修复真实 Dregora 启动画面加载 `FontRenderer` 时的 Core/Main 类加载边界崩溃：`FontRenderCacheAccess` 以及粒子、OptiFine VboRegion、Lycanites、Mo' Bends 的全部注入 ABI 现在只由 Core JAR 提供；新增无父加载器回归，证明改写后的字体类在 ABI 缺失时必然失败、Core ABI 可见时能够完成链接。
- reobf 后统一规范化 JAR entry 顺序和时间戳，manifest 时间从 `SOURCE_DATE_EPOCH` 派生；相同输入连续两次独立 `clean` 构建的 optimizer/profiler Main、Core、optimizer bundle 和 combined-dev 六个产物均逐字节一致，部署脚本固定校验最终 SHA-256。
- 使用 OptiFine G5、OTG 9.7、Forge SRG/Minecraft 映射和 Dregora 真实只读依赖执行完整矩阵：181 个测试类、674 项测试、0 failure、0 error、1 skipped（未额外提供 Better Foliage 运行期改写单类样本）；发布任务链、独立 manifest/重复 entry/类交集/早期 ABI/依赖重定位/bundle 哈希审计及临时隔离 `mods` 的首次与幂等部署均通过。
- 上述零命中与喝药探针修复后的当前 Dregora 实例矩阵为 182 个测试类、682 项测试、0 failure、0 error、8 skipped；跳过项只对应当前机器没有的 Xaero/Better Foliage 运行时单类，以及不能用 Dregora 新版 JAR 冒充的六项普通 RLCraft 旧版精确基线。两次独立 `clean` 发布构建的六个归档逐字节一致。
- 经用户明确批准，optimizer/profiler Main/Core 四个新 JAR 已原子部署到真实 Dregora 客户端；旧四包保存在 `rollback/client-Dregora-before-1.0-20260821-234005394`，其他模组、存档、配置与历史 Session 未修改。真实 OpenGL 画面 A/B、ShaderPack 实机图像认证、随机化 ABBA、new-chunk Frame/GPU P95/P99/1% low 与长时间 Fence/Query/RAM/VRAM soak 仍须在真实游戏环境验收，不能由自动回归结果替代。

## 0.10.0 portable compression, render guards and low-overhead recording - 2026-08-16

- 修正 0.9.4 的主要反优化：最新实际 Session 中动画纹理链出现数百次 `glFenceSync`，通用单级 `TextureUtil` PBO 会为小纹理反复进入驱动。该入口现在始终执行原上传；FoamFix 只有完整 mip 批次达到 256 KiB 才尝试 PBO，小批次和忙槽立即回退。区块 VBO 同样增加 256 KiB 下限，每次最多探测两个 Fence 槽，GPU 落后时不再遍历六槽。
- 所有注入热路径改用稳定 `OptimizationModule.ordinal()` 与单次 volatile operational bit mask；`ModuleCircuitBreaker` 的成功、失败和拒绝计数改用 `LongAdder`/CAS，状态真正变化时才重发 mask。客户端纯计算 Worker 改为 Agrona 有界 MPMC 专用线程，渲染队列降低对象与 `nanoTime` 检查频率。
- 新增 `vanilla-chunk-compression`：主线程仍生成完全相同的区块 NBT 快照，1–4 个按 CPU/堆自适应的有界 Worker 只负责序列化与 zlib Deflate，原 FILE_IO 线程按原 pending Map 顺序写 RegionFile。队列满、结果过大、世界代际变化、Accessor 缺失、关闭取消或异常时执行原压缩；取消排队任务会立即释放等待者，避免世界关闭死锁，并按实际 backing buffer 容量执行内存硬预算。
- 新增 `konkrete-locale-lookup`：`LocaleUtils.getKeyForString` 按语言 Map 身份建立资源代际反向索引，重复翻译值仍返回源 Map 的第一个 key；未知实现、重载竞争或异常调用保留的原反射与线性扫描方法。
- 新增 `forge-blockstate-direct-calls`：只在 OptiFine `ReflectorForge` / `StateImplementation` 的参数、Reflector 调用数量和控制流完全匹配时，以等价 Forge 虚调用处理区块光照与侧面遮挡。普通 Forge 已经直调时主动跳过，原方法被重命名保留为逐调用 fallback。
- 新增 SRPMixins 刷怪过滤连续编译路径与 Lycanites 方块成员索引；两者都保留原列表顺序、动态世界/配置读取和修改代际，未知结构只回退当前能力。
- Profiler 深度采样始终优先客户端/服务端主线程，Worker 每批最多四个并轮询；线程描述符与 ID 数组复用，CPU/分配统计使用 primitive counter table，并在 HotSpot 上按 ID 数组批量读取 CPU 时间与 allocated bytes。新增 GPU 驱动/同步、主动限帧、ICE 自身和世界生命周期归因，持续帧税、连续尖峰和单次尖峰分开报告，`SmoothSync -> sleep` 不再误判成计算热点。
- 实体范围查询与通用 Capability 缓存未强行上线：现有 RLTweaker/FoamFix 容器改写和 Forge 动态 attach/clone/换维度生命周期无法用一个通用 Hook 严格证明一致；在可验证生命周期出现前继续执行原逻辑，避免改变实体顺序或 capability 结果。
- 正式发布仍为四个互不重叠的 JAR：optimizer Main/Core 与 profiler Main/Core。Profiler 保持完全独立；optimizer 不建立 Session、不注册记录按键，profiler 不包含任何优化实现。
- 使用普通 RLCraft 与 Dregora 的实际 Forge SRG、OptiFine、FoamFix、Konkrete、SRPMixins、SRP、Lycanites、RenderLib、Better Foliage、Ice and Fire、Better Caves、OTG 等样本执行 `176` 项测试：`0` 失败、`0` 错误，`1` 项仅因没有额外传入运行期已改写的 Better Foliage 单类样本而跳过；`verifySplitJars`、重混淆与四 JAR 干净构建全部通过。

## 0.9.4 early texture bridge crash fix - 2026-08-16

- 修复 Dregora 客户端在初始化帧缓冲时崩溃：`TextureUtil` 已被 Core 转换，但当时普通 Forge 模组尚未进入 pre-init，旧注入会直接解析只存在于主 JAR 的 `FoamFixUploadBridge`，从而触发 `NoClassDefFoundError`。这不是显卡驱动、内存不足或 FoamFix 自身崩溃。
- 新增只随 optimizer Core JAR 加载的 `TextureUploadBootstrap`。Minecraft/FoamFix 在主运行时尚不可见时只会收到 `false` 并执行原上传；客户端 pre-init 完成运行时初始化后，再原子安装主 JAR 的两个 MethodHandle 委托。稳定热路径没有类名扫描或逐次反射调用。
- Core 缺失、主/Core 版本混装、委托签名不兼容或运行期异常时，该纹理能力只回退原版/FoamFix 路径，不再让 `TextureUtil.<clinit>` 失败；纹理参数、像素、mip 顺序、PBO 能力判断与最终画面保持不变。
- 新增 Core-only 隔离类加载回归：测试环境完全隐藏主 optimizer JAR 后定义并执行转换后的 FoamFix 上传类，确认启动期仍进入未修改的原实现；分包校验同时要求早期引导桥及其委托容器确实位于 Core JAR。
- optimizer / profiler 产物与 Forge 握手常量统一为 `0.9.4`。
- 使用普通 RLCraft 旧版目标、Dregora 新版目标、Forge 1.12.2 SRG 与 Core-only 启动夹具执行 `155` 项测试：`0` 失败、`0` 错误，`1` 项仅因没有额外传入运行期改写后的 Better Foliage 单类样本而跳过；正式重混淆、主/Core 分包、bundle 和早期桥归属校验全部通过。

## 0.9.3 portable Better Caves hot-gate fix - 2026-08-16

- 普通 RLCraft 新采样确认稳定阶段约 248–313 FPS、GPU 用时约 1.66–2.14 ms，卡顿主体位于单人集成服务器的 Better Caves、Recurrent Complex、Dynamic Trees、Lycanites 与区块保存链路，不是持续 GPU 饱和。进入世界时的 `Display.sync` 是客户端等待集成服务器；同期仍可出现数秒 GC 与大量新区块生成/保存。
- 修复 ICE 自身的热路径回退：`OptimizationModule.byId` 不再在每次模块开关检查时调用 `values()` 分配枚举数组并逐项执行 `equals/equalsIgnoreCase`，改为启动期构建的 O(1) 精确 ID 表与兼容枚举名表。规范模块 ID 的大小写语义、旧枚举名兼容和未知 ID 回退保持不变。
- Better Caves 的 `NoiseTuple`、`NoiseColumn`、`NoiseGen` 与 `CaveCarver` 共享门现在缓存稳定的 `ModuleCircuitBreaker` 引用，热循环只读取实时熔断状态，不再解析字符串模块 ID 或二次查询注册表。缓存的不是永久 boolean，因此配置关闭、结构不兼容或运行时熔断仍会立即回退原模组实现。
- 新增字节码性能回归测试，禁止模块查找重新引入 `values()`/`equalsIgnoreCase` 线性扫描，并禁止 Better Caves 热路径门重新调用字符串版 `OptimizerBridge.isEnabled`；普通 RLCraft Better Caves 2.0.4 真实 JAR 的 primitive 存储、深复制隔离和关闭回退测试通过。
- optimizer / profiler 产物与 Forge 握手常量统一为 `0.9.3`；这次只减少 ICE 状态检查开销，不更改洞穴噪声、随机数、结构生成、区块内容、保存顺序或画面结果。
- 使用普通 RLCraft Better Caves/Better Foliage/RenderLib/SRP 等样本、Dregora 目标 JAR 与 Forge 1.12.2 SRG 执行 `150` 项测试：`0` 失败、`0` 错误，`1` 项仅因没有额外传入运行期改写后的 Better Foliage 单类样本而跳过。

## 0.9.2 measured chunk-worker hotspots - 2026-08-16

- 最新采样显示稳定阶段约 34–38 FPS，GPU 用时约 14–15 ms，而整帧约 27–32 ms，瓶颈仍在 CPU 区块重建；Chunk Worker 中 OptiFine 动态光约占 24.2%，AO 调用链约占 55.4%，Rustic 扩展状态约占 17.6%，因此本版直接处理这些实测热点，不采用预生成、降画质或减少游戏逻辑。
- 新增 `optifine-dynamic-lights`：在 OptiFine 原有 50 ms 动态光更新边界复制不可变 primitive 快照，区块工作线程不再持有全局 `DynamicLightsMap` 锁，也不再为每个 AO 采样重复调用五个虚方法；96 个以上光源自动建立 8 方块空间索引。距离公式、15 级亮度、水下衰减、Clear Water 和更新时序与 OptiFine G5 保持一致，结构不兼容时调用保留的原方法。
- 新增 `rustic-lattice-state`：六方向邻居查询和 `canConnectTo` 判定仍由 Rustic 1.1.7 原方法执行，只把重复的不可变 `withProperty` 转换规范化，并复用 64 个与原坐标完全相同的 `AxisAlignedBB`。渲染、碰撞、粒子和寻路共同受益，模块也通过物理服务端分侧检查。
- Fermium/NormalASM 兼容从“检测到便整体跳过线程限制”改为后置限制：ICE 在 Fermium 的 worker 局部变量改写之后读取最终结果，并在其 builder 字段赋值之后收紧池大小；不替换 Fermium 的优先级或数量算法。典型 16 逻辑线程、足够堆内存的 `10 Worker / 100 Builder` 收敛为 `8 / 32`，其他 CPU/堆配置继续使用同一通用分档。
- FoamFix 动画纹理上传增加可靠的原版 `TextureUtil` 单级入口，避免 FoamFix 类在 ICE 晚期转换器注册前已加载时完全失效；可用时仍优先保留 FoamFix 批量 mip 入口。三槽 PBO 同时支持 OpenGL 3.2 核心 Sync 和 `GL_ARB_sync` 扩展，PBO 不支持、槽位忙、预算不足、数据过大或异常时立即执行原上传。
- 目标目录由 56 个唯一类、57 个能力项增至 61 个唯一类、62 个能力项；新增真实 OptiFine G5、Rustic 1.1.7、FoamFix 0.10.15、Forge SRG `TextureUtil`、Fermium 后置策略、核心/ARB PBO 后端、光照公式快照和 64 包围盒回归。所有新目标按方法签名和调用图放行，不要求特定电脑、CPU 核心数、显卡厂商、整合包名称或 JAR SHA-256。
- optimizer / profiler 产物与 Forge 握手常量统一为 `0.9.2`；客户端与服务端仍只要求 ICE optimizer 主 JAR 版本一致，不要求两端安装相同的客户端模组。
- 使用 Forge 1.12.2 SRG 与当前 Dregora 的真实 OptiFine/Rustic/FoamFix/SRP/Lycanites/Mo' Bends/RenderLib/RLFoliage/Ice and Fire/Better Caves/Quality Tools/Quark/Dynamic Trees/OTG 样本执行 `146` 项测试：`0` 失败、`0` 错误，`9` 项仅因未提供普通 RLCraft 旧版或 Xaero 可选 JAR 而跳过；正式重混淆、四 JAR 分包、optimizer bundle 和 `verifySplitJars` 全部通过。

## 0.9.1 capability-local compatibility - 2026-08-16

- `ChunkRenderDispatcher` 不再由一个原子适配器同时承担线程策略和 VBO 上传：目标目录现在允许同一类注册多个有序能力，转换器逐项串联；某一项结构失配只回退该项，后续独立能力继续尝试。Dregora/Fermium 改写构建器策略时，ICE 会保留其线程策略，同时仍可安装兼容的 VBO 上传 wrapper。
- 区块线程适配不再依赖固定的 `j * 10` 指令片段，也移除了 `availableProcessors()` 后最多 16 条指令的机器化距离限制；改为验证唯一构建器字段写入、初始化先后关系与 worker 局部变量。完整类 SHA-256 继续只作为审计信息，不参与放行。
- Chunk Worker 从固定最多 8 个改为 CPU 与 JVM 堆双重自适应：1–2 逻辑处理器使用 1 个，24–31 线程平台可到 12 个，32+ 可到 16 个；低内存 JVM 会进一步收紧，且永远不超过原实现算出的 worker 数。
- 区块 VBO staging 除 OpenGL 3.1 核心 `glCopyBufferSubData` 外，新增 `GL_ARB_copy_buffer` 后端；同步仍可使用 OpenGL 3.2 或 `GL_ARB_sync`。F3 会区分 `GL31-COPY` / `ARB-COPY`，任何能力缺失、槽位忙、预算不足或异常仍立即执行原 `glBufferData`。
- F3 `ICE Opt` 新增 `MISS`，显示已经观察到但至少有一项结构能力未安装的模块，避免不同电脑上把“Core 已加载”误认为全部补丁都生效。
- 目标目录为 56 个唯一类、57 个独立能力项；新增未知 SHA、无害指令距离变化、同类首项失败但后项成功、CPU/堆分档以及核心/ARB GPU 后端回归。
- 使用 Forge 1.12.2 SRG、Dregora 实际捕获类及当前 SRP/Lycanites/RenderLib/RLFoliage/Ice and Fire/Better Caves/Quality Tools/Quark/Dynamic Trees/OTG 夹具执行 `135` 项测试：`0` 失败、`0` 错误，`6` 项仅因未提供普通 RLCraft 旧版或 Xaero 可选 JAR 而跳过；重混淆、主/Core 分包和 bundle 校验通过。

## 0.9.0 portable vanilla chunk pipeline - 2026-08-16

- 新增 `vanilla-chunk-dispatch`：原版 `ChunkRenderDispatcher` 不再把全部逻辑处理器都交给 Chunk Worker；按 2/4/8/16+ 线程规模为客户端与单人集成服务器保留处理能力，最多启用 8 个 Worker。默认 `RegionRenderCacheBuilder` 队列从每 Worker 十个限制为四个，16 逻辑线程、约 9 GiB 堆的典型配置由 `16 Worker / 160 Builder` 收敛为 `8 / 32`，显著降低线程争用与 Direct Buffer 常驻量；原优先队列、区块任务与结果不变。
- 新增 `vanilla-chunk-sort`：`BufferBuilder.sortVertexData` 的 `Integer[]`、Comparator、TimSort 与循环切片改为 Chunk Worker 私有的 primitive 稳定归并排序和可复用原始顶点备份。距离公式、`Float.compare`/NaN 语义、相等四边形先后与最终顶点位逐 bit 保持一致；任何结构或运行条件不满足时调用保留的原方法。
- 新增 `vanilla-chunk-vbo-upload`：支持 OpenGL 3.1 + Sync 时，区块 VBO 使用六槽、每槽最多 16 MiB、受 GPU 硬预算约束的 staging buffer；CPU 上传到空闲槽后由 `glCopyBufferSubData` 在 GPU 命令队列复制到目标 VBO，并以 Fence 防止槽位提前复用。槽位忙、扩展缺失、数据过大、预算不足或异常时不等待，立即执行原 `glBufferData`。
- F3 将 `PATCH`（字节码已安装）和 `HIT`（优化分支确实运行）分开；`CORE` 明确显示 Core JAR 是否缺失，新增区块 Worker/Builder、已排序四边形、GPU 后端与上传/回退计数。缺少 Core JAR 时进入世界只提示一次，不再让用户把主 JAR 正常加载误认为底层补丁已生效。
- `ModuleCircuitBreaker.patchInstalled` 不再提前标记 `ACTIVE`；首次 `activate` 或 `recordSuccess` 后才进入实际命中状态。发布新增 bundle ZIP，内含主 JAR、Core JAR 与中英文安装说明，降低只下载一个 JAR 的误安装概率。
- 目标目录由 53 个增至 56 个，新增原版 `ChunkRenderDispatcher`、`BufferBuilder` 和 `VertexBuffer`；两个 CoreMod ABI 继续只存在于 Core JAR，并由分包检查阻止主/Core 重复类。
- 实际 Forge 1.12.2 SRG、Dregora SRP/Lycanites/RenderLib/RLFoliage/Ice and Fire、Better Caves、Quality Tools、Quark、Dynamic Trees 与 OTG 夹具共执行 `129` 项测试：`0` 失败、`0` 错误、`9` 项因未提供普通 RLCraft 旧版或 Xaero 可选 JAR 而跳过；三发布优化产物、重混淆、JVM 类定义与分包验证通过。

## 0.8.0 structural compatibility and measured world/combat hotspots - 2026-08-15

- 新增 `vanilla-save-tick-index`：只在一次同步 `ChunkProviderServer.saveChunks(true)` 内，按 WorldServer 计划刻集合的变更版本建立临时只读分区索引。每个区块得到的条目仍保持原 `TreeSet → pendingThisTick` 顺序，边界仍使用原版两格扩展；保存、事件、NBT 和世界写入顺序均不变，集合变更会在下一次查询前重建，重入或异常立即回退逐区块原扫描。
- 新增 `lycanites-spawn-scan`：真实 2.0.8.10 `BlockSpawnLocation` 保留原 Y/X/Z 扫描、流体判定、排序/RNG 和虚方法顺序；方块计数表按需分配并使用 primitive 值，只有精确基类、原版 World、双 surface/underground、标准 ArrayList 和非流体方块同时满足时，才复用同一位置刚读取的只读方块状态，其余情况执行原读取。
- Profiler 根因归因改为实际触发区间，并按服务端 Tick/客户端帧优先使用对应主线程；Netty selector、Windows selector native poll 和 `ThreadedFileIOBase + Thread.sleep` 明确识别为空闲旁证，不再压过真正的主线程 NBT、世界生成或渲染栈。
- 移除 RLCraft 2.9.3、Dregora 3.9、组件版本和 JAR SHA-256 运行门禁；旧 `strictPackLock` 配置字段只为配置兼容保留，不再拒绝补丁。已知类 SHA 仅用于审计，真正放行条件改为每个适配器的字段、方法描述符和精确指令图。
- 未审查 SHA 但结构兼容的目标类现在会正常转换；结构变化、桥接能力不完整或适配器异常时只保留该目标原字节码，不会全局关闭其他优化。开发磁盘输出仍默认关闭，组件诊断文件改为 `components-observed.properties` / `components-observed-server.properties`。
- Better Caves `NoiseTuple` 热存储从装箱 `ArrayList<Double>` 改为 `double[]` 并保留实时 `List<Double>` 兼容视图；`NoiseColumn` 的正常高度改用连续数组并提供深复制隔离，公开 Map 只在需要时物化。
- Better Caves `NoiseGen` 增加 64 槽完整坐标/高度角点列缓存，命中返回深复制；四组 `times + times + plus` 中间对象合并为一次等价 blend。Tuple/Column 任一 ABI 未成功安装时，整个噪声流水线自动回退原实现，避免部分转换导致链接错误。
- Better Caves `CaveCarver` 的逐列 `HashMap<Integer, Float>` 阈值改为连续 Map，阈值公式保持逐 bit 相同；不改变随机数调用、区块内容或世界写入顺序。
- Better Foliage + OptiFine 的自定义颜色开关读取由逐方块 Kotlin/Java Field 反射改为 `ClassValue` 缓存访问器；普通 Better Foliage 与 Dregora RLFoliage 的真实 JAR 均通过结构转换和 JVM 定义回归。
- Quality Tools 对普通非玩家、非马生物只在装备实际改变时重算属性，并每 140 Tick 强制复核；首次处理、玩家、马和装备变化时仍执行原逻辑。
- Quark 掉落物 age/lifespan 检测将两个装箱 `WeakHashMap` 合并为单个可变弱状态，同时保留异常 age/lifespan 变化后持续同步的原状态机。
- 目标目录由 43 个增至 53 个；新增 7 个 Better Caves、Better Foliage、Quality Tools、Quark 目标，以及 2 个原版同步保存目标和 1 个 Lycanites 刷怪扫描目标，并补充原始类型等价、深复制隔离、float 位等价、保存索引顺序/失效、主线程归因和未知 SHA 兼容回归。
- optimizer / profiler 发布版本统一为 `0.8.0`，客户端与服务端仍要求安装相同的 ICE optimizer 主 JAR；该握手只约束 ICE 自身版本，不限制 RLCraft 或其他模组版本。
- 本次构建以实际 Forge 1.12.2 SRG 和 Dregora Lycanites 2.0.8.10 执行 `123` 项测试：`0` 失败、`0` 错误，`19` 项仅因未传入其他可选模组 JAR 而跳过；重混淆、四发布 JAR 分包与 `verifySplitJars` 全部通过。

## 0.7.0 required dual-side optimizer runtime - 2026-08-15

- 移除 optimizer 的 `clientSideOnly` 声明，Forge 版本握手改为精确 `[0.7.0]`；客户端与专用服务端必须安装同一主 JAR 版本。
- 新增完全不引用 Minecraft 客户端类的 `OptimizerServerProxy` 与 `ServerOptimizerRuntime`，专服 preInit 会回放 CoreMod 补丁日志、检查服务端包锁并启动独立模块熔断。
- 模块目录增加物理 side 能力声明：专服只允许 CORE、SRP 寻路/目标搜索、Lycanites 寻路/注册表/效果、Ice and Fire 粒子暂存及三项 OTG/BO4 优化；全部渲染、GPU、区块网格、Xaero、Mo' Bends 与头颅模块强制关闭。
- 普通 RLCraft 与 Dregora 新增独立服务端包锁，只固定服务端真实目标所有者 3/5 个 JAR，避免把 Entity Culling、RenderLib、Mo' Bends、RLFoliage 等客户端组件误要求到专服；完整目标类 SHA-256 与精确调用图仍是最终安全门。
- `IceOptimizerLoadingPlugin` 改为注册双端 `IceOptimizerTransformer`；旧 `IceClientOptimizerTransformer` 仅作为测试与开发工具兼容代理保留。
- 服务端停止时显式关闭模块注册表；optimizer 仍不启动 profiler、不创建 Session、不逐 Tick 写盘，开发观察文件在专服使用独立 `pack-observed-server.properties`。
- 新增双端握手、分侧模块、服务端入口/公共桥无客户端类引用、四套 profile 选择和真实 Dregora 服务端目标 JAR 包锁回归；全部 `116` 项测试在真实目标参数下 `0` 失败、`0` 跳过。

## 0.6.5 Dregora OTG generation and async skull profiles - 2026-08-15

- Dregora 严格包锁新增实际 `OpenTerrainGenerator-1.12.2-v9.7.jar`：ModContainer 版本 `1.12.2-v9.5-R2`、JAR SHA-256 `099661c…9d3c`；锁定组件由 21 增至 22，精确目标类由 38 增至 43。
- `BO4.onEnable` 在模块可运行时跳过已解析 BO4 的冗余 `WriteWithoutComments` 回写；关闭、包锁失败或运行时桥接不可用时继续执行原 writer。
- `BO4.trySpawnAt` 在一次生成内复用第一份 `getBlocks()` 结果，消除第二套完整方块函数对象；不跨生成缓存会被随机方块选择修改的对象，保持 RNG、材质、元数据和生成顺序。
- `BO4Config.loadBlockArrays` 将逐方块 16×16 列前缀扫描替换为线程局部、按精确 `short[][]` 身份失效的 256 项前缀表。
- OTG 配置函数参数使用结果等价的低分配解析器；函数名 lowercase 使用最大 128 项、默认 Locale 变化即清空的 Caffeine 缓存。
- `LayerCustomHead` 的不完整玩家头颅资料改由单线程有界队列解析；渲染立即使用原输入/default skin，正负缓存、in-flight 和队列均有上限，断网不会退回渲染线程同步 Authlib 联网。
- 头颅解析器新增配置代际隔离：旧 executor 中未结束的网络结果不能污染重配置后的缓存，也不能移除新请求标记。
- 新增真实 OTG JAR、Forge 生产 SRG `LayerCustomHead`、解析等价性、BO4 列偏移、异步去重/负缓存/重配置代际和完整 22 组件 Dregora 包锁回归。

## 0.6.1 RLCraft Dregora compatibility and zero-development-write default - 2026-08-15

- 修复 optimizer CoreMod 将 `dev.rlcraft.ice.optimizer.*` 错误加入 transformer exclusion 导致 FermiumASM/Dregora 启动时找不到 `OptimizerRegistry` 的崩溃；CoreMod 现在只排除自己的 hooks 包。
- 新增 RLCraft Dregora 3.9 独立严格包锁，精确固定 DregoraRL、FermiumBooter、FermiumASM/FermiumMixins、EagleMixins、SRPMixins、Entity Culling、Phosphor 和全部已优化组件，共 21 个 JAR。
- 用真实 Dregora 运行时捕获类和 SRP 1.9.21、Lycanites 2.0.8.10、RenderLib 1.4.5、RLFoliage 2.4.2、Ice and Fire 2.0.9 原始 JAR 验证并加入精确类 SHA-256 白名单。
- Lycanites `ObjectManager` 适配器同时支持普通版 getter 的 lowercase 语义与 Dregora getter 的精确、区分大小写语义；两者都只消除重复 `containsKey + get` 探测。
- Ice and Fire 海蛇粒子适配器同时支持旧版 `new int[]{0}` 和 Dregora 2.0.9 的 `new int[0]` 拍击粒子调用图，不改变粒子参数或数量。
- 优化器开发磁盘输出默认关闭：不再自动创建/更新 `ice-optimizer/discovery` 或 `pack-observed.properties`。只有显式开启 `settings.developmentDiskOutput` 或 JVM 参数后才导出适配样本；已有文件保留但不会继续增长。
- 修正 optimizer 与 profiler 模组入口版本常量，全部发布产物统一为 `0.6.1`；构建校验额外要求 optimizer 主 JAR 包含 `OptimizerRegistry` 和两套包锁资源。

## 0.6.0 full client combat/entity optimization - 2026-08-15

- 将战斗优化从 SRP 扩展到 Lycanites、Mo' Bends 与 Ice and Fire；严格包锁由 14 个组件增至 16 个，精确目标类由 23 个增至 38 个。
- 为 Lycanites `TessellatorModel` 与 `VBOModel` 增加有界稳定分组 GPU 批处理；完整 key 包含网格身份、数组身份/长度、VBO、颜色/UV 原始位和 `VertexFormat`，三次热身、每组最多 8 个变体、全局最多 1024 个 Display List，并接入 GPU 硬预算与资源/GL 代际释放。
- 为 Lycanites Animator、`ModelObjPart`、`ModelObjAnimationFrame` 和三个模型基类消除恒等 GL 调用、Iterator 分配、重复字符串类型分派与 10 个逐帧 lowercase 调用；公开可变字段仍逐次验证，父级/offset/中心变换与动画调用顺序保持不变。
- 将 Lycanites `PotionEffects` 的 35 个常量效果查询映射为 20 个原子槽，并按目标实体类型复用附近实体 Predicate；不减少事件、实体 Tick、效果检查或附近实体扫描。
- 优化 Mo' Bends 公共 `ModelPart`，覆盖其接管的玩家、原版怪物和动物：缓存并逐次验证父链拓扑、按索引遍历子模型，并保持 `当前 pre → 父链 pre → 根到当前 local` 与原绘制顺序。
- 为 Mo' Bends Quaternion 增加按 x/y/z/w raw float bits 验证的实例矩阵缓存；Java 8 JVM 已实际定义改写类并执行缓存命中/变化重算回归。`LivingEntityData.calcClimbing` 在非梯子或已落地时避免三个无用方块状态读取，真正攀爬仍调用完整原判定。
- Ice and Fire Tabula 姿态循环把每部件五次 `getCube` 收敛为两个局部查询；海蛇粒子路径复用两个只读语义参数数组，不减少粒子数量、类型或顺序。
- 新增三组真实 RLCraft JAR 回归，覆盖 15 个新增目标的 SHA-256、ASM 调用图、Java 8 类定义和 Mo' Bends 四元数实际执行；全套回归为 `OK (81 tests)`。
- 所有模块可独立关闭并 fail-open；不删实体、不跳 Tick、不降 AI 频率、不改变碰撞、寻路、事件、动画、绘制顺序或画面内容。

## 0.5.0 SRP/Lycanites combat and model batching - 2026-08-15

- 将 SRP 静态网格优化从 `ModelKirin` 扩展到 13 个真实热点模型；逐类锁定 SHA-256、渲染入口和精确 `ModelRenderer.render` 调用数。
- SRP 批处理改为自适应动态关节模型：每个批次根关节的实时位移、旋转点和旋转角仍由原动画驱动，只合并连续稳定至少四次且不少于三个可见节点的分支。
- 每次 GPU 提交前验证全部批处理后代的原始 float 位、显隐、compiled、Display List ID、子节点身份/顺序和 scale；任一漂移当次立即精确遍历原树，并冷却 40 次调用后重试，避免战斗动画反复编译。
- SRP `EntityParasiteBase` 注入行为等价的 ground navigator，在单次 `PathFinder` 生命周期内复用 `WalkNodeProcessor` 节点分类；缓存不跨寻路、不跨 Tick、不保存路径结果。
- SRP `EntityAINearestAttackableTargetStatus` 将只为读取第一个元素而进行的完整排序替换为稳定线性最小值选择，相等候选仍保留原列表先后语义。
- Lycanites `CreatureNodeProcessor` 在一次搜索内缓存 2 个原始节点类型调用和 14 个方块状态调用；支持嵌套上下文，生命周期失配或缓存错误时独立回退。
- Lycanites `ObjectManager.getEffect/getBlock` 消除 `containsKey + get` 的重复 HashMap 探测；模块关闭时仍执行原双探测逻辑。
- 严格包锁新增 Lycanites Mobs `2.0.8.9`，锁定组件增至 14 个、精确目标类增至 23 个；新增真实 SRP/Lycanites JAR 的 SHA、ASM、调用图和 JVM 定义回归。
- 所有新增优化不删实体、不跳 Tick、不降低 AI 频率、不改变寻路结果或模型画面；远程服务器 AI 不在客户端模组作用范围内。

## 0.4.0 split runtime and hidden optimizer UI - 2026-08-15

- 将旧合并版拆成 `iceoptimizer` 与 `iceprofiler` 两个 Forge 模组，版本统一为 `0.4.0`。
- 构建现在生成 optimizer main/core 与 profiler main/core 四个正式 JAR；发布校验会拒绝重复 class、越界包、错误入口或混装 transformer。
- `IceProfilerLoadingPlugin` 只注册只读性能探针，新增 `IceOptimizerLoadingPlugin` 独立注册 RLCraft 精确优化 transformer 与发现目录。
- 优化器主 JAR 不再包含采样、报告、命令、F8/F9/F10、诊断 HUD 或 Dashboard；只安装 optimizer 两个 JAR 时不会启动记录器或创建 Session。
- 优化器常规画面保持完全隐藏，只在打开原版 F3 时于右侧显示两行包锁、可运行模块数和有界队列占用；可通过 `display.showF3Summary` 关闭。
- Profiler Dashboard 和客户端命令移除全部 optimizer 链接，客户端主命令更名为 `/iceprofilerclient`，兼容别名保留 `/iceclient` 与 `/icec`。
- 服务端/集成服务器记录器命令更名为 `/iceprofiler`，保留 `/iceperf` 与 `/iceprofile` 别名。
- 新增 F3 摘要、独立配置默认值、双 LoadingPlugin 隔离与四 JAR 内容验证测试；真实 Better Foliage 2.3.3 回归通过。

## 0.3.0 runtime hardening - 2026-08-15

- 修复单次捕获被触发前样本直接填满 12000 容量的问题：触发前最多占 40%，优先保留最接近触发时刻的样本，剩余容量保证触发后窗口。
- 新增按线程角色加权的有界样本保留，客户端/服务端主线程不会再被大量 Chunk Worker 样本挤出；报告同时记录前后样本数、实际时间覆盖与丢弃数。
- 根因分析与 `stacks.folded` 排除处于 `WAITING/TIMED_WAITING` 且停在 park、队列 take/poll、select 等路径的空闲 Worker，原始二进制捕获仍保留这些样本。
- JVM 堆、GC 与 `getProcessCpuLoad` 改由独立最低优先级线程缓存采集，客户端/服务端游戏线程每秒只读取不可变快照；GC 增量保持单消费者语义。
- 区块渲染队列指标改为优先解析 `ChunkRenderDispatcher.getDebugInfo()`，并对变换后的字段层级与任意 `size()` 队列提供重试式反射回退，不再因一次失败永久显示 `-1`。
- 严格包锁增加独立、不可逆的本次启动拒绝状态；晚加载的 `targetObserved`、`patchInstalled`、`activate` 与 `recordSuccess` 均不能重新启用外部补丁。
- 包锁版本比较规范化 Minecraft `1.12.2-` 前缀，并按目标 JAR 实际元数据修正 OreLib、Dynamic Surroundings、DSHuds 与 RLMixins 版本；13 个锁定组件仍必须全部通过 JAR SHA-256。
- 审查并接纳 Better Foliage 经当前 CoreMod 链变换后的 `AoFaceData` SHA-256，同时加强 AO 参数数据流校验；真实运行时 class 已通过 ASM 变换与 JVM 定义测试。

## 0.3.0 - 2026-08-14

- 将工程扩展为 `ICE RLCraft Client Runtime`，保留全部自动诊断功能。
- 新增客户端专用工作池、世界/资源/GL 代际取消和有界 Agrona MPSC 渲染提交队列。
- 新增堆、Direct、GPU 硬预算，接入 Caffeine、Agrona 与 LZ4，并在最终 JAR 中私有重定位。
- 新增 RLCraft 组件版本/JAR SHA-256 包锁、完整类指纹目录和未知目标发现导出。
- 新增每模块状态机、错误熔断和 F10 优化状态面板。
- 新增独立 `IceClientOptimizerTransformer`；实际目标 JAR 缺失时严格保持原字节码。
- 新增优化内核、包锁、压缩、预算、队列和转换器回退测试。
- 固化当前 RLCraft 客户端的 SRParasites、FoamFix、RenderLib、XaeroLib、Xaero Minimap、Xaero World Map、RLTweaker、OreLib、Dynamic Surroundings、Dynamic Surroundings HUDs、RLMixins、Better Foliage 与 Dynamic Trees 版本/JAR SHA-256。
- 新增 SRParasites 1.9.11 `ModelKirin` 精确适配器：把 144 节点静态后代树的 Java/JNI 矩阵与 `glCallList` 提交合并为一个外层 Display List，同时保留根节点逐实体动画。
- SRP 路径逐次校验后代变换、可见性、子节点顺序、原 Display List ID 与 scale 原始位；任一状态漂移、预算拒绝、类哈希变化或异常都回退原 `ModelRenderer.render`。
- 新增 SRP 合成调用图、真实 JAR SHA/ASM/JVM 定义和纯树快照回归测试；两个渲染入口必须各有且仅有一个根调用。
- 代际计数读取改为零分配原子直读，避免 SRP、FoamFix 和 Xaero 热路径仅为读取 GL 代际反复创建 `EpochToken`。
- 新增 FoamFix `uploadTextureMaxMips` 精确适配器：每次调用只设置一次纹理参数，合并 mip 暂存，使用三槽 PBO + Fence；槽位忙或预算不足时无等待回退 Direct Buffer。
- FoamFix 优化在红蓝立体模式、未知类/JAR、预算拒绝和运行时错误时完整回退原方法，并增加合成字节码测试及真实 JAR 可选集成验证。
- 新增 Xaero World Map `TextureUploader` 精确适配器：保留原上传对象、池化、预算、队列和顺序，将初始化 2560 次 `glFinish` 同步基准替换为 32 对非阻塞 GPU Timestamp Query。
- Xaero 只读取已经 available 的查询结果，保留原 512/256 样本目标、默认估计和同步原版回退，并验证真实 JAR 调用图及 JVM 类定义。
- 新增 RenderLib `processTileEntities` 精确适配器：用受 Heap 硬预算约束的可复用 Agrona 成员表消除批量方块实体合并中的 `pending × loaded` 扫描。
- RenderLib 对自定义相等语义、小列表、预算拒绝、重入和列表异常变化保留原 `List.contains`，同时保持原方块实体注册、区块更新和位置读取顺序。
- 新增 OreLib 3.6.0.1 `OpenGlState` 精确适配器：Dynamic Surroundings 常规状态快照由 16 次同步驱动查询降为 3 次，其余 13 项读取 `GlStateManager` Java 缓存。
- OreLib 路径始终保留 blend、blend equation 与当前纹理单元三个驱动哨兵，并在首次及每 32 次快照执行完整真值校验；任何缓存不一致或反射映射失败立即永久回退该模块的原 16 次查询。
- 新增 OreLib 合成调用图、真实 JAR/类 SHA、ASM/JVM 定义、缓存映射、模块关闭、真值失配与反射失败回归测试。
- 新增 Better Foliage `AoFaceData` 精确适配器：复用每个 Chunk Worker 私有六面 AO 对象中的 `float[12]` 与 `BitSet(3)` 暂存，不改 vanilla AO 调用、数值、方向与写入顺序；关闭模块时保留原分配。
- 新增 Dynamic Trees `pollConnections` 精确适配器：同一线程对同一 baked model、半径和不可变扩展状态进行重复面查询时复用只读 `int[6]`，首次查询仍完整读取六个连接属性。
- 新增两条区块网格路径的类/JAR SHA、调用图变化拒绝、配置关闭、复用/清空语义、真实 JAR ASM 与 JVM 定义测试。
- 修复独立 CoreMod 在 Forge 启动早期直接链接主 JAR `ProbeBridge` 导致的 `NoClassDefFoundError`；两个转换器现在可在主运行时尚不可见时独立初始化。
- CoreMod 使用本地 SHA/探针 ABI 和可重放补丁日志，主运行时初始化后再反射同步模块状态；构建脚本禁止 hooks 源集重新依赖 main 输出，并增加隔离类加载回归测试。

## 0.2.2 - 2026-08-14

- 自动卡顿捕获默认静默，不再向聊天框反复发送捕获完成提示。
- 新增 `client.silentAutomaticRecording` 配置；旧配置升级后也会默认静默。
- F8/F9 主动操作反馈、HUD、F10 Dashboard 和后台导出保持不变。

## 0.2.1 - 2026-08-14

- 默认改为自动卡顿事件会话，无需手动按 F9。
- 自动回填触发前 15 秒的时间线与线程栈，并记录触发后 5 秒。
- 后置窗口安静 2 秒后自动关闭并后台导出。
- 自动报告最短间隔 30 秒，冷却期间的新卡顿继续聚类，避免报告文件爆发增长。
- F8 改为可选事件备注，F9 保留为手动长录制，F10 实时面板及最近根因在自动导出后继续保留。

## 0.2.0 - 2026-08-14

- 将工程从主动区块优化器完整重构为只读的 ICE Profiler。
- 删除区块提前生成、地区文件预热、卸载节流和客户端线程优先级修改。
- 加入固定内存预算、每秒聚合、环形缓冲、调用栈字典去重和重复卡顿聚类。
- 加入客户端帧/Tick、服务端 MSPT、维度 Gauge、区块事件、JVM/GC/CPU、线程 CPU/分配、渲染队列、GPU timer query 和 Netty 旁路计数。
- 加入绝对阈值与中位数/MAD/倍率组合的自动卡顿触发，以及前 15 秒、后 5 秒捕获窗口。
- 加入世界生成、区块 I/O、实体、方块实体、AI、碰撞、事件、网络、渲染、GC 和锁竞争根因分析。
- 加入 Forge 模组/JAR 归属、中文证据、置信度与针对性建议。
- 加入 `.icecap`、中文摘要、CSV、JSON、folded stacks、自包含 HTML 和 ZIP 报告，以及 Session 自动清理和报告对比。
- 加入 F8/F9/F10、HUD、Dashboard、服务端命令和客户端命令。
- 加入独立可选 `ice-profiler-hooks` CoreMod，提供签名/指纹校验和 fail-open 精确计时探针。
- 加入核心数据结构、分析、探针、报告和 ASM 转换测试。

## 0.1.0 - 2026-08-10

- 初始主动优化器实验版本；相关主动行为已在 0.2.0 中移除。
