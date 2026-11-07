# ICE 1.20.3 — 地形重建卡顿修复与精简优化

发布日期：2026-11-07。适用于 Minecraft 1.12.2 / Forge 14.23.5.2860 / Java 8 / RLCraft Dregora。**1.20.3 是 ICE 模组版本，不是 Minecraft 版本。** 沿用 1.20 系列的预发布渠道。

本版以用户已实机测试并确认效果的 `slim-20261107` 候选为基础，发布准备仅更新版本、构建标识和说明，保留该候选的运行逻辑。启动日志标识为 `release-1.20.3-20261107`。

## 本次发布内容

- GL43 替换区块网格时先释放旧区间，使同尺寸重建可以复用原空间，减少新旧网格并存引起的显存扩容与复制。上传失败后使退休几何失效并标记重建。GL21 固定页继续上传成功后释放旧网格，避免页反复创建。
- 撤下新增 Capability NBT 序列化结果比较、Trinkets 标量缓存及差量同步的生产接入，不注册差量网络通道；旧配置中的 `trinketsStateNbt=true` 和 `trinketsSyncDelta=true` 也不会重新启用。实验实现和测试保留供研究，不参与正式启动接入。
- 保留原装备 NBT 优化、已知 Capability 状态比较、QualityTools 属性复用、Lycanites 装备单次解析、Recurrent Complex 世界加载清理，以及索引批量生成和命令部分上传。
- 相对远程 v1.20.2，合入 OpenGL 4.3/2.1 自动选择和固定缓冲页、OptiFine G5 AF/AA 多重纹理兼容、透明排序和 F6 配套处理、空 VBO 防护及资源重载后旧上传任务失效处理。
- 保留此前双截棍物品动画、世界切换、透明排序与光影兼容修复。

## 安装

1. 完全退出游戏和服务端，移出所有旧 ICE Optimizer JAR，包括本地 `local-test`、`slim` 候选及旧 Main/Core 拆分包。
2. 只将 `ice-rlcraft-optimizer-1.20.3.jar` 放入 `mods`。安装 ZIP 需先解压，不能直接放进 `mods`。
3. 联机客户端和服务端都使用 1.20.3；可以保留原 `config/ice-optimizer.cfg`，无需删除存档或配置。
4. 本次 Release 只分发 Optimizer，不包含 Profiler、第三方模组或测试夹具。

保留整合包的 RenderLib。使用 ICE 自有地形时移出 Nothirium，并关闭 OptiFine Smart Animations、Fast Render 和 Render Regions；安装 Nothirium 时遵循现有外部渲染器所有权规则。图形上下文支持 4.3 时使用 GL43，否则选择 2.1 兼容路径，低于 2.1 会明确报错。光影包本身的硬件要求仍然适用。

## 验证范围

发布构建执行 `build optimizerBundleZip verifyArtifactStartup`，检查版本、安装包隔离、启动目标目录和退出生产接入的实验功能。常规测试共 1423 项：1335 通过、88 项可选跳过、零失败；另有 11 项最终制品启动检查全部通过。实际统计与源码提交保存在 Release 附件 `BUILD-INFO.txt` 和源码/构建证据包中。

用户已确认精简候选效果可以；此次发布不增加功能或重新进行游戏内 FPS 基准测试。自动回归不能代替多显卡、不同光影或 Android/gl4es 实测。此前其他设备的 `TerrainVisibilityAccessor` 注入失败仍缺完整启动日志，不宣称本版修复该问题。

开发背景见 [精简候选记录](ICE-SLIM-PLAYTEST-20261107.md) 和 [1.20.1 至候选的审查](ICE-1.20.1-TO-LOCAL-AUDIT-20261107.md)。
