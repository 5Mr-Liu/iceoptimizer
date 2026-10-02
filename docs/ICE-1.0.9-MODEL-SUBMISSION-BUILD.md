# ICE 1.0.9 模型提交优化测试版

本次针对未开启光影、实体和怪物较多时的模型提交开销。优化器继续使用一个安装 JAR：`build/libs/ice-rlcraft-optimizer-1.0.9.jar`。退出游戏后移除旧 optimizer JAR，再安装此文件；联机的客户端和专服均使用 1.0.9。Profiler 保持可选。

## 修改内容

1. **恢复实际双足模型批次。** 当前游戏留下的 LLibrary 转换 dump 在 `ModelBiped.render` 尾部追加 `LLibraryHooks.renderModel`，导致原整方法摘要拒绝该目标。现在只在类转换时证明动画之后的完整绘制分支，并在尾部回调之前恢复客户端数组。动画、child/adult/sneaking 分支、矩阵及回调顺序保留。区间内发生变化仍使用原绘制路径。
2. **接入已审查的模组模型。** LLibrary `AdvancedModelRenderer` 使用自己的 private compiled/displayList；Mo’ Bends 普通 `ModelPart` 使用自己的编译与绘制方法。分别接入正确的几何捕获、字段读取和批次范围。Mo’ Bends 的父链预扫描结果在批次内复用；未知 subclass、extension 回调和未经证明的父变换继续沿用即时路径。
3. **部件共享 VBO 页。** 默认页为 96 KiB，部件保留独立分配身份及顶点范围。连续部件处于同一页时，仅进行一次 buffer bind 和一组 vertex/texture/normal pointer 设置；原位置仍提交每个部件的 draw，继续使用当时的矩阵。页切换才重新设置。
4. **清理无效工作。** 删除从未读取的逐绘制 `lastUsedFrame` 自增。资源/代际/几何预检继续集中在批次入口；绘制中只保留动态矩阵有效性和必要的页切换比较。类结构摘要、分配、范围回收和 Fence 查询均位于冷路径，Fence 使用零超时查询。

新增诊断字段 `model_mesh_shared_pages`、`model_state_batch_buffer_binds` 和 `model_state_batch_pointer_sets`；最后一个字段表示一组三次指针设置。可以通过批次 draw 与 bind/pointer 计数对比观察实际覆盖。

对包含 N 个部件、顺序经过 P 次页绑定的批次，数组相关原生调用与 draw 从 `5N+5` 降为 `N+4P+5`（不含原矩阵指令）。例如 16 个部件共享一页时为 85→25 次，部件 draw 仍为 16 次。

本次没有合并不同部件的 Draw Call，也没有扩大实体剔除距离或改变画质。机器只在使用这些模型渲染路径时受益；动态 TESR、复杂自定义渲染及游戏逻辑仍可能成为瓶颈。

## 局部性能对照

`tools/ModelSubmissionBenchmark.java` 在本机 RTX 4060 Laptop 的 32×32 离屏上下文中，对照 1.0.8 备份源码与本次 cache。每轮绘制 128 个模型，8 轮预热、9 轮测量，三个独立进程对照交替运行旧/新版本。表内为三个进程中位数的中位数，单位为每模型的微秒。

| 每模型部件数 | 1.0.8 | 1.0.9 | 局部提交耗时下降 |
| ---: | ---: | ---: | ---: |
| 16 | 3.069 | 2.509 | 18% |
| 64 | 5.481 | 3.159 | 42% |
| 128 | 9.178 | 3.851 | 58% |

各轮 draw 数及读取的像素一致。计时包含 cache 批次 begin/end、原生矩阵指令和 draw 提交；`glFinish` 在计时范围之外。驱动背压仍可能影响提交耗时，测试也不含真实模型动画、批次树预检、可见性计算、整个帧及游戏逻辑。因此这些百分比不能当作游戏 FPS 增幅。实际收益取决于模型是否进入批次、部件跨页情况以及当前瓶颈。

## 验证与产物

两次最终 `clean build optimizerBundleZip` 均成功，每轮包含 classpath 导出共 26 个任务。四个正式归档逐字节一致。

| 文件 | 字节 | SHA-256 |
| --- | ---: | --- |
| `ice-rlcraft-optimizer-1.0.9.jar` | 4,225,087 | `5600D9A22FC1BAF20AF46E85C8999D4954BA7269534232C8D5CE0838BECE2FC4` |
| `ice-rlcraft-optimizer-bundle-1.0.9.zip` | 3,755,627 | `5460C328DD6FB28191BBFD6784D56476DD070146C1B669953CF802AAC26F9DDB` |
| `ice-rlcraft-profiler-1.0.9.jar` | 237,149 | `61629A308710B33480CC6DA3F4A9858C0FF0243156BA593B203D6F1260D9B2CE` |
| `ice-rlcraft-profiler-core-1.0.9.jar` | 21,267 | `20E69B525D57CEBDB4A3464FC164AAF8CAAA417EE58A363E86DC0051DD95B123` |

| 验证任务 | 测试类 | 项数 | 通过 | 跳过 | 失败/错误 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 普通回归 `test` | 207 | 842 | 812 | 30 | 0/0 |
| 最终安装包 `verifyArtifactStartup` | 2 | 6 | 6 | 0 | 0/0 |
| 合计 | 209 | 848 | 818 | 30 | 0/0 |

真实依赖包含 Forge/client、OptiFine G5、LLibrary、RenderLib、Mo’ Bends 和 Lycanites，以及游戏目录中的 LLibrary 转换 dump。新增验证覆盖双足绘制区间、原分支执行次序和异常恢复，模组模型独立字段、模块关闭、父链缓存与未知子类，共享范围的 Fence、分配失败及上下文释放。其余 30 项因未提供可选依赖而跳过。

RTX 4060 Laptop 原生 `ModelStateBatchProbe` 验证 16 个不同几何部件与 3 个场景：Legacy、逐部件即时 VBO 和共享页批次的像素、客户端数组与软件镜像一致；同页一批仅 1 次 bind/1 组指针设置，跨页切换严格按页重新设置。另覆盖顶点偏移对齐、动态矩阵失效、发布回滚保留旧 entry、成功替换、零 GPU 预算和上下文失效。

`RendererLifecycleProbe` 验证实际组件构造、世界切换、资源重载、context abandon、重新初始化、关闭与重复关闭，以及部分构造失败；预算占用归零。部署脚本在 workspace 夹具中验证本次实际安装包的错误哈希拒绝、双包迁移、Profiler 保留、幂等和精确回滚。

最终单 JAR 的版本、manifest、全部 early ABI、重定位、重复 entry、Profiler 隔离及真实 Forge CLIENT/SERVER × Profiler 有/无启动注册验证通过。独立只读审查未发现新增的无谓逐帧检测或重复计算；保留的精确类型、模块位及动态矩阵前提分别用于防止未知回调、关闭路径被接管和失效矩阵绘制。

1.0.8 完整源码与安装产物位于 `.refactor-backups/1.0.8-before-model-submit`，可用于游戏对照及回退。真实游戏实例的 JAR、配置与存档没有在本次构建中修改。

## 游戏对照建议

同一存档、位置、视角和实体规模，分别运行 1.0.8 与 1.0.9；等区块和模型加载稳定后记录平均 FPS 与帧时间。优先覆盖怪物密集区、Mo’ Bends 动画和 LLibrary 模型，再测试机器区域。检查动画、透明/发光部件和装备层是否一致，并执行一次资源重载后复测。

当前验证使用真实依赖及现有 LLibrary 转换 dump，不等于完整启动 Dregora 后的全部 Mixin 链验证；最终画面与 FPS 由游戏测试确认。
