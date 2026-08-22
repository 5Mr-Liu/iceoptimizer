# ICE RLCraft Optimizer 1.0.5

发布日期：2026-08-22

适用：Minecraft 1.12.2 / Forge 14.23.5.2860 / RLCraft 与 RLCraft Dregora

## 发布定位

1.0.5 是 ICE 1.x 混合现代渲染与兼容优化管线的稳定维护基线。它重点修复 ChunkAnimator 与 HZB 的动态坐标冲突、遮挡确认和真实列表事务安全，并统一 optimizer/profiler Main/Core 四包版本。

本版本不会以放宽正确性门槛强制现代后端接管。最新实机数据确认区块生成与远处闪烁恢复正常，同时也显示 Terrain Arena、MDI Fence、HZB 历史复用和实体批处理仍有架构上限；这些问题进入 ICE 2.0 方案，不继续改变 1.0.5 的稳定语义。

## 主要变化

- ChunkAnimator 1.12.2-1.2.1 中仍处于动画的区块强制保持可见。
- 动画区块使用真实 `ChunkRenderContainer.preRenderChunk` 和 region-local 补偿，稳定区块继续参与 Arena batching。
- 普通 HZB 遮挡需要连续两个独立深度发布确认同一区块身份。
- HZB 可见列表更新成为提交事务；GPU 提交前的拒绝或异常会恢复原对象身份、顺序和长度。
- 确认表使用固定预算；预算不足时缩容或停止剔除，不使整个现代渲染器初始化失败。
- 增加 ChunkAnimator 真实 JAR ABI、字节码、遮挡见证和列表事务回归。
- optimizer/profiler Main/Core 与 Forge 握手统一为 1.0.5，禁止混装旧版。

## 实机验证状态

2026-08-22 的最新 Session 已确认：

- `modernRenderer=true`
- `modernTerrainBackend=true`
- `modernVisibilityHzb=true`
- `chunk_animator_probe_status=READY`
- `chunk_animator_probe_runtime_failures=0`
- `hzb_filter_transaction_failures=0`
- 用户观察区块生成正常，远处区块不再闪烁。

已知性能限制：

- HZB 捕获与发布正常，但最新累计 `hzb_tested=0`，当前历史门尚未产生实际剔除收益。
- Terrain 最后可见所有权约 32.94%，Arena 接近 128 MiB 上限。
- 实体与 TESR 的现代候选仍未证明稳定收益，因此收益门会保留 Legacy。
- 密集区域仍是 CPU、渲染提交、集成服务器与约 20–24 ms GPU 帧时的混合瓶颈。

上述限制不会通过强制关闭回退解决；后续重构见 [ICE 2.0 数据驱动性能架构方案](ICE-2.0-ARCHITECTURE-PLAN.md)。

## 发布文件

| 文件 | SHA-256 |
| --- | --- |
| `ice-rlcraft-optimizer-1.0.5.jar` | `FFCEBDDDCDBFDA20E04DE102B17DCB861D52188B14051419BDC0815521919EC4` |
| `ice-rlcraft-optimizer-core-1.0.5.jar` | `8F082BE7DFC7E0A4FDC03EBF86F9E4A32BC8C0FFA65A28CA0D6CDFE5FD65BF31` |
| `ice-rlcraft-profiler-1.0.5.jar` | `8CB4B2B145A3215750F49C2EA8EB89FE3476DBF5D524A85403FF8D60F525B2E9` |
| `ice-rlcraft-profiler-core-1.0.5.jar` | `6FE9D528874BD7C788B9C0775133C510FFBC1CEE9ACE3C20138C27BF3E64CA01` |
| `ice-rlcraft-optimizer-bundle-1.0.5.zip` | `5F440E1CB47B983FDB777C7DDACEDD81338561668E2835802898EE11D0F3301E` |

## 安装

客户端完整诊断与优化需要同时安装四个同版本 JAR。只使用优化器时，可以安装 optimizer Main/Core 两包或使用 optimizer bundle ZIP。

安装前移除旧版本 ICE optimizer/profiler Main/Core，不能混装版本。专用服务器至少安装 optimizer Main/Core，并确保联机客户端的 optimizer Main 版本相同。

## 自动验证

- 发布前干净构建：20 个任务实际执行，194 个测试类、740 项测试、0 failure、0 error、61 skipped；跳过项均为当前命令未传入的可选真实模组 JAR或运行期单类样本。
- 完整 Gradle 测试与 Main/Core 分包验证。
- reobf、manifest、重复 entry、依赖重定位和 Core-only ABI 审计。
- 发布构建可复现性与固定 SHA-256 校验。
- 真实 ChunkAnimator 1.2.1 JAR ABI 和动画绘制契约。
- 部署脚本旧版替换、备份、哈希和幂等行为验证。

自动验证不能代替不同显卡、Shader Pack 和长时间真实游戏路线测试。
