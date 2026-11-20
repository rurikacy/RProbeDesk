# HTTPS 直链安装与签名升级验收

2026-11-21 在 Windows 11 主控连接 Android 11 测试模拟器时执行。检查程序加载桌面分发包中的 `WorkspaceModel` 和 `WindowsHost`。服务仅监听本机回环地址，独立 Java 进程使用临时信任库和带 `127.0.0.1` IP SAN 的证书。系统信任库和用户的 RProbeDesk 配置均保持原状。

| 检查 | 目标与请求结果 |
| --- | --- |
| 校验证书与跳转 | 用 `localhost` 访问该证书时出现 `SSLHandshakeException`。合法 HTTPS 地址重定向至明文 HTTP 时终止下载。两者均未进入安装确认。 |
| 验证下载与取消 | 服务器观测到 TLS 1.3。产品读取 8,538 字节的 APK 并显示包名与版本 1；取消后包管理器查询为空。 |
| 首次安装与同签名升级 | 再次下载并确认后，包管理器回读版本 1。同一签名证书签发的版本 2 触发「更新」确认，完成后回读版本 2。 |
| 不同签名 | 同包名版本 3 使用第二张签名证书。包管理器返回 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，界面模型报告原因，目标仍为版本 2。 |
| 清理 | 测试以随机包名隔离，三个 APK 均无 Activity。结束时卸载该包并复查，测试目录、临时证书和签名材料随测试子进程退出清理。 |

这次执行用 `apksigner verify --print-certs` 确认三个 APK 各自具有可验证的签名，以及 v1/v2 的证书摘要相同、v3 摘要不同。签名不一致在设备端更新时被拒绝。测试直接应答产品生成的安装确认模型，覆盖 Windows 实际 ADB 安装通路。镜像窗口与输入有单独的[Windows Q08 实测记录](../windows-mirror/README.md)。

### Windows 1.1.1 下载失败回归

2026-11-21 从已安装的 Windows 1.1.1 分发包加载 `WorkspaceModel`，用随机回环端口提供下列 HTTP 响应。两次都先出现「允许明文 HTTP 下载？」并显示本次来源。测试同意下载后观察模型到任务结束。设备调用由计数桩记录，设置和缓存位于独立临时目录。

| 服务实际发送 | 模型最终错误 | 安装确认 / 设备调用 / 遗留暂存文件或租约 |
| --- | --- | --- |
| `/html.apk` 声明并发送 45 字节 HTML，HTTP 200 | 文件不是有效的 APK，或下载未完成 | 0 / 0 / 0 |
| `/truncated.apk` 声明 4,096 字节，发送 18 字节 ZIP 前缀后关闭连接 | 传输不完整，已收到 18 / 4096 字节 | 0 / 0 / 0 |

复验这两条失败路径只需 JDK 与 Windows 发布包的 `app` 目录。`$appLibs` 指向其中含 `app-desktop-*.jar` 的目录。本次加载的应用 JAR 的 SHA‑256 为 `86B0FA2BDFA20EEC5D880140B1828536726A00643E678FC2119F96FB676A36C7`。

```powershell
python -X utf8 qa/https-install/check_https.py --invalid-downloads-only --app-libs $appLibs --jdk $env:JAVA_HOME
```

### Android 1.1.1 正式包 HTTPS 验收

2026-11-21，Android 15 主控（5588）通过已配对的无线调试连接目标（5586）。测试先逐字节核对主控上已安装的 APK 与发布文件，再由同签名仪器驱动实际 `WorkspaceModel`。本地 HTTPS 服务只监听 Windows 回环地址，使用一次性 CA 和只含 `127.0.0.1` IP SAN 的证书，经主控专属 `adb reverse` 传输。

| 场景 | 观察结果 |
| --- | --- |
| 先访问 `https://localhost` | Android 报告 `SSLPeerUnverifiedException`。未出现安装确认，目标查询不到测试包。 |
| 再访问 `https://127.0.0.1` | 服务器记录一次 TLS 1.3 GET，共 12,657 字节。确认框显示来源、`dev.rprobedesk.fixture` 与版本 1。确认前目标仍无该包，确认后在 Android 用户 0 回读版本 1。 |
| 清理 | 卸载测试包并确认包列表已移除，恢复仪器进程原 TLS 工厂并核对原主机名验证器，移除本轮反向端口。CA 只交给此次测试进程。 |

runner 将测试 CA 公钥放入当前进程内存中的独立 KeyStore，以标准 TrustManagerFactory 校验服务端证书链；原 Android 主机名验证器全程参与。临时 SSL 工厂在 finally 中恢复。服务端私钥随测试目录删除，系统信任库和产品配置均未写入证书。[仪器输出](android-https.txt) · [测试请求和安装回读](android-https.json)

复验需要已配对的上述两台 AVD、Python `cryptography`、正式 APK，以及与正式包签名相同的测试 runner。用 `:app:assembleDebugAndroidTest` 构建 runner，按本地发布签名流程给测试 APK 签名。脚本会核对设备身份，并从 runner 的 assets 读取本仓库构建的 fixture。[测试脚本](check_android_https.py)

```powershell
python -X utf8 qa/https-install/check_android_https.py --adb $adbExe --release dist/RProbeDesk-1.1.1-android.apk --runner $signedRunner --target-port 48372
```

## 复验

准备 Windows 桌面分发包、JDK 17+、Python 3.9+、Android SDK 和已授权的本地测试模拟器。以下命令中的 `$sdkDir` 为 SDK 目录，`$port` 为该模拟器的 TCP 连接端口。默认使用 Android 35 平台与 Build Tools 35.0.0，可用脚本参数选择本机安装的版本。

```powershell
.\gradlew.bat :app:createDistributable
python -X utf8 qa/https-install/check_https.py `
  --app-libs app/build/compose/binaries/main/app/RProbeDesk/app `
  --sdk $sdkDir --jdk $env:JAVA_HOME --port $port
```

每次复验都重新生成证书、签名、随机包名和独立的主控配置目录。结果由 HTTPS 服务所见协议、产品安装确认数据、包管理器版本及错误码交叉核对。

