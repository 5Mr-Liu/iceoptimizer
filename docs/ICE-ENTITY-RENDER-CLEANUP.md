# 实体密集场景的渲染开销精简

日期：2026-10-01。基于 1.0.7，处理 Lycanites 网格检查、模型部件提交和 CPU 矩阵跟踪。保持 1.0.7 版本号，重新生成本地开发包；修改前的归档在 `build/performance-audit/entity-before/libs-1.0.7/`。未部署到游戏实例，未测量游戏内 FPS。

## 修改及边界

真实 `lycanitesmobs-1.12.2-2.0.8.10.jar` 中，VBOModel 使用已有 VBO 时只读取 CPU 索引数组的长度，不读取顶点、索引或法线元素；Mesh.getVbo 直接返回已有名称，Mesh.delete 将名称重置为 -1。真实 JAR 字节码契约测试验证了这些行为。

仅对 VBOModel、由原 Mesh 持有且与缓存完全匹配的已上传 VBO，省去索引遍历和临时 MeshSignature。每次仍检查 group、mesh、缓冲区名称及三个数组引用；删除、替换、资源/GL 代际变化立即失效。CPU 数组原地修改按原 VBOModel 行为处理：已上传几何保持不变，重新上传时检查 CPU 内容。未上传、Tessellator 及 ICE 自有缓冲区继续完整检查。模型是否属于 VBOModel 在缓存建立时确定，省去逐次查找父类。

模型部件成功执行 glPopClientAttrib 后，客户端数组状态栈已经恢复数组缓冲区绑定，省去随后重复的原生 glBindBuffer，只同步软件镜像。恢复失败时仍尝试原生绑定并维持原异常处理。依据为 [ARB VBO 规范](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_vertex_buffer_object.txt) 中缓冲区绑定 push/pop 的定义，并以离屏 GL 验证。正常路径每部件从 11 次原生 GL 调用减为 10 次，绘制次数保持不变。

这项完成的是提交精简，跨部件批处理尚未实现。各部件有独立矩阵、材质和顺序，子模型可以进入自定义虚方法；延迟恢复客户端数组状态会改变这些调用可观察的状态。共享 VBO 本身也不能合并不同变换的绘制。

矩阵跟踪对精确单位轴省去平方根及三次除法，旋转矩阵只写需要的固定零槽，通用乘法展开内层循环并复用右矩阵列。保留原始 +0 起点、累加顺序、有符号零、有限值检查及溢出失效行为。

## 验证

完整 Gradle build、optimizerBundleZip 及真实 Lycanites JAR 契约测试通过：197 个测试类、778 项测试，718 项通过、60 项跳过、0 failure、0 error。新增 8 项覆盖网格替换/删除/原地变化、矩阵逐浮点原始位等价、有符号零/溢出、失效计数及无原生 GL 调用的软件镜像恢复。

`tools/ModelClientStateRestoreProbe.java` 使用不可见的 16×16 Pbuffer，在 NVIDIA GeForce RTX 4060 Laptop GPU 上对比带/不带重复绑定的序列。像素逐字节一致；数组缓冲区及每属性缓冲区绑定、顶点布局、三个数组启用状态一致；GL 错误为零。该工具验证原生序列，JUnit 直接验证生产恢复函数。

## CPU 微基准

独立 JVM 顺序运行，Java 8、固定 -Xms256m -Xmx256m。

| 操作 | 修改前 | 修改后 | 说明 |
| --- | ---: | ---: | --- |
| Lycanites 已上传网格检查，3,072 个索引 | 1,958.50 ns | 15.08 ns | 仅适用于原 Mesh 已上传 VBO 的命中 |
| 精确单位轴旋转 | 90.09 ns | 81.56 ns | 约降低 9.5% |
| 一般轴旋转 | 86.31 ns | 83.73 ns | 约降低 3.0% |
| 通用矩阵乘法 | 35.50 ns | 35.61 ns | 本轮无可辨认收益 |

Lycanites 工具 `tools/LycanitesMeshValidationBenchmark.java` 调用实际生产方法，旧分支执行 capture + 完整检查，新分支执行 matchesUploaded；每轮 50,000 次、预热 3 轮、测量 5 轮取中位数，前后接受数相同。分配为 64 B/次和 16 B/次，包含反射开销；生产快速分支不创建签名对象。

矩阵工具 `tools/MatrixTransformBenchmark.java` 每轮 640,000 次变换，预热 4 轮、测量 7 轮取中位数，使用修改前后独立编译的跟踪器。前后校验和相同，分配相同（快照读取摊销为 1.25 B/次）。这些结果不包含完整实体绘制或 GPU 耗时，不能换算为 FPS 增幅。

## 本地开发产物

`build/libs/ice-rlcraft-optimizer-bundle-1.0.7.zip` 已重建，校验清单同步到 `build/libs/SHA256SUMS-1.0.7.txt`。替换时使用包内两个 JAR，避免旧 Core 矩阵实现与新 Main 混用。本轮补丁为 `build/performance-audit/entity-cleanup.patch`；备份和基准类会被 clean 清除。
