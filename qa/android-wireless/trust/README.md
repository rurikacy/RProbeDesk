# 无线调试的系统授权与目标身份

本轮在同一个地址 `10.0.2.2:48492` 先后接入两台独立 Android 15 模拟设备，检查 Android 系统如何授权主控，以及 RProbeDesk 如何识别目标变化。主控为 `emulator-5588`。先用签名 APK 1.1.0 检查身份替换与撤销，再原位升级签名 APK 1.1.1（版本码 4，SHA-256 `f4dafd691c6aabfc0b390fbbd0d91e99d56d4c4269cddd6aadbf42a2657a2622`）复验中文反馈与恢复。

| 目标 | 系统 | 验证用途 |
|---|---|---|
| A，`emulator-5586` | AOSP 15 `userdebug/test-keys`，`ro.adb.secure=0` | 通过系统配对并核对目标通知后，将 A 的身份与保存的连接端点关联 |
| B，`emulator-5592` | Google Play API 35 x86_64 revision 9，`user/release-keys`，`ro.adb.secure=1`，构建 `AE3A.240806.036/12592187` | 作为替换目标，验证强制认证时遗忘授权后系统拒连 |

B 使用 Android SDK Manager 发布的 `system-images;android-35;google_apis_playstore;x86_64`。在系统的「关于模拟设备」中点按版本号七次，通过开发者选项开启无线调试并允许 AndroidWifi。测试使用模拟器本身提供的端口转发和目标设置显示的实际端口。所有配对均经过系统配对码交换。[Google Play 系统镜像说明](https://developer.android.com/studio/run/managing-avds)

## 同地址更换目标

1. 打开 A 的系统配对窗口，在主控提交六位码，再从 A 的通知回读八位确认码。连接备注 `QA-System-Trust` 的记录，核对面板已经显示 A 的型号和内存，然后断开。[A 的面板](a-confirmed.png)
2. 先在 B 与主控之间完成系统配对。关闭并重新打开主控，清除本次配对的临时状态，保留已确认的设备档案。将先前指向 A 的端口转发改指 B。主控所用 IP、端口和备注均保持相同。
3. 点击原记录的「连接」。主控提示目标身份尚未确认或已更改，在线设备为零，镜像入口不可用。[身份不符](replacement-rejected.png)
4. 重新在 B 通过系统六位码配对，在当前连接上读回 B 的八位通知码。主控随后展示新旧型号供核对。确认后面板显示 B 的型号 `sdk_gphone64_x86_64`；断开再连接正常。[档案核对](replacement-profile-review.png) · [B 的面板](b-confirmed.png)

## 系统撤销授权

1. 在主控断开与 B 的连接。在 B 的「无线调试 → 已配对设备 → RProbeDesk → 设备详情」点击 **Forget**。操作针对单项主控授权，未关闭无线调试。
2. 保留同一目标、同一 TLS 端口 `42643`、同一主控端点与同一应用私钥，再点击原记录连接。1.1.0 显示 `ADB pairing is required.`，在线设备数为零，面板未打开。这一判断取自实际新连接的结果；该镜像的系统设置列表在操作后仍一度显示主控名称。[系统拒绝连接](system-revoked.png)
3. 重新打开 B 的系统配对窗口，在主控输入新码；沿用之前保存的目标身份和端点再次连接，主控能够读回内存指标。
4. 再次在 B 的系统设置中忘记 RProbeDesk，并将主控覆盖升级到 1.1.1。在原记录上点「连接」，主控保持离线并显示「目标未授权此主控。请在目标设备打开无线调试的配对码窗口，重新配对。」在 B 完成新的系统配对后成功进入面板。1.1.1 将上游明确报告的需要配对及认证失败转换为中文，其他错误保留各自的原因。[1.1.1 的拒绝反馈](system-revoked-zh.png)

## Windows 1.1.2 使用已失效的系统配对码

复验使用同一台开启强制认证的 Google Play 用户版目标 B。Windows 载入已安装 MSI 的 `WindowsHost`。在目标打开「使用配对码配对设备」，读码后关闭这个弹窗，再打开一个新弹窗。把新弹窗的配对端口映射至 Windows 的回环地址，在该有效端口只提交一次已失效的旧码。程序返回「配对失败，请核对配对端口和有效期」，错误中未包含所提交的数字。

再次打开系统配对弹窗，使用当次显示的新码后配对成功。通过无线调试主页给出的独立 TLS 连接端口连接，读回 `ro.adb.secure=1` 与型号 `sdk_gphone64_x86_64`。本例在内存中传递一次性码，退出时移除本轮的两个端口转发。[Windows 配对和连接适配器](../../../app/src/desktopMain/kotlin/dev/rprobedesk/DesktopMain.kt)

## 返回原目标后的发布包回归

在 1.1.1 中断开 B，用目标 A 的系统配对码与通知确认码重新核验原有记录 `QA-Pixel`。同一台 `emulator-5588` 保留已升级发布包的数据，安装用同一证书签名的 QA instrumentation 包。通过已核对的 `10.0.2.2:48372` 对 `emulator-5586` 运行五项回归，覆盖普通启动窗口、Shell 与指标、文件与截图、应用生命周期、实时日志。2026-11-21 全部通过，用时 11.187 秒。[测试输出](instrumentation-1.1.1.txt) · [测试源码](../../../app/src/androidInstrumentedTest/kotlin/dev/rprobedesk/AndroidDeviceTest.kt)
