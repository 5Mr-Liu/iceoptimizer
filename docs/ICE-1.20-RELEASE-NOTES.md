# ICE 1.20 — 自有地形渲染与崩溃修复（修订 7）

发布日期：2026-10-14。此版本为预发布，包含截至修订 7 的实现；1.20 是 ICE 模组版本，游戏仍为 Minecraft 1.12.2。

## 本次变化

- 增加 ICE 自有地形渲染路径：常驻顶点存储、有序 16 位索引和批量间接绘制。默认启用，不需要安装 Nothirium。
- F6 可关闭或恢复当前已启用的优化，包括自有地形；保留配置中已关闭或因兼容性停用的模块。修复切换时任务与构建器相互等待的死锁，复用区块对象以减少切换重建开销。
- 修复空透明层重排序误用旧缓冲数据，以及绘制队列使用未驻留网格造成的崩溃。
- 合并索引暂存上传，批量写入四边形索引，显存扩容只复制已写入前缀。
- 将原主线程立即重建提交到原有后台队列；正常帧上传遵循原截止时间及约 2ms 时间片，取消 OptiFine 在该调用处额外放宽约 100ms 的效果。单个任务仍可超过时间片。
- 将复杂区块的面分类移到已有工作线程，结果随原可取消上传任务传递，减少主线程逐面扫描。
- 修订 7 修复 ICE 的 Phosphor 光照坐标优化：保留原压缩邻居坐标解码，避免高度边界产生负数区段下标及 `ArrayIndexOutOfBoundsException: -1`。无需移除 Phosphor 或已有 PhosphorCrashFix。

## 下载与安装

- `ice-rlcraft-optimizer-1.20.jar`：直接安装的优化器，已包含启动补丁。
- `ice-rlcraft-optimizer-bundle-1.20.zip`：同一 JAR 和安装说明。
- `ice-rlcraft-optimizer-1.20-hotfix7-playtest.zip`：同一 JAR、安装说明、构建信息和校验文件。
- `SHA256SUMS.txt`：以上三个附件的 SHA-256。

三种包选择一种即可。退出游戏，将旧 ICE 优化器 JAR（包括旧 Main/Core 拆分包）移出 `mods`，放入新的单个 JAR，再完整重启。旧版 1.20 文件也要替换，不能同时安装。客户端与服务端应使用同一优化器构建。

本次只发布 Optimizer，不附带 Profiler、Nothirium、RenderLib 或诊断工具；没有重新构建 Profiler，不把旧的分析器改名为 1.20 附件。

## 运行要求与边界

面向 Minecraft 1.12.2 / Forge 14.23.5.2860 / Java 8 / RLCraft Dregora，保留整合包的 RenderLib（当前参考版本 1.12.2-1.4.5）。自有地形需要显卡与驱动支持 OpenGL 4.3，不绑定显卡品牌、CPU 型号或本机安装路径。

自有地形的已用图形条件为关闭光影包、OptiFine Smart Animations、Fast Render 和 Render Regions。使用自有路径时移出 Nothirium；若保留 Nothirium，ICE 会选择已有兼容方案，不启用自有地形。显卡不支持 OpenGL 4.3 时，应在启动前设置 `B:nativeTerrainRenderer=false` 并重启，不能依赖进世界后按 F6。

F6 切换仍会重建网格，部分区块可能逐步出现，方块编辑的画面也可能稍后刷新。单次 GPU 上传、显存扩容、索引准备或服务端生成仍可能停顿，不保证所有机器、场景的 FPS 增幅或零卡顿。

## 验证状态

修订 7 已于 2026-10-13 完成 `-PackageOnly` 编译、重混淆和打包（30 秒），并核对归档完整性、版本元数据与冻结源码。本次发布复用该制品，没有更改或重新构建运行时代码。

按用户安排，修订 3–7 没有运行自动测试、启动检查或游戏。修订 7 已补充边界回归用例源码，但未编译或执行测试。历史测试和旧实测不能作为当前制品通过的证明，多硬件验证也未完成。

最新完整匹配性能记录仍是此前 Build10 相对 ICE + Nothirium 的三场景几何平均约 +7.43%，不是当前修订 7 的结果，也未达到 +20% 目标。历史约 60% 的组合结果存在实体渲染距离不一致，不作为同画质达标证明。

优化器 JAR SHA-256：
`B7E06766E5FD932ABB5398B4DC9F64F5BF1F77884E75D09DBE14F04748D319AC`

## English

This pre-release publishes ICE 1.20 revision 7 for Minecraft 1.12.2. It includes the default-on native terrain renderer, F6 lifecycle fixes, empty/resident mesh crash fixes, upload scheduling and worker-side face preparation. Revision 7 fixes an ICE Phosphor adapter error that could produce a negative section index at height boundaries.

Native terrain requires OpenGL 4.3 and the documented mod/graphics configuration. Install exactly one optimizer JAR and restart completely. No profiler or third-party dependencies are bundled. The final artifact was compiled and packaged, but was not runtime-tested; no universal performance or stability guarantee is claimed.
