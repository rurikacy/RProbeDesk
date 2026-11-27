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

internal data class CpuTicks(val counters: List<Long>)
internal data class DeskMetrics(
    val cpu: Double?, val cpuSource: String, val memory: String, val memoryPercent: Double?,
    val load: Double?, val cores: Int?, val gpu: Double?, val disk: Double?, val diskCapacity: String?,
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
    df -k /data 2>/dev/null | awk 'NF>=5 && $(NF-1) ~ /^[0-9]+%$/ {print "disk=" $(NF-1); print "diskTotalKiB=" $(NF-4); print "diskUsedKiB=" $(NF-3)}'
    for node in /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage /sys/class/devfreq/*gpu*/device/utilisation /sys/class/devfreq/*gpu*/load; do
        [ -r "${'$'}node" ] || continue
        read -r usage < "${'$'}node" || continue
        echo "gpu=${'$'}usage"
        break
    done
""".trimIndent()

internal class MonitorReading(
    val counters: LongArray, val cpu: Double, val total: Long, val available: Long,
    val gpu: Double, val load: Double, val cores: Int, val disk: Double,
    val diskTotal: Long, val diskUsed: Long, val level: Int, val scale: Int,
    val status: Int, val temperature: Int, val testBattery: Boolean,
)

internal fun parseMetrics(text: String, previous: CpuTicks?, now: Long): DeskMetrics {
    val reading = NativeCore.metrics(text, previous?.counters?.toLongArray() ?: longArrayOf())
    val ticks = reading.counters.takeIf { it.isNotEmpty() }?.let { CpuTicks(it.toList()) }
    val percent = reading.cpu.takeIf(Double::isFinite)
    val total = reading.total
    val available = reading.available
    val hasMemory = total > 0 && available in 0..total
    val temperature = reading.temperature.takeUnless { it == Int.MIN_VALUE }
    val battery = if (reading.scale > 0 && reading.level in 0..reading.scale) {
        val state = when (reading.status) { 2 -> "充电中"; 3 -> "放电中"; 4 -> "未充电"; 5 -> "已充满"; else -> null }
        listOfNotNull("${100L * reading.level / reading.scale}%", state).joinToString(" · ")
    } else null
    fun decimal(value: Double) = String.format(Locale.ROOT, "%.1f", value)
    fun memorySize(kib: Long): String {
        val mb = kib * 1024.0 / 1_000_000
        return if (mb > 1000) "${decimal(mb / 1000)} GB" else "${decimal(mb)} MB"
    }
    val diskTotal = reading.diskTotal
    val diskUsed = reading.diskUsed
    fun diskSize(kib: Long) = if (kib >= 1024L * 1024) "${decimal(kib / 1048576.0)} GiB" else "${decimal(kib / 1024.0)} MiB"
    val diskCapacity = if (diskTotal > 0 && diskUsed in 0..diskTotal)
        "已用 ${diskSize(diskUsed)} · 总计 ${diskSize(diskTotal)}" else null
    return DeskMetrics(
        percent,
        when { ticks != null && percent == null -> "/proc/stat · 建立基线"; ticks != null -> "/proc/stat"; percent != null -> "dumpsys cpuinfo · 系统采样窗口"; else -> "整机 CPU 不可读" },
        if (hasMemory) "已用 ${memorySize(total - available)} · 总计 ${memorySize(total)}" else "—",
        if (hasMemory) 100.0 * (total - available) / total else null,
        reading.load.takeIf(Double::isFinite),
        reading.cores.takeIf { it > 0 }, reading.gpu.takeIf(Double::isFinite),
        reading.disk.takeIf(Double::isFinite), diskCapacity, battery,
        temperature?.let { "${decimal(it / 10.0)} °C" + if(reading.testBattery) "（测试电池上报）" else "" } ?: "—", ticks, now
    )
}
