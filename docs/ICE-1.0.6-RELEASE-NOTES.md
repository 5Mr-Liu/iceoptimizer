# ICE RLCraft Optimizer 1.0.6

**稳定维护版本 / Stable maintenance release**<br>
发布日期 / Released: 2026-08-26

仅适用于 Minecraft 1.12.2、Forge 14.23.5.2860、Java 8 和 RLCraft Dregora。<br>
For RLCraft Dregora only, on Minecraft 1.12.2, Forge 14.23.5.2860, and Java 8.

> [!IMPORTANT]
> **兼容范围 / Compatibility scope:** 从 1.0.5 起，ICE 仅支持 RLCraft Dregora，不再支持原版 RLCraft。原版 RLCraft 用户请继续使用 1.0.4 或更早版本。<br>
> Starting with 1.0.5, ICE supports RLCraft Dregora only. Standard/original RLCraft users should stay on 1.0.4 or earlier.

## 主要更新 / Highlights

- 修复大量玩家头颅引发的卡顿：所有方块、物品栏/JEI、快捷栏、手持/掉落及佩戴头颅现在都在进入 `SkinManager`/CustomSkinLoader 前应用同一策略。默认情况下，不完整资料直接使用原版默认皮肤，不触发 Authlib、网络解析、resolver worker 或后台任务；已有 `textures` 的资料保持原样。<br>Fixed stalls caused by large numbers of player heads. Placed, inventory/JEI, hotbar, held/dropped, and worn heads now share one policy before `SkinManager`/CustomSkinLoader. By default, incomplete profiles use the vanilla default skin without Authlib, network resolution, resolver workers, or background submissions; profiles with existing `textures` remain unchanged.
- 修复现代渲染器在休眠或回退到 Legacy 后仍持续制造 CPU 开销的问题。OptiFine Region/pass/program、Particle、FBP、RenderLib、HUD、模型捕获、动画纹理和 Terrain 热钩子现在会在终态入口直接旁路。<br>Fixed continuing CPU overhead after modern render backends went idle or fell back to Legacy. OptiFine Region/pass/program, Particle, FBP, RenderLib, HUD, model capture, animated-texture, and Terrain hot hooks now bypass work at their terminal-state entry points.
- GPU Query 环改为 O(1) 空闲槽栈与活动队列，环满时整帧退避；每个 `frame + pass + backend` 最多一个 Query，HZB 地形提交与深度捕获合并计时。<br>The GPU-query ring now uses an O(1) free-slot stack and active queue, backing off for the frame when full. Each `frame + pass + backend` emits at most one query, and HZB terrain submission plus depth capture share one timing interval.
- 为 `WARMUP`、`OUTPUT_VALIDATE` 和 `PAIRED_MEASURE` 增加 600 帧无进展超时；连续空测量或严重回退会及时返回休眠 Legacy。移除 ICE 对区块编译线程和 builder 数量的额外硬限制，并补全上游队列诊断。<br>Added a 600-frame no-progress timeout to `WARMUP`, `OUTPUT_VALIDATE`, and `PAIRED_MEASURE`; repeated empty measurement or severe regressions now return promptly to sleeping Legacy. ICE's extra hard limits on chunk compiler workers and builders were removed, and upstream queue diagnostics were completed.
- Optimizer/Profiler Main/Core 统一提升为 1.0.6，并拒绝与 1.0.5 混装。<br>Optimizer/Profiler Main/Core are aligned at 1.0.6 and reject mixing with 1.0.5.

## 玩家头颅行为 / Player-skull Behavior

- 默认 `settings.allowIncompletePlayerSkullProfileLookup=false`：不完整资料显示原版默认 Steve/Alex 皮肤，避免渲染线程和第三方皮肤加载器被大量资料查询拖慢。<br>With the default `settings.allowIncompletePlayerSkullProfileLookup=false`, incomplete profiles show the vanilla default Steve/Alex skin, preventing large lookup bursts from stalling the render thread or third-party skin loaders.
- 如确实需要补全这些皮肤，可设置为 `true`；随后由 `settings.skullProfileAsync` 选择有界异步或原版同步解析。修改配置后需要重启客户端。<br>If resolving these skins is required, set it to `true`; `settings.skullProfileAsync` then selects bounded asynchronous or vanilla synchronous resolution. Restart the client after changing the setting.

## 验证 / Verification

- 20 个发布构建任务全部执行；195 个测试类、758 项测试、0 failure、0 error，48 skipped 仅为未提供的可选真实模组 JAR 或运行期样本。10 项玩家头颅专项测试全部执行并通过。<br>All 20 release-build tasks executed; 195 test classes and 758 tests completed with 0 failures and 0 errors. The 48 skips only cover optional real-mod JARs or runtime samples not supplied. All 10 player-skull-specific tests executed and passed.
- Main/Core 分包、reobf、manifest、重复 entry、依赖重定位、bundle 和固定版本握手检查通过。<br>Main/Core split, reobf, manifest, duplicate-entry, dependency-relocation, bundle, and fixed-version handshake checks passed.
- 从远程 `main` 基线创建的隔离干净发布树可直接编译，五个发布归档与前一轮本地构建逐字节一致。<br>An isolated clean release tree created from the remote `main` baseline builds directly, and all five release archives are byte-for-byte identical to the preceding local build.

## 安装 / Installation

1. 完全退出 Minecraft，并删除所有旧版 ICE Optimizer/Profiler JAR。<br>Exit Minecraft completely and remove every older ICE Optimizer/Profiler JAR.
2. 仅优化：解压 optimizer bundle，或安装 optimizer Main + Core。<br>Optimizer only: extract the optimizer bundle, or install optimizer Main + Core.
3. 完整诊断：再安装 profiler Main + Core。<br>Full diagnostics: also install profiler Main + Core.
4. 联机客户端与服务端必须使用相同版本的 optimizer Main。<br>Multiplayer clients and servers must use the same optimizer Main version.

## 文件校验 / Checksums

| 文件 / File | SHA-256 |
| --- | --- |
| `ice-rlcraft-optimizer-1.0.6.jar` | `FAF9277FD9F21593A9E70E20132F749409D311A2C3258AD3D96C0432FE0868FA` |
| `ice-rlcraft-optimizer-core-1.0.6.jar` | `D6E2078BA2EB961A31B5CEF0B0B5ABF45D185A0317E13AE3CD5FB6EEC20F63F9` |
| `ice-rlcraft-profiler-1.0.6.jar` | `93DAFE132C36DE0B9567E3A56B24CD34AE0E6791CD3C84A1B51990CC0A2DBE76` |
| `ice-rlcraft-profiler-core-1.0.6.jar` | `B839EED68FDA2E77684D6293D6D6478F3E3E8E1150001E1C2D63A6910FC3D6C2` |
| `ice-rlcraft-optimizer-bundle-1.0.6.zip` | `683FC213C91A3D511385CAE6ABF17B1E5E692CBE2D43C6A31A23217963BF90BB` |

默认关闭资料查询会让缺少 `textures` 的头颅暂时使用默认皮肤；这是避免卡顿的预期取舍。自动验证不能替代不同显卡、Shader Pack 和长时间真实游戏测试。<br>
Disabling profile lookup by default makes heads without `textures` use the default skin; this is the intended tradeoff that prevents the stalls. Automated verification does not replace field testing across GPUs, shader packs, and long play sessions.
