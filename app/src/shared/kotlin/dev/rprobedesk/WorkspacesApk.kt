package dev.rprobedesk

import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import java.util.zip.ZipException

internal data class ApkDetails(val packageName: String, val versionCode: Long, val versionName: String?)

internal fun readApk(file: File): ApkDetails = try {
    ZipFile(file).use { apk ->
        val manifest = apk.getEntry("AndroidManifest.xml") ?: error("文件中缺少 AndroidManifest.xml")
        require(manifest.size in 8..(8L * 1024 * 1024)) { "APK 清单大小无效" }
        val bytes = ByteArray(manifest.size.toInt())
        DataInputStream(apk.getInputStream(manifest)).use { it.readFully(bytes) }
        readManifest(bytes)
    }
} catch (e: ZipException) {
    throw IllegalArgumentException("文件不是有效的 APK，或下载未完成", e)
}

// The Android binary XML string pool is shared by every node. Read only the root manifest attributes.
internal fun readManifest(bytes: ByteArray): ApkDetails {
    val xml = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    fun u16(p: Int) = xml.getShort(p).toInt() and 0xffff
    fun i32(p: Int) = xml.getInt(p)
    fun u8(p: Int) = xml.get(p).toInt() and 0xff
    fun valid(ok: Boolean) = require(ok) { "无法识别 APK 二进制清单" }
    valid(bytes.size >= 8 && u16(0) == 3 && i32(4) == bytes.size)
    var pool = -1
    var poolEnd = 0
    fun string(index: Int): String {
        valid(pool >= 0 && index in 0 until i32(pool + 8))
        val table = pool + u16(pool + 2)
        valid(index < (poolEnd - table) / 4)
        val strings = i32(pool + 20)
        val offset = i32(table + index * 4)
        valid(strings in 0 until poolEnd - pool && offset in 0 until poolEnd - pool - strings)
        var p = pool + strings + offset
        val utf8 = i32(pool + 16) and 0x100 != 0
        fun length(): Int {
            if (utf8) {
                valid(p < poolEnd)
                val n = u8(p++)
                if (n < 128) return n
                valid(p < poolEnd)
                return ((n and 127) shl 8) or u8(p++)
            }
            valid(p <= poolEnd - 2)
            val n = u16(p)
            p += 2
            if (n < 32768) return n
            valid(p <= poolEnd - 2)
            p += 2
            return ((n and 32767) shl 16) or u16(p - 2)
        }
        val chars = length()
        val count = if (utf8) length().toLong() else chars * 2L
        valid(count in 0..(poolEnd - p).toLong())
        return String(bytes, p, count.toInt(), if (utf8) Charsets.UTF_8 else Charsets.UTF_16LE)
    }
    var p = u16(2)
    valid(p >= 8)
    while (p <= bytes.size - 8) {
        val kind = u16(p)
        val header = u16(p + 2)
        val size = i32(p + 4)
        valid(header >= 8 && size in header..bytes.size - p)
        if (kind == 1) {
            valid(header >= 28)
            pool = p
            poolEnd = p + size
        } else if (kind == 0x0102) {
            val ext = p + header
            valid(header >= 16 && size - header >= 20 && string(i32(ext + 4)) == "manifest")
            val start = u16(ext + 8)
            val stride = u16(ext + 10)
            val count = u16(ext + 12)
            valid(start >= 20 && stride >= 20 && start + count.toLong() * stride <= size - header)
            var pkg = ""
            var version: String? = null
            var low = 0L
            var high = 0
            for (i in 0 until count) {
                val a = ext + start + i * stride
                val ns = i32(a)
                val key = string(i32(a + 4))
                val raw = i32(a + 8)
                val type = u8(a + 15)
                val data = i32(a + 16)
                val value = when {
                    type == 3 -> string(data)
                    raw >= 0 -> string(raw)
                    else -> null
                }
                if (ns < 0) {
                    if (key == "package") pkg = value.orEmpty()
                    require(key != "split" || value.isNullOrEmpty()) { "请选择单个完整 APK，当前文件是分包" }
                } else if (string(ns) == "http://schemas.android.com/apk/res/android") {
                    when (key) {
                        "versionName" -> if (type == 3) version = value
                        "versionCode" -> low = data.toLong() and 0xffffffffL
                        "versionCodeMajor" -> high = data
                        "isSplitRequired" -> require(data == 0) { "此 APK 需要配套分包" }
                        "requiredSplitTypes" -> require(value.isNullOrBlank()) { "此 APK 需要配套分包" }
                    }
                }
            }
            valid(pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*")) && high >= 0)
            return ApkDetails(pkg, (high.toLong() shl 32) or low, version)
        }
        p += size
    }
    error("APK 缺少 manifest 节点")
}

