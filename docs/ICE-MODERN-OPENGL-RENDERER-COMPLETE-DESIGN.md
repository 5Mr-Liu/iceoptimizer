# ICE 现代 OpenGL 渲染器完整设计与实施规范

> 文档日期：2026-08-20
>
> 当前实现与发布版本：ICE `1.0`
>
> 当前代码基线：ICE `1.0`
>
> 状态：生产混合渲染器、自动验证、源码尾审与隔离发布链已完成；真实 OpenGL/性能/长时 soak 待外部验收
> 适用环境：Minecraft 1.12.2、Forge 14.23.5.2860、OptiFine G5、RLCraft Dregora 231 模组环境

## 1. 最终结论

ICE 应实现的不是“把每一条旧 OpenGL 指令逐帧翻译成新指令”的通用模拟器，而是一个保留原有渲染语义、在稳定边界捕获数据、将静态结果一次编译并长期缓存的混合渲染器。

正确模型是：

```text
Minecraft / Forge / 模组渲染语义
                ↓
      FrameCoordinator + PassGraph
                ↓
   ┌────────────┴────────────┐
   │ OF_COMPAT_REGION        │ ICE_NATIVE
   │ OptiFine 原格式与程序   │ Arena/MDI/实例化/HZB
   └────────────┬────────────┘
                ↓
       Legacy GL Compatibility Island
                ↓
       OpenGL Compatibility Context
                ↓
             GPU 驱动与 GPU
```

它仍使用 OpenGL 4.x Compatibility Context，不要求迁移 LWJGL3，也不切换纯 Core Profile。能够证明正确且有收益的高频路径进入现代后端；未知原始 GL、自建 FBO、自建 Shader、Display List、即时模式、同步读回等作为旧式兼容岛原样执行。

“翻译”只能发生在高层语义边界，并满足以下条件：

- 区块顶点在区块重建时生成一次，随后驻留 GPU；不能逐帧重新转换。
- 稳定实体模型只缓存网格，每帧只提交矩阵、光照、颜色和材质参数。
- 粒子继续执行原 CPU Tick、RNG、碰撞和生命周期，只把认证 billboard 的绘制改成实例流。
- 模组事件、取消、顺序、异常传播、透明排序和 OptiFine pass 生命周期保持不变。
- 任何现代路径都必须通过能力自测、输出校验、配对性能测量和持续回归监控；无收益或异常时只回退对应子后端。

这种设计有效的原因不是“现代 API 名字更新”，而是消除重复 CPU/驱动工作、减少状态切换和小上传、提高 GPU 数据局部性，并安全减少最终不可见的几何和像素工作。

## 2. 不可妥协的约束

完整实现必须同时满足：

1. 不降低分辨率、画质、视距、实体距离、粒子数量、模型精度、光影质量或阴影质量。
2. 不跳 Tick，不修改 RNG、碰撞、AI、世界生成或最终世界结果。
3. 不按 CPU/GPU 型号、核心数、P/E 核、核显/独显建立静态档位。
4. Forge、OptiFine、RenderLib 和模组渲染事件的次数、取消、顺序与可观察行为保持一致。
5. 未认证路径必须安全回退原实现，不能通过猜测兼容。
6. 所有队列、缓存、Native 内存、GPU Arena、Fence 和 Query 都有明确上限。
7. 所有 GL 调用只允许在 Minecraft 渲染线程；Worker 只生产普通内存中的不可变 payload。
8. 实现目标是完整后端，不以只支持原版方块的最小原型作为交付结果。
9. 实施和测试不得直接覆盖真实 Dregora 实例；只能使用隔离 `mods` 目录和可恢复的测试副本。
10. 失败必须 fail-open：恢复原渲染，而不是黑块、缺实体、错误遮挡或崩溃。

## 3. 实测基线与“接近翻倍”的硬预算

主要参考 Session：

```text
D:\Program Files\Mcserver\rlcraftDregora\.minecraft\versions\RLCraft Dregora\ice-profiler\sessions\20260816-235520-916
```

在 `FPS >= 60` 的近稳态 20 个采样点中：

- FPS 中位数：`107.5`
- 整帧中位数：`9.32 ms`
- GPU 中位数：`7.04 ms`
- 区块编译队列：`0`
- 上传队列：约 `1.5`
- 地形提交采样权重：约 `24%`
- 实体/TESR：约 `23%`
- 可见性：约 `8%`
- 动画纹理：约 `7%`
- Swap：约 `3%`，没有客户端限帧睡眠

配置已经排除游戏内硬限帧：

```text
maxFps=260
enableVsync=false
ofSmoothFps=false
ofSmoothWorld=false
useVbo=true
ofRenderRegions=false
ofSmartAnimations=false
shaderPack=OFF
```

从 `107.5 FPS` 达到严格两倍吞吐的帧预算是：

```text
1000 / (107.5 × 2) = 4.65 ms
```

CPU 和 GPU 是重叠流水，不能简单相加。最终条件是：

```text
max(CPU ready-to-present, GPU execution, present/sync) <= 4.65 ms
```

为抖动和提交延迟留余量，工程目标为：

- Frame P50 `<= 4.40 ms`
- GPU P50 `<= 4.40 ms`
- 参考场景真实吞吐 `>= 2.0x`
- 代表性场景集合几何平均 `>= 1.8x`
- P95/P99、1% low 和超过 `16.7 ms` 的帧频率不得恶化

一阶子系统预算：

| 子系统 | 当前估算 | 工程目标 | 需要节省 |
|---|---:|---:|---:|
| 地形列表与提交 | 2.24 ms | <= 0.55 ms | 约 75% |
| 实体/TESR | 2.14 ms | <= 0.80 ms | 约 63% |
| 可见性 | 0.75 ms | <= 0.18 ms | 约 76% |
| 动画纹理 | 0.65 ms | <= 0.12 ms | 约 82% |
| 其他路径 | 3.54 ms | <= 2.75 ms | 约 22% |

当前 GPU 本身已有 `7.04 ms`，要达到 `4.40 ms` 还需减少约 `37.5%` GPU 工作。因此只减少 Draw Call 或只替换区块渲染器不可能翻倍。必须同时覆盖地形、可见性、热点实体/TESR、粒子、纹理上传、HUD/字体，以及不改变图像的 GPU 过度绘制和阴影几何削减。

## 4. 当前代码状态与缺口

工作区：

```text
D:\Program Files\pycharmProject\minecraftMode\ice
```

当前状态：

- `gradle.properties` 与本次发布产物版本均为 `1.0`。
- 第 21 节第 1–12 项已经有生产源码和运行时接线，不再只是 `RENDER_SUBMISSION` 的有界队列：地形、可见性、实体/TESR、粒子、动画纹理、HUD/字体和 ShaderPack 都有现代候选路径、验证、在线收益状态机、独立熔断器和 Legacy 回退。
- 2026-08-21 对现代渲染零命中和喝药探针修复后的当前源码执行完整 `gradlew test`，并提供本机可用的真实 OptiFine G5、OTG 9.7、Dregora 专用只读依赖、Minecraft client/Forge SRG JAR 与 notch→SRG 映射：182 个测试类、682 项测试、0 failure、0 error、8 skipped。跳过项仅对应本机缺失的 Xaero/Better Foliage 运行时样本和六项普通 RLCraft 旧版精确基线。
- 当前源码的 `build`、`reobfJar`、`reobfProfilerJar`、`reobfShadowJar`、`optimizerBundleZip` 和 `verifySplitJars` 均成功。
- 全部 reobf JAR 在 ForgeGradle 处理后执行确定性规范化；连续两次独立干净全构建的 optimizer/profiler Main、Core、optimizer bundle 与 combined-dev 六个 SHA-256 均逐字节一致。
- 真实 Dregora 启动画面暴露的字体 Core/Main 边界已经修复：全部注入型接口 ABI 只存在于 Core JAR，并由无父类加载器链接回归和 `verifySplitJars` 双重约束。
- 新采样暴露的 OptiFine G5 后变换不兼容也已修复：地形容器坐标/List/boolean 字段按认证方法与描述符解析；可见性完整保留 OptiFine `Deque`、对象池、三张 render-info 列表、五参数 offset、frustum 缓存和 packed path；动画纹理不再错误要求插值方法必须为 private。真实 OptiFine patch + notch→SRG remap 后的四个生产目标已通过断言型端到端回归。
- 渲染线程每 120 帧发布不可变命中快照，F3 与 Profiler 可直接看到 Arena/Legacy 上传和绘制、multi-draw、MDI 命令、各类回退原因及每个后端的 lifecycle/收益样本；报告线程不会遍历实时 GL 对象。Profiler 使用纯反射可选读取并输出 `optimizer-renderer.txt`，且不再链接 optimizer-only fatal ABI。
- 四个发布 JAR 的 manifest、主/Core 类边界、重复类、依赖重定位和 bundle 内容已经独立审计；主优化器中没有未重定位的 Agrona、Caffeine 或 LZ4 类名/字节码引用。
- 隔离 `mods` fixture 已完成空目录首装、同名旧四包升级、备份保真和次轮幂等哈希验证，随后已清理；用户明确批准后，真实 Dregora 已部署本轮四个 JAR，旧包保存在工作区 rollback。
- 自动故障注入和源码尾审已经覆盖 hooks fatal、HUD/纹理/模型不安全重放、Context-loss 资源放弃、Fence/Query/VBO/PBO 所有权、Deflater native 生命周期及 ChunkSave 在线 Worker 策略。
- 中断后的发布尾审进一步覆盖 `ReportWriter` 发布/ZIP cleanup、逐能力诊断、FBO/FBP/HZB/MDI 完整状态沙箱、ShaderPack 安全接管、HUD 空提交、Profiler 栈字典溢出与完整窗口冻结，以及 OTG 同步解析缓存的强文件身份、配置代际、深复制、稳定负缓存和 fatal 异常等价。后续真实 Session 又确认 LWJGL 2 三类多值 `glGet*v` 均要求至少 16 个缓冲元素；FBO 与启动/HUD 状态工作区已同时修复，Timer Query 自测改用独立 250 ms 有界退休窗口，避免现代后端因自测假阴性保持零命中。
- 尚未完成的项目只剩必须在真实游戏与真实 OpenGL 驱动中执行的外部验收：画面 A/B、ShaderPack 图像认证、随机化 ABBA 性能、new-chunk Frame/GPU P95/P99/1% low 和长时间 RAM/VRAM/Fence/Query soak。

## 5. 渲染器总体架构

### 5.1 FrameCoordinator 与 PassGraph

`FrameCoordinator` 是唯一帧协调者，负责：

- 分配 `frameId`、`viewId` 和各类 generation。
- 建立与原版/OptiFine 一致的 pass 顺序。
- 决定每个 pass、renderer、vertex format 和 shader permutation 使用哪个后端。
- 维护现代状态镜像、硬 barrier 和 Legacy Island 切换。
- 在帧边界执行资源发布、延迟回收和后端状态迁移。
- 关联 CPU/GPU Profiler 数据。

`PassGraph` 至少表示：

- sky/weather
- shadow terrain/entity/TESR
- main solid/cutout
- entity/TESR pass0/pass1/multipass/outline
- translucent
- particles/lit particles
- hand
- deferred/composite/final
- HUD/GUI
- portal/递归世界视图

它只描述原有顺序与依赖，不允许为了合批改变可观察的 pass 语义。

### 5.2 双后端

#### `OF_COMPAT_REGION`

兼容优先后端：

- 保留 OptiFine `SVertexBuilder` 的完整扩展顶点格式。
- 保留 block/entity ID、tangent、midTexCoord、AO、lightmap、tint 和自定义颜色。
- 保留 `gbuffers_*`、shadow、deferred、composite、final、FBO、uniform 和 drawbuffer 生命周期。
- 复用 OptiFine `VboRegion/VboRange` 与 `glMultiDrawArrays`，或经过验证的等价批量提交。
- 不能和 ICE 自建区域 Arena 同时持有并绘制同一层。
- 任意 shader program 无法验证时，整段 pass 或具体调用点回退此路径。

#### `ICE_NATIVE`

高收益现代后端：

- Persistent Mapped GPU Arena，并提供 orphan/subdata 回退。
- Multi Draw Indirect；能力不足时降级为 multi-draw 或有界连续 draw。
- SSBO、base-instance 或实例属性保存 per-draw/per-instance 数据。
- 连续 primitive section grid、CPU hierarchy 和保守 HZB。
- 稳定实体模型 VBO 与部件矩阵实例数据。
- 粒子实例流。
- 动画图集 PBO/Persistent ring。
- HUD/字体动态流。
- 自有无光影等价 Shader；光影程序只有完整转换和图像验证后才进入 Native。

### 5.3 Legacy GL Compatibility Island

进入未知原始 GL 模组前必须：

1. Flush 所有待提交的现代批次。
2. 恢复原调用点应该看到的 FBO、program、VAO、VBO/IBO、纹理单元、矩阵、blend、depth、cull、viewport、scissor、stencil 和 color mask。
3. 原样执行模组代码。
4. 返回后将现代状态镜像标记为 unknown/dirty。
5. 下一现代批次完整重绑定所需状态。

以下调用属于硬 barrier：

- `glBegin/glEnd`
- Display List
- 自建 FBO 或 Shader program
- `glReadPixels/glGetTexImage`
- Query、Fence、同步读回
- 未跟踪的直接 LWJGL 调用
- 可能观察 framebuffer 的 Forge/模组事件
- Portal 递归世界渲染

正式运行不能靠每次 `glGet*` 重建状态；应使用软件状态镜像。`glGet*` 仅限认证和抽检，否则驱动同步会抵消收益。

## 6. 为什么翻译层不会天然负优化

旧路径的主要成本近似为：

```text
C_old = N × (Java/LWJGL/driver validation + bind + draw)
      + small uploads
      + redundant state
      + avoidable GPU work
```

现代路径近似为：

```text
C_new = packet capture
      + changed-data upload
      + M × batch draw
      + compatibility barriers
      + remaining GPU work
```

其中 `M << N`，并且静态顶点转换成本跨很多帧摊销。只有满足以下不等式才允许长期启用：

```text
saved driver/submission/upload/GPU cost
>
packet capture + batching + barrier + validation cost
```

会造成负优化的实现必须明确禁止：

- 逐条拦截并模拟全部 OpenGL 调用。
- 每帧重新转换静态顶点或复制完整模型。
- 同一资源在 OptiFine 和 ICE 两套后端重复常驻。
- 每个对象在现代/旧路径之间往返切换。
- 为合批创建大量短命对象、Map、List 或装箱值。
- 当前帧等待 Fence/Query。
- 使用同步 `glGet*` 验证普通帧。
- 对透明对象或事件边界做不安全重排。
- 在像素受限场景把 MDI 虚报为 GPU 着色收益。

## 7. 地形完整实现

### 7.1 接入原则

继续让原版 `RenderChunk.rebuildChunk`、Forge baked model、CTM、Better Foliage、Dynamic Trees、OptiFine 自定义颜色生成顶点，避免重新实现 231 个模组的方块模型语义。

构建结束后发布不可变 `ChunkMeshPayload`，至少包含：

```text
worldGeneration
resourceGeneration
contextGeneration
shaderGeneration/permutation
vertexFormatGeneration
viewFrustumGeneration
layer
raw or normalized vertex data
vertexCount/stride
chunkOrigin/regionOrigin
AABB
transparent sort state
material segments
content checksum
```

Worker 只能构造 payload，不能创建、上传或删除 GL 资源。

### 7.2 GPU Arena

Arena 键至少包含：

```text
world + resource + GL context + shader permutation + vertex format + layer
```

分配器使用有界 page 和可合并空闲区间：

1. 渲染线程从有界提交队列消费 payload。
2. Persistent Mapping 可用且通过自测时写入未被 Fence 占用的 page。
3. 不可用或实测更慢时使用 orphan/subdata ring。
4. 新 handle 完整写入后才原子发布。
5. 旧 range 等相关 Fence 完成后回收。
6. 绝不等待忙 Fence；饱和时保留旧 mesh，或者走兼容上传。
7. 所有 offset、size、stride 和乘法使用 checked `long`，调用 GL 前验证可安全缩窄。

当前 `ChunkVboUploadBridge` 在 Native 后端启用时只能服务 legacy VBO，不能对同一层重复 staging。

### 7.3 Draw Command

Native 路径的每个可见区块命令至少为：

```text
count, instanceCount, first/baseVertex, baseInstance
```

`baseInstance` 指向 chunk origin、light/material 和 draw metadata。不能假定任意 OptiFine ShaderPack 支持 `gl_DrawID`；兼容路径只在相同 region、相同状态且连续的 draw 上批处理。

### 7.4 透明层

透明层不使用 OIT，也不按材质重排：

- 保留 `BufferBuilder.sortVertexData` 的区块内 quad 顺序。
- 保留 `RenderGlobal` 产生的远近区块顺序和稳定 tie-break。
- MDI command 按相同顺序排列。
- 禁止对 translucent 使用可能产生错误否定结果的 HZB 剔除。
- 无法提供正确 per-draw origin 时回退逐区块兼容路径。

## 8. 可见性与保守 HZB

CPU 可见性使用连续 primitive section grid：

- `CompiledChunk` 六向连接压成 bit mask。
- AABB、状态和访问标记使用数组与 generation stamp。
- 不在热路径创建临时 Set/List/Iterator。
- 保持原 BFS、spectator、视锥和 render-info 顺序。
- CPU frustum 是第一级；GPU HZB 只能做第二级保守剔除。

HZB 必须满足：

- 只能剔除可证明完全遮挡的 opaque 对象。
- 不确定时必须绘制，允许 false visible，禁止 false occluded。
- 相机传送、FOV/分辨率变化、维度切换和 depth convention 变化立即清空历史。
- Shadow pass、透明层、写入/读取特殊 depth 的未知 ShaderPack 默认禁用。
- Query/HZB 结果延迟消费，不能同步读取。
- 若构建 HZB 的 GPU 成本高于减少的绘制成本，该后端自动回退。

Opaque 尽量 front-to-back 提交以利用 Early-Z，但不得跨越可观察事件和状态边界。

## 9. 实体、TESR 与模型后端

RenderLib 1.4.5 已经接管 RenderGlobal、RenderManager 和 TESR Dispatcher。ICE 必须保留 RenderLib 的唯一一次实体/TESR 遍历，在最终 Draw Emitter 分流，禁止再做第二次遍历。

Java renderer 仍在原来的时间和线程执行。只有认证 draw site 才记录：

```text
meshHandle
model/part matrices
texture/material
color/lightmap
blend/depth/cull
pass
original sequence number
event scope
generation keys
```

必须保持：

- Forge Pre/Post 和取消结果。
- `shouldRenderInPass`、pass0/pass1、multipass、outline。
- name tag、layer、发光层和手动事件。
- 事件次数、顺序和异常传播。
- 透明对象的原始 sequence。

可批处理范围：

- 同一认证 renderer 内稳定 `ModelRenderer` mesh。
- 连续、状态相同且顺序不变的 draw。
- 已证明不存在 framebuffer 观察和透明依赖的实体族。
- FastTESR 原有 batch。
- 专用适配器中的稳定模型。

模型缓存 key 必须覆盖模型/资源代际、装备、姿态拓扑、材质、CEM、随机实体、emissive、自定义 layer 以及会改变几何或 UV 的 NBT。动态几何、未知 CEM、直接 GL 分支一律回退。

高优先级完整适配对象：

- Lycanites OBJ/VBO model group：共享网格页、材质桶、部件实例数据。
- SRP Kirin/Heblu 和大型 `ModelRenderer` 树：静态几何 + 当前部件矩阵。
- Mo' Bends：刚体部件适配；剑轨、披风、透明层保留专用/旧路径。
- Ice and Fire/LLibrary：统一矩阵记录桥，未覆盖实体逐个回退。
- 常见 dropped item、箱子、告示牌和 FastTESR。
- Forge `IBakedModel` 的稳定实体/物品路径。

## 10. 粒子后端

粒子的 CPU Tick、RNG、碰撞、生命周期和模组回调不变。认证标准 billboard 每帧只写实例：

```text
interpolated position
scale/rotation
color/alpha
light
sprite UV
original sequence
```

顶点 Shader 展开四边形。实例顺序与原列表一致；layer、纹理、blend、depth-mask、Shader 或事件变化时 flush。

Fancy Block Particles 是重要 batching breaker：`FBPParticleBlock` 会在单粒子内部多次 Tessellator flush。必须实现专用 Render Packet 适配器；无法证明等价的特殊粒子继续走 legacy。不能把粒子 simulation 搬到 GPU，因为这会改变碰撞、RNG 和回调语义。

## 11. 动画纹理、图集和上传

接入 `TextureMap.updateAnimations`、`TextureAtlasSprite.updateAnimation`、`TextureUtil.uploadTextureMipmap` 及现有 FoamFix/ICE Bridge。

规则：

- CPU 动画时间和帧索引始终更新。
- 只停止当前不可见 sprite 的 GPU 像素上传；重新可见时立即上传当前正确帧。
- 一帧共用有界 PBO/Persistent ring。
- 保持 texture、mip、rectangle 和重叠写入顺序。
- 只有相邻且数学等价的矩形才合并。
- 资源包、atlas stitch、mipmap 或 shader custom texture 变化时增加 generation。
- 陈旧上传任务直接丢弃，不能写入新图集。

## 12. HUD、字体和 GUI

- 字体 geometry/layout 使用有界缓存；动态顶点进入连续 stream。
- 一个字符串按 font texture page 分成最少 draw run，但 shadow/main 顺序不变。
- 连续且状态一致的 GUI quad 可合并。
- 每个 `RenderGameOverlayEvent.Pre/Post`、scissor、stencil、FBO、Shader 和未知模组调用都是硬 barrier。
- FancyMenu、BetterQuesting、Antique Atlas、Dynamic Surroundings HUD 默认作为 GUI/HUD Legacy Island。
- iChunUtil WorldPortal 是独立递归 Portal Pass，不能混入主视图批次。

## 13. OptiFine 与 ShaderPack

兼容路径必须保留：

- `gbuffers_*`、shadow、deferred、composite、final。
- drawbuffers、FBO attachments、clear 语义。
- custom textures 和全部原 uniform 生命周期。
- `mc_Entity`、`mc_midTexCoord`、`at_tangent`。
- CTM、CIT、CEM、Random Entity、Emissive 和动态光。

Native 后端若要在光影下使用全局 MDI，必须实现真正的 ShaderPack 转换与认证：

1. 解析 OptiFine include/macro 和 `shaders.properties`。
2. 为 terrain/entity/particle variant 注入 per-draw origin/material。
3. 在不改变数学结果的前提下改写 compatibility GLSL。
4. 保持所有 attachment、uniform、pass 和状态。
5. 对每个 program/permutation 做离屏编译、状态验证和图像 A/B。

无法验证的 program 自动使用 `OF_COMPAT_REGION`。把 GLSL 编译成 SPIR-V 本身不等于完成 OptiFine 兼容，也不保证性能提升。

## 14. 核显、独显与不同 CPU 的统一自适应

不创建任何“核显档位”“独显档位”或核心数表。运行时只根据实际能力、正确性和收益选择。

### 14.1 能力阶梯

```text
1. persistent mapping + MDI + SSBO/base-instance + HZB
2. persistent mapping + CPU visibility + multi-draw
3. orphan/subdata ring + multi-draw
4. OptiFine VboRegion
5. vanilla VBO
```

每项不仅检查 extension/capability bit，还必须创建小型 Buffer/FBO，执行提交、Fence/Query、结果校验和超时处理。驱动声明支持但自测失败时立即禁用对应能力。

### 14.2 核显专门风险，但不做型号判断

核显与 CPU 共享内存带宽、缓存、功耗和散热预算，因此：

- Draw Call/状态减少、遮挡剔除、实例化和紧凑数据仍然有效。
- Persistent Coherent Mapping 可能因缓存污染和共享带宽争用变慢。
- 默认实现必须支持非 coherent 映射 + 显式 flush 修改区间。
- 所有动态写入使用有界 ring，禁止每帧复制完整 mesh。
- HZB 使用低分辨率层级，只有实际减少 GPU 时间时才保留。
- CPU 区块 Worker 预算也要观察帧时间和内存带宽压力，不能无限增加并发。
- 减少 CPU 驱动工作有时还能释放共享功耗预算给 GPU，但这只能通过实测确认。

一台机器可以自动形成如下结果，但这个结果来自测量而非硬件标签：

```text
terrain batching             enabled
entity instancing            enabled
conservative HZB             enabled
persistent coherent mapping  disabled
bounded texture upload ring  enabled
expensive GPU culling        disabled
unknown raw GL               legacy
```

### 14.3 每个后端独立闭环

```text
LEGACY
→ CAPABILITY_SELF_TEST
→ WARMUP
→ OUTPUT_VALIDATE
→ PAIRED_MEASURE
→ MODERN
→ REGRESSION_MONITOR
```

失败进入本次 generation/session 的 `QUARANTINED`。地形、实体、TESR、粒子、纹理、HUD、HZB、映射策略分别拥有状态和熔断器，不能使用一个全局开关连坐。

配对测量规则：

- 世界加载、传送、资源重载、Shader 编译、异常 GC 和持续区块生成时不做结论。
- 候选后端先预热，切换后的污染帧丢弃。
- 使用匹配场景指纹的 ABBA 窗口。
- 指纹包含维度、相机区域/方向、可见对象数、视距、分辨率、资源包、ShaderPack、天气等，不包含硬件型号。
- 使用 median-of-means 或 bootstrap 置信区间，不看单次平均 FPS。
- 候选至少改善 P50 `5%`，P95 不得恶化超过 `2%`。
- 使用滞回和冷却期，避免反复切换。
- 世界、资源、Shader、GL context 或相关模组状态变化后重新学习。

闭环只允许选择后端、batch 大小、缓存容量、上传配额和后台预算，不允许降低画质或游戏内容。

## 15. Profiler 必须扩展为 CPU/GPU 关联分帧系统

每个样本至少携带：

```text
frameId
viewId
passId
worldGeneration
resourceGeneration
shaderGeneration
backendId
```

### 15.1 CPU 分段

至少记录：

- 可见集遍历、排序和命令生成。
- terrain solid/cutout/translucent。
- entity、各 RenderLayer、TESR、outline。
- particles、weather、sky、hand、HUD。
- shadow、deferred、composite、final。
- 动画纹理、mesh/texture upload。
- Fence 等待、Buffer 分配、缓存查找。
- worker 生产、队列等待、陈旧任务丢弃。
- Java/native 分配、GC、swap/present。

必须区分 inclusive、exclusive、实际工作和等待。

### 15.2 GPU 分段

使用延迟 timestamp query ring：

- shadow terrain/entity/TESR
- main opaque/cutout
- translucent
- entity/TESR
- particles/weather/sky
- deferred/composite/final
- hand/HUD
- resolve/blit

若结果尚未就绪就跳过该样本，绝不阻塞当前帧读取。

### 15.3 计数器

至少统计 draw、multi-draw、indirect command、顶点/实例、program/VAO/VBO/FBO/纹理切换、真实和消除的状态变化、uniform 字节、各类上传字节、Fence busy、遮挡数量、cache hit/miss/eviction、legacy fallback 原因、状态重同步和 GL error 抽样。

普通 Profiler 开销目标 `< 1%`；完整 GL 调用审计只在显式诊断模式启用。当前 `optimizer-renderer.txt` 读取的是渲染线程预先构造的有界 immutable 字符串，因此后台报告线程不会跨线程读取 Arena、Controller、Fence、Query 或任何实时 GL 状态。

## 16. 生命周期、代际和线程安全

至少维护：

- world/dimension generation
- resource/model/atlas generation
- GL context generation
- ShaderPack generation
- shader permutation generation
- vertex format generation
- render-distance/view-frustum generation

触发点包括世界加载/卸载、维度切换、资源重载、TextureStitch、ModelBake、ShaderPack 切换、窗口 resize、全屏、context recreation 和 shutdown。

规则：

- 所有 handle 携带 generation，不能长期裸缓存 GL ID。
- Worker 发布不可变 payload；陈旧 generation 永远不能上传。
- 新资源完整创建并验证后才原子发布。
- 旧 GPU range 等 Fence 完成后回收。
- Context 已丢失时只能丢弃旧 ID 和释放账本，不能在新 Context 删除旧对象。
- Fence 永不完成、Query 永不返回或显存分配失败时必须有超时、容量上限和回退。
- 关闭过程先停止生产、排空可安全排空的队列，再在有效 Context 中释放。
- 使用代际 handle 防止 ABA；资源索引复用必须同时校验 generation。

## 17. 模块与源码组织建议

`OptimizationModule` 是 append-only，新增枚举只能追加到末尾。不能把全部渲染器塞入现有 `RENDER_SUBMISSION` 熔断器。建议至少追加独立模块：

```text
MODERN_FRAME_COORDINATOR
MODERN_TERRAIN_BACKEND
MODERN_VISIBILITY_HZB
MODERN_ENTITY_BACKEND
MODERN_TESR_BACKEND
MODERN_PARTICLE_BACKEND
MODERN_TEXTURE_STREAM
MODERN_HUD_STREAM
OPTIFINE_REGION_BACKEND
OPTIFINE_SHADER_BRIDGE
LEGACY_GL_ISLAND
RENDER_VALIDATION
```

建议源码包：

```text
dev.rlcraft.ice.optimizer.render.frame
dev.rlcraft.ice.optimizer.render.backend
dev.rlcraft.ice.optimizer.render.arena
dev.rlcraft.ice.optimizer.render.terrain
dev.rlcraft.ice.optimizer.render.visibility
dev.rlcraft.ice.optimizer.render.entity
dev.rlcraft.ice.optimizer.render.particle
dev.rlcraft.ice.optimizer.render.texture
dev.rlcraft.ice.optimizer.render.hud
dev.rlcraft.ice.optimizer.render.optifine
dev.rlcraft.ice.optimizer.render.legacy
dev.rlcraft.ice.optimizer.render.telemetry
dev.rlcraft.ice.optimizer.render.validation
```

每个后端统一实现：

```text
capabilitySelfTest()
prepareGeneration()
validateOutput()
beginPairedMeasure()
activateAtSafeBoundary()
record/submit()
drain()
fallback(reason)
destroyGeneration()
statusSnapshot()
```

字节码/Mixin 适配必须按目标类结构与指纹验证。未知版本不注入或 fail-open，不能捕获异常后继续使用半初始化现代状态。

## 18. 真实模组兼容矩阵

扫描真实实例 `mods` 下 226 个 JAR，116 个包含渲染相关引用。核心结论：

| 模组/组件 | 风险证据 | 完整处置 |
|---|---|---|
| OptiFine G5 | 26 类直接 LWJGL，11 类管理 Shader；已有 VboRegion/MultiDraw | 保留 Shader/FBO/pass 语义；每层单一所有权；未认证 program 整段回退 |
| RenderLib 1.4.5 | Mixin 接管实体和 TESR 遍历 | 保留唯一一次遍历，在最终 Draw Emitter 分流 |
| Better Foliage | 51 类写 BufferBuilder | 主体进入 Chunk 构建；保留 `SVertexBuilder.pushEntity/popEntity` 和自定义颜色 |
| EntityCulling | 可自建 Shader/VAO/SSBO/Query | ICE HZB 开启时不得重复运行；必要时回填其可见状态 |
| Mo' Bends | 6 类 direct GL，37 类 GlStateManager | 专用模型适配；剑轨/披风/透明分支回退 |
| Lycanites | 18 类 direct GL；每 group 重复 bind/pointer/draw | 高收益共享 mesh/实例适配；保留发光、lightmap、动画 |
| SRP | 77 类修改 GlStateManager，复杂多 pass/事件 | 静态树缓存 + 部件矩阵；复杂透明/发光/事件路径专用描述或回退 |
| Ice and Fire | 42 个直接 GL 的实体渲染类 | 矩阵记录桥；未认证 renderer 逐实体回退 |
| Fancy Block Particles | 单粒子内部多次 Tessellator flush | 专用粒子 packet 适配器 |
| LibrarianLib | 31 direct GL、13 Shader、8 FBO | Shader/FBO Legacy Island 的重点覆盖对象 |
| iChunUtil WorldPortal | 递归世界渲染、自建 Shader/FBO、即时模式 | 独立 Portal Pass |
| FancyMenu/BetterQuesting/Antique Atlas | GUI/HUD 原始 GL | HUD Legacy Island |
| Xaero | 当前实例没有 Xaero JAR | 不作为当前验收依赖，保留现有 Bridge 的 fail-open |

纯 OpenGL Core Profile 会破坏固定功能数组、矩阵栈、Display List 和即时模式，因此不采用。

## 19. 正确性验证

### 19.1 数据级

- Legacy/Modern 区块每层顶点流规范化哈希。
- Quad、属性、AO、lightmap、tint、UV、normal、sprite、block/entity ID 对比。
- 透明排序结果逐项一致。
- 实体 mesh cache key 完整性测试。
- Forge/RenderLib 事件 trace：调用次数、顺序、取消和异常传播。
- 每个 Draw Packet 的 sequence、pass 和状态边界检查。

### 19.2 图像级

固定世界、Tick 和相机轨迹，分别捕获：

- final color
- depth
- shadow
- 可取得时的 normal/material/entity ID attachment

无光影、无时序噪声路径最多允许浮点舍入导致的 1 LSB 差异；对象缺失、透明错误和深度边界变化不能用 SSIM 掩盖。带时序效果的 ShaderPack 使用相同 warmup 后逐帧和时间窗口比较，持续结构差异直接失败。

### 19.3 场景集合

- Better Foliage 森林。
- SRP/Lycanites/Mo' Bends/Ice and Fire 战斗。
- 高 TESR 基地。
- FBP 粒子、天气和爆炸。
- 水体、玻璃和复杂透明层。
- 无光影及目标 OptiFine ShaderPack。
- 跑图、区块重建和持续上传。
- Portal、HUD、GUI、资源重载和维度切换。
- Alt-Tab、窗口 resize、全屏/FBO 重建。
- 多小时 soak test。

### 19.4 故障注入

- Fence 永不完成。
- Query 永不就绪。
- Buffer/Arena 分配失败。
- Shader 编译/链接失败。
- Context recreation/loss。
- 队列饱和和 generation 过期。
- 模组事件任意修改 texture/program/FBO/matrix/blend/depth/cull。

## 20. 完成实现后的漏洞与隐形 Bug 审计清单

实现完成后必须独立审计，而不能只依赖功能测试：

### 20.1 内存与资源安全

- Buffer offset/size/stride/count 的整数溢出、负值和越界。
- DirectByteBuffer、Native staging、PBO/VBO、Fence、Query 泄漏。
- Arena 空闲区间重叠、重复释放、ABA 和 use-after-free。
- Context loss 后跨 Context 删除或复用 GL ID。
- 缓存 key 漏项导致错误网格、纹理或 Shader 复用。
- RAM/VRAM 预算绕过、逐帧增长和 eviction 风暴。

### 20.2 并发与生命周期

- 非渲染线程 GL 调用。
- Worker 发布可变对象或陈旧 generation。
- world unload/resource reload 与上传、回收并发。
- Fence 完成与 handle 发布之间的竞态。
- shutdown 时生产者仍写队列。
- 后端切换发生在 pass 中间，产生半帧双所有权。

### 20.3 渲染语义

- Forge 事件重复、遗漏、顺序变化或异常被吞。
- RenderLib 被重复遍历。
- OptiFine VboRegion 与 ICE Arena 重复持有/绘制。
- EntityCulling 与 HZB 重复剔除。
- 透明 tie-break、depth mask、blend function 或 color mask 泄漏。
- Legacy Island 返回后状态镜像未完全失效。
- Shadow/portal/outline/multipass 使用错误 view/pass generation。

### 20.4 驱动与故障恢复

- Persistent coherent 在共享内存设备上造成隐性 stall。
- 当前帧同步等待 Query/Fence/readback。
- `glGetError` 被放在逐 draw 热路径。
- MDI command 未对齐、越界或引用已回收 range。
- 部分初始化失败后仍保留 active 标志。
- 熔断器回退时没有可显示的 legacy mesh。
- 自测超时或驱动错误导致无限重试/振荡。

### 20.5 ShaderPack 输入安全与健壮性

- include 路径规范化和目录逃逸。
- include 递归/宏展开无上限。
- 超大 Shader、编译日志和 permutation 数量导致内存耗尽。
- 失败 program 被缓存为成功或跨 generation 复用。
- Shader 验证工具对本地文件执行无关代码；验证只能处理受限文本和 GL 编译输入。

所有审计结果必须记录严重级别、触发条件、影响、修复和回归测试。高/严重问题未清零不能发布。

## 21. 完整实施依赖顺序

下面是工程依赖顺序，不是“分阶段交付”或可删减的 MVP；最终交付必须完成所有适用项：

1. 扩展关联式 CPU/GPU Profiler，建立真实分 pass 基线和固定 GPU 成本。
2. 建立 generation、RenderHandle、线程约束、资源账本和独立熔断器。
3. 实现 FrameCoordinator、PassGraph、状态镜像与 Legacy GL Island。
4. 实现能力自测、离屏输出验证、ABBA 测量和回归监控状态机。
5. 实现 ChunkMeshPayload、GPU Arena、上传 ring、Fence 回收和兼容上传。
6. 实现原顺序地形命令生成、OF_COMPAT_REGION 与 ICE_NATIVE 地形提交。
7. 实现 primitive section grid、CPU hierarchy 和保守 HZB。
8. 在 RenderLib 唯一遍历末端实现实体/TESR packet，完成热点模组专用适配器。
9. 实现标准粒子实例流和 FBP 专用适配器。
10. 实现动画纹理可见性、PBO/Persistent ring 和资源代际。
11. 实现字体/HUD 动态流与所有事件/FBO barrier。
12. 完成 OptiFine ShaderPack 兼容桥、program/permutation 图像认证和安全回退。
13. 完成数据、图像、事件、故障注入、兼容场景、长时稳定性和性能验收。自动数据/事件/故障注入与兼容回归已完成；真实图像、性能与长时稳定性仍是外部验收。
14. 对全部新增与修改源码执行漏洞、竞态、生命周期和隐形 Bug 审计并修复。当前源码尾审已完成，后续真实驱动验收若发现新问题仍按同一账本与熔断边界处理。
15. 完成 mapped/reobf/bundle 构建和隔离部署烟测；在用户明确批准前不部署真实实例。当前发布链与隔离烟测已完成，真实实例仍未触碰。

每完成一个基础组件都必须保留自动测试，但不能因为局部组件可运行就宣称现代渲染器已经完成。

## 22. 性能验收标准

相同世界、相机轨迹、分辨率、视距、资源包、Shader、模组和 JVM 条件下做随机化 ABBA 重复测试。

主要门槛：

- 参考场景 Frame P50：`9.32 -> <= 4.65 ms`。
- 工程目标 Frame P50：`<= 4.40 ms`。
- GPU P50：`7.04 -> <= 4.40 ms`。
- 参考场景真实吞吐至少 `2.0x`。
- 代表性场景几何平均至少 `1.8x`。
- 任一支持场景 P50 不回退超过 `5%`。
- P95/P99、1% low 和超过 `16.7 ms` 的帧频率不恶化。
- Profiler 普通模式开销 `< 1%`。
- 无新增 GL error、崩溃、死锁、UAF 或 Native 泄漏。
- RAM/VRAM、Fence、Query 和缓存对象在 soak test 中达到稳定平台。

运行时单个候选后端的保留门槛仍为 P50 至少改善 `5%` 且 P95 不恶化超过 `2%`；整体产品验收与单后端在线准入不能混为一谈。

## 23. 真实收益边界

- 只实现现代地形，合理期望约 `1.2x–1.45x`，不能承诺翻倍。
- 地形、可见性、热点实体/TESR、粒子、纹理和 HUD 全覆盖，CPU 受限且存在足够可剔除工作的场景可争取约 `1.5x–2.0x`。
- 当前 GPU 约 `7 ms` 的场景还必须把 GPU 压到约 `4.4–5.0 ms`，否则会在约 `140 FPS` 左右形成新瓶颈。
- 若新增分 pass Profiler 证明不可优化、又不允许改变画质的 GPU 固定成本已经超过 `4.65 ms`，该场景原生两倍在物理上不可达。
- 任意 ShaderPack 的逐像素固定负载不会因 Draw Call 合批自动消失。
- 高版本 Sodium/Blaze3D 不能直接复制到 1.12.2；可移植的是 allocator、visibility、command generation、batching、缓存和验证算法，模型/事件/Shader 语义必须重新适配。

因此最终承诺应是“以严格正确性和无静态硬件分档为前提，完整覆盖可优化热路径，并用测量证明是否接近翻倍”，而不是对所有世界、所有视角、所有核显或所有光影包无条件保证两倍。

## 24. 自动交付边界与外部验收

当前工作区的生产实现、真实 OptiFine 后变换结构回归、自动正确性回归、源码尾审、构建、重混淆、分包审计、bundle 和隔离部署烟测已经完成。发布产物位于 `build/libs`；部署脚本只允许显式命名为 `mods` 的目录，并已更新为本次最终产物哈希。下一轮真实 Session 会额外生成 `optimizer-renderer.txt`，用于确认现代地形是否实际命中以及每一次 Legacy 回退的具体原因。

以下项目不能由无真实 Minecraft/OpenGL 场景的自动测试替代，也没有在本次自动闭环中宣称通过：

- 相同世界、相机轨迹、资源包和设置下的 Legacy/现代真实画面 A/B。
- 每个目标 ShaderPack 的实机图像认证与递归 portal/FBO 组合检查。
- 随机化 ABBA 的 Frame/GPU P50、P95、P99、1% low 与吞吐目标。
- 新区块生成/加载场景的 Frame 与 Tick P95/P99，不以合成测试或旧 Session 代替。
- 长时间 Fence、Query、Direct/RAM/VRAM 和 Context 重建 soak。

执行这些外部项目时仍不得降低画质、跳 Tick、改变 RNG/世界结果或事件/透明顺序；任何未认证或收益不达标的子路径必须由现有独立熔断器退回 Legacy。真实 Dregora 部署已经获得用户批准并完成，但画面与性能结论仍须由新的实机 Session 验收。
