# ICE RLCraft Optimizer 1.0.7

构建日期：2026-10-02。本版本汇总此前在 1.0.6 工作区完成的两轮优化器自身开销精简，Optimizer/Profiler Main/Core 及 Optimizer 联机握手统一更新为 1.0.7。当前渲染器测试包包含新增路径的冗余清理，为本地构建，未发布到 GitHub、未部署到游戏实例。

当前归档包含 [实体渲染开销精简](ICE-ENTITY-RENDER-CLEANUP.md) 和 [渲染器测试修改](ICE-1.0.7-RENDERER-TEST-BUILD.md)，下方校验值对应当前产物。最初的 1.0.7 归档保存在 `build/performance-audit/entity-before/libs-1.0.7/`；上一轮实体精简的归档保存在 `build/performance-audit/render-batch-before/libs-1.0.7/`。便于区分的测试包为 `ice-rlcraft-optimizer-bundle-1.0.7-renderer-test.zip`，内容与本次标准 bundle 相同。

## 变更

- 绘制统计复用当前帧对象，减少键对象、装箱值和重复哈希查找；统计报告仍提供独立快照。
- 后端进入稳定回归监测后每 16 帧采样一次，首次配对测量继续逐帧；完整窗口只排序一次。回归发现速度相应降低，16 个有效同场景样本通常跨越约 256 帧。
- 减少非 SOLID 地形层的所有权扫描、无须回放时的 Legacy 副本扫描和相邻重复状态检查。
- 同一次矩阵捕获后的读取复用快照，减少数组复制及哈希；再次捕获立即失效缓存。
- 地形上传复用缓冲区视图，移除逐顶点临时对象和重复测试实现。
- 字体入缓存只复制有效前缀一次；不可变字体布局在构造时校验内容，回放继续检查动态提交条件。
- 移除矩阵读取临时视图、重复帧缓存查表和枚举数组分配。

实现及 CPU 微基准详见 [第一轮说明](ICE-1.0.6-PERFORMANCE-CLEANUP.md) 和 [第二轮说明](ICE-1.0.6-DATA-PATH-CLEANUP.md)。这些历史报告记录的是升级版本号之前的测量；局部耗时和分配下降不代表实际 FPS 同比例提升，尚未完成游戏内 FPS 对比。

## 安装

仅使用优化器时，解压 `ice-rlcraft-optimizer-bundle-1.0.7.zip`，将其中的 Optimizer Main/Core 两个 JAR 一起放入实例的 `mods` 目录，替换旧版本。需要性能记录时，再安装 Profiler Main/Core 两个 1.0.7 JAR。

联机双方的 Optimizer Main 均需使用 1.0.7；同一组件 Main/Core 必须版本一致。继续面向 Minecraft 1.12.2、Forge 14.23.5.2860、Java 8 和 RLCraft Dregora。

## 构建验证

```powershell
.\gradlew.bat --gradle-user-home .gradle-sandbox-home build optimizerBundleZip --offline --no-daemon --rerun-tasks
```

初次 1.0.7 构建的 19 个任务全部重新执行。当前渲染器测试构建提供真实 Forge、OptiFine G5、LLibrary、RenderLib、Mo’ Bends 和 Lycanites：202 个测试类、810 项测试，780 项通过、30 项跳过、0 failure、0 error；重混淆、Main/Core 分包检查及安装包生成通过。新模型 ABI 仅在 Core 中，四个 JAR 版本与 1.0.7 一致，bundle 内嵌 JAR 哈希与独立产物一致；清理后编译代码的离屏 GL 输出、状态恢复及矩阵失效拒绝验证通过。

## 产物与 SHA-256

文件位于 `build/libs/`；校验清单为 `build/libs/SHA256SUMS-1.0.7.txt`。

| 文件 | SHA-256 |
| --- | --- |
| `ice-rlcraft-optimizer-1.0.7.jar` | `DBBC646045A53BEBA6170DC19ED562F4AB65252DDB3A9056E3F38A003ADD1504` |
| `ice-rlcraft-optimizer-core-1.0.7.jar` | `EC64277F17492C6D9B9BD0E63965E499F569E7573FA6185A9A72FF2B8314B232` |
| `ice-rlcraft-profiler-1.0.7.jar` | `4F6B8923B3AAEBCBC93E3D57F8947CCEF71B481BB7CC76F9ED4668515A29C8E0` |
| `ice-rlcraft-profiler-core-1.0.7.jar` | `5CBD9FE25FBAD2DB36D87EAB3025FE3A841DE6B871BD7CEE1E1CD717325882E8` |
| `ice-rlcraft-optimizer-bundle-1.0.7.zip` | `E397AD0082092529178B065D32436064DBD7D83A226AC48C1FECD6E6F965CF1F` |
| `ice-rlcraft-optimizer-bundle-1.0.7-renderer-test.zip` | `E397AD0082092529178B065D32436064DBD7D83A226AC48C1FECD6E6F965CF1F` |
