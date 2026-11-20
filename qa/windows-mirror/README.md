# Windows 独立镜像验收

## 1.1.2 发布包复验

从正式 MSI 安装目录加载产品的 `WindowsHost` 和 `DeviceSession`，通过 Windows 11 连接本机 Android 11 专用模拟器。使用 scrcpy 3.3.4。测试页包含固定文字、输入框和四角按钮。窗口、焦点及剪贴板操作均在独立测试环境内完成。

| 检查 | 实测结果 |
| --- | --- |
| 可辨首帧 | 从调用产品 `startMirror` 到捕获真实独立窗客户区的首个匹配画面，首次 **2603 ms**，关闭后同一会话重开 **2259 ms**。按 170 ms 间隔抽样，时间包含截图比对与写盘，是本机观测上界。窗口画面与目标原始 PNG 按比例缩小后平均灰度差为 0.66 和 0.76（0–255）。[首次](first-frame.png) · [重开](reopened-frame.png) · [测量数据](mirror-render.json) |
| 原始截图与旋转 | 产品截图接口导出的 PNG 经 Pillow 完整解码为 **1080 × 2280** 与 **2280 × 1080**。目标的系统栏随方向变化，图片保留目标内容。镜像窗口中也可辨认横屏四角与中央控件。[竖屏原图](target-portrait.png) · [横屏原图](target-landscape.png) · [横屏镜像](landscape-frame.png) |
| 主控向目标输入中文 | 在全新、独立且非交互的 Windows 窗口站中，仅向测试剪贴板写入「主控验收」。向镜像键入 `7`，再执行 scrcpy 显式快捷键左 Alt+V，目标 EditText 从自身系统控件树读回 `7主控验收`。本轮夹具未预填目标剪贴板。用户交互桌面的剪贴板未被打开。[输入结果](mirror-control.json) |
| 失焦与关窗 | 将 Win32 键盘焦点从镜像移至独立本地 Edit。普通数字键投递给系统报告的焦点控件，本地读回 `8`，目标仍为 `7主控验收`。两次关闭后原会话均能执行只读核验。 |

非交互窗口站与用户窗口站分别拥有剪贴板。测试使用仅本地有效的新登录会话 ID 创建窗口站，创建时要求对象此前不存在。完成创建后立即恢复调用方身份。渲染验证使用另一个不切到前台的私有桌面，通过 `PrintWindow` 读取指定 scrcpy 窗口，使用 SDL 软件渲染。测量范围为本机测试环境中的适配器启动到画面可辨，实体 WLAN 延迟和系统输入法直接上屏仍需分别验证。[窗口站创建规则](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-createwindowstationw) · [scrcpy 3.3.4 控制说明](https://github.com/Genymobile/scrcpy/blob/v3.3.4/doc/control.md)

复跑需要 64 位 Python、Pillow、pywin32、JDK 17 及下方相同的 AVD 与夹具。传入安装目录下含 `RProbeDesk.cfg` 的 `app` 子目录。先运行渲染，再运行输入验证。

```powershell
$tools = @('--app-libs', 'C:/QA/RProbeDesk/app', '--scrcpy', $scrcpyExe, '--adb', $adbExe,
    '--java', "$env:JAVA_HOME/bin/java.exe", '--fixture', 'qa/fixture/build/outputs/apk/debug/fixture-debug.apk',
    '--evidence-dir', 'qa/windows-mirror')
python -X utf8 qa/windows-mirror/check_isolation.py @tools --phase render
python -X utf8 qa/windows-mirror/check_isolation.py @tools --phase control
```

## 四角、留白与按键基线

Q10 另通过正式 MSI 的「面板 → 复制设备信息」操作，在私有 Windows 编辑框粘贴两台设备各自的备注、端点和平台字段，并与独立 ADB 读数核对。[面板实测](../desk-ui/windows-panel-copy.json)

2026-11-21 使用 Windows 11、scrcpy 3.3.4 和 Android 11 专用测试模拟器完成 Q08。Windows 主控连接 `127.0.0.1:5591` 上已经授权的测试端点。检查程序直接加载桌面分发包里的 `WindowsHost`，由产品代码启动及跟踪 scrcpy 独立窗口。窗口内的结果通过同仓库测试 APK 的控件或 `RProbeFixture` 日志回读。

| 操作 | 实测结果 |
| --- | --- |
| 竖屏、横屏依次点击 TL / TR / BL / BR | 1080 × 2280 和 2280 × 1080 各四次点击均产出对应标签。[竖屏实测](mirror-portrait.png) · [横屏实测](mirror-landscape.png) |
| 在竖屏时将 Windows 镜像客户区调整至 862 × 603 | 单击左侧留黑区，夹具靶点事件数保持原值。再单击画面内 TL，夹具记录一次命中。 |
| 用 Windows 键盘输入 `A1` | 从目标 EditText 读回相同的两个字符。直接向 SDL 窗口提交中文字符时，默认 SDK 键盘未将其写入目标。 |
| 在镜像窗按 Ctrl+V | 夹具事先在**测试设备的剪贴板**放入「测试」，目标 EditText 成功粘贴这两个字。产品启用了 `--no-clipboard-autosync`。 |
| 右键返回，关闭镜像窗口 | 返回操作使目标退出测试 Activity。关闭独立窗口后，主控镜像状态恢复，原 ADB 会话成功执行下一条只读命令。 |

## 复验

准备 JDK 17+、Python 3.9+、Platform-Tools、scrcpy 3.3.4 和一台一次性的已授权本地模拟器。在项目根目录构建应用和夹具。运行命令中的 `$scrcpyExe` 指向已安装的工具，`$port` 指向该测试模拟器的明确 TCP 连接端口。生成截图需要 Python Pillow 11.2.1+。

```powershell
.\gradlew.bat :app:createDistributable :qa:fixture:assembleDebug
python -X utf8 qa/windows-mirror/check_mirror.py `
  --app-libs app/build/compose/binaries/main/app/RProbeDesk/app `
  --java "$env:JAVA_HOME/bin/java.exe" `
  --adb "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" `
  --scrcpy $scrcpyExe --port $port `
  --fixture qa/fixture/build/outputs/apk/debug/fixture-debug.apk `
  --evidence-dir qa/windows-mirror
```

脚本检查目标的模拟器标记。所有窗口消息都绑定本次产品启动的 scrcpy 子进程及其完整标题，移动鼠标前还会核对该客户区像素的窗口归属。测试时保持镜像窗无遮挡。脚本最终恢复目标旋转设置，若夹具是本轮首次安装，也会将其卸载。此记录覆盖模拟器与桌面独立窗，实体设备的 Wi-Fi 延迟和厂商输入限制需另行验证。

