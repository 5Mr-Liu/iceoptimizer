ICE 崩溃日志收集工具（不是新版优化模组）

在发生崩溃的那台电脑上使用：
1. 将 collect-ice-crash.cmd 和 collect-ice-crash.ps1 放进该游戏版本目录。
   该目录中应当能看到 mods、logs 和 crash-reports 等文件夹。
2. 崩溃后双击 collect-ice-crash.cmd，尽量在重新启动游戏前收集。
3. 桌面的 ICE-Diagnostics 文件夹内会生成 ice-crash-时间.zip。
   将这个 ZIP 发回当前对话即可，不需要自己判断是 F6 还是光影导致。

收集内容：latest.log、可用的 debug/FML 日志、最近 3 份历史压缩日志、
最近 3 份崩溃报告、光影/OptiFine/ICE 配置、模组文件清单、关键模组 SHA256、
CPU 和显卡驱动信息。显卡列表仅表示已安装设备，实际使用的显卡仍以游戏日志为准。
不会复制存档或模组本体，不会修改游戏配置，不会自动上传文件。

如果日志已被后续启动覆盖，工具会一并收集最近的历史日志，但更早记录可能仍缺失。
游戏日志可能包含聊天或服务器地址，发送前可自行检查压缩包内容。

可选命令行用法：
powershell.exe -NoProfile -ExecutionPolicy Bypass -File collect-ice-crash.ps1 -GameDirectory "游戏版本目录"

注意：这是补齐完整报错过程的工具，不代表已定位或修复 PjoRmwt 中的崩溃。
