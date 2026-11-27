# RProbeDesk 1.2.1 架构与验收记录

1.2.1 基于 1.1.3 发布后的主分支提交 `42fdc3e`。Windows 是本轮安装目标，Android APK 在测试模拟器使用。设备所有者指定在当前 Windows x64 系统就地安装，并于 2026-11-27 将 24 小时长稳调整为短时实机稳定性验收。

## 代码边界

| 功能 | 入口和执行位置 | 生命周期与兼容性 |
| --- | --- | --- |
| 发现 | `CoreDiscovery.kt` → `native/core/src/discovery.rs` | 有时间上限的 mDNS 查询，按服务类型区分配对和连接。Windows 可回退到 Platform-Tools，Android 可回退到 NSD。 |
| 探活与重连 | `DeviceSessionRepository`、`DeskController` → `DeviceSessionLease` → `session.rs` | 各设备持有独立句柄，连续三次失败后离线。退避带随机抖动，最大 32 秒。重新发现的端点通过身份核验后才能交付给工作区。 |
| 认证和传输 | Windows 通过官方 ADB 配对和访问经典 TCP 设备。`wireless.rs` 使用 libadb 与 rustls 建立独立 STLS 业务连接。`adb_server.rs` 订阅官方本地服务端的设备列表。Android 使用 `AndroidAdb` 与 libadb-android。 | 两端在实际 TLS 业务连接上核对目标 SPKI。Windows 读取当前 Windows 用户已配对的 ADB 密钥，Android 由 AndroidKeyStore 签名。Windows 初次信任需核对目标通知。经典 TCP 使用绑定 transport ID 的兼容后端及设备属性辅助核对。 |
| Shell、Logcat 与截图 | 两端平台流 → `CoreShell.kt` → `shell.rs` | Rust 处理 Shell V2 帧，含 stdout、stderr 和退出码。截图直接写入二进制流。Rust 批量解析 threadtime、等级、标签与 PID，工作区保留最多 1,500 行，负责过滤、暂停、导出和显示。 |
| 文件与 APK | `DeviceSession`、`AdbFileSync.kt` → `sync.rs` | Sync v1 流式发送和接收。进度以实际传输字节计。保留上传暂存、取消、完整写入后发布及结果待核实流程。 |
| 设备面板 | `metricCommand`、`monitor.rs`、`DeskMetrics.kt` | Shell 采集，Rust 解析 CPU 增量、内存、GPU、电池和磁盘。Compose 格式化并显示。 |
| 镜像 | Windows 使用官方 scrcpy 3.3.4 和同版 server。Android 沿用原生解码和控制组件。 | 保留独立镜像窗口、视频解码、旋转和系统剪贴板策略。评估 Rust Host 时应先复用解码库并通过现有画面和输入夹具，再替换官方实现。 |
| 系统集成 | Compose、Android Activity、前台服务、SAF、Windows 窗口与托盘 | 原有应用标识、升级标识、Android 签名配置、资料和 WebDAV 格式继续使用。 |

## JNI 与资源管理

两端保留 JVM 以运行 Compose。手写 JNI 可以直接复用 SAF、Java 输入输出流及 AndroidKeyStore 的非导出密钥。采用 UniFFI 的值类型绑定时，大文件流仍需要流回调或额外缓冲。本次使用类型化的 Kotlin 包装将可关闭的句柄、状态流和监控 DTO 提供给工作区。JNI 调用结束时释放局部引用。文件有效载荷使用可复用的 JVM 数组，Java I/O 异常按原类型返回。

Windows 通过 jpackage 的资源根相对定位 `native/rprobe_core.dll`、`tools/scrcpy/`。Temurin 17 同包安装。Android 延续旧版覆盖的 arm64-v8a、armeabi-v7a、x86、x86_64 四种 ABI。构建脚本与二进制来源见 [原生构建](../app/native.gradle.kts)、[工具和源码哈希](../app/packaging/windows/stage-tools.ps1) 和 [第三方声明](../THIRD-PARTY-NOTICES.md)。

## 复验记录

| 检查 | 本机观察 |
| --- | --- |
| 迁移前基线 | v1.1.3 运行 74 个桌面测试，70 个通过，4 个需要设备条件的测试跳过。MSI 67,170,304 字节，未签名 release APK 34,065,087 字节。 |
| Rust | `cargo test --locked --manifest-path native/Cargo.toml` 通过 8 项，覆盖 Shell 与 Sync 帧、监控计数器、日志 UTF-16 偏移、探活阈值、身份策略、同一传输短暂离线与授权撤销。Rustfmt 和 Clippy（警告视为错误）均通过。 |
| Windows → 实机 | rk3566_r，Android 11。验证中文 stdout、独立 stderr、退出码 7、设备信息、Logcat、1920 × 1080 PNG 及 4 MiB 文件往返字节一致。连接跟踪修订后的一轮上传 977 ms，下载 778 ms。系单轮测量。 |
| 无线配对及公钥变化 | Windows 与 API 37 模拟器完成系统配对，通过独立模拟器控制台核对通知后，两次通过原生 TLS 连接执行设备 Shell。测试替换已保存公钥，第三次连接被拒绝，确认次数仍为一次。 |
| 多设备 | Windows 同时连接 A、B。主动断开后台设备 A 的 TCP 别名后，A 在首轮 3.547 秒、最终回归 4.171 秒后恢复，继续采集日志。B 仍为当前设备，原有 Shell 可用。 |
| Windows 镜像 | 产品接口启动官方 scrcpy。可辨首帧 2.261 秒，重开 0.744 秒。目标实测 720 × 1280 和 1280 × 720。经独立窗口站发送中文，目标编辑框回读 `7主控验收`。两次关闭后原 ADB 会话均可使用。 |
| Windows 工具选择 | 在隔离应用配置及仅含 System32 的 PATH 下启动打包程序。确认内置路径，检验失效覆盖回退，并实际使用 6 次原生文件选择器验证覆盖、旧版拒绝及使用前复核。 |
| Android 主控 | API 37 模拟器上 6 个 instrumentation 测试通过，目标为 API 30 专用模拟器。覆盖启动、发现、Shell 和面板、文件和截图、测试 APK 安装/启停/冻结/卸载、实时日志。耗时 19.937 秒。API 30 主控另通过 6 项（24.608 秒）；API 33、34 各通过 5 项。API 35 在确认 Wi-Fi 已连接后通过 6 项，耗时 20.385 秒。 |
| 实机连续稳定性 | 所有者调整验收时长后，2026-11-27 12:01 至 12:11（北京时间）在恢复网络的 rk3566_r 上连续运行 609 秒。11 次核验型号、uptime、面板采样和 Logcat 均成功，只有 1 个连接会话。 |
| 先前实机中断 | 前两轮在约 1 分钟和 40 分钟结束。独立 ADB 订阅随后记录到约 280 毫秒离线，原 transport ID 恢复。已为这种抖动加入 2 秒宽限。第三轮记录到实机持续离网，重连 5555 端口超时。本次结果覆盖上述 609 秒。 |
| Android 无线认证 | Android 15 主控与 Android 高版本目标使用一次性配对码、AndroidKeyStore 和目标通知完成认证。随后确认同一公钥重连成功，错误公钥被拒绝。独立用例耗时 2.998 秒。 |
| Windows MSI | 91,729,920 字节。行政解包后核对桌面 JAR、Rust DLL、ADB、scrcpy 客户端及服务端 SHA-256，均与用于 rk3566 连续测试的副本相同。在独立配置、仅 System32 的 PATH 和无效 SDK 环境变量下，内置工具和六次系统文件选择器验证通过。 |
| Windows 镜像输入 | Android 11 目标纵向和横向各四角点击全部命中。目标回报拖动起止坐标，窗口缩放后黑边未触发按钮。验证普通键盘、粘贴中文和返回键。 |
| 桌面回归 | 79 个用例中，78 个在组合运行中通过。另一个需要新鲜配对码的 Windows 无线用例已单独运行并通过（6.410 秒）。覆盖工作区、WebDAV、设备和文件操作、布局、单实例和连接恢复。 |
| 地址变化与 ADB 进程退出 | 两条仅监听 Windows 回环的路径通向同一台 QA 模拟器。输入受控发现记录，使地址和端口同时改变，4.264 秒后核对原设备并恢复日志。目标休眠、唤醒后 Shell 可用。明确终止专用模拟器 adbd 并核对新 PID，23.414 秒后工作区重新收到指定的真实 Logcat 标记，原工作区继续可用。 |
| 无线重启、弱网传输 | 关闭目标 Wi-Fi 和无线调试，重开后系统报告的 TLS 端口由 37285 变为 38105。Android 主控核对原公钥成功。向测试模拟器 Wi-Fi 加入 40 ms 延迟及 2% 丢包后，1 MiB 上传和下载均逐字节一致。内核计数为已发送 1,221 个包、丢弃 20 个包。清理测试流控后用例通过。 |
| Android 系统文件选择器和镜像 | API 34 主控打开系统 DocumentsUI，验证前台传输服务及后台 Shell。返回后服务停止，原连接可用。打开 Android 端镜像，从解码 Surface 成功读出非单色画面，关闭后原 Shell 仍可用。全程 43.337 秒。 |
| 转发兼容后端 | 使用 MSI 内的官方 adb，以两个动态分配的回环端口进行 Forward → Reverse 的随机 32 字节往返校验。两条测试映射均已移除。 |
| Android 产物 | 正式发行 APK 为 41,033,766 字节，包名 dev.rprobedesk、版本码 24。发布前校验 208 个应用内容条目与验收过的未签名 release APK 完全一致。签名证书 SHA-256 与公开版 1.1.3 相同，均为 `d7f639d7bdab0eb394c216f04aa4c61353b166380a64e10d83de590d012ce761`。在独立 API 34 模拟器上从正式版 1.1.3（23）覆盖升级至 1.2.1（24），首次安装时间保留，主界面启动成功。 |
| 镜像帧率 | rk3566 上通过包内官方客户端建立独立 720 × 1280 虚拟显示，滚动系统设置时每秒统计一组，整段（含起止静止画面）为 2–62 FPS，中位数 29 FPS。测试退出后已核对仅剩原显示。Windows 产品镜像窗口对 Android 11 模拟器持续变化的 Surface 采样 8 秒，观测到每秒 13.2 次画面变化；同客户端计数 12–14 FPS。早期纯色 View 夹具及软件模拟器曾测到 1–3 FPS。最终安装包解出的产品镜像首帧 2.203 秒，重开 1.073 秒。 |
| Windows 原位升级 | 旧版隐藏在托盘时，首次安装遇到文件占用（1603）并回滚。通过旧版设置正常退出，恢复原关闭偏好后重试，MSI 返回 0。已核对 Windows 安装记录、主程序文件版本 1.2.1、原目录和关键二进制 SHA-256。原 desk.properties 逐字节一致；已启动安装后的主窗口，安装目录中的 ADB 实测读到 rk3566_r / SDK 30。 |
| Android 包检查 | 调试 APK 签名验证通过。调试及 release APK 中的 arm64-v8a、armeabi-v7a、x86 和 x86_64 四份 Rust 库均具备至少 16 KiB 的 ELF 加载段对齐。 |

## 前后对照

以下为本机观测值。Windows 启动计时从创建进程到能读取导航控件，每次新建独立配置。Android 启动使用相同 API 35 模拟器的调试包；这些样本不包含系统重启。进程工作集、Android PSS 和 JVM 堆分别统计。

| 指标 | v1.1.3 | v1.2.1 |
| --- | ---: | ---: |
| Windows MSI（字节） | 67,170,304 | 91,729,920 |
| Android 未签名 release APK（字节） | 34,065,087 | 41,007,643 |
| Windows 首次可操作（3 次，毫秒） | 5793 / 1568 / 1679 | 2178 / 1863 / 2051 |
| Windows 空闲工作集中位数（字节） | 227,291,136 | 228,466,688 |
| Android 启动 TotalTime（3 次，毫秒） | 5364 / 4187 / 1277 | 5562 / 7763 / 6552 |
| Android 空闲 PSS 中位数（KiB） | 74,418 | 75,578 |
| 两个已连接设备的适配器进程工作集（字节） | 68,059,136 | 68,730,880 |
| rk3566 上传 4 MiB（3 次，毫秒） | 767 / 634 / 530 | 654 / 636 / 674 |
| rk3566 下载 4 MiB（3 次，毫秒） | 677 / 636 / 605 | 675 / 621 / 793 |
| 手动重新打开连接至首条 Shell 返回（毫秒） | 400 | 166 |
| 真实 Logcat 涌入时进程 CPU / 墙钟（毫秒） | 671 / 1480 | 171 / 1256 |
| 真实 Logcat 采样期间进程 CPU（单个逻辑核百分比） | 45.4% | 13.7% |
| 同样 24,000 行逐行解析的 Shell 回调 CPU / 墙钟（毫秒） | 437 / 534 | 593 / 461 |

4 MiB 文件三次往返均逐字节校验。双设备适配器测试使用同一台 rk3566 和同一台本地模拟器。真实 Logcat 试验通过同一台专用模拟器输入 24,000 行，测试工作区观察到 20,334 和 22,160 行（含其他系统行），均收到了结束标记。逐行回调是另一组固定 24,000 行的工作负载。两个测试覆盖不同调用路径。文件吞吐、内存和 CPU 的变化方向取决于工作负载。这批样本未控制操作系统磁盘缓存和其他进程负载。最终 APK 在四台模拟器并行运行、Windows 可用物理内存一度约 2 GiB 时测得。此前同一候选分支在 API 35 上的三次启动是 1231 / 1273 / 1098 ms。

## 兼容后端及迁移范围

Windows 的 TLS 业务流已使用 Rust。经典 ADB 握手和 AUTH、无线配对及 scrcpy 所需的转发继续由包内 Platform-Tools 执行。Android 的握手、TLS 和配对继续由 libadb-android、Conscrypt 与 AndroidKeyStore 执行。两端使用相同的身份策略、会话状态和退避、mDNS、Shell V2、Sync、日志解析和监控模块。任意端口的通用 Forward / Reverse 尚无独立 Rust API，产品现有镜像通道沿用适配后端。Windows 的视频解码、窗口与输入由同版本官方 scrcpy 客户端执行，Android 使用原有 MediaCodec 和控制组件。完整移交这些后端前仍需覆盖旧 Android ROM、硬件密钥和两端完整镜像回归。

验证入口见[实机连续测试](../app/src/desktopTest/kotlin/dev/rprobedesk/SessionSoak.kt)、[无线配对](../qa/native-core/android_pair.py)、[双设备与异常恢复](../app/src/desktopTest/kotlin/dev/rprobedesk/WindowsFaultTest.kt)、[镜像画面与输入](../qa/windows-mirror/check_isolation.py)及[工作区日志负载](../qa/native-core/WorkspaceLogBench.java)。
