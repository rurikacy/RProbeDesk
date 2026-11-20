# Android 1.1.1 首次安装与启动

2026-11-21 在 `RProbeDesk_Auth35`（`emulator-5592`，Android 15 / API 35，Google Play `user/release-keys`）测试。该 AVD 在此前的授权测试中只作为被控目标。安装前枚举全部 Android 用户，仅有用户 0。`pm list packages -u --user 0 dev.rprobedesk` 及 `pm path dev.rprobedesk` 均为空。

首次安装的正式 APK 版本码为 4，SHA-256 为 `f4dafd691c6aabfc0b390fbbd0d91e99d56d4c4269cddd6aadbf42a2657a2622`。首次安装与最后更新时间同为设备本地 `2026-11-21 01:30:21`。确认产品进程尚未运行后，从系统 Pixel Launcher 的应用列表点按 **RProbeDesk** 图标。

主界面显示「未连接设备」「尚未保存设备」，已保存设备数量为 0；七个页签为「设备、面板、应用、文件、Shell、镜像、日志」。截图分辨率为 1080 × 2340。验收后仅卸载了本轮安装的 `dev.rprobedesk`，核实包记录为空，随后正常关闭 5592 并保留 AVD 及用户数据。[首次启动截图](clean-1.1.1-device.png)
