# 1.0.6 渲染热路径开销精简

日期：2026-10-01。基于当前工作区的 1.0.6 实现，这是本地开发构建，尚未发布，也未部署到游戏实例。

本次处理优化器自身的重复工作。代码证据来自调用路径审查；性能数字来自独立 CPU 微基准，尚未完成真实 Dregora 场景的 FPS 对比。

## 已修改的路径

| 问题 | 修改 |
| --- | --- |
| 每次绘制计数创建 `RenderProfileKey`、计算完整代际哈希、查询映射，再装箱累计值 | 当前视图按 pass/backend 复用统计对象，计数和 CPU 分类累计改为原始类型数组；读取报告时才生成不可变映射 |
| 稳定后端每帧继续执行 GPU 收益测量 | 通用渲染后端和 HZB 的收益监测每 16 帧采样一次；MDI 的 CPU 回归样本采用相同频率。首次 ABBA 测量仍逐帧进行，非采样帧继续使用现代绘制路径 |
| 每个地形层都扫描完整区块列表计算所有权数量和 region runs，但只有 SOLID 使用这些数据训练负载档位 | SOLID 保留完整统计；其他层使用列表长度与遇到第一个匹配网格即可返回的所有权查询 |
| 已经选择现代绘制，仍先调用 `canReplayLegacy()` 扫描旧路径副本 | 先判断后端状态及测量分支，仅实际可能回放 Legacy 时扫描 |
| HZB 仅判断状态是否就绪，也创建完整 GL 快照 | 改用现有的无分配 `isKnown()`，判定的位掩码相同 |
| 模型状态认证成功后，立即重复检查刚认证的两个状态 | 删除相邻重复检查；实际绘制入口仍验证资源与绘制状态 |
| 中位数和 P95 各自复制、排序同一批样本 | 完整窗口只原地排序一次，然后读取两个分位点 |

统计缓存继续按完整帧/视图/代际身份隔离；CPU 或延迟 GPU 结果推进帧窗口时会同时失效缓存。报告快照保持独立，不随后续累计变化。

采样影响的是性能回归发现速度。需要 16 个有效同场景样本的回归窗口，现在通常跨越约 256 帧；持续移动或查询缺失时可能更久。输出正确性检测、资源归属、GL 上下文失效及运行异常处理仍按原来的执行时机运行。

## 微基准

工具：`tools/RenderTelemetryBenchmark.java`。运行环境为 Windows、Temurin Java 8；固定 `-Xms256m -Xmx256m`，每轮 240 帧、每帧 10,000 次计数，在实体与 TESR 两个 pass 间交替；预热 4 轮，测量 7 轮取中位数。每轮核对最后 120 帧的累计值，以验证统计未被省略。

修改前后在独立 JVM 中顺序执行，使用同一基准程序。分配量包含帧创建和统计对象创建的摊销，不包含最后的报告快照。

| 指标 | 修改前 | 修改后 | 变化 |
| --- | ---: | ---: | ---: |
| 单次计数耗时 | 22.64 ns | 17.43 ns | 降低约 23.0% |
| 单次计数分配量 | 47.4801 B | 0.1121 B | 降低约 99.76% |

这只是统计热路径的测量结果，不代表总帧时间或 FPS 同比例改善；地形扫描和 GPU 采样的实际收益需要在同一游戏场景中比较。

编译并运行当前版本的基准：

```powershell
New-Item -ItemType Directory -Force build/performance-audit/benchmark | Out-Null
javac -encoding UTF-8 -cp build/classes/java/main -d build/performance-audit/benchmark tools/RenderTelemetryBenchmark.java
java -Xms256m -Xmx256m -cp 'build/performance-audit/benchmark;build/classes/java/main' RenderTelemetryBenchmark
```

## 验证与产物

```powershell
.\gradlew.bat --gradle-user-home .gradle-sandbox-home build optimizerBundleZip --offline --no-daemon
```

完整构建、重混淆、Main/Core 分包检查与安装包生成通过。JUnit 报告共 196 个测试类、764 项：702 项通过，62 项因未提供可选外部 JAR/运行期样本而跳过，0 failure、0 error。新增 6 项测试覆盖统计隔离、窗口失效、饱和累计、快照独立性、分位数和抽样后继续执行现代路径及回归回退行为。

本地安装包：`build/libs/ice-rlcraft-optimizer-bundle-1.0.6.zip`。内部版本仍为 1.0.6，但文件内容已修改，不对应原公开发布版本的校验值。修改前的源码副本位于 `build/performance-audit/before/`；该目录会被 `clean` 清除。
