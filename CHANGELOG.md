# 发布记录 / Changelog

## v1.2.0

### 中文

Windows x64 与 Android 11+ 主控使用统一版本号。打开「设备」，按备注保存自己的调试设备，使用无线配对码建立信任，再用目标无线调试主页的连接端口进入面板。

- **图标与工作台**。Windows 安装程序、桌面窗口和 Android 启动器统一使用新图标。中英文主页展示了新版安装后的实际界面。
- **面板与应用**。查看可用的 CPU、内存和温度读数，保存截图或复制设备信息。在当前 Android 用户下启动、停止、冻结、解冻或卸载应用。选择本机单 APK 或通过直链下载，核对包名和版本后安装。
- **文件与 Shell**。从主控上传、下载到主控，或在目标的同一卷内移动文件。同名覆盖会重新核对目标。保存常用 Shell 命令，查看实际输出和退出状态。
- **镜像与日志**。Windows 使用独立 scrcpy 窗口，Android 主控可在镜像页操作目标。开始 logcat 采集后切到镜像复现问题，再按级别、Tag、PID 或关键字筛选并导出。断开或切换目标时，写操作和日志保留其设备归属。
- **Windows 工具恢复**。adb 或 scrcpy 移动、选错或缺少必需能力时可以重新选择，执行前也会检查工具。

下载时按主控平台选择 Windows MSI 或 Android APK，并用同一发行页的 SHA‑256 清单核对。Windows 主控需另行准备 Android SDK Platform-Tools，镜像还需 scrcpy。Android 主控内置无线传输与镜像组件。[使用指南](README.md) · [问题排查](SUPPORT.md)

### English

The Windows x64 and Android 11+ hosts share one release number. In Devices, save a label for your development device, pair using its wireless code, then connect using the port on the target's Wireless debugging main screen.

- **Icon and workspace.** The Windows installer, desktop window and Android launcher use the new app icon. Both READMEs show the workspace from the installed release.
- **Dashboard and apps.** Inspect available CPU, memory and temperature readings, take a screenshot or copy device information. Launch, stop, freeze, unfreeze or uninstall apps for the current Android user. Install a local single APK or download one from a direct URL and confirm its package and version.
- **Files and Shell.** Transfer files in either direction or move a target file within the same volume. Overwrites recheck the destination. Save frequently used Shell commands and inspect output and exit status.
- **Mirror and logs.** Windows opens a separate scrcpy window. Android hosts provide a mirror view with input controls. Start logcat, reproduce a problem in Mirror, then filter by priority, tag, PID or text and export the result. Writes and logs stay associated with their original device when a session ends or changes.
- **Windows tool recovery.** Reselect adb or scrcpy after a move, a wrong selection or a missing capability. Tools are also checked before use.

Choose the Windows MSI or Android APK for the host and verify it against the SHA‑256 list from the same release. The Windows host needs Android SDK Platform-Tools and uses an external scrcpy installation for mirroring. The Android host includes its transport and mirror components. [User guide](README_EN.md) · [Troubleshooting](SUPPORT.md)
