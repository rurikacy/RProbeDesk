# Android 正式包覆盖升级验收

1.1.1（版本号 4）已从 1.1.0 直接同签覆盖。强停后通过应用主 Activity 重开，浅色设备工作台、备注与完整端点、Shell 收藏名称和正文均已读回。首次安装时间仍为 00:39:59，本版更新时间为 01:23:56。[1.1.1 设备页](results/patch_upgrade-device.png) · [1.1.1 收藏详情](results/patch_upgrade-favorite.png) · [结构化版本记录](results/results.json)

1.1.0（版本号 3）已在 Android 11 / API 30 模拟器完成同签覆盖。升级前的 1.0.1 使用暗色，强停重开升级后的应用显示浅色工作台。设备备注 `QA-5590`、端点 `192.0.2.170:40321`、收藏名称 `Release-QA-5590` 和命令 `echo qa5590` 均可从界面读回。首次安装时间保持为 00:39:59，最终包更新时间为 01:02:02。[1.1.0 设备页](results/light_upgrade-device.png) · [1.1.0 收藏详情](results/light_upgrade-favorite.png) · [版本、签名与 APK SHA256](results/results.json)

## 旧版基线

在 Android 11 / API 30 的 `RProbeDesk_WindowsQA` 模拟器上，1.0.0（版本号 1）首次安装后默认为亮色、设备列表为空。通过应用界面切到暗色，添加设备备注与地址，并保存一条 Shell 命令。强停重开后读回，再用相同证书签名的 1.0.1（版本号 2）覆盖安装。两版的首次安装时间一致，更新时间递增。升级后再次强停并启动，主题、备注、端点、收藏名称和命令正文都与原值一致。[验收数据及 APK 摘要](results/results.json) · [升级后设备页](results/upgrade-device.png) · [升级后收藏详情](results/upgrade-favorite.png)

## 复跑

准备未安装 RProbeDesk 的指定 AVD，使用 `emulator-5590` 启动。脚本核对序列号、AVD 名称、API 级别和模拟器属性后才执行安装。选择新的证据输出目录，四个阶段使用同一目录。需要 Python 3.11 或更高版本、Android Platform-Tools、Build Tools 及可运行 `apksigner` 的 JDK。

```powershell
$adb = 'C:/Android/Sdk/platform-tools/adb.exe'
$tools = 'C:/Android/Sdk/build-tools/35.0.0'
python qa/android-release/verify.py seed --adb $adb --build-tools $tools --apk C:/qa/RProbeDesk-1.0.0.apk --output C:/qa/android-release-run
python qa/android-release/verify.py upgrade --adb $adb --build-tools $tools --apk C:/qa/RProbeDesk-1.0.1-android.apk --output C:/qa/android-release-run
python qa/android-release/verify.py light --adb $adb --build-tools $tools --apk C:/qa/RProbeDesk-1.1.0-android.apk --output C:/qa/android-release-run
python qa/android-release/verify.py patch --adb $adb --build-tools $tools --apk dist/RProbeDesk-1.1.1-android.apk --output C:/qa/android-release-run
```

数据通过可访问性节点定位控件，并用系统输入事件写入。第三阶段从已记住暗色偏好的 1.0.1 升级至 1.1.0（版本号 3）。脚本检查签名与安装时间，强停重开，在新设备页核对搜索框、记录与主题切换控件的移除，再检查系统截图中的浅灰画布与白色工作区，最后通过 Shell 页打开收藏详情。测试地址属于文档示例网段，验证内容为离线档案保存与升级读取。运行后保留应用和记录以便复核。
