# 使用支持 / Support

## 中文

先确认软件运行在哪台**主控**上，以及实际操作的是哪台**目标设备**。需要安装、配对或构建步骤时，请从 [README](README.md) 开始。

| 遇到的问题 | 可先核对 |
| --- | --- |
| 已配对，连接仍失败 | 目标「无线调试」主页显示的是连接端口，配对码弹窗显示的是配对端口。核对主页当前端口、两台设备的网络连通性和主控备注中的地址。 |
| Windows 提示选择工具 | 在「设备」选择 Android SDK Platform-Tools 的 `adb.exe`。在「镜像」选择官方发行包中的 `scrcpy.exe`。解压完整工具包并保留相邻文件；工具移动或版本不兼容时可重新选择。 |
| Android 上传或下载找不到文件 | 核对「文件」页显示的目标目录与系统文档选择器选择的主控位置。尝试用 Downloads 中的一个无敏感内容的小文件复现。 |
| 指标为空或日志无输出 | 记下缺少的具体指标、系统和机型。「日志」需要手动开始采集；检查缓冲区、最低等级、Tag、PID 与关键字。主控回到前台后可重新开始采集。 |
| 操作中断，结果待核实 | 先重连同一目标，刷新应用列表或查看目的路径，核对目标上的实际结果后再决定是否重试。 |

仍可重现时，请用本仓库的错误报告模板附上软件版本、主控/目标版本、步骤和脱敏错误原文。只需提供与问题有关的几行日志。[安全问题](SECURITY.md)

## English

Identify the **host** running RProbeDesk and the **target** you intend to control. Start with the [English README](README_EN.md) for installation, pairing and build instructions.

| Symptom | What to check |
| --- | --- |
| Paired but unable to connect | The target's Wireless debugging main screen shows its connection port. The pairing-code dialog shows a separate pairing port. Check the current connection port, network reachability and the address in the saved entry. |
| Windows asks for a tool | In Devices, choose `adb.exe` from Android SDK Platform-Tools. In Mirror, choose `scrcpy.exe` from the official distribution. Extract the whole tool package and keep its neighboring files. Select again if a tool moved or its version lacks a required feature. |
| Android upload or download cannot find a file | Check the target directory in Files and the host location selected in the system document picker. Try a small, non-sensitive file in Downloads. |
| A metric is missing or the log is empty | Record the affected metric, OS and model. Start log collection explicitly and check the buffer, minimum priority, tag, PID and text filters. After bringing the host back to the foreground, restart collection as needed. |
| An interrupted operation has an unknown result | Reconnect to the same target and refresh its application list or inspect the destination path before deciding whether to retry. |

If the problem persists, use the repository's bug template and include the app version, both systems, steps and the redacted error text. A few relevant log lines are usually enough. [Security reports](SECURITY.md)
