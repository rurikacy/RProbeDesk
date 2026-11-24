# 发布记录 / Changelog

## v1.1.3

### 中文

- **Windows 安装。** 修复旧版卸载后重新安装时可能出现的 MSI 错误 2613。已验证卸载后重装和原位升级。
- **ADB 连接。** 探活失败后先重试，连续失败才关闭会话，断线提示显示最后一次错误。
- **Shell 收藏。** 使用紧凑列表和分组配色。本地文件与 WebDAV 会同步收藏顺序，旧同步文件仍可导入。
- **日志。** 缩小正文和行高，移除行间空隙。

本次发布 Windows x64 MSI。Android 当前下载版本为 [1.1.2](https://github.com/rurikacy/RProbeDesk/releases/tag/v1.1.2)。

### English

- **Windows setup.** Fixed MSI error 2613 when reinstalling after an older version was uninstalled. Reinstallation and in-place upgrade were verified.
- **ADB connections.** Retry failed health checks before closing the session and show the last error.
- **Shell favorites.** Use denser rows and group colors. File and WebDAV sync preserve favorite order and accept older sync files.
- **Logs.** Use smaller text and shorter lines, with no gaps between rows.

This release includes the Windows x64 MSI. The current Android download is [1.1.2](https://github.com/rurikacy/RProbeDesk/releases/tag/v1.1.2).

### 下载 / Downloads

[Windows x64 MSI](https://github.com/rurikacy/RProbeDesk/releases/download/v1.1.3/RProbeDesk-1.1.3-windows-x64.msi) · [SHA-256](https://github.com/rurikacy/RProbeDesk/releases/download/v1.1.3/SHA256SUMS.txt)


## v1.1.2

### 中文

- **面板与镜像。** 在面板的“复制设备信息”右侧即可打开系统镜像。原镜像页已移除。连接期间，设备地址、呼吸状态点和取消按钮显示在顶栏右侧，设备列表保持原位。
- **Shell 收藏。** 收藏或编辑命令时可填写标签，按标签查看并在组内排序。Windows 上可拖动手柄、命令标题或收起时的命令预览，Android 上可长按后拖动。“运行”紧邻“详情”，展开后同一组操作中还可编辑或删除收藏。
- **应用图标与 APK 安装。** 连接设备后会在后台预取图标。启动、停止应用或刷新列表时复用当前连接的缓存，遇到新增或更新的包再补读。将单个 APK 拖入已连接设备的应用页，松开后即可核对包信息并确认安装。
- **滚动反馈。** 可上下滚动的区域新增紫色滚动条，支持点击定位和拖动。面板按下滑柄时固定定位基准，展开的参数行高度不同时拖动也能保持稳定。滚动到边缘后继续滚动会轻微回弹，启用“减少动态效果”时关闭该动效。

从 1.1.1 升级时，Windows x64 用户可从托盘退出 RProbeDesk 后运行 MSI。Android 11 及以上用户可直接覆盖安装 APK。APK 沿用上一版的发布证书，内部版本号为 22。

### English

- **Dashboard and mirroring.** Open the system mirror directly beside the button for copying device information on the Dashboard. The dedicated mirror page has been removed. While connecting, the device address, pulsing status dot and cancel button stay at the right edge of the header, keeping the device list in place.
- **Shell favorites.** Add a tag when saving or editing a command, group favorites by tag and reorder commands within a group. On Windows, drag the handle, title or collapsed command preview. On Android, press and hold to drag. Run sits beside Details, with edit and delete actions in the same group when expanded.
- **App icons and APK installation.** Icons load in the background as soon as a device connects. Starting or stopping an app and refreshing the list reuse the connection’s cache. New or updated packages are fetched individually. Drop a single APK onto the connected device’s Apps page to review the package and confirm installation.
- **Scrolling.** Purple scrollbars support click-to-jump and dragging. The Dashboard keeps a stable drag reference across parameter rows of different heights. Scrolling past either edge gives a subtle stretch and release that respects the reduced-motion setting.

To upgrade from 1.1.1, exit RProbeDesk from the Windows system tray and run the x64 MSI. On Android 11 or later, install the APK over the existing app. The APK uses the same release certificate as 1.1.1 and has version code 22.

### 下载 / Downloads

[Windows x64 MSI](https://github.com/rurikacy/RProbeDesk/releases/download/v1.1.2/RProbeDesk-1.1.2-windows-x64.msi) · [Android APK](https://github.com/rurikacy/RProbeDesk/releases/download/v1.1.2/RProbeDesk-1.1.2-android.apk) · [SHA-256](https://github.com/rurikacy/RProbeDesk/releases/download/v1.1.2/SHA256SUMS.txt)

## v1.1.1

### 中文

- **面板更容易查找设备参数。** 可搜索系统属性、设置、硬件功能和电源信息，也能查看当前 Wi-Fi 的 MAC 随机化策略及 ADB 安装时的应用降级限制。中文界面翻译了常见属性和值。每个分组都会记住展开状态，搜索后清空关键词即可回到原来的布局。
- **实时监测显示具体容量。** 等高圆环卡片同时显示占用率、已用量和总量。内存超过 1 GB 时按 GB 显示，磁盘统计对应设备的 `/data` 分区。Windows 参数区采用三列布局，单条搜索结果可占满整行，方便阅读较长的构建指纹。
- **可以在设置中检查更新。** 查看当前版本，检查 GitHub 上的新版本，并选择下载和安装。应用会先验证安装包的 SHA-256，再交给系统安装程序。
- **修复 Windows 连接与升级。** 修复本地 ADB 服务未启动时连接设备报 10061 的问题。手动运行新版 MSI 会沿用已有安装目录，并保留本机设置。

从 1.1.0 升级时，Windows x64 用户运行下方 MSI，Android 11 及以上用户可直接覆盖安装 APK。APK 使用与 1.1.0 相同的发布证书，内部版本号为 21。

### English

- **Find device parameters in the Dashboard.** Search system properties, settings, hardware features and power data. Inspect the current Wi-Fi network’s MAC randomization policy and ADB app-downgrade restrictions when the device reports them. Common names and values are translated in Chinese. Each section remembers whether it was expanded, and clearing a search restores your layout.
- **Read used and total capacity.** Equal-height ring gauges show memory and disk usage alongside used and total capacity. Memory values above 1 GB use GB. Disk figures refer to the device’s `/data` partition. On Windows, parameters use three columns, and a single search match gets the full row for long values such as build fingerprints.
- **Check for updates in Settings.** See your installed version, check GitHub for a newer release, then choose to download and install it. RProbeDesk verifies the package’s SHA-256 before opening the system installer.
- **Windows connection and upgrade fixes.** Fixed error 10061 when connecting with the local ADB server stopped. Running the MSI reuses the existing installation directory and preserves local settings.

To upgrade from 1.1.0, run the MSI on Windows x64 or install the APK over the existing app on Android 11 or later. The APK uses the same release certificate as 1.1.0 and has version code 21.

### 下载 / Downloads

[Windows x64 MSI](https://github.com/rurikacy/RProbeDesk/releases/download/v1.1.1/RProbeDesk-1.1.1-windows-x64.msi) · [Android APK](https://github.com/rurikacy/RProbeDesk/releases/download/v1.1.1/RProbeDesk-1.1.1-android.apk) · [SHA-256](https://github.com/rurikacy/RProbeDesk/releases/download/v1.1.1/SHA256SUMS.txt)

## v1.1.0

### 中文

设备多起来时，可以在「设备」页直接点「按场地」或「按 WiFi」，用彩色书签找到同一地点或网络下的设备。编辑设备的场地或 WiFi 后，它会自动进入对应分组。点「不分组」即可回到完整列表。

- **设备和 Shell 收藏排序。** Windows 拖动行左侧的六点手柄，也可以聚焦手柄后按上下方向键。Android 长按设备名称或收藏名称即可拖动。分组时只调整同组设备，搜索和筛选时只调整可见条目。顺序保存在本机，重启后保留。
- **在另一台主控上恢复设备列表。** 本地同步文件和 WebDAV 都会带上设备资料、当前分组方式、完整设备顺序和 Shell 收藏。在另一台主控导入文件前可以核对记录数量。1.1.0 兼容旧同步文件，读取旧文件时沿用本机已有的分组和排序。互传新版配置时，请将两端都更新到 1.1.0。
- **桌面和手机的操作反馈。** Windows 托盘菜单用图标区分打开窗口和退出程序。设置页可以保存 adb、scrcpy 路径。Android 的手势导航区随应用明暗模式变化。按钮按下时即时反馈，界面尊重系统的减少动态效果设置。

Windows x64 用户从托盘退出正在运行的正式版 1.0.0，然后运行下方的 MSI。Android 11 及以上可用同一发布证书签名的 APK 覆盖升级，本版内部版本号为 20。Windows 连接设备需准备 Android SDK Platform-Tools，镜像另需 scrcpy。Android 主控已内置这两项功能。[中文使用说明与截图](https://github.com/rurikacy/RProbeDesk/blob/v1.1.0/README.md)

### English

The Devices page now lets you switch between a flat list, sites and Wi-Fi networks in one click. A colored bookmark marks each group. Edit a device’s site or network and it moves to the matching group as soon as you save.

- **Arrange devices and saved Shell commands.** On Windows, drag the six-dot handle or focus it and use the arrow keys. On Android, press and hold a device or favorite name to drag it. Grouping confines a move to its group, and filtered views keep hidden devices in place. Each host remembers its order across restarts.
- **Restore the same device list on another host.** Both local files and WebDAV include device profiles, the selected grouping mode, the complete device order and Shell favorites. File imports show record counts before you confirm. Version 1.1.0 reads older profiles and keeps the receiving host’s existing grouping and order when that information is absent. Update both hosts to 1.1.0 to exchange new profiles.
- **Desktop and mobile controls.** The Windows tray menu has icons for opening the window and quitting. You can save the adb and scrcpy paths in Settings. On Android, the gesture navigation area follows the app theme. Buttons respond on press and the interface honors the system’s reduced-motion preference.

To upgrade Windows x64 from the public 1.0.0 release, quit RProbeDesk from the tray and run the MSI below. The Android 11+ APK uses the same release certificate and version code 20 for in-place updates. Windows hosts need Android SDK Platform-Tools to connect and scrcpy for mirroring. Android hosts include both capabilities. [English guide and screenshots](https://github.com/rurikacy/RProbeDesk/blob/v1.1.0/README_EN.md)

### 下载 / Downloads

[Windows x64 MSI](https://github.com/rurikacy/RProbeDesk/releases/download/v1.1.0/RProbeDesk-1.1.0-windows-x64.msi) · [Android APK](https://github.com/rurikacy/RProbeDesk/releases/download/v1.1.0/RProbeDesk-1.1.0-android.apk) · [SHA-256](https://github.com/rurikacy/RProbeDesk/releases/download/v1.1.0/SHA256SUMS.txt)

## v1.0.0

### 中文

RProbeDesk 1.0.0 包含 Windows x64 和 Android 11 及以上两个主控端，可以通过局域网调试另一台 Android 设备。

- 在「设备」发现 ADB 服务，或手动输入地址。传统 TCP 连接可留空配对码，无线调试配对使用目标提供的六位码。
- 面板集中显示性能圆环和设备详情。可管理应用、传输文件、运行收藏的 Shell 命令、操作镜像，或按条件筛选并导出 logcat。
- WebDAV 可手动同步设备资料、标签与 Shell 收藏。Windows 支持单实例、托盘单击恢复，并在设置中统一配置 adb 和 scrcpy 路径。

Windows 主控需要安装 Android SDK Platform-Tools，使用镜像时另需 scrcpy。Android 主控内置连接和镜像组件。从旧版迁移时，Windows 用户请先从托盘退出 RProbeDesk，再运行 1.0.0 MSI。旧 GitHub Release 的 v1.2.0 APK 与本版签名不同，Android 无法覆盖安装。请先备份需要保留的设备资料和命令，再卸载旧版。对于使用相同发布签名且内部版本号低于 12 的本地 Android 版本，可直接覆盖升级。

[中文使用说明与截图](https://github.com/rurikacy/RProbeDesk/blob/v1.0.0/README.md)

### English

RProbeDesk 1.0.0 turns a Windows x64 PC or an Android 11+ device into an ADB host for another Android device on the local network.

- Discover ADB services or enter an address manually. Use an empty pairing code for classic TCP connections or the target’s six-digit code for wireless pairing.
- Inspect performance gauges and device details, manage apps, transfer files, run saved Shell commands, control the target through a mirror, and filter or export logcat.
- Manually sync devices, tags and Shell favorites through WebDAV. Windows runs a single instance, restores from the tray with one click, and keeps adb and scrcpy paths in Settings.

Windows requires Android SDK Platform-Tools and uses scrcpy for mirroring. The Android host includes both its connection and mirroring components. On Windows, exit RProbeDesk from the tray before running the 1.0.0 MSI. The GitHub v1.2.0 APK used a different signing certificate. Back up any device records and commands you need before uninstalling that APK. A locally installed Android build with the same release signature and a lower version code can be updated in place. This release uses version code 12.

[English guide and screenshots](https://github.com/rurikacy/RProbeDesk/blob/v1.0.0/README_EN.md)

### 下载 / Downloads

[Windows x64 MSI](https://github.com/rurikacy/RProbeDesk/releases/download/v1.0.0/RProbeDesk-1.0.0-windows-x64.msi) · [Android APK](https://github.com/rurikacy/RProbeDesk/releases/download/v1.0.0/RProbeDesk-1.0.0-android.apk) · [SHA-256](https://github.com/rurikacy/RProbeDesk/releases/download/v1.0.0/SHA256SUMS.txt)

## v1.2.0（已撤下 / withdrawn）

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
