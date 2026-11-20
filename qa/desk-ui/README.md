# Q11 键盘与大字号回归

在 Windows 上运行真实的 Compose Desktop 界面。测试为独立临时配置与设备替身写入一条长中文备注、完整 IPv6 地址和一条收藏命令，截图按 360 × 760 逻辑像素、2 倍字号生成。所有操作均通过窗口分发 Tab、Shift+Tab、Enter 与空格，断言设备调用次数、控件焦点及截图像素。

| 实测场景 | 结果 |
| --- | --- |
| 360dp 宽、字号 200% | 七项导航均可见。添加弹窗的首个输入框自动聚焦，取消后焦点回到添加按钮。已保存设备的连接行仍可达。 |
| 键盘连接与运行 | 连接后出现设备面板与内存读数。切换 Shell 后，Tab 与 Shift+Tab 可离开多行输入框，Enter 运行收藏一次。 |
| 取消卸载 | 键盘打开应用行「更多 → 卸载」，弹窗显示目标包名，初始焦点落在「取消」。Enter 取消后没有发出卸载命令。 |
| 宽度边界 | 760 × 500dp 和 1280 × 850dp 显示七项侧栏导航，390 × 850dp 和 360 × 760dp 显示七项紧凑导航。 |

[大字号与焦点截图](workbench-360-200pct.png) · [初次失败及修复后通过的 JUnit 断言摘要](assertions.json) · [可执行测试](../../app/src/desktopTest/kotlin/dev/rprobedesk/DeskUiTest.kt)

```powershell
.\gradlew.bat :app:desktopTest --tests '*DeskUiTest*'
```

## Android TalkBack

Android 11 的 `emulator-5590` 使用正式包 1.1.1 与同一升级测试档案。通过模拟器触摸设备发送单指探索和双击，TalkBack 系统焦点依次命中 Shell 标签、收藏的「详情」、展开后的命令正文。Android 事件日志记录了由 TalkBack 进程发起、长度大于零的成功 TTS 请求。设备以无声模式运行，语音引擎使用 eng-USA，测试范围止于合成成功，中文发音和实际听感待人工复验。[Shell 焦点](talkback-shell-focus.png) · [详情焦点](talkback-favorite-focus.png) · [命令焦点](talkback-command-read.png) · [系统事件和焦点坐标](talkback.json)

在该专用 AVD 上可用 F-Droid 签名的 TalkBack 2021-04-23（版本号 370044210，支持 x86_64）和 eSpeak NG 1.52.0（版本号 22）复跑。安装后先打开 eSpeak 初始化随包语音，在系统设置中选择该 TTS 引擎并打开 TalkBack 触摸探索。按本仓库 Android 升级验收保存指定设备和收藏后运行以下命令。测试完毕关闭服务、恢复原 TTS 引擎与辅助设置。实际验收时两款测试工具均已卸载。[TalkBack 包与 ABI](https://f-droid.org/en/packages/com.android.talkback/) · [eSpeak NG 包](https://f-droid.org/en/packages/com.reecedunn.espeak/)

```powershell
python qa/desk-ui/check_talkback.py --adb C:/Android/Sdk/platform-tools/adb.exe
```

## Windows Java Access Bridge

1.1.1 正式 MSI 覆盖安装后，其运行时含 `jdk.accessibility` 和 `windowsaccessbridge-64.dll`。外部测试进程创建私有 Win32 桌面，将测试应用的本地数据保存到临时目录，仅在子进程启用 Java Access Bridge。系统桥返回七个工作区的中文名称与 `page tab` 角色，设备标签初始带 `selected` 状态。客户端通过 `doAccessibleActions` 激活 Shell，并从更新后的系统控件树读到「设备 Shell 命令」。此次实测覆盖读屏软件使用的桥接路径，Windows 讲述人的实际发音尚待人工复验。[系统桥查询结果](windows-bridge.json) · [独立客户端](check_windows_bridge.py)

```powershell
python qa/desk-ui/check_windows_bridge.py --exe C:/QA/RProbeDesk/RProbeDesk.exe
```

Android 抽检脚本需要 Pillow，Windows 桥接脚本需要 64 位 Python 和 pywin32。Windows 测试使用自己的窗口桌面与进程级辅助技术参数，退出时关闭该应用。

## Windows 1.1.2 工具选择与恢复

实际安装 MSI 后，从私有桌面启动安装目录中的 `RProbeDesk.exe`。Java Access Bridge 激活页面控件，Win32 客户端核对应用 PID 后，在原生「选择文件」对话框输入路径并确认。测试进程单独加载 `QaToolPreferences`，同时把主控配置、SDK 发现路径和 PATH 指向测试目录或系统目录。

| 场景 | 观察结果 |
| --- | --- |
| 找不到 adb / 旧 scrcpy 路径失效 | 「设备」可选择 adb，点击「连接」提示先选择 Platform-Tools 中的 adb.exe。「镜像」可重新选择 scrcpy。 |
| 文件名选错 | 选中 `where.exe` 后提示「请选择 adb.exe」，原设置未变。 |
| 同名工具缺少能力 | 两个测试替身仅报告版本。产品分别提示 ADB 1.0.32 缺少按连接区分设备的能力、scrcpy 1.18 缺少镜像选项。 |
| 已选 adb 的内容随后被换掉 | 在测试目录中替换刚选定的官方 adb 副本后点击「连接」，产品重新读取帮助并报告能力缺失。替身只收到 `help`，没有收到 `connect`。 |
| 重新选择官方工具 | 选择 Platform-Tools 37.0.1 的 adb 和 scrcpy 3.3.4 后均显示「更改」，临时配置中保存的路径与所选文件一致。 |

复验需要 64 位 Python、pywin32、JDK 17+ 及 Windows 自带的 .NET Framework 4.x C# 编译器。[实测记录](windows-tools.json) · [测试脚本](check_windows_tools.py)

```powershell
python -X utf8 qa/desk-ui/check_windows_tools.py --exe $installedExe --adb $adbExe --scrcpy $scrcpyExe --jdk $env:JAVA_HOME
```

## Windows 面板复制设备信息（Q10）

在独立的 Windows 窗口站启动正式 MSI 安装的 1.1.2，使用临时的两个已保存设备档案。系统辅助功能桥依次激活对应列表行的「连接」，连接后在实际面板激活「复制设备信息」。同一测试窗口站的原生编辑框通过 `WM_PASTE` 粘贴，脚本从编辑框读回文本。两次均获七个字段，逐项与对应模拟器的独立 ADB 读数和档案信息核对。[两次完整粘贴结果](windows-panel-copy.json) · [复跑脚本](check_panel_copy.py)

| 实际设备行 | 粘贴出的端点 | 单独核对的目标 |
| --- | --- | --- |
| Q10-A-Target | 127.0.0.1:5587 | emulator-5586，RProbeDesk_API35 |
| Q10-B-Host | 127.0.0.1:5589 | emulator-5588，RProbeDesk_Host35 |

两台目标使用相同镜像配置，型号均为 `Android SDK built for x86_64`，均返回 Android 15、API 35、ABI `x86_64`、Android 用户 0。切换后备注和端点同步变化。测试使用窗口站自己的剪贴板，ADB 读取操作无需将目标应用切到前台。运行结束后仅断开本轮新增的本地 TCP 连接。

```powershell
python -B -X utf8 qa/desk-ui/check_panel_copy.py --exe $installedExe --adb $adbExe --jdk $env:JAVA_HOME
```
