# ICE 1.0.8 单 JAR 与渲染结构重构

日期：2026-10-02。本地测试构建，尚未发布或部署到游戏实例。已完成原方案 A/B/C；D/E 的模型、地形、深度及其他长文件整理作为后续独立批次。

## 安装产物

将 `build/libs/ice-rlcraft-optimizer-1.0.8.jar` 放入 mods 即可。升级前关闭游戏/服务端并移除所有旧 optimizer Main/Core JAR。客户端和专服都使用 1.0.8；配置与存档无需迁移。

优化器单 JAR 同时含普通模组入口、启动 Plugin/Transformer、early ABI 和重混淆、重定位后的运行时。内部 JAR 放在 `build/intermediates`，不再发布 optimizer Core。可选 Profiler 保持独立，其 Main/Core 仍需同时安装。

| 正式产物 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `ice-rlcraft-optimizer-1.0.8.jar` | 4,204,550 | `9D1FF5E5823DAA85D2225EFFF8425B65E9BF14EA9E968C5053017F121266B08B` |
| `ice-rlcraft-optimizer-bundle-1.0.8.zip` | 3,736,937 | `406022D5A3F921D51842F47506B7033077CA3D782965F49CB0CED4D602208251` |
| `ice-rlcraft-profiler-1.0.8.jar` | 237,150 | `AB8B0BF2A3A1BB70E1F94885D12154B0387AE2B583A9B9E4862C8226944BAA40` |
| `ice-rlcraft-profiler-core-1.0.8.jar` | 21,268 | `C8AB6C1196673C180E91F4BE0B482FF116C81AB61DA2171D0CF91D083405BE9C` |

ZIP 仅包含同一优化器 JAR 和 `INSTALL.txt`。通用部署工具支持双包升级、幂等与回滚，默认只操作 workspace 内的测试目录；本次没有调用真实游戏路径部署。

## 结构调整

生产源码分为 early 24 文件、hooks 99 文件、main 248 文件、profiler 61 文件。Java package、ASM owner/descriptor、配置字段、公开桥接入口和 Profiler 反射协议保持兼容。

| 文件 | 重构前行数 | 当前行数及职责 |
| --- | ---: | --- |
| `build.gradle` | 755 | 17；组合 Forge、测试、打包、归一化、产物验证五个脚本 |
| `ModernRendererRuntime` | 6,515 | 3,844；帧/Pass 与失效协调，以及尚未抽出的模型、地形和深度控制 |
| `RendererComponents` | 内嵌组件图 | 72；唯一组件所有者 |
| `RendererComponentFactory` | Runtime 内部 | 374；构造、后端配置、部分初始化失败与资源释放 |
| `RendererDiagnosticsWriter` | Runtime 内部 | 640；报告格式、摘要和 120 帧发布节流 |
| `OptifineShaderRuntime` | Runtime 内部 | 1,483；源码队列、认证、绑定双索引、激活区间及恢复 |
| `RenderMeasurementRuntime` | Runtime 内部 | 431；CPU/GPU 样本关联、HZB 完成顺序和测量进度 |

行数包括空行和注释。拆分后的总行数包含各类自身的 import 与声明；主类变短不是算法工作量下降的证明。本轮实际删除了 Runtime 的 30 个组件镜像字段、发布/清理时逐项复制与清空，以及私有跨域转发空壳。组件图只有一个真实所有者；activeGraph 是事务发布/释放的所有权指针，不存储组件副本。

Shader、测量、诊断均直接读取唯一组件状态。新 owner 对象只在 Runtime 创建时分配，不为 draw、模型部件或每帧建立 context/snapshot DTO，也没有添加反射扫描、事件总线、查表或资格检测。模型批次、姿态复用、HZB 证据复用以及现有采样节流沿用 1.0.7。

启动注册状态由实际 Plugin `injectData` 和 Transformer 构造共同记录，普通运行时只在初始化读取。合包后类可见性不再被当作启动已注册的证据。transformer exclusion 仍只针对 hooks 包。

另修复 Profiler Core 异常路径对优化器专用 `HookFatalErrors` 的隐性依赖：同样的 fatal cause 遍历移到 Profiler 自身的私有辅助方法，保证 Profiler 单独安装时仍可回退。

## 验证结果

最终连续两次 `clean build optimizerBundleZip` 都成功，每轮 25 个构建任务实际执行；另执行一个 classpath 导出任务，共 26 个任务。四个正式产物逐字节一致。

| 验证任务 | 测试类 | 项数 | 通过 | 跳过 | 失败/错误 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 普通回归 `test` | 204 | 818 | 788 | 30 | 0/0 |
| 最终产物 `verifyArtifactStartup` | 2 | 6 | 6 | 0 | 0/0 |
| 合计 | 206 | 824 | 794 | 30 | 0/0 |

普通回归提供真实 Forge/client、OptiFine G5、LLibrary、RenderLib、Mo’ Bends 和 Lycanites JAR；30 项跳过对应其他缺少的可选依赖。包含 Shader 双索引事务、CPU/GPU 完成次序、HZB 复用、诊断发布节奏和组件所有权回归。

最终产物测试使用安装 JAR 作为隔离加载器的 ICE 来源，隐藏晚期运行时，验证全部早期 ABI 与 Plugin/Transformer；另验证 Profiler Core 单独加载的异常回退。真实 Forge `CoreModManager.discoverCoreMods`、`FMLPluginWrapper.injectIntoClassLoader`、`ASMTransformerWrapper` 和 `LaunchClassLoader.registerTransformer` 覆盖 CLIENT/SERVER × Profiler 有/无四种组合，同一优化器 JAR 同时进入普通模组候选扫描并完成实际 Transformer 注册，晚期运行时加载次数为零。

分层编译及 jdeps 对 early 32 个 class、hooks 282 个 class、profiler 133 个 class 的审查没有发现晚期优化器类型链接。最终包没有重复 entry，early 与普通运行时均完整包含；版本、manifest、@Mod/握手、资源元数据、依赖重定位及 Profiler 隔离检查通过。

通用部署脚本使用实际 1.0.8 产物在 workspace 夹具验证：错误哈希拒绝、旧 Main/Core 备份迁移、Profiler 保留、重复部署和精确回滚全部通过。

RTX 4060 Laptop 的离屏原生 GL 验证结果：

- `ModelStateBatchProbe`：16 部件、3 场景，像素、客户端数组与软件镜像状态一致，动态矩阵失效拒绝通过。
- `RendererLifecycleProbe`：实际组件构造、世界切换、资源重载、context abandon、新代际重建、关闭与重复关闭通过；预算占用全部归零。
- 受限预算导致部分初始化失败时，没有发布半成品图，预算全部释放。

独立生命周期夹具首次运行遗漏了 Minecraft 启动时设置的纹理单元常量，触发原生驱动错误；在夹具中补齐 `OpenGlHelper.defaultTexUnit/lightmapTexUnit` 后全部通过，未修改生产渲染判断。

只读等价审查确认迁移方法只调整状态所有者引用，保留组件构造、失败回滚、Shader 恢复和释放顺序，公开 API 无缺失。诊断的报告字段与摘要格式保留。

这些验证覆盖加载、行为回归和离屏画面，不代表完整 Dregora 游戏启动或实体密集场景的 FPS 实测。游戏内帧率、帧时间和长期资源重载仍由本次测试包验证；本轮结构重构本身不承诺额外 FPS 增幅。

## 后续范围

下一批先拆 `ModelRenderController`，再整理 `TerrainRenderController` / `DepthVisibilityController` 和共享 GL 状态同步。地形提交、过滤回滚及已提交后禁止重放的事务仍保持同一协调流程。底层 TerrainArena/DepthHistory 的资源类、OTG IO、Profiler 报告和多目标 ASM 适配器随后按各自验证边界推进，详见原方案。

1.0.7 源码及原安装包已备份到 workspace 的 `.refactor-backups/1.0.7-before-singlejar`，可以进行游戏对照。
