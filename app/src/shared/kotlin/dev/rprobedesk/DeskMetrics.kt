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
    val cpu: String, val cpuSource: String, val memory: String, val memoryRatio: String,
    val temperature: String, val ticks: CpuTicks?, val captured: Long
)

internal val metricCommand = """
    echo @mem
    cat /proc/meminfo 2>/dev/null
    echo @cpu
    head -n 1 /proc/stat 2>/dev/null || dumpsys cpuinfo 2>/dev/null | tail -n 3
    echo @battery
    dumpsys battery 2>/dev/null
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
    fun decimal(value: Double) = String.format(Locale.ROOT, "%.1f", value)
    return DeskMetrics(
        percent?.let { "${decimal(it)} %" } ?: "—",
        when { ticks != null && percent == null -> "/proc/stat · 建立基线"; ticks != null -> "/proc/stat"; fallback != null -> "dumpsys cpuinfo · 系统采样窗口"; else -> "整机 CPU 不可读" },
        if (hasMemory) "${(total!! - available!!) / 1024} / ${total / 1024} MiB" else "—",
        if (hasMemory) "${decimal(100.0 * (total!! - available!!) / total)}% · /proc/meminfo" else "MemTotal 或 MemAvailable 不可读",
        temperature?.let { "${decimal(it / 10.0)} °C" } ?: "—", ticks, now
    )
}
