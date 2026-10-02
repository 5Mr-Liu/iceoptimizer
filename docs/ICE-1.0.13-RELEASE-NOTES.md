# ICE RLCraft Optimizer 1.0.13

这是自 v1.0.6 起的累计更新，包含单 JAR 安装、代码结构整理、渲染提交精简，以及持续移动和探索新地形时的加载、保存与采样修复。

## 主要修改

- **单 JAR 与结构整理**：Optimizer 和可选 Profiler 各一个安装文件，普通模组入口与启动补丁一起打包。构建、渲染组件、测量、Shader、诊断和文件解析按职责拆分。
- **渲染热路径**：减少模型状态、粒子提交和动画纹理参数的重复工作。地形缺候选数据后可由新的区块上传恢复验证；休眠状态跳过动画反射与扫描，待上传像素仍在绘制前追赶。
- **探索加载与保存**：OTG 设置直接流式解析，普通文件修改只失效对应缓存；结构保存减少空单元坐标和重复列表分配。原版 NBT 按本次保存的局部调色板解析注册 ID，保留自定义状态转换；压缩缓冲复用或扩大，保存字节与写入顺序保持一致。
- **Profiler**：合并触发复用现有捕获窗口，在固定预算内优先保留实际卡顿区间。客户端、服务端和 GC 分开诊断；缺样尖峰明确标为未知，原生 IO/压缩不再因 ICE 桥接祖先被误归为 ICE 自身。聚类报告保留最长尖峰的缺样证据。

## 安装与升级

适用于 **RLCraft Dregora / Minecraft 1.12.2 / Forge 14.23.5.2860 / Java 8**。

1. 关闭游戏和服务端，备份实例。
2. 移除旧版 ICE Optimizer/Profiler Main/Core JAR，避免混装。
3. 安装 `ice-rlcraft-optimizer-1.0.13.jar`；如需性能记录，再安装 `ice-rlcraft-profiler-1.0.13.jar`。Profiler 也可独立安装。
4. 客户端和独立服务端使用相同 optimizer 版本，精确握手为 `[1.0.13]`。

`ice-rlcraft-optimizer-bundle-1.0.13.zip` 包含同一个 optimizer JAR 和安装说明；先解压再安装。现有配置、记录与存档可继续使用。

## 验证

Java 8 干净构建通过，共 **950 项测试：928 通过、22 跳过、零失败/错误**。最终 Forge 检查覆盖 CLIENT/SERVER 的独装与同装六种组合，单包隔离、启动探针及升级/回滚夹具通过。实际 OTG、OptiFine、FBP 等模组 JAR 的适配和局部行为检查通过；NBT/gzip 压缩字节与读回结果一致。

跳过项主要是未提供的可选模组测试输入，以及 Windows 文件符号链接权限；目录 junction 重定向实际验证通过。局部调用或分配减少不能换算成游戏 FPS 百分比，平均 FPS 和帧时间变化仍需相同场景的游戏内对照。

详细记录：[1.0.13 修复与验证](ICE-1.0.13-LIVE-PERFORMANCE-FIXES.md)、[CHANGELOG](../CHANGELOG.md)。

## SHA-256

| 文件 | SHA-256 |
| --- | --- |
| `ice-rlcraft-optimizer-1.0.13.jar` | `1008D4B072081BB8BCE11F17AF366118DCEEA37F58990471054F633AD899C674` |
| `ice-rlcraft-profiler-1.0.13.jar` | `C5CD39667034FC0879B58BDAB69C7330478565571FE1CCC090143CC47BF9BE0C` |
| `ice-rlcraft-optimizer-bundle-1.0.13.zip` | `9F5EC0F47B50AB17EC84F3AB8F3A81C6D6D75FBF37549185589C5E9EEDE1A504` |

## English

This cumulative update since v1.0.6 combines single-JAR installation, code organization, rendering overhead reductions, and fixes for loading, saving, and profiling while exploring new terrain.

- Optimizer and optional Profiler each use one installation JAR, including startup patches and the ordinary mod entry.
- Rendering uses less repeated model, particle, and texture-parameter work. New chunk uploads can resume terrain qualification; dormant animation scans stop while pending pixels still catch up before drawing.
- OTG settings parse directly from streams, file changes invalidate only the affected cache entry, and structure saving allocates less temporary data. NBT local palettes resolve IDs per save while preserving custom state conversion, compression bytes, and write order.
- Profiler reuses merged capture windows and retains hitch evidence within its fixed budget. Client/server/GC attribution uses actual trigger intervals; missing samples remain unknown, and the largest peak's evidence remains visible in clustered reports.

Requires RLCraft Dregora, Minecraft 1.12.2, Forge 14.23.5.2860, and Java 8. Stop the game/server and remove older ICE Main/Core JARs before installing. Install the optimizer JAR, optionally add the profiler JAR, and use optimizer 1.0.13 on both client and server. Extract the bundle before use. Existing configuration, recordings, and saves remain usable.

Validation: **950 checks, 928 passed, 22 skipped, no failures/errors**, including all six Forge client/server installation combinations and deployment/rollback fixtures. Skips cover unavailable optional test inputs and one Windows file-symlink permission restriction. In-game FPS gains require a matched-scene comparison.
