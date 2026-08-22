# ICE 2.0 数据驱动性能架构方案

状态：设计基线

日期：2026-08-22

前置稳定版本：ICE 1.0.5

目标平台：Minecraft 1.12.2、Forge 14.23.5.2860、Java 8、LWJGL 2、RLCraft / RLCraft Dregora

## 1. 决策摘要

ICE 1.0.5 已经证明结构认证、能力自测、局部 fail-open、资源代际和实机取证能够在大型 1.12.2 整合包中安全运行，但当前执行层仍是围绕原版即时模式渲染和同步世界生成逐点接管的混合架构。继续扩大 Arena、增加 Fence 槽或放宽收益门，只能改善局部指标，无法获得持续的大幅提升。

ICE 2.0 采用新的数据所有权和执行模型：

- 服务端主线程独占可变世界，只发布不可变快照并提交已经验证的结果。
- 工作线程只执行纯 IO、解析、规划、网格构建和路径计算，不直接修改 Minecraft 对象。
- 渲染线程独占 OpenGL，只消费不可变场景增量和已经编码的上传命令。
- GPU 常驻保存已认证场景，负责同帧 HZB、批量可见性测试、间接命令生成和实例化绘制。
- 不兼容内容以对象或 Render Pass 为边界进入 Legacy Island，不再使整个 Terrain、Entity 或 TESR 后端来回切换。
- 正确性认证和性能选择分离：资源加载期认证输出等价，运行期不再持续执行 ABBA 双路径和永久影子副本。

ICE 2.0 是新的执行层，不在 1.0.5 上继续堆叠热路径补丁。1.0.5 保留为可回滚的稳定维护分支。

## 2. 实机基线与问题定义

2026-08-22 最新 14 个实机 Session 的密集区域基线：

- 实际约 25–30 FPS，平均帧时约 35–42 ms。
- GPU 帧时约 20–24 ms，约占整帧的 53–60%。
- CPU load 平均约 52–54%，峰值约 78–80%。
- 实体约 1100–1200，Tile Entity 约 6000。
- 稳态 GC 多为 7–22 ms，不是持续低帧率的主要原因。

现代路径的关键诊断：

- `hzb_captures=529`、`hzb_published=417`，但 `hzb_tested=0`。
- `hzb_view_gate.VIEW_CHANGED=28895`，`hzb_scene_invalidations=48237`。
- Terrain Arena 使用约 `125.8 / 128 MiB`，分配拒绝 `30528` 次。
- Arena/Legacy draw 为 `1841 / 190734`，最后可见所有权约 `32.94%`。
- MDI 提交停在 `855`，indirect fallback `22897`，busy Fence `68855`。
- ChunkAnimator 兼容单独绘制累计 `126707` 次。
- 模型状态重新认证 `57882` 次；现代实体路径仍是一部件一提交。
- OTG 冷文件路径出现 1.1–3.1 秒 Tick，`BO4Config.readResources` 最大约 9 秒，并在一个 46 秒窗口分配约 4.9 GiB。

因此当前不是单一硬件瓶颈。GPU 给出了同画质约 42–50 FPS 的当前下限，但实际帧时还包含明显的 CPU、驱动提交和集成服务器停顿。只有同时改变渲染提交、可见性、场景常驻和世界生成调度，才可能获得显著提升。

## 3. 目标与非目标

### 3.1 目标

- 密集区域的客户端 CPU 渲染与提交时间降至 10–14 ms 以内。
- 遮挡密集区域 GPU 帧时由 20–24 ms 降至约 13–18 ms。
- 固定实机路线 Frame P95 不高于 25–28 ms，P99 不高于 33 ms。
- Terrain 现代所有权超过 95%，稳态分配拒绝为 0。
- 可批处理 Terrain 的 MDI 覆盖超过 90%。
- HZB 在支持场景中真实测试至少 80% 的远距离候选，且零可见闪烁。
- 现代实体/TESR 的可认证绘制调用降低 70% 以上。
- 游戏过程中 OTG/BO3/BO4 同步磁盘打开为 0。
- 固定 30 分钟跑图中不出现大于 250 ms 的世界生成主线程 Tick。
- 不改变世界种子、RNG 调用顺序、结构内容、实体逻辑、存档格式和网络语义。

### 3.2 非目标

- 不以减少实体、粒子、模型、视距或 Tick 频率伪装性能提升。
- 不把 Minecraft 可变世界对象交给任意 Worker 并发读写。
- 不把 AI、药水事件或模组回调直接迁移到 GPU。
- 不要求所有 Shader Pack 在第一阶段进入 Native；未认证 Pass 保持 Legacy Island。
- 不追求 GPU 使用率数字本身；目标是降低帧时和尾延迟。

## 4. 总体数据流

```text
Minecraft / Forge / Mod hooks
            |
            v
  WorldSnapshotProducer + SceneDeltaProducer
            |
            +-----------------------+
            |                       |
            v                       v
  DeadlineWorkScheduler        ServerCommitQueue
  IO / Parse / Plan / Mesh          |
            |                       v
            |               authoritative world
            v
       immutable results
            |
            v
      GpuSceneDatabase
            |
            v
 Persistent Upload Rings
            |
            v
 Occluder Depth -> GPU HZB -> GPU Culling -> MDI / Instanced Draw

Uncertified object or pass ------------------------> Legacy Island
```

## 5. 不可变快照与截止时间调度器

### 5.1 数据所有权

- `WorldSnapshotProducer` 在服务端主线程按 Chunk Section 发布只读方块、光照、碰撞和实体索引快照。
- `SceneDeltaProducer` 在客户端主线程发布 Chunk、Entity、TESR、资源和 Render Pass 增量。
- 快照使用 `worldGeneration/resourceGeneration/glContextGeneration/chunkContentGeneration/configGeneration` 认证。
- Worker 结果包含输入代际和内容摘要；提交前重新验证，失败只丢弃结果，不重放旧状态。
- 不存在 Worker 对 Minecraft `World`、`Chunk`、`Entity` 或 OpenGL 对象的长期引用。

### 5.2 调度 Lane

- IO Lane：两个低优先级线程，负责顺序预取、内容索引和写前压缩。
- Compute Lane：按可用处理器、客户端帧时和集成服务器 Tick 余量动态调整并行度。
- Render Prepare Lane：Chunk Mesh、模型实例数据、命令编码和压缩 CPU 备份。
- Server Commit Lane：服务端主线程以时间预算执行已经排序的世界修改计划。
- Background Lane：远距离预取、缓存压缩、Profiler 报告等无截止时间工作。

### 5.3 调度规则

- 任务具有 deadline、优先级、空间距离、代际和去重键。
- 同一 Chunk/资源的旧任务被新代际覆盖，不在主线程遍历并发队列删除。
- 队列满时按优先级丢弃或合并，不允许主线程等待队列腾空。
- 客户端 Frame P95 或服务端 Tick P95 超预算时，降低 Background 和远距离预取并行度。
- 不使用 `Future.get`、阻塞 Fence、同步文件打开或 Worker join 作为正常帧/Tick 路径。

## 6. ICE Pack Store 与确定性世界生成

### 6.1 预编译内容数据库

进入世界前建立 `ICE Pack Store`：

- 扫描 BO3、BO4、NBT 和 OTG 配置文件。
- 以 canonical path、file key、size、mtime、SHA-256 和配置代际认证源文件。
- 编译为扁平二进制记录，包含字符串表、方块 Palette、资源表、结构列索引和不可变 NBT Blob。
- 使用内存映射读取；稳定命中只检查 Pack 代际和记录 ID。
- 缺失文件和稳定解析错误作为带源身份的负记录保存。
- `WatchService` 或定期目录审计只推进 Pack 代际；摘要仅在发现变化时重算。
- 首次构建使用独立进度页面，允许加载阶段更长，但游戏 Tick 中禁止首次解析。

### 6.2 生成规划器

- Worker 只读取编译对象、确定性 RNG 输入和不可变区块快照，产生 `GenerationPlan`。
- `GenerationPlan` 保存有序方块修改、Tile NBT、计划刻、光照需求和跨 Chunk 依赖。
- 跨 Chunk 结构先取得区域预约，避免两个计划同时写相同位置。
- 服务端主线程按原始顺序验证 Chunk 代际并提交。
- 只有明确证明事件、邻居通知和光照语义等价的路径才允许批量 Section 提交；其余操作仍调用原 `setBlock`。
- Recurrent Complex、Roguelike、Ice and Fire、Lycanites 通过同一 Planner SPI 接入，不建立各自无界线程池。

### 6.3 分配控制

- 编译记录采用 primitive 数组、Palette 和共享 Blob，禁止每次生成重建完整资源对象图。
- 调用方确实需要可变对象时，只在最终边界深复制必要字段。
- 大型临时缓冲来自有界 Arena，任务结束立即归还；不得把 4–5 GiB 短命对象交给 GC。

## 7. RenderDevice 与 FrameGraph

将当前集中式运行时拆分为：

- `RenderDevice`：能力、Context、状态所有权和资源创建。
- `FrameGraph`：Pass 依赖、barrier、可见资源和提交顺序。
- `GpuSceneDatabase`：Terrain、模型、实例、材质和包围盒的稳定句柄。
- `GeometryStore`：分页 GPU 几何内存和压缩 CPU 恢复数据。
- `VisibilityPipeline`：Frustum、同帧 HZB、候选压缩和可见性历史。
- `TerrainRenderer`、`EntityRenderer`、`TesrRenderer`、`ParticleRenderer`、`HudRenderer`：独立数据消费者。
- `LegacyIslandExecutor`：只在明确边界保存、恢复和污染状态镜像。
- `CompatibilityRegistry`：按类、模型、材质、程序和 Pass 记录认证结果。

FrameGraph 至少包含：

1. 场景增量上传。
2. 近场与稳定遮挡物深度预通过。
3. HZB mip 构建。
4. Terrain / Entity / TESR GPU culling。
5. Opaque / Cutout MDI。
6. 对应顺序位置的 Legacy Islands。
7. 有序 Translucent、Particles、Hand、Post Process 和 HUD。

透明对象不得跨语义 barrier 重排；Opaque 只在状态和 Shader 等价时重新排序。

## 8. 分页 Geometry Store

### 8.1 内存模型

- 默认页大小 16 MiB，按 Terrain Layer、Vertex ABI 和用途分池。
- 从小规模开始按需求增长，在用户 GPU 预算内运行；不再固定为一个 128 MiB Buffer。
- 使用稳定逻辑句柄和 GPU indirection table，允许对象在不改变上层引用的情况下迁移。
- Region 以 16×16 Chunk 为驻留和淘汰单元，页内采用大小分级分配器。
- 碎片超过阈值时用 GPU Buffer Copy 迁移到新页，Fence 完成后原子切换描述符。
- 远距离、长时间不可见 Region 整页淘汰；重新可见时从压缩 CPU Mesh 恢复。

### 8.2 上传与 Fence

- 顶点、索引、实例和间接命令分别使用持久映射环。
- 每个环至少 8 个 Frame Segment，并按 GPU 实际延迟自适应扩展到 12 个。
- 正常路径只在 Pass 或 Frame 尾部建立一个 Fence，不为每个 MDI run 建 Fence。
- 不支持 persistent mapping 时使用多缓冲 orphan/subdata 环，仍禁止等待。
- GPU 落后时推迟非关键上传；已可见且无现代副本的对象走对象级 Legacy Island。

### 8.3 Terrain 数据

- Chunk Worker 输出规范化 `TerrainMeshBlob`，包括顶点、索引、Layer、Bounds、材质段和内容摘要。
- Native Shader 路径使用索引三角形；OptiFine 认证路径保留其完整扩展 Vertex ABI。
- Chunk 原点、动画起始时间、Region 原点和 Layer flag 存于 per-draw 数据，不再通过每 Chunk matrix push/pop 表达。
- ChunkAnimator 动画直接由实例元数据计算，动态 Chunk 强制可见但仍能和相同状态的动态 Chunk 批量提交。

## 9. 全 GPU 同帧 HZB

旧方案的 CPU 深度回读和完全相同视图门被整体删除。

新流程：

1. 绘制近距离环、新上传 Chunk、最近发生几何变化的 Chunk，以及上一帧稳定大遮挡物的深度。
2. Compute Shader 对当前帧深度生成保守 HZB mip。
3. GPU 使用当前相机矩阵测试对象 AABB，不需要相机位置逐位相同。
4. 可见性结果直接将 count 写入 MDI command，或写入压缩后的命令列表。
5. 新对象和变化对象至少两帧强制可见；包围盒按运动速度扩张。
6. 历史只用于选择遮挡物和迟滞，不作为单独隐藏新几何的依据。

安全规则：

- 标准深度与反向深度使用各自正确的 reduction 运算。
- AABB 穿过近裁剪面、投影不有限、Mip 覆盖不完整或 Shader/FBO 状态未知时判定可见。
- Chunk/Entity/TESR 使用各自局部 geometry generation；一个 Chunk 上传不能使全场历史失效。
- 连续低遮挡收益时进入有冷却时间的 `LOW_YIELD_BYPASS`，避免开放地形额外 GPU Pass。
- HZB 决策不读回 CPU；Profiler 只延迟读取聚合计数和时间 Query。

## 10. 实体与 TESR 实例化

### 10.1 模型 IR

- 捕获完整模型网格、部件层级、材质、渲染层和动态参数槽，而不是单个 Display List。
- 静态顶点常驻 Geometry Store。
- 每帧实例流只包含世界变换、部件/骨骼矩阵、颜色、亮度、hurt/overlay 和装备索引。
- 相同 Mesh 与材质的实例使用 Instanced Draw 或 MDI。
- Vanilla ModelRenderer、Lycanites 和已认证 Mo' Bends/Tabula 模型分别实现 ABI Adapter。

### 10.2 TESR 数据库

- 按 Chunk Section 维护 Tile Entity 空间索引和 Render Bounds。
- 静态 TESR 只在方块状态、NBT、资源或相邻连接发生变化时更新 GPU 数据。
- 动态 TESR 仍逐帧产生小型实例数据，但不重建静态网格。
- GPU Frustum/HZB 先剔除，再进入材质批次。
- 未认证 TESR 在整个对象边界进入 Legacy Island，不在每个模型部件之间切换状态。

### 10.3 顺序语义

- Opaque 实体可按状态和 Mesh 排序。
- Transparent、Outline、Multipass、装备层和事件回调保留原 barrier 与次序。
- 任何模组可能观察 framebuffer、GL Query 或调用未知直接 LWJGL 时，当前对象完整进入 Legacy。

## 11. Shader、纹理与首次使用预热

### 11.1 Shader ABI

- 无 Shader Pack 时使用 ICE 自有等价 Shader，统一读取 per-draw/per-instance 数据。
- Shader Pack 激活时，只有 program、vertex ABI、draw buffers、uniform 和图像输出均认证的 permutation 才注入 ICE Draw ID 接口。
- 未认证 permutation 只使对应 Pass 进入 Legacy，不关闭其他现代 Pass。
- Shader、FBO 和资源状态只在 Pass 边界认证；正常 Draw 不执行 `glGet*`。

### 11.2 纹理流

- 动画纹理只推进逻辑帧；不可见 Sprite 延迟 GPU 上传，重新可见时直接上传最新内容。
- 大批纹理更新通过 persistent PBO ring 合并，每帧最多一次 Fence。
- 小型更新继续直接 subimage，避免固定 Fence 成本。
- 资源加载阶段建立药水、HUD、常用粒子、模型与 Shader 的 `FirstUseWarmupManifest`，消除首次喝药或首次特效的懒加载停顿。

## 12. 兼容认证与稳定选择

每个资源代际的对象状态只有：

- `CERTIFIED_NATIVE`：输出等价且 ABI 完整，稳定使用现代路径。
- `LEGACY_ISLAND`：未认证或明确需要原调用边界。
- `QUARANTINED`：本代际发生正确性或提交状态不确定错误，不再自动重试。

认证流程：

1. 字节码、字段和方法 ABI 认证。
2. 离屏资源与状态恢复自测。
3. 小规模输出摘要或图像 Oracle。
4. 只在固定回放路线中做性能资格测量。
5. 发布后冻结本代际选择；运行期只做极低频正确性抽样。

删除稳态 ABBA、永久 Legacy twin 和父后端级性能振荡。性能较差的 Native Adapter 在下一资源代际或版本之前保持 Legacy，不在游戏中反复重测。

## 13. C++ / Native 边界

Native 不是 ICE 2.0 的前置条件。JNI 只能用于纯批量内核：

- SHA-256、压缩、Pack Store 解码。
- SIMD 顶点规范化、索引生成和 Mesh 打包。
- 大型 DirectBuffer 的无对象转换。

约束：

- JNI 只接受 DirectBuffer、primitive 和长度，不持有 Minecraft Java 对象。
- Native 不修改 World，不发 Forge 事件，不拥有 OpenGL Context。
- 每个 Native 内核都有 Java 参考实现和随机差分测试。
- 启动自测失败、DLL 缺失或 ABI 不匹配时只退回 Java 内核。
- 不把 JNI 崩溃风险放入存档写入和世界权威路径。

渲染的大幅提升主要来自 GPU 驱动可见性、实例化和消除提交泡沫，而不是把同一逐对象算法翻译成 C++。

## 14. 代码迁移边界

保留并演进：

- Profiler、Session、根因归因和 renderer diagnostics。
- Class fingerprint、目标结构认证与真实模组 JAR 回归。
- generation token、FatalErrors、资源账本和故障注入思想。
- 配置侧选择、主/Core 分包和可复现发布链。

替换：

- 集中的 `ModernRendererRuntime` 数据平面。
- 固定单 Buffer 的 `LwjglTerrainArena`。
- CPU readback 的 `LwjglDepthHistory`。
- 一部件一 `glDrawArrays` 的 `LwjglModelMeshCache` 路径。
- 稳态 `AdaptiveBackendController` ABBA 选择。
- 单一通用 Client Worker Queue。
- 首次调用才同步读取的 OTG file cache。

ICE 1.0.5 分支只接受崩溃、存档安全和严重兼容性修复；ICE 2.0 在独立分支和独立配置命名空间开发。

## 15. 实施阶段

### Phase 0：确定性回放与度量

- 固定世界、相机路线、输入、资源包和热/冷缓存条件。
- 保存 Render Pass、候选对象、场景增量和世界生成计划摘要。
- 建立图像 diff、世界结果 hash、Frame/GPU/Server Tick 分位数。

完成门：同一 1.0.5 基线重复运行方差可解释，Profiler 对 CPU/GPU 等待不误归因。

### Phase 1：运行时基础与 Pack Store

- 实现快照、Deadline Scheduler、提交队列和 Pack Store。
- OTG/BO3/BO4 运行期不再首次打开源文件。
- 接入配置代际、文件监控和确定性生成计划。

完成门：固定生成路线世界 hash 相同，运行期同步 OTG 文件打开为 0。

### Phase 2：RenderDevice、Geometry Store 与 Terrain

- 实现 FrameGraph、分页 Arena、persistent upload/command rings。
- Terrain Mesh Blob、Region 驻留、GPU indirection、MDI。
- ChunkAnimator 转为实例数据。

完成门：Terrain 现代所有权超过 95%，分配拒绝为 0，MDI busy fallback 为 0。

### Phase 3：同帧 GPU HZB

- 深度预通过、GPU pyramid、AABB culling、间接命令写入。
- 局部代际、强制可见、低收益 bypass。

完成门：30 分钟路线零闪烁，支持场景 `tested/candidates >= 80%`，遮挡密集段 GPU P95 明显下降。

### Phase 4：实体/TESR 实例化

- 完整模型 IR、实例流、TESR 空间数据库和对象级 Legacy Island。
- 接入 RenderLib、Lycanites、Mo' Bends、Tabula 等认证 Adapter。

完成门：实体/TESR 可认证 Draw Call 降低至少 70%，密集段 Client CPU P95 降低至少 35%。

### Phase 5：世界生成、路径与首次使用尾延迟

- Recurrent Complex、Roguelike、Myrmex、Lycanites Planner。
- 路径 snapshot/evaluate/validate/commit。
- 药水和资源 FirstUseWarmupManifest。

完成门：固定路线无大于 250 ms 的已适配生成 Tick，首次喝药不产生大于 100 ms 的资源懒加载长帧。

### Phase 6：Shader Pack 和可选 Native

- Shader ABI Adapter 与实机图像认证矩阵。
- 只在纯批量内核收益明确时加入可选 Native。

完成门：不支持的 Shader Pack 稳定停留于 Pass 级 Legacy；支持的 permutation 无图像差异和状态泄漏。

## 16. 测试矩阵

- 单元：分配器、句柄代际、任务去重、Pack 记录、命令编码、透明顺序。
- 属性测试：随机 Mesh、分页迁移、GenerationPlan 排序和 Java/Native 差分。
- 故障注入：OOM 预算拒绝、Fence 不完成、Context loss、文件中途变化、Worker 异常、提交状态不确定。
- 字节码：真实 Dregora 与普通 RLCraft 模组 JAR 的描述符、调用图和 Core-only 类加载。
- 图像：固定相机路线、ChunkAnimator、天气、传送门、Outline、Multipass、Shader Pack。
- 世界结果：相同 Seed 的 Chunk NBT、结构方块、Tile NBT、计划刻和 RNG 摘要。
- 压力：1200 实体、6000 Tile Entity、持续新区块生成、资源重载和换维度。
- 长稳：至少 60 分钟 Fence/Query、RAM、Direct、VRAM、队列和句柄泄漏检查。

## 17. 发布验收表

| 指标 | 1.0.5 实机 | ICE 2.0 发布门 |
| --- | ---: | ---: |
| 密集区域 FPS | 25–30 | 常规 38–50；遮挡密集目标 45–70 |
| Frame P95 | 约 35–42 ms | <= 25–28 ms |
| GPU Frame | 约 20–24 ms | 遮挡密集目标 13–18 ms |
| Terrain ownership | 约 32.94% | >= 95% |
| Arena allocation reject | 30528 | 稳态 0 |
| MDI busy Fence | 68855 | fallback 0 |
| HZB tested | 0 | 支持场景候选 >= 80% |
| ChunkAnimator compatibility draws | 126707 | GPU 实例路径，不产生逐 Chunk fallback |
| 模型状态重新认证 | 57882 | Pass 边界认证，正常 Draw 为 0 |
| 游戏中 OTG 同步打开 | 多次秒级 | 0 |
| 已适配世界生成长 Tick | 最大数秒 | 固定路线无 >250 ms |

开放地形中所有内容都真实可见时，当前 GPU 仍可能把帧率限制在约 42–50 FPS。ICE 2.0 不承诺在无法剔除且不降低画质的场景中突破硬件吞吐，但必须消除 CPU/提交空泡和秒级软件停顿。

## 18. 发布与回滚策略

- 1.0.5 保持稳定标签和 Release，不直接覆盖同版本字节码。
- 2.0 使用独立分支、缓存目录、配置段和诊断 format 版本。
- Alpha 只用于固定回放与副本世界；Beta 才进入真实存档的只读/可回滚验证。
- 不将未完成的 Terrain、HZB 或实体数据面单独部署到用户正式实例。
- 每个阶段必须可通过单一顶层开关退回完整 1.0.5 执行层，且回滚不修改存档格式。
- Release 必须包含源提交、签名/哈希、四个 Main/Core JAR、安装说明、已知限制和固定实机验收报告。

## 19. 下一会话首要工作

1. 冻结并发布 1.0.5 稳定基线。
2. 从新分支建立 ICE 2.0 的包结构、设计接口和测试夹具，不直接修改 1.0.5 热路径。
3. 先完成 Phase 0 的确定性回放格式和指标契约。
4. 以 Pack Store 与 Geometry Store 两条数据面为第一批实现目标。
5. 在 Terrain + HZB 完整闭环之前，不通过放宽回退条件宣称现代后端提升。
