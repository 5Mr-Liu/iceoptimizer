# ICE 1.0.13 探索场景性能修复

日期：2026-10-02。基于 1.0.12 实际游玩记录，场景为持续移动和探索新地形。

分析依据：[现场性能分析](ICE-1.0.12-LIVE-PERFORMANCE-AUDIT.md)。此次修改直接处理休眠后的渲染观察、OTG 配置读取、区块/结构保存及记录器的重复工作；不使用本地测试推算游戏 FPS 增幅。

## 渲染后端恢复与休眠开销

- `CandidateDataSource` 明确区分模型发布和地形上传。地形缺候选数据时，真实 legacy VBO 上传边界记录一个待恢复事件，在之后的帧边界恢复原输出验证/ABBA 测量。
- 使用既有 600 帧冷却与最多三次工作负载重试额度。新上传不是认证通过；真正接管仍需要覆盖、正确性与收益验证。已因收益或正确性被拒绝的后端保持原状态。
- 暂停时保留确因父后端而暂停的上传子后端状态，父恢复后只恢复这部分子状态。
- 动画纹理可见性观察引用按帧选择。休眠且没有待上传像素时，chunk/draw/buffer 桥接立即返回，省去反射与列表扫描。
- 成功延迟纹理上传的现有回调同步恢复本帧观察引用；其他后端都休眠时，绘制边界仍能补传待上传像素。世界、资源重建重新选择和认证观察源。

验证包括真实冷上传桥接、1,000 次休眠调用零反射、帧内待传像素即时追赶、父子恢复及拒绝路径隔离。纹理追赶测试执行实际桥接和反射路径，使用确定性 CPU 像素 sink；本轮没有更改 GL 命令、几何或纹理提交顺序。

## OTG 同步加载与结构保存

- `OtgSettingsSnapshot` 直接计数输入流并解析，去掉整文件 byte-array 累积、复制及超大的重复读缓冲；保留原 Charset、Locale、解析顺序、重复键处理和行号。
- EOF、关闭成功及已有前后文件身份认证之后才发布缓存。读失败、超限或文件变化仍使用原方法。
- `OtgFileMutationTracker` 按文件内容事件失效缓存。普通文件修改不再清空同目录所有 settings；目录替换、路径别名变化、watch 溢出/丢失仍重新认证。source/target 是同一个 watch 时，活性认证复用一次结果。
- 热命中仍只检查 watcher 与内存 token，不新增文件 stat、canonical path、内容 hash 或额外读取。
- `CustomStructureFileManager.saveStructureData` 的坐标分配移动到非空单元分支，分组列表仅在新分组时分配。遍历、分组顺序、dirty flags、磁盘序列化与备份流程保留。
- OTG 默认 zlib 压缩复用每线程 64 KiB scratch，保持原 input-length 预分配、压缩设置及日志，并在 finally 释放 Deflater。没有异步修改世界或调整存档写入顺序。

真实 OTG v9.7 JAR 的 settings/metadata、压缩和保存入口执行回归覆盖同大小/mtime 修改、不同文件独立失效、别名重定向、stream 关闭/超限失败和模块关闭。空、随机、全零资料及缓冲边界的压缩字节与原实现一致；稀疏/满 region fixture 的分组和写入顺序一致。fixture 中坐标分配从 30,000 降至 10,003，分组列表从超过 10,000 降至 4；这些是该 fixture 的分配次数。

## 原版 NBT 保存

- `ChunkSectionNbtBridge` 对标准 4–8 bit 局部调色板，在每次保存中解析 16–256 个槽位的直接注册 ID，省去 4,096 次相同状态的注册查表。
- 直接读取已知标准 BitArray 的固定 4,096 个槽位，避免每格 Validate、坐标换算及重复 nibble read/modify/write；输出相同的 Blocks、Data、可选 Add 数组。
- ID 内容每次重算；ThreadLocal 只复用一个 int[256]，不跨保存保留注册映射。自定义 section、palette、BitArray、NibbleArray 或 registry 使用原方法。
- 任一调色板状态未直接注册时，在写出任何数组前完整回退，保留 Forge 的 `getMetaFromState/getStateFromMeta` 回调。资格检查在每个 section 一次，循环中没有增加兼容门禁。
- `CompressedStreamTools` 的 gzip 输出使用 64 KiB 缓冲，减少小块压缩写出。JDK gzip 格式、压缩设置、NBT 顺序、关闭和异常语义保留，模块关闭使用原默认缓冲。

测试比较全部 4–8 bit 宽度、跨 word、负/高 ID、预填 metadata 和 Add 数组；验证每次保存注册 ID 刷新、缺失状态回退及原 AIR 常量。真实 SRG 和 OptiFine G5 补丁后的 section 安装通过。gzip 大随机 NBT 逐字节一致、可读回、输出调用次数减少，关闭与写入错误回归通过。

## Profiler 开销与诊断

- 合并到现有 capture 的触发只延长窗口，不再复制随后丢弃的预窗口；惰性接口只在创建新 capture 时读取 recent samples。
- 固定角色预算拆为近期 tail 与确认区间证据。trigger 只扫描其实际角色和区间；采样插入不扫描历史 triggers。优先样本使用带索引的有界堆，未入选样本不创建保护对象。
- 混合 CLIENT/SERVER/GC/标记最多形成四个诊断视图，不复制 StackSample 对象或增加总样本预算。sequence 与各角色首次触发锚点独立，ICECAP v4 布局保留。
- 根因仅使用实际触发区间的目标线程栈；没有匹配栈的单事件标记未识别，不借邻近栈推断。分组包含多个事件时，证据列明缺样，整体归因不能代表缺样事件。
- 聚类展示诊断跟随最大峰值事件；同类后续大峰缺样时，summary/JSON 保留其警示，之后较小事件不会覆盖。键、明细预算和 v4 布局保留。
- 原生文件、驱动和压缩工作按实际调用链归因；ICE 桥接祖先不再让被调用方自动归为 ICE 自身，实际 ICE 计算叶仍可归到 ICE。

旧六段会话离线回放总保留样本预算相等：原先被客户端分类压住的服务端 OTG 信息进入独立诊断；860 ms/601 ms 读配置事件归到 OTG，751 ms 保存事件归到区块保存。3.091/3.151 秒尖峰没有原始区间栈，仍保持未识别。旧记录已经丢掉的样本无法补回。

## 构建与交付

版本和优化器精确联机握手为 1.0.13。Optimizer 和 Profiler 各一个安装 JAR；Profiler 可独立或同时安装。构建使用 Java 8 / ForgeGradle 3 / Gradle 4.9，目标 Minecraft 1.12.2 与 Forge 14.23.5.2860。

干净构建 `clean build optimizerBundleZip` 成功，27 个任务全部执行，构建日志为 `.refactor-backups/1.0.13-build-final.log`。普通回归 940 项：918 通过、22 跳过、零失败/错误；最终产物 10 项全部通过。合计 950 项：928 通过、22 跳过。跳过项中一个是 Windows 文件符号链接权限限制，其余是未提供本轮无关可选模组测试输入；目录 junction 的冷/热重定向实际执行通过。

最终真实 Forge 检查覆盖 CLIENT/SERVER × 优化器独装、Profiler 独装、同装六种组合。各 JAR 同时保留普通模组候选和启动插件，各 transformer / probe 只注册或注入一次。最终类隔离、版本/握手和 bundle 内嵌 JAR 一致性验证通过。两份 workspace 部署夹具的升级、幂等、失败恢复与精确回滚通过。

| 安装产物 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `ice-rlcraft-optimizer-1.0.13.jar` | 4265851 | `1008D4B072081BB8BCE11F17AF366118DCEEA37F58990471054F633AD899C674` |
| `ice-rlcraft-optimizer-bundle-1.0.13.zip` | 3793782 | `9F5EC0F47B50AB17EC84F3AB8F3A81C6D6D75FBF37549185589C5E9EEDE1A504` |
| `ice-rlcraft-profiler-1.0.13.jar` | 272859 | `C5CD39667034FC0879B58BDAB69C7330478565571FE1CCC090143CC47BF9BE0C` |

统一计数/哈希：`.refactor-backups/1.0.13-final-verification.json`。源变更清单：`.refactor-backups/1.0.13-source-change-inventory.json`。修改前源码和 1.0.12 安装文件保存在 `.refactor-backups/1.0.12-before-live-performance-fixes`。本次测试只读实际模组 JAR，在 workspace 内建立测试夹具。

补充证据目录：

- `.refactor-backups/1.0.13-renderer-fixes`：冷上传、休眠旁路、即时追赶和模型恢复测试。
- `.refactor-backups/1.0.13-otg-validation`：实际 OTG v9.7 及文件事件/stream 测试；22 项 scoped 运行，21 通过、1 权限相关假设跳过。
- `.refactor-backups/1.0.13-nbt-validation`：NBT 数组等价、gzip 字节/读回及实际 SRG/OptiFine 适配。
- `.refactor-backups/1.0.13-profiler-fixes`：角色分组、预算、ICECAP v4 和旧六会话回放。旧 JSON 触发时间为毫秒精度，离线事件边界与原 nano 锚点最多约 1.57 ms 差异，缺样结论保持一致。


升级测试时移除旧 ICE JAR，再安装本次 1.0.13 文件。建议沿相同探索路线、相同设置记录一段；比较帧时间 P95/P99、区块加载和保存峰值，再比较平均 FPS。此版本的游戏内效果等待实测。
