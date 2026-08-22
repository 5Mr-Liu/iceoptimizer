# ICE RLCraft Optimizer 1.0.5

**稳定维护版本 / Stable maintenance release**<br>
发布日期 / Released: 2026-08-22

适用于 Minecraft 1.12.2、Forge 14.23.5.2860、Java 8、RLCraft 与 RLCraft Dregora。<br>
For Minecraft 1.12.2, Forge 14.23.5.2860, Java 8, RLCraft, and RLCraft Dregora.

## 主要更新 / Highlights

- 修复 ChunkAnimator 1.2.1 与 HZB 的动态区块坐标冲突；动画区块保持可见，稳定区块继续批处理。<br>Fixed the ChunkAnimator 1.2.1/HZB dynamic-chunk coordinate conflict; animated chunks stay visible while stable chunks remain batchable.
- HZB 遮挡需要两个独立深度发布确认，避免单帧错误隐藏。<br>HZB occlusion now requires two independent depth publications, preventing one-frame false occlusion.
- 可见列表提交改为事务操作；GPU 提交前失败会恢复原对象、顺序和长度。<br>Visible-list submission is transactional; pre-submit failures restore the original objects, order, and size.
- 确认表按内存预算缩容，容量不足只停止剔除，不关闭整个现代渲染器。<br>Confirmation storage scales to its memory budget; exhaustion disables culling instead of the entire modern renderer.
- Optimizer/Profiler Main/Core 统一为 1.0.5，并拒绝混装版本。<br>Optimizer/Profiler Main/Core are aligned at 1.0.5 and reject mixed versions.

## 验证 / Verification

- 194 个测试类、740 项测试、0 failure、0 error；61 skipped 仅为未提供的可选真实模组样本。<br>194 test classes, 740 tests, 0 failures, and 0 errors; 61 skips only cover optional real-mod samples not supplied.
- Main/Core 分包、reobf、manifest、重复 entry、依赖重定位与可复现构建检查通过。<br>Main/Core split, reobf, manifest, duplicate-entry, dependency-relocation, and reproducibility checks passed.
- 最新实机会话中，现代 Terrain、HZB 和 ChunkAnimator 探针正常，区块生成稳定且远处闪烁消失。<br>In the latest field session, modern Terrain, HZB, and ChunkAnimator probes were healthy; chunk generation was stable and distant flicker was gone.

## 已知限制 / Known Limitations

- 最新样本中 HZB 尚未产生实际候选测试收益，Terrain 现代所有权约 32.94%。<br>HZB had not yet tested real candidates in the latest sample, and modern Terrain ownership was about 32.94%.
- 密集区域仍有 Terrain 驻留、实体/TESR 提交和 CPU/GPU 混合瓶颈。<br>Dense scenes remain limited by terrain residency, entity/TESR submission, and mixed CPU/GPU bottlenecks.
- 这些限制进入 [ICE 2.0 架构重构](https://github.com/5Mr-Liu/iceoptimizer/blob/main/docs/ICE-2.0-ARCHITECTURE-PLAN.md)；1.0.5 不会通过降低正确性门槛强制接管。<br>These limits are addressed by the [ICE 2.0 architecture](https://github.com/5Mr-Liu/iceoptimizer/blob/main/docs/ICE-2.0-ARCHITECTURE-PLAN.md); 1.0.5 does not force ownership by weakening correctness gates.

## 安装 / Installation

1. 完全退出 Minecraft，并删除旧版 ICE Optimizer/Profiler JAR。<br>Exit Minecraft completely and remove older ICE Optimizer/Profiler JARs.
2. 仅优化：解压 optimizer bundle，或安装 optimizer Main + Core。<br>Optimizer only: extract the optimizer bundle, or install optimizer Main + Core.
3. 完整诊断：再安装 profiler Main + Core。<br>Full diagnostics: also install profiler Main + Core.
4. 联机客户端与服务端必须使用相同版本的 optimizer Main。<br>Multiplayer clients and servers must use the same optimizer Main version.

## 文件校验 / Checksums

| 文件 / File | SHA-256 |
| --- | --- |
| `ice-rlcraft-optimizer-1.0.5.jar` | `FFCEBDDDCDBFDA20E04DE102B17DCB861D52188B14051419BDC0815521919EC4` |
| `ice-rlcraft-optimizer-core-1.0.5.jar` | `8F082BE7DFC7E0A4FDC03EBF86F9E4A32BC8C0FFA65A28CA0D6CDFE5FD65BF31` |
| `ice-rlcraft-profiler-1.0.5.jar` | `8CB4B2B145A3215750F49C2EA8EB89FE3476DBF5D524A85403FF8D60F525B2E9` |
| `ice-rlcraft-profiler-core-1.0.5.jar` | `6FE9D528874BD7C788B9C0775133C510FFBC1CEE9ACE3C20138C27BF3E64CA01` |
| `ice-rlcraft-optimizer-bundle-1.0.5.zip` | `5F440E1CB47B983FDB777C7DDACEDD81338561668E2835802898EE11D0F3301E` |

自动验证不能替代不同显卡、Shader Pack 和长时间真实游戏测试。<br>
Automated verification does not replace field testing across GPUs, shader packs, and long play sessions.
