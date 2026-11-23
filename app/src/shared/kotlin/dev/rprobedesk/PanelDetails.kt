package dev.rprobedesk

// Keep the full Wi-Fi configuration dump on the target. Only return network IDs and MAC policy fields.
internal val panelCommand = """
    echo @wifi
    cmd wifi status 2>/dev/null | grep -m1 '^WifiInfo:'
    echo @profiles
    dumpsys wifi 2>/dev/null | awk '
      /Configured networks Begin/ {inside=1; next}
      /Configured networks End/ {inside=0}
      inside && /^[* ]*ID: [0-9]+ SSID:/ {id=$0; sub(/^[* ]*ID: /,"",id); sub(/ .*/,"",id)}
      inside && id!="" && ($1=="macRandomizationSetting:" || $1=="mRandomizedMacAddress:") {print id, $1, $2}'
    echo @props
    getprop | awk -F "]: " '{if(tolower($1) ~ /serialno|password|credential|secret|token/) print $1 "]: [•••]"; else print}'
    for ns in global system secure; do
      echo "@${'$'}ns"
      settings --user current list "${'$'}ns" 2>/dev/null | awk -F= 'NF>=2 {if(tolower($1) ~ /android_id|password|credential|secret|token|private_key|bluetooth_addr/) print $1 "=•••"; else print}'
    done
    echo @memory
    cat /proc/meminfo 2>/dev/null
    echo @battery
    dumpsys battery 2>/dev/null
    echo @features
    pm list features 2>/dev/null
    echo @systemInfo
    printf 'Kernel='; uname -r
    printf 'SELinux='; getenforce
    true
""".trimIndent()

internal data class PanelDetails(val overview: Map<String,String>, val sections: Map<String,Map<String,String>>)

internal fun parsePanelDetails(text: String): PanelDetails {
    val sections = linkedMapOf<String, MutableMap<String,String>>()
    val profiles = mutableMapOf<String,MutableMap<String,String>>()
    var section = ""
    val wifi = linkedMapOf<String,String>()
    for (line in text.lineSequence().map { it.trim() }) {
        if (line.startsWith('@')) { section = line.drop(1); continue }
        fun add(group: String, key: String, value: String) {
            if (key.isNotBlank()) sections.getOrPut(group) { linkedMapOf() }[key] = value
        }
        when (section) {
            "wifi" -> if (line.startsWith("WifiInfo: ")) {
                for (part in line.removePrefix("WifiInfo: ").split(", ")) {
                    if (": " in part) wifi[part.substringBefore(": ")] = part.substringAfter(": ")
                }
            }
            "profiles" -> {
                val fields = line.split(' ')
                if (fields.size == 3) profiles.getOrPut(fields[0]) { mutableMapOf() }[fields[1]] = fields[2]
            }
            "props" -> if (line.startsWith('[') && "]: [" in line && line.endsWith(']'))
                add("Android 系统属性", line.substringAfter('[').substringBefore("]: ["), line.substringAfter("]: [").dropLast(1))
            "global", "system", "secure" -> if ('=' in line) add("Settings · $section",line.substringBefore('='),line.substringAfter('='))
            "battery", "memory" -> if (':' in line && line.substringAfter(':').isNotBlank())
                add(if (section == "battery") "电池原始读数" else "内存原始读数",line.substringBefore(':'),line.substringAfter(':').trim())
            "features" -> if (line.startsWith("feature:")) {
                val feature = line.removePrefix("feature:")
                add("系统功能", feature.substringBefore('='), feature.substringAfter('=', "支持"))
            }
            "systemInfo" -> if ('=' in line) add("内核与安全",line.substringBefore('='),line.substringAfter('='))
        }
    }
    if (wifi.isNotEmpty()) sections["Wi-Fi 链路"] = wifi
    val props = sections["Android 系统属性"].orEmpty()
    val profile = profiles[wifi["Net ID"]]
    val randomMac = profile?.get("mRandomizedMacAddress:")
    val activeMac = wifi["MAC"]
    val usingRandom = randomMac != null && randomMac.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) &&
        randomMac.lowercase() !in setOf("02:00:00:00:00:00","00:00:00:00:00:00") && randomMac.equals(activeMac,true)
    val macMode = when (profile?.get("macRandomizationSetting:")) {
        "0" -> "已关闭（设备 MAC）"
        "1" -> "已开启（持久随机）"
        "2" -> "已开启（非持久随机）"
        "3" -> if (usingRandom) "已开启（自动，当前使用随机 MAC）" else "自动（实际状态未知）"
        else -> if (usingRandom) "已开启（当前使用随机 MAC）" else "未知（当前网络未提供策略）"
    }
    val downgrade = when (props["ro.debuggable"]) {
        "1" -> "ADB 显式请求时允许（可调试系统，仍需满足签名等条件）"
        "0" -> "普通 ADB 安装仅允许显式请求降级已安装的可调试应用"
        else -> "未知（无法读取系统调试状态）"
    }
    val overview = linkedMapOf("是否开启 MAC 随机化" to macMode, "是否允许降级更新应用" to downgrade)
    for ((label, key) in mapOf("产品代号" to "ro.product.device", "构建编号" to "ro.build.display.id",
        "构建类型" to "ro.build.type", "构建指纹" to "ro.build.fingerprint", "启动验证状态" to "ro.boot.verifiedbootstate",
        "加密状态" to "ro.crypto.state", "系统调试标志" to "ro.debuggable")) {
        props[key]?.takeIf(String::isNotBlank)?.let { overview[label] = it }
    }
    return PanelDetails(overview, sections)
}
