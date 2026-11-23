package dev.rprobedesk

import java.util.Locale

internal fun panelGroup(group: String, english: Boolean): String = if (english) tr(group) else when (group) {
    "Settings · global" -> "全局设置"
    "Settings · system" -> "系统设置"
    "Settings · secure" -> "安全设置"
    else -> group
}

private val panelLabels = mapOf(
    "Kernel" to "内核版本", "SELinux" to "SELinux 安全模式", "SDK" to "Android API 级别",
    "ABI" to "支持的处理器架构", "SoC" to "系统芯片", "MAC" to "网卡 MAC 地址",
    "AC powered" to "交流电源供电", "USB powered" to "USB 供电", "Wireless powered" to "无线供电",
    "Dock powered" to "底座供电", "Max charging current" to "最大充电电流", "Max charging voltage" to "最大充电电压",
    "Charge counter" to "剩余电荷", "status" to "充电状态", "health" to "电池健康状态", "present" to "检测到电池",
    "level" to "当前电量刻度", "scale" to "电量总刻度", "voltage" to "电池电压", "temperature" to "电池温度",
    "technology" to "电池类型", "Charging state" to "充电阶段", "Charging policy" to "充电策略", "Capacity level" to "容量等级",
    "SSID" to "无线网络名称", "BSSID" to "接入点地址", "IP" to "IP 地址", "Security type" to "无线安全类型",
    "Supplicant state" to "无线认证状态", "Wi-Fi standard" to "无线标准", "RSSI" to "接收信号强度",
    "Link speed" to "链路速率", "Tx Link speed" to "发送速率", "Rx Link speed" to "接收速率",
    "Max Supported Tx Link speed" to "最大发送速率", "Max Supported Rx Link speed" to "最大接收速率",
    "Frequency" to "工作频率", "Net ID" to "网络编号", "Metered hint" to "按流量计费提示", "score" to "网络评分",
    "isUsable" to "网络可用", "CarrierMerged" to "运营商合并网络", "SubscriptionId" to "订阅编号",
    "IsPrimary" to "主连接", "Trusted" to "受信任", "Restricted" to "受限制", "Ephemeral" to "临时网络",
    "OEM paid" to "厂商付费网络", "OEM private" to "厂商专用网络", "OSU AP" to "在线注册接入点",
    "FQDN" to "完整域名", "Provider friendly name" to "提供商名称", "Requesting package name" to "请求方包名",
    "AP MLD Address" to "接入点多链路地址", "AP MLO Link Id" to "多链路编号", "AP MLO Affiliated links" to "关联链路",
    "MemTotal" to "总内存", "MemFree" to "空闲内存", "MemAvailable" to "可用内存", "Buffers" to "缓冲区",
    "Cached" to "文件缓存", "SwapCached" to "交换缓存", "Active" to "活跃内存", "Inactive" to "非活跃内存",
    "Active(anon)" to "活跃匿名内存", "Inactive(anon)" to "非活跃匿名内存",
    "Active(file)" to "活跃文件页", "Inactive(file)" to "非活跃文件页", "Unevictable" to "不可回收内存",
    "Mlocked" to "已锁定内存", "SwapTotal" to "交换空间总量", "SwapFree" to "空闲交换空间",
    "Dirty" to "待写回内存", "Writeback" to "正在写回的内存", "AnonPages" to "匿名页", "Mapped" to "文件映射",
    "Shmem" to "共享内存", "Slab" to "内核对象缓存", "KReclaimable" to "内核可回收内存",
    "SReclaimable" to "可回收内核对象缓存", "SUnreclaim" to "不可回收内核对象缓存",
    "KernelStack" to "内核栈", "PageTables" to "页表", "SecPageTables" to "辅助页表",
    "CommitLimit" to "内存提交上限", "Committed_AS" to "已承诺分配内存", "VmallocTotal" to "内核虚拟地址空间",
    "VmallocUsed" to "已用内核虚拟地址空间", "VmallocChunk" to "最大连续虚拟块", "CmaTotal" to "连续内存池总量",
    "CmaFree" to "连续内存池余量", "Percpu" to "各处理器专用内存", "HardwareCorrupted" to "硬件损坏内存",
    "adb_enabled" to "USB 调试", "adb_wifi_enabled" to "无线调试", "development_settings_enabled" to "开发者选项",
    "wifi_on" to "无线局域网", "bluetooth_on" to "蓝牙", "auto_time" to "自动设置时间", "auto_time_zone" to "自动设置时区",
    "airplane_mode_on" to "飞行模式", "airplane_mode_radios" to "飞行模式下关闭的无线功能",
    "airplane_mode_toggleable_radios" to "飞行模式下可单独开启的无线功能", "mobile_data" to "移动数据",
    "data_roaming" to "数据漫游", "device_name" to "设备名称", "device_provisioned" to "设备首次配置完成",
    "user_setup_complete" to "用户初始设置完成", "boot_count" to "启动次数", "screen_brightness" to "屏幕亮度",
    "screen_brightness_mode" to "亮度调节模式", "screen_off_timeout" to "自动熄屏等待时间",
    "accelerometer_rotation" to "自动旋转屏幕", "user_rotation" to "用户设定旋转方向", "font_scale" to "字体缩放",
    "location_mode" to "定位模式", "default_input_method" to "默认输入法", "enabled_input_methods" to "已启用输入法",
    "private_dns_mode" to "私人 DNS 模式", "private_dns_specifier" to "私人 DNS 主机名",
    "window_animation_scale" to "窗口动画缩放", "transition_animation_scale" to "过渡动画缩放",
    "animator_duration_scale" to "动画时长缩放", "ro.debuggable" to "系统调试标志",
    "ro.adb.secure" to "ADB 身份验证", "ro.boot.verifiedbootstate" to "启动验证状态",
    "ro.boot.flash.locked" to "引导加载器已锁定", "ro.boot.vbmeta.device_state" to "引导加载器状态",
    "ro.crypto.state" to "加密状态", "ro.crypto.type" to "加密类型", "ro.build.tags" to "构建签名标签",
    "ro.product.cpu.abilist" to "支持的处理器架构", "persist.sys.timezone" to "系统时区",
    "ro.product.locale" to "默认语言与地区", "persist.sys.locale" to "当前语言与地区",
    "sys.boot.reason" to "本次启动原因", "sys.boot.reason.last" to "上次启动原因", "ro.boot.bootreason" to "引导程序上报的启动原因",
    "sys.usb.config" to "USB 功能配置", "sys.usb.state" to "当前 USB 功能", "persist.sys.usb.config" to "默认 USB 功能",
    "gsm.network.type" to "移动网络制式", "gsm.sim.state" to "SIM 卡状态", "ro.control_privapp_permissions" to "特权应用权限检查",
    "gsm.operator.iso-country" to "运营商所在国家或地区", "gsm.sim.operator.iso-country" to "SIM 卡归属国家或地区",
    "ro.boot.veritymode" to "分区完整性校验模式", "ro.bootmode" to "启动模式", "ro.bootloader" to "引导加载器版本",
    "ro.soc.model" to "系统芯片型号", "ro.soc.manufacturer" to "系统芯片制造商", "ro.board.platform" to "主板平台",
    "ro.hardware" to "硬件代号", "ro.secure" to "系统安全标志", "sys.boot_completed" to "系统启动完成",
    "ro.build.characteristics" to "设备形态", "ro.build.version.preview_sdk_fingerprint" to "预览版 SDK 指纹",
    "ro.build.version.all_codenames" to "版本代号列表", "ro.boot.debug.hwui.renderer" to "界面绘制引擎",
    "location_providers_allowed" to "已允许的定位来源", "add_users_when_locked" to "锁屏时允许添加用户",
    "sound_effects_enabled" to "触摸音效", "haptic_feedback_enabled" to "触觉反馈", "vibrate_on" to "振动",
    "lockscreen_sounds_enabled" to "锁屏音效", "stay_on_while_plugged_in" to "充电时保持亮屏的电源类型",
    "enabled_accessibility_services" to "已启用的无障碍服务", "accessibility_enabled" to "无障碍功能",
    "speak_password" to "朗读密码", "battery_saver_constants" to "省电模式参数",
    "low_power" to "省电模式", "zen_mode" to "勿扰模式", "night_display_activated" to "夜间护眼模式",
    "time_12_24" to "时间显示制式", "screen_brightness_for_vr" to "虚拟现实显示亮度",
    "default_install_location" to "默认应用安装位置", "install_non_market_apps" to "允许非应用商店安装",
    "Unaccepted" to "尚未接纳的内存", "WritebackTmp" to "临时写回缓冲", "Bounce" to "设备传输缓冲",
    "NFS_Unstable" to "等待网络文件系统提交的页", "AnonHugePages" to "匿名大页", "DirectMap4k" to "4 KiB 页直接映射",
    "DirectMap2M" to "2 MiB 页直接映射", "DirectMap1G" to "1 GiB 页直接映射"
)

private val buildFields = mapOf("type" to "构建类型", "tags" to "构建签名标签", "fingerprint" to "构建指纹",
    "id" to "构建标识", "date" to "构建时间", "date.utc" to "构建时间戳", "version.sdk" to "API 级别",
    "version.release" to "Android 版本", "version.incremental" to "增量版本号", "version.security_patch" to "安全补丁",
    "version.codename" to "版本代号", "display.id" to "构建编号")
private val productFields = mapOf("brand" to "品牌", "device" to "设备代号", "manufacturer" to "制造商",
    "model" to "型号", "name" to "产品名称", "board" to "主板")
private val partitions = mapOf("vendor" to "厂商分区", "odm" to "硬件定制分区", "product" to "产品分区",
    "system" to "系统分区", "system_ext" to "系统扩展分区", "bootimage" to "启动镜像")
private val features = mapOf("hardware" to "硬件", "software" to "软件", "audio" to "音频", "output" to "输出",
    "camera" to "相机", "front" to "前置", "external" to "外接", "flash" to "闪光灯", "autofocus" to "自动对焦",
    "sensor" to "传感器", "accelerometer" to "加速度计", "gyroscope" to "陀螺仪", "compass" to "指南针",
    "location" to "定位", "network" to "网络", "telephony" to "移动通信", "bluetooth" to "蓝牙", "wifi" to "无线局域网",
    "touchscreen" to "触摸屏", "multitouch" to "多点触控", "microphone" to "麦克风", "ethernet" to "以太网",
    "usb" to "USB", "host" to "主机", "accessory" to "配件", "screen" to "屏幕", "portrait" to "纵向", "landscape" to "横向")

internal fun panelLabel(group: String, key: String, english: Boolean): String {
    if (english) return tr(key)
    panelLabels[key]?.let { return it }
    if (group == "Android 系统属性") {
        if (key.startsWith("init.svc.")) return "服务状态 · " + key.removePrefix("init.svc.")
        if (key.startsWith("init.svc_debug_pid.")) return "服务进程号 · " + key.removePrefix("init.svc_debug_pid.")
        if (key.startsWith("pm.dexopt.")) return "应用编译策略 · " + when(val reason=key.removePrefix("pm.dexopt.")) {
            "install" -> "安装时"; "bg-dexopt" -> "后台优化"; "first-boot" -> "首次开机"; "boot-after-ota" -> "系统更新后"
            "install-fast" -> "快速安装"; "inactive" -> "长期未使用"; "cmdline" -> "命令行请求"; else -> reason
        }
        val path = key.removePrefix("ro.")
        val build = path.substringAfter("build.", "")
        buildFields[build]?.let { return listOfNotNull(partitions[path.substringBefore('.')], it).joinToString(" · ") }
        if (path.startsWith("product.")) productFields[path.substringAfterLast('.')]?.let {
            return listOfNotNull(partitions[path.removePrefix("product.").substringBefore('.')], it).joinToString(" · ")
        }
    }
    if (group == "系统功能" && key.startsWith("android.")) return key.removePrefix("android.").split('.')
        .joinToString(" · ") { features[it] ?: it }
    return key
}

private val stateValues = mapOf("true" to "是", "false" to "否", "enabled" to "已启用", "disabled" to "已禁用",
    "on" to "已开启", "off" to "已关闭", "auto" to "自动", "default" to "默认", "unknown" to "未知",
    "none" to "无", "<none>" to "无", "null" to "未设置", "running" to "运行中", "stopped" to "已停止",
    "stopping" to "正在停止", "restarting" to "正在重启")
private val switches = setOf("系统调试标志","ro.debuggable","ro.adb.secure","ro.boot.flash.locked","IsPrimary",
    "wifi_on","bluetooth_on","auto_time","auto_time_zone","mobile_data","data_roaming","device_provisioned",
    "user_setup_complete","accelerometer_rotation","airplane_mode_on","ro.secure","sys.boot_completed",
    "add_users_when_locked","low_power","night_display_activated","install_non_market_apps")

internal fun panelValue(group: String, key: String, value: String, english: Boolean): String {
    if (value.isEmpty()) return "—"
    if (english) return uiMessage(value)
    val lower = value.lowercase(Locale.ROOT)
    fun enumValue(vararg names: String): String = names.getOrNull(value.toIntOrNull() ?: -1) ?: value
    if (group == "电池原始读数") return when (key) {
        "status" -> enumValue("未知","未知","充电中","放电中","未充电","已充满")
        "health" -> enumValue("未知","未知","良好","过热","失效","过压","故障","温度过低")
        "Capacity level" -> if(value=="-1") "不支持" else enumValue("未知","电量极低","低","正常","高","已满")
        "technology" -> when (lower) { "li-ion", "lion" -> "锂离子电池"; "li-poly", "lipo" -> "锂聚合物电池"; else -> value }
        "temperature" -> value.toIntOrNull()?.let { "${it / 10.0} °C" } ?: value
        "voltage" -> "$value mV"
        "Max charging voltage" -> "$value µV"
        "Max charging current" -> "$value µA"
        "Charge counter" -> "$value µAh"
        else -> stateValues[lower] ?: value
    }
    if (key in switches || group.startsWith("Settings · ") && key.endsWith("_enabled")) {
        if (value == "1") return "已开启"
        if (value == "0") return "已关闭"
    }
    if ((group=="Android 系统属性" && key.endsWith(".build.type")) || key=="构建类型") return when(lower) {
        "user" -> "正式用户版"; "userdebug" -> "可调试用户版"; "eng" -> "工程调试版"; else -> value
    }
    if (key.endsWith(".build.tags")) return value.split(',').joinToString("、") {
        when(it) { "release-keys" -> "发行密钥"; "test-keys" -> "测试密钥"; "dev-keys" -> "开发密钥"; else -> it }
    }
    if (key.startsWith("pm.dexopt.")) return when(lower) {
        "verify" -> "验证字节码"; "speed-profile" -> "按运行使用情况优化"; "speed" -> "优先优化速度"; "skip" -> "跳过编译"; else -> value
    }
    when(key) {
        "ABI","ro.product.cpu.abilist","ro.product.cpu.abilist32","ro.product.cpu.abilist64" ->
            return value.split(',').joinToString("、") { abi -> when(abi.trim()) {
                "arm64-v8a" -> "64 位 ARM（arm64-v8a）"; "armeabi-v7a" -> "32 位 ARM（armeabi-v7a）"
                "x86_64" -> "64 位 x86"; "x86" -> "32 位 x86"; "riscv64" -> "64 位 RISC-V"; else -> abi.trim()
            } }
        "启动验证状态","ro.boot.verifiedbootstate" -> return when(lower) {
            "green" -> "已验证（厂商信任根）"; "yellow" -> "已验证（自定义信任根）"
            "orange" -> "引导加载器已解锁"; "red" -> "验证失败"; else -> value
        }
        "加密状态","ro.crypto.state" -> return when(lower) { "encrypted" -> "已加密"; "unencrypted" -> "未加密"; else -> value }
        "ro.crypto.type" -> return when(lower) { "file" -> "文件级加密"; "block" -> "全盘加密"; else -> value }
        "SELinux","ro.boot.veritymode" -> return when(lower) {
            "enforcing", "enforce" -> "强制执行"; "permissive" -> "宽容模式（仅记录）"; "disabled" -> "已禁用"; else -> value
        }
        "ro.boot.vbmeta.device_state" -> return when(lower) { "locked" -> "已锁定"; "unlocked" -> "已解锁"; else -> value }
        "ro.build.version.all_codenames","ro.build.version.codename","ro.build.version.preview_sdk_fingerprint" ->
            if(value=="REL") return "正式发布版"
        "sys.boot.reason","sys.boot.reason.last","ro.boot.bootreason" -> return when(lower) {
            "reboot" -> "重新启动"; "cold" -> "冷启动"; "hard" -> "硬件复位"; "watchdog" -> "看门狗复位"; else -> value
        }
        "ro.product.locale","persist.sys.locale" -> if(value.isNotBlank()) {
            val name=Locale.forLanguageTag(value.replace('_','-')).getDisplayName(Locale.SIMPLIFIED_CHINESE)
            if(name.isNotBlank() && !name.equals(value,true)) return "$name（$value）"
        }
        "gsm.operator.iso-country","gsm.sim.operator.iso-country" -> if(value.matches(Regex("[a-zA-Z]{2}"))) {
            return Locale("",value.uppercase(Locale.ROOT)).getDisplayCountry(Locale.SIMPLIFIED_CHINESE)
        }
        "sys.usb.config","sys.usb.state","persist.sys.usb.config" -> return value.split(',').joinToString("、") {
            when(it) { "adb" -> "USB 调试"; "mtp" -> "文件传输"; "ptp" -> "照片传输"; "rndis","ncm" -> "网络共享（$it）"; "none" -> "无"; else -> it }
        }
        "gsm.sim.state" -> return value.split(',').joinToString("、") {
            when(it) { "LOADED" -> "已加载"; "READY" -> "已就绪"; "ABSENT" -> "未插卡"; "NOT_READY" -> "未就绪"
                "PIN_REQUIRED" -> "需要 PIN 码"; "PUK_REQUIRED" -> "需要 PUK 码"; "UNKNOWN" -> "未知"; else -> it }
        }
        "gsm.network.type" -> if(value=="NR_SA") return "5G 独立组网"
        "ro.control_privapp_permissions" -> return when(lower) { "enforce" -> "强制检查"; "log" -> "仅记录"; "disable" -> "已禁用"; else -> value }
        "Wi-Fi standard" -> return when(value) { "0" -> "未知"; "1" -> "传统无线标准"; "4" -> "Wi-Fi 4（802.11n）"
            "5" -> "Wi-Fi 5（802.11ac）"; "6" -> "Wi-Fi 6（802.11ax）"; "7" -> "WiGig（802.11ad）"; "8" -> "Wi-Fi 7（802.11be）"; else -> value }
        "Security type" -> return enumValue("开放网络","WEP 加密","个人网络（预共享密钥）","企业网络（EAP）",
            "WPA3 个人网络","WPA3 企业网络（192 位）","增强型开放网络","WAPI 预共享密钥","WAPI 证书",
            "WPA3 企业网络","在线注册网络","Passpoint 第一／二版","Passpoint 第三版","设备配网协议")
        "Supplicant state" -> return when(lower) {
            "completed" -> "认证完成"; "disconnected" -> "已断开"; "scanning" -> "扫描中"; "authenticating" -> "认证中"
            "associating" -> "正在关联"; "associated" -> "已关联"; "four_way_handshake" -> "四次握手中"
            "group_handshake" -> "组密钥握手中"; "inactive" -> "未活动"; "interface_disabled" -> "接口已禁用"; else -> value
        }
        "screen_brightness_mode" -> return enumValue("手动","自动")
        "default_install_location" -> return enumValue("自动","内部存储","外部存储")
        "zen_mode" -> return enumValue("关闭","仅优先通知","完全静音","仅闹钟")
        "screen_off_timeout" -> return value.toLongOrNull()?.let { "$it 毫秒" } ?: value
        "private_dns_mode" -> return when(lower) { "opportunistic" -> "自动"; "hostname" -> "指定主机"; "off" -> "已关闭"; else -> value }
        "airplane_mode_radios","airplane_mode_toggleable_radios" -> return value.split(',').joinToString("、") {
            when(it) { "cell" -> "蜂窝网络"; "wifi" -> "无线局域网"; "bluetooth" -> "蓝牙"; "nfc" -> "近场通信"; else -> it }
        }
    }
    // Network names, model names, build IDs and user-entered labels are literal data.
    if (key in setOf("SSID","BSSID","device_name","型号","产品代号","制造商","构建编号","构建指纹") || key.endsWith("_name") ||
        key.endsWith(".model") || key.endsWith(".brand") || key.endsWith(".manufacturer")) return value
    return stateValues[lower] ?: value
}
