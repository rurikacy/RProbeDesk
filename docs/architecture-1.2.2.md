# RProbeDesk 1.2.2 架构与验收

本次验收以公开版本 `v1.2.1`（`67274fa`）为基线。Windows x64 和 Android 仍共用 Compose 工作区。交付物包括自包含 MSI、一个通用 APK、四个单 ABI APK 和 SHA-256 清单。本次采用本地分发，公开下载仍指向 1.2.1。

## 架构变更与验证

| 范围 | 实现与边界 | 本轮验证 |
| --- | --- | --- |
| 会话与重连 | Rust 定义 12 个显式状态码、事件码和 10 类传输错误。每个会话保留已验证身份、端点、传输代次、一次关闭回调和一个可取消定时器。最多 32 个会话。Kotlin 在各自协程中执行平台连接、监控与 UI 提示。关闭先移除资源所有权，再在锁外执行回调。 | 非法转移、重复关闭、旧代次释放、用户断开、网络恢复提前唤醒、前后台切换、双工作区独立恢复。修复后台探活和恢复前台时的锁等待。 |
| Windows ADB | 使用包内 ADB 前台进程监听系统分配的回环 TCP 端口。按子进程 PID 枚举监听器并验证 ADB 版本响应。ADB 命令、订阅、传统 TCP 的业务流与 scrcpy 使用同一个私有端口。端点通过引用计数管理。 | 两个私有服务可以同时工作，关闭一个后另一个仍可列出设备。测试中关闭镜像使用的兼容服务，已验证的原生 TLS 会话继续返回 Shell 结果，再次打开镜像可按需建立兼容连接。 |
| 无线认证 | Windows 保留官方配对和原 Windows 用户密钥，原生 TLS 在业务连接上验证签名与 SPKI。首次镜像额外核对同一目标。Android 继续由 AndroidKeyStore、Conscrypt 和平台适配器完成配对。 | 两端分别以一次性系统配对码连接专用模拟器，通过独立控制台核对目标通知。重连同一公钥成功，保存的公钥改变后拒绝连接。Android 用例切换 Wi-Fi、重开无线调试并注入网络延迟及丢包。 |
| 并发、JNI 与取消 | 共用双工作线程 Tokio Runtime。原生拨号上限 8，每个 Windows TLS 传输最多 16 个通道。读泵结束唤醒该传输上的订阅和读写。JNI 只在当前线程借用可复用的缓冲；Java 数组在调用期间复制，长期句柄由对应会话释放。 | 取消无响应握手、无效句柄、越界数组、取消传输后继续开 Shell、传输世代隔离。 |
| Logcat | 原始字节进入 Rust，按块处理 UTF-8，解析时间、PID、TID、等级与标签。单行上限 4,096 个 UTF-16 单元，未结束行最多 16 KiB，采集环保留最近 1,500 行。界面按游标接收新增行，延续异常堆栈上下文，分别保留显示快照、暂停、关键词/标签/PID/等级过滤、导出、淘汰计数及断线提示。 | 逐字节切分中文、Emoji、CRLF、异常堆栈与 24,000 行溢出均通过。正式 R8 包在真实连接上显示了目标写入的指定日志并可暂停。 |
| 文件与 Shell | 两端复用 Shell V2 和 Sync v1。Sync 使用 64 KiB 缓冲，控制帧及时刷新，数据帧合并刷新。60 秒无进展可取消，持续日志支持主动关闭。应用保留临时文件提交、进度、结果待核实和原有 SAF。 | 4 MiB 实机往返逐字节比对。32 MiB 流式测试在模拟器验证上传与下载 SHA-256、单调进度、取消下载及后续 Shell。失败的写操作由用户核实。 |
| 发现、健康与身份 | 共享 mDNS 服务按事件更新快照，最多 128 个服务、每服务 8 个地址。120 秒缓存上限，8 秒无需求后退出。处理移除、网络变化、IPv6 scope 和配对/连接服务。最多尝试三个不同发现地址及保存端点，再核验身份；身份拒绝终止自动尝试。Android 前后台约束发现，平台保留 NSD / Platform-Tools 回退。 | 双地址夹具先给出不可用地址，随后迁移 IP 与端口，约 5.51 秒恢复。专用模拟器 adbd 重启后约 6.56 秒恢复并收到新日志。休眠/唤醒、后台设备自动恢复及活动工作区隔离通过。 |
| 监控与镜像 | 活动设备监控和健康检查共用近期成功采样；窗口隐藏、非活动页面和暂停视图降低刷新频率。Windows 官方 scrcpy 与绑定的兼容连接一起关闭，Android 保留 MediaCodec 和原控制组件。 | Windows 竖屏和横屏渲染、打开/关闭/重开、输入测试通过。目标编辑框实际回读中文 `7主控验收`。Android 系统文件选择器返回后仍可显示镜像。 |
| 构建与升级 | Gradle 追踪 Rust 源、清单、锁文件、rustc 版本和 NDK 属性。普通开发可指定一个 ABI，验收构建保留四 ABI。Android release 默认启用 R8 与资源压缩，保留 JNI、反射和 TLS 入口。Windows 保持原 MSI 升级标识。 | 五个正式 APK 均为 `dev.rprobedesk`、1.2.2、版本码 25，签名与 1.2.1 一致。通用包内 16 个 ELF 及四个 ABI 包各 4 个 ELF 全部通过 PT_LOAD 及 APK 对齐检查。 |

## 回归环境与结果

- 2026-11-27 在 Windows x64 上执行 Rustfmt、Clippy 全目标（警告视为错误）及锁定依赖的 Cargo 测试。11 项 Rust 测试通过。桌面套件 85 个用例，组合运行覆盖 84 个，另用新鲜配对码单独运行 Windows 无线用例通过。
- Android API 35 主控对 API 30 目标运行 7 项工作区测试。HTTPS 安装另测 1 项，包括错误主机名拒绝、显式确认安装与清理。Android 无线配对及恢复另测 1 项。最终正式 R8 包在 API 34 x86_64 模拟器依次从原正式版覆盖安装单 ABI 和通用 APK，首次安装时间相同。实测启动、保存设备、连接、系统监控、JNI 日志与暂停。
- Windows 连接 Android 11 的 RK3566 连续工作 183 秒，4 次核对型号、uptime、监控及日志；期间使用两个先后连接的会话。恢复后采集继续。故障注入使用项目专用模拟器。实机短测与模拟器结果分别记录。
- Windows MSI 行政解包后，从解包目录实测包内 ADB、scrcpy、客户端和服务端。工具界面允许指定绝对路径；无效覆盖回退到内置工具。Windows 原目录升级返回 MSI 状态 0。安装记录和主程序版本均为 1.2.2，已打开主窗口。升级前后的 desk.properties SHA-256 一致，已安装主程序、启动配置、桌面 JAR、Rust DLL、ADB、scrcpy 客户端和服务端共七项与最终 MSI 解包副本逐一相同。

## 性能与体积

下表使用包内组件。Windows 冷启动从创建进程计至可读取导航控件；Android 在同一模拟器强制停止进程后用 `am start -W` 测量，随后记录 PSS。两个平台各运行三次，列中位数。系统文件缓存、模拟器负荷和局域网速率会影响数值。

| 指标 | 1.2.1 | 1.2.2 | 观察 |
| --- | ---: | ---: | --- |
| Windows MSI | 91,729,920 B | 91,496,448 B | 减少 233,472 B |
| Windows 干净安装文件总量 | 184,916,960 B | 182,575,808 B | 减少 2,341,152 B |
| 正式通用 APK | 41,033,766 B | 22,331,080 B | 减少 45.6% |
| 正式单 ABI APK | 仅提供通用包 | arm64 9,640,752 B；armv7 8,166,200 B；x86 9,866,008 B；x86_64 10,009,380 B | 四种包均可独立安装 |
| Rust DLL | 8,603,787 B | 6,181,376 B | 发行 DLL 移除符号 |
| 包内 Rust SO，arm64 / armv7 / x86 / x86_64 | 1,810,960 / 1,296,388 / 1,887,996 / 1,813,128 B | 2,353,248 / 1,697,100 / 2,403,976 / 2,316,080 B | 加入共享调度与采集后均增大 |
| Windows 启动 | 1,953 ms | 2,135 ms | 增加 182 ms |
| Android 启动 | 1,865 ms | 707 ms | 减少 1,158 ms |
| Windows 空闲工作集 | 227,606,528 B | 228,528,128 B | 增加约 0.88 MiB |
| Android 空闲 PSS | 45,197 KiB | 34,602 KiB | 减少 10,595 KiB |
| 两设备适配器 JVM 工作集 | 69,332,992 B | 78,884,864 B | 各一次采样；另起的 ADB 进程未计入 |
| 真实 Logcat 进程 CPU / 墙钟 | 312 / 7,983 ms | 265 / 7,930 ms | 同一 API 35 模拟器，分组写入 24,000 行。三轮都观察到结束标记 |
| 固定 24,000 行，JNI 接口重放 | 19.965 ms | 7.730 ms | 三次中位数，全部行与标签一致 |
| 同一重放的 JNI 次数 | 750 | 86 | 旧接口每 32 行一次；新接口按 32 KiB 输入和读增量，含打开/关闭 |
| RK3566 上传 4 MiB | 809 ms，4.94 MiB/s | 914 ms，4.38 MiB/s | 同一实机每版三次，本轮上传较慢 |
| RK3566 下载 4 MiB | 644 ms，6.21 MiB/s | 658 ms，6.08 MiB/s | 两组各三次，每次往返字节一致 |
| 手动重连至首条 Shell 返回 | 174 ms | 178 ms | 每次关闭原连接后重开 |
| 后台设备自动恢复 | 4.171 s | 3.506 s | 同一双模拟器场景，基线来自上一版验收记录 |
| 镜像首帧 / 重开 | 2.261 / 0.744 s | 1.266 / 0.660 s | 以识别目标画面为准，基线来自上一版验收记录 |

固定日志重放覆盖版本各自的公开 JNI 数据路径。旧接口的输入已经是 Java 字符串，新接口从 UTF-8 字节开始，并维护原生环。若较早的堆栈首行已经淘汰，仍可导出当前保留的原文；完整历史须在淘汰前导出。真实采集另用 `WorkspaceLogBench`，包含目标系统日志的实际可见记录为每轮约 2.2 万至 2.3 万行，三轮分别计算中位数。一次重复运行未在夹具启动窗口内读到准备标记，重新建连后通过；该次不计入计时。定时器、CPU、网络及窗口站负载会引入波动，不能据此推断所有场景的趋势。

## 包体积审计与兼容取舍

Windows 使用相同代码比较 `opt-level=3 / thin LTO` 和 `opt-level=s / fat LTO / codegen-units=1`。大小优先配置生成 4,575,232 B DLL，固定日志重放中位数 12.282 ms；默认速度配置未剥除符号时为 8,852,827 B、8.536 ms。最终保留速度配置并剥除 Windows 发行符号，得到上表的 6,181,376 B。Android 另行构建了 x86_64 的大小优先方案，得到 1,419,832 B SO。该变体未在 Android 真机建立速度基准。正式 Android 包沿用速度配置，AGP 处理发行 SO 的符号。

| MSI 解包组件 | 1.2.1 字节 | 1.2.2 字节 |
| --- | ---: | ---: |
| JPackage JRE | 92,951,506 | 92,951,506 |
| Compose / Skiko 运行组件 | 26,777,774 | 26,777,774 |
| Kotlin 及其余 JVM 依赖 JAR | 11,153,207 | 11,153,207 |
| 应用 JAR | 1,912,812 | 2,006,763 |
| Rust DLL | 8,603,787 | 6,181,376 |
| ADB 及 Windows USB DLL | 6,823,200 | 6,823,200 |
| scrcpy 客户端和服务端 | 805,806 | 805,806 |
| FFmpeg / SDL / libusb | 7,306,752 / 1,786,368 / 215,741 | 7,306,752 / 1,786,368 / 215,741 |
| 第三方对应源码 / 工具许可证 | 13,836,706 / 1,727,568 | 13,836,706 / 1,714,874 |
| 独立图像和图标 | 6,530 | 6,530 |
| 其他，包括 10,468,208 B ICU 数据、启动器与配置 | 11,009,203 | 11,009,205 |

两版应用及依赖 JAR 内的字体、图像条目合计压缩大小均为 187,969 B，已计入所属 JAR。表格来自干净解包文件，本机原目录升级后共 201,304,271 B，包含历次安装留下的文件；实际启动配置仅引用当前一套桌面 JAR。JRE 中的桌面、无障碍、TLS、国际化与监控模块服务现有功能。继续随 MSI 携带匹配版 FFmpeg、libusb、dav1d、scrcpy 对应源码及许可证。Android 各 ABI 使用匹配架构的本地库。应用、平台密钥与 WebDAV 格式保持兼容。[第三方声明](../THIRD-PARTY-NOTICES.md)

Windows 经典 TCP、RSA AUTH、配对及镜像所需转发继续使用官方 Platform-Tools。Android 保留系统密钥库、SAF、NSD 及硬件解码路径。应用使用两端均已验证的 Sync v1。文件传输数据仍需跨平台输入输出流，用户态缓冲和原生环均有显式所有者。Android 真机的 ARM 执行、不同 VPN/多网卡组合和所有厂商 ROM 未覆盖；ARM 产物完成静态签名、内容及对齐检查。稳定性结论限于上述短测。

## 构建复验

Windows x64，Temurin 17.0.21.1、Gradle wrapper 8.10.2、rustc / cargo 1.100.0、NDK r27c（27.2.12479018）、Android 编译与目标 API 35、最低 API 30。正式 APK 签名证书 SHA-256 为 `d7f639d7bdab0eb394c216f04aa4c61353b166380a64e10d83de590d012ce761`。Gradle 通过锁文件构建原生组件并校验下载的 Windows 工具。正式签名配置见项目 README。

```powershell
Push-Location native
cargo fmt --all -- --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --locked
Pop-Location
.\gradlew.bat :app:desktopTest :app:packageMsi
.\gradlew.bat :app:assembleRelease -PrprobeAbiApks=true
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PrprobeAbi=x86_64
python qa/native-core/check_apks.py dist/1.2.2 1.2.2
```

设备夹具用例需要明确指定测试模拟器、标记和新鲜配对码。Windows 与 Android 的无线认证使用不同的一次性配对码。在外部以 `apksigner verify --print-certs`、`zipalign -c -P 16 4` 核验每个 APK。正式签名 APK 可通过同证书的 `adb install -r` 复验原位升级。

复验入口见 [会话与 JNI](../app/src/desktopTest/kotlin/dev/rprobedesk/RuntimeTest.kt)、[故障恢复](../app/src/desktopTest/kotlin/dev/rprobedesk/WindowsFaultTest.kt)、[无线测试](../qa/native-core/android_pair.py)、[镜像输入](../qa/windows-mirror/check_isolation.py)、[固定日志重放](../qa/native-core/LogCaptureBench.java)、[真实 Logcat 负载](../qa/native-core/WorkspaceLogBench.java)与[基线记录](architecture-1.2.1.md)。
