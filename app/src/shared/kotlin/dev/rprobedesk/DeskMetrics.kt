package dev.rprobedesk

import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

internal fun normalizedIp(text: String): String {
    val raw = text.trim()
    val ip = if (raw.startsWith('[') && raw.endsWith(']')) raw.substring(1, raw.length - 1) else raw
    if (':' in ip) {
        val literal = ip.substringBefore('%')
        val zone = ip.substringAfter('%', "")
        require(literal.matches(Regex("[a-fA-F0-9:.]+")) && ('%' !in ip || zone.matches(Regex("[a-zA-Z0-9_.-]+")))) { "请输入有效的 IPv6 地址" }
        val address = runCatching { InetAddress.getByName(literal) }.getOrNull()
        require(address != null) { "请输入有效的 IPv6 地址" }
        return requireNotNull(address.hostAddress) { "无法解析 IPv6 地址" }.substringBefore('%') +
            if (zone.isEmpty() || address !is Inet6Address) "" else "%$zone"
    }
    val parts = ip.split('.')
    require(parts.size == 4 && parts.all { it.matches(Regex("[0-9]{1,3}")) && it.toInt() <= 255 }) { "请输入有效的 IPv4 或 IPv6 地址" }
    return parts.joinToString(".") { it.toInt().toString() }
}

internal fun parsePort(text: String): Int = text.trim().takeIf { it.matches(Regex("[0-9]{1,5}")) }
    ?.toInt()?.takeIf { it in 1..65535 } ?: throw IllegalArgumentException("端口应在 1–65535 之间")

internal data class CpuTicks(val counters: List<Long>) {
    val total = counters.sum()
    val idle = counters[3] + counters.getOrElse(4) { 0L }
}
internal data class DeskMetrics(
    val cpu: Double?, val cpuSource: String, val memory: String, val memoryPercent: Double?,
    val load: Double?, val cores: Int?, val gpu: Double?, val disk: Double?,
    val battery: String?, val temperature: String, val ticks: CpuTicks?, val captured: Long
)

internal val metricCommand = """
    echo @mem
    cat /proc/meminfo 2>/dev/null
    echo @cpu
    head -n 1 /proc/stat 2>/dev/null || dumpsys cpuinfo 2>/dev/null | tail -n 3
    echo @battery
    dumpsys battery 2>/dev/null
    [ ! -d /sys/class/power_supply/test_battery ] || echo batterySource=test_battery
    cat /proc/loadavg 2>/dev/null | awk '{print "load=" $1}'
    getconf _NPROCESSORS_ONLN 2>/dev/null | awk '{print "cores=" $1}'
    df -k /data 2>/dev/null | awk '$5 ~ /^[0-9]+%$/{print "disk=" $5}'
    for node in /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage /sys/class/devfreq/*gpu*/device/utilisation /sys/class/devfreq/*gpu*/load; do
        [ -r "${'$'}node" ] || continue
        read -r usage < "${'$'}node" || continue
        echo "gpu=${'$'}usage"
        break
    done
""".trimIndent()

internal fun parseMetrics(text: String, previous: CpuTicks?, now: Long): DeskMetrics {
    fun number(name: String) = Regex("(?m)^" + name + ":\\s*(\\d+)\\s+kB").find(text)?.groupValues?.get(1)?.toLongOrNull()
    val total = number("MemTotal")
    val available = number("MemAvailable")
    val hasMemory = total != null && available != null && total > 0 && available in 0..total
    val cpuFields = text.lineSequence().firstOrNull { it.startsWith("cpu ") }?.trim()?.split(Regex("\\s+"))?.drop(1)?.take(8)
    val times = cpuFields?.mapNotNull { it.toLongOrNull() }
    val ticks = if (times != null && times.size >= 4 && times.size == cpuFields.size && times.all { it >= 0 })
        CpuTicks(times) else null
    val fallback = Regex("(?m)^\\s*([0-9]+(?:\\.[0-9]+)?)%\\s+TOTAL:").find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 0.0..100.0 }
    val delta = if (previous != null && ticks != null && ticks.total > previous.total &&
        ticks.counters.size == previous.counters.size && ticks.counters.indices.all { ticks.counters[it] >= previous.counters[it] })
        100.0 * (1.0 - (ticks.idle - previous.idle).toDouble() / (ticks.total - previous.total)) else null
    val percent = delta?.takeIf { it in 0.0..100.0 } ?: fallback
    val temperature = Regex("(?m)^\\s*temperature:\\s*(-?\\d+)\\s*$").find(text)?.groupValues?.get(1)?.toIntOrNull()
    fun field(name: String) = text.lineSequence().firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.trim()
    fun batteryField(name: String) = text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("$name:") }
        ?.substringAfter(':')?.trim()?.toIntOrNull()
    val level = batteryField("level")
    val scale = batteryField("scale")
    val battery = if (level != null && scale != null && scale > 0 && level in 0..scale) {
        val state = when (batteryField("status")) { 2 -> "充电中"; 3 -> "放电中"; 4 -> "未充电"; 5 -> "已充满"; else -> null }
        listOfNotNull("${100L * level / scale}%", state).joinToString(" · ")
    } else null
    fun percentage(value: String?) = value?.removeSuffix("%")?.trim()?.toDoubleOrNull()?.takeIf { it in 0.0..100.0 }
    fun decimal(value: Double) = String.format(Locale.ROOT, "%.1f", value)
    return DeskMetrics(
        percent,
        when { ticks != null && percent == null -> "/proc/stat · 建立基线"; ticks != null -> "/proc/stat"; fallback != null -> "dumpsys cpuinfo · 系统采样窗口"; else -> "整机 CPU 不可读" },
        if (hasMemory) "${(total!! - available!!) / 1024} / ${total / 1024} MiB" else "—",
        if (hasMemory) 100.0 * (total!! - available!!) / total else null,
        field("load")?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 },
        field("cores")?.toIntOrNull()?.takeIf { it > 0 }, percentage(field("gpu")?.substringBefore('@')),
        percentage(field("disk")), battery,
        temperature?.let { "${decimal(it / 10.0)} °C" + if(field("batterySource")=="test_battery") "（测试电池上报）" else "" } ?: "—", ticks, now
    )
}
