# 1.0.6 数据路径精简（第二轮）

日期：2026-10-01。接续 `ICE-1.0.6-PERFORMANCE-CLEANUP.md`，本轮处理矩阵、地形上传和字体缓存中的重复复制、分配与校验。当前产物是本地开发构建，未发布、未部署到游戏实例。

## 修改内容

| 路径 | 修改及功能边界 |
| --- | --- |
| `RenderMatrixBridge` | 每次矩阵捕获后延迟创建一个快照，后续读取复用它，省去重复数组复制和哈希。再次捕获即失效，包括版本号饱和时；公开数组访问仍返回防御性副本 |
| `LwjglTerrainArena` | 扩展顶点格式转换复用一个输入缓冲区视图，消除逐顶点 `duplicate()`；缓存枚举映射，避免每次 `values()` 分配数组 |
| 地形转换测试入口 | 删除生产文件中两份仅供测试调用的转换实现，测试直接执行实际上传所用的转换函数 |
| `FontLayoutCache` / `HudRenderBridge` | 字体入缓存时只复制有效前缀一次，消除调用方裁剪后构造函数再次复制；原完整数组构造函数仍要求长度严格匹配，空文本仍支持缓存 |
| `LwjglHudRenderer` | 不可变字体布局在构造时校验一次，缓存回放省去重复遍历所有浮点数；原始数组入口仍校验内容，每次提交仍检查平移参数、容量、绘制拓扑及线程 |
| `EarlyMatrixStateTracker` | 矩阵读取使用绝对索引，省去临时 `FloatBuffer` 视图，同时保留调用方的 position、limit、mark |
| `FrameMemoTable` | 映射不存储 null，因此删除 `get()` 后的重复 `containsKey()`；加载结果为 null 时仍不缓存 |

## CPU 微基准

工具为 `tools/RenderDataPathBenchmark.java`。Windows / Java 8，固定 `-Xms256m -Xmx256m`；预热 3 轮、测量 5 轮取中位数。修改前后使用独立 JVM 顺序运行，分配量通过 `ThreadMXBean` 测量。

矩阵场景每轮捕获 100,000 次，每次改变模型平移，并读取快照及其哈希 8 次；结果按单次读取摊销捕获开销。字体场景每轮创建 10,000 个布局，每个布局使用 128 个字形，输入数组容量为 256 个字形；修改前执行调用方裁剪与构造函数复制，修改后使用有效前缀构造函数。两组前后校验和相同。

| 场景 | 修改前耗时 | 修改后耗时 | 修改前分配 | 修改后分配 |
| --- | ---: | ---: | ---: | ---: |
| 矩阵快照读取 | 39.64 ns/次 | 26.73 ns/次 | 232 B/次 | 29 B/次 |
| 字体布局入缓存 | 4,452.68 ns/次 | 3,777.92 ns/次 | 21,592 B/次 | 10,808 B/次 |

本次测量中，矩阵路径耗时降低约 32.6%、分配降低 87.5%；字体布局创建耗时降低约 15.2%、分配降低约 49.9%。矩阵复用收益取决于每次捕获后的读取次数。上述结果只描述这些 CPU 路径，未测量 GPU 绘制、总帧时间或实际 FPS。

当前实现可这样复测：

```powershell
New-Item -ItemType Directory -Force build/performance-audit/benchmark | Out-Null
javac -encoding UTF-8 -cp build/classes/java/main -d build/performance-audit/benchmark tools/RenderDataPathBenchmark.java
java -Xms256m -Xmx256m -cp 'build/performance-audit/benchmark;build/classes/java/main' RenderDataPathBenchmark matrix after
java -Xms256m -Xmx256m -cp 'build/performance-audit/benchmark;build/classes/java/main' RenderDataPathBenchmark font after
```

修改前的相关源码保存在 `build/performance-audit/round-2-before/`，对应基准类在 `build/performance-audit/round-2-before-classes/`。运行基线时将后者放在 classpath 最前，并使用 `before` 参数；参数本身不会替换被测类。这些本地备份会被 `clean` 清除。

## 验证及产物

```powershell
.\gradlew.bat --gradle-user-home .gradle-sandbox-home build optimizerBundleZip --offline --no-daemon
```

完整构建、重混淆、Main/Core 分包检查和安装包生成通过。共 196 个测试类、770 项测试：708 项通过，62 项跳过，0 failure、0 error。本轮新增 6 项测试，并将原地形转换测试改为覆盖实际生产函数。

验证包括：矩阵快照复用与独立性；版本号饱和后刷新；28/32/36/56 字节顶点步长、堆内/直接缓冲区与只读输入；地形顶点坐标和属性字节；字体前缀所有权、非法输入、空布局及缓存回放与原始数组路径的顶点输出逐字节一致；矩阵输入游标与 mark 不变；null 加载结果不缓存。

安装包为 `build/libs/ice-rlcraft-optimizer-bundle-1.0.6.zip`，包含两轮修改。内部版本仍为 1.0.6，内容与原公开发布版本不同。第二轮补丁为 `build/performance-audit/round-2-cleanup.patch`。
