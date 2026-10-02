# ICE 1.0.11 渲染提交优化测试版

本版继续使用一个优化器安装 JAR。优先处理 DregoraRL2 记录中实际出现的 FBP 立方体粒子提交和 FoamFix 动画纹理上传；保留 1.0.10 的模型捕获恢复、OTG 和记录器改动。

## 现场依据

已有游戏记录来自 1.0.9。03:53:00–03:54:50 的持续低帧率片段，在剔除严重尖峰后保留 87 个秒级点，FPS 中位数为 51，平均帧时间的中位数为 19.80 ms。GPU 指标是最近完成的查询值，不能与墙钟帧时间直接相减得到 CPU 独占耗时。

零栈字典溢出的会话中，208 个客户端采样包含 `FBPParticleDigging → FBPRenderUtil.renderCubeShaded_S → Tessellator → glDrawArrays`；动画纹理路径有 145 个 `glTexParameteri` 和 156 个 `glTexSubImage2D` 采样。采样次数不是对应代码的耗时占比，也不是新旧版本的同场景性能对照。

## FBP 实际立方体路径

新增适配目标 `com.TominoCZ.FBP.util.FBPRenderUtil`，覆盖实际使用的两种立方体 helper。类转换时证明重复的 flush、图集绑定和 begin 指令区段，再删除其中第二段。原有非空缓冲提交、立方体顶点生成、真实绘制、灯光切换和粒子格式恢复仍按原位置执行。

无光影且软件镜像确认固定管线、客户端内存缓冲可用时，按 FBP 实际 31 字节的 POSITION/TEX/COLOR/LMAP/NORMAL 布局直接设置数组。公开可变的 VertexFormat 在使用时读取五个描述符和偏移，未知布局继续交给原上传器。此路径不创建 VBO、Fence 或每粒子测量 scope。

新增独立配置 `settings.fbpCubeSubmission`，默认启用；其目标安装状态与普通模块开关独立于现代粒子后端的资格测量和休眠状态。原先 Block/Flame 的适配保持各自调用路径。

本版仍在原位置执行每个立方体的一次真实 draw，主要节省重复边界、格式遍历和反射调用。跨粒子合并需要处理 helper 返回后的灯光状态以及混合粒子的边界，不将本版描述为已经减少非空 draw 数量。

## 动画纹理

真实 FoamFix CoreMod 使用 `@TransformerExclusions("pl.asie.foamfix")`。Forge 会让该前缀跳过 LaunchWrapper 的转换器，因此原有直接适配 FastTextureAtlasSprite 的目标在这个组合中不可达。独立实际类加载测试记录了排除开关对应的转换调用次数。

新的优化从仍可转换的 TextureMap/TextureUtil 调用边界接入。已有动画 sprite 边界在先前排队上传完成后开启复用；精确 FastTextureAtlasSprite 定义由 ClassValue 在冷端核对其定义 JAR 的类内容一次。这个核对用于证明 mip helper 之间没有额外 GL 状态写入，未知定义继续执行完整参数设置。

TextureUtil 的参数 helper、复制和上传调用图在类转换时验证，包含实际 OptiFine G5 的 `Config.getMipmapType` 设置读取。仅相同 sprite 内连续递增的 mip 和相同 blur/clamp/mipFilter 可跳过重复参数设置；每个新区间首层正常设置参数。换 sprite、纹理绑定边界、嵌套、异常退出以及模块关闭使复用失效。现代动画后端没有 delegate 或已休眠、token 为零时，这组边界仍独立工作。

像素上传、动画计时和插值数组更新继续执行。当前游戏配置为 mipmapLevels=1，典型慢路径两层 mip 的四个 filter/wrap 参数由总计 8 次设置降为 4 次；两次像素上传均保留。FoamFix 的 GPU CopyImage 快路径没有调用这个上传 helper，此改动的收益范围是慢上传和插值路径。

参数复用不保留跨 sprite/帧的数组身份或内容缓存。定义检查和 MethodHandle 查找只在冷端执行，热路径读取复用状态及原始参数，没有逐帧哈希、GL 查询、像素扫描或新增 PBO/Fence 工作。原大批 PBO 门槛保持 256 KiB。

## 验证结果

Java 8 的一次 `clean build optimizerBundleZip` 及 classpath 导出共 26 个任务，全部执行并在 54 秒完成。最终产物的包隔离、元数据、版本/握手、early ABI、依赖重定位、重复 entry 及真实 Forge CLIENT/SERVER × Profiler 有/无的启动注册均通过。

| 任务 | 测试类 | 项数 | 通过 | 跳过 | 失败/错误 |
| --- | ---: | ---: | ---: | ---: | ---: |
| test | 216 | 891 | 868 | 23 | 0/0 |
| verifyArtifactStartup | 2 | 6 | 6 | 0 | 0/0 |
| 合计 | 218 | 897 | 874 | 23 | 0/0 |

此次带入实际 FBP 2.4.1 与 FoamFix 0.10.15 JAR，另带入 OptiFine G5、Forge SRG/client 映射及既有模型/OTG/RenderLib 输入。剩余跳过项来自其他可选输入和 Windows 符号链接权限。

RTX 4060 Laptop 的隐藏兼容 OpenGL 上下文对比结果如下：

| 局部场景 | 原路径 | 新路径 | 保留项 |
| --- | ---: | ---: | --- |
| billboard → cube S → cube WH → billboard：Tessellator 调用 | 7 | 3 | 非空 draw 4→4，顺序与 RGBA 相同 |
| 同场景空 flush | 3 | 1 | 首个必要空边界仍执行 |
| 同场景图集 bind / begin | 6 / 7 | 4 / 5 | 绘制后状态相同 |
| 当前两层 mip helper：参数设置 / 像素上传 | 8 / 2 | 4 / 2 | 所有 mip 像素、filter/wrap 相同 |
| 两个三层 mip helper：参数设置 / 像素上传 | 24 / 6 | 8 / 6 | 同一可变数组更新仍上传 |

FBP 工具使用真实 JAR 的 helper/putCube/CUBE 几何代码，并与 mapped Forge 的真实 WorldVertexBufferUploader 对照。cartoon 开关两种场景均逐字节像素、客户端数组、选择器、颜色、法线、VAO、缓冲绑定和 BufferBuilder 状态一致。光影不安全、未知 GL、同尺寸格式突变均使用原上传器并保持像素一致；实际 OptiFine `isMultiTexture`/quadSprites 字节码另外隔离验证，多纹理保留原上传器。非零缓冲绑定保留原异常与绑定。

纹理工具从真实 JAR 定义未改动的 FastTextureAtlasSprite，调用实际 private helper，并保留实际 OptiFine G5 的上传/filter/wrap/copy 与 Config 只读方法。夹具仅提供游戏初始化、设置和统计入口，再执行真实 GL 上传。验证覆盖跨 helper 的参数变化、同一可变数组、新 sprite/纹理、模块关闭、无现代 delegate、嵌套及异常/绑定边界。它没有完整启动 Dregora 的所有转换器和渲染回调，因此仍需要游戏内测试。

源工具为 `tools/FbpCubeNativeProbe.java` 和 `tools/TextureParameterScopeProbe.java`。构建日志、归档 SHA 与 XML 汇总位于 `.refactor-backups/1.0.11-build-final.log` 和 `1.0.11-final-verification.json`；原生/独立审查证据分别位于 `1.0.11-fbp`、`1.0.11-texture-parameter-audit` 和 `1.0.11-independent-render-audit`。

| 正式文件 | 字节 | SHA-256 |
| --- | ---: | --- |
| ice-rlcraft-optimizer-1.0.11.jar | 4238344 | `6A1E1D7ED8D6A80C7B48CCD2FF1CAFB22DA9925793D038768352CBF6B95D36A9` |
| ice-rlcraft-optimizer-bundle-1.0.11.zip | 3768987 | `4E419ADC6D0C3EE6A71268C14CDC400AF39EF7B012079CAC2AB02D751145250D` |
| ice-rlcraft-profiler-1.0.11.jar | 240511 | `9CA638B650820391ACC93213856DB6D1FE90A50D005E4539C598886D5BC83F87` |
| ice-rlcraft-profiler-core-1.0.11.jar | 21268 | `1EC460AD7A73635E7EB75822174C4F013B8E02EAD6E9436432B589705514B363` |

## 安装与测试

关闭游戏后，用 `build/libs/ice-rlcraft-optimizer-1.0.11.jar` 替换旧优化器。联机客户端与服务端使用相同版本，精确握手为 `[1.0.11]`。如使用记录器，Profiler Main/Core 同样使用 1.0.11。

进入同一个存档、位置和视角，在加载稳定后比较平均 FPS、帧时间和卡顿次数；粒子场景可连续挖掘相同方块进行对比。本地方法或离屏绘制测试不能换算成游戏 FPS 增幅。

1.0.10 的源码及安装产物备份位于 `.refactor-backups/1.0.10-before-fps-render-fixes`。本次构建不部署游戏文件或更改存档与画面设置。
