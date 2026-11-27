package dev.rprobedesk

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import java.util.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun remoteArg(value: String): String {
    require('\u0000' !in value)
    return "'" + value.replace("'", "'\\''") + "'"
}

internal suspend fun DeviceSession.checked(command: String): String {
    // A status trailer also works on older ADB shell transports with no exit-code channel.
    val result = shell("(\n$command\n)\nrpd_status=$?\nprintf '\\n__RPD_STATUS__%d\\n' \"\$rpd_status\"")
    val text = result.stdout.trimEnd('\n', '\r')
    val position = text.lastIndexOf("\n__RPD_STATUS__")
    check(position >= 0) { result.stderr.trim().ifBlank { "设备响应不完整，请核对连接" } }
    val output = text.substring(0, position).removeSuffix("\r")
    if (text.substring(position + 15).trim() != "0") {
        error((result.stderr.trim() + "\n" + output.trim()).trim().takeLast(1200).ifBlank { "设备操作失败" })
    }
    return output
}

internal suspend fun DeviceSession.currentUser(): Int =
    checked("am get-current-user").trim().toIntOrNull()?.takeIf { it >= 0 } ?: error("无法读取当前 Android 用户")

internal data class TargetApp(val name: String, val version: String, val system: Boolean, val frozen: Boolean, val launchable: Boolean?, val label:String = "", val icon:ByteArray? = null)
internal suspend fun DeviceSession.apps(user: Int): List<TargetApp> {
    val all = checked("pm list packages --show-versioncode --user $user")
    fun names(output: String) = output.lineSequence().filter { it.startsWith("package:") }
        .map { it.removePrefix("package:").trim() }.toSet()
    val frozen = names(checked("pm list packages -d --user $user"))
    val system = names(checked("pm list packages -s --user $user"))
    val launcher = shell("cmd package query-activities --components --user $user -a android.intent.action.MAIN -c android.intent.category.LAUNCHER")
    val launchable = if (launcher.exitCode == 0) launcher.stdout.lineSequence().filter { '/' in it }
        .map { it.trim().substringBefore('/') }.toSet() else null
    return all.lineSequence().filter { it.startsWith("package:") }.map { line ->
        val row = line.removePrefix("package:").trim()
        val name = row.substringBefore(' ')
        TargetApp(name, row.substringAfter("versionCode:", "未知").trim(), name in system, name in frozen, launchable?.contains(name))
    }.sortedBy { it.name }.toList()
}

internal suspend fun DeviceSession.appResources(user:Int, packages:List<String> = emptyList()): Map<String,Pair<String,ByteArray?>> {
    val remote="/data/local/tmp/rpd-catalog-${java.util.UUID.randomUUID()}.jar"
    try {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val bytes = requireNotNull(TargetApp::class.java.getResourceAsStream("/rprobe-catalog.jar")).use { it.readBytes() }
            bytes.inputStream().use { push(remote,it,bytes.size.toLong()) }
        }
        val result = mutableMapOf<String,Pair<String,ByteArray?>>()
        val decoder = Base64.getDecoder()
        val args=packages.joinToString("") { " " + remoteArg(it) }
        val reply = shell("chmod 444 $remote && CLASSPATH=$remote app_process /system/bin dev.rprobedesk.catalog.AppCatalog $user$args") { line,err ->
            if (!err) {
                val fields=line.split('\t',limit=3)
                if (fields.size == 3 && fields[1].length <= 16384) {
                    val label = try { String(decoder.decode(fields[1]), Charsets.UTF_8) }
                                catch (_: IllegalArgumentException) { return@shell }
                    val icon = fields[2].takeIf { it.length in 1..65536 }?.let {
                        try { decoder.decode(it) } catch (_: IllegalArgumentException) { null }
                    }?.takeIf(::isCatalogIcon)
                    result[fields[0]] = label to icon
                }
            }
        }
        check(reply.exitCode==0 && result.isNotEmpty()) { "设备未提供应用名称和图标" }
        return result
    } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            kotlinx.coroutines.withTimeoutOrNull(3000) { runCatching { shell("rm -f $remote") } }
        }
    }
}

// The on-device catalog renders every icon as a 48 × 48 PNG.
internal fun isCatalogIcon(bytes: ByteArray): Boolean {
    if (bytes.size < 24) return false
    val header = java.nio.ByteBuffer.wrap(bytes)
    return header.long == 0x89504e470d0a1a0auL.toLong() && header.int == 13 &&
        header.int == 0x49484452 && header.int == 48 && header.int == 48
}

internal data class TargetFile(
    val name: String, val directory: Boolean, val link: Boolean, val bytes: Long, val modified: Long,
    val regular: Boolean = !directory && !link
)
internal fun remotePath(dir: String, name: String): String {
    require(dir.startsWith('/') && name.isNotEmpty() && name !in listOf(".", "..") && '/' !in name && '\u0000' !in name) { "文件名无效" }
    return dir.trimEnd('/') + "/" + name
}
internal fun parentPath(dir: String) = dir.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }

private const val stampCommand = "stat -c '%f:%d:%i:%s:%y' --"

internal suspend fun DeviceSession.fileStamp(path: String): String {
    val file = remoteArg(path)
    return checked("if [ -e $file ] || [ -L $file ]; then $stampCommand $file; fi").trim()
}

internal fun regularStamp(stamp: String) = stamp.substringBefore(':').toIntOrNull(16)?.and(0xf000) == 0x8000

/** Android's mv -n follows dangling links; mksh noclobber reserves a new name exclusively, including on shared storage. */
internal suspend fun DeviceSession.publishFile(source: String, destination: String, existing: String, sourceStamp: String? = null) {
    val from = remoteArg(source)
    val to = remoteArg(destination)
    val folder = remoteArg(parentPath(destination))
    val checks = "[ -d $folder ] || { echo '目标目录不存在'; exit 1; }\n" +
        "[ -f $from ] && [ ! -L $from ] || { echo '源文件类型已变化'; exit 1; }\n" +
        "[ \"\$(stat -c %d -- $from)\" = \"\$(stat -L -c %d -- $folder)\" ] || { echo '仅支持同一卷内移动'; exit 1; }\n" +
        if (sourceStamp != null) "[ \"\$($stampCommand $from)\" = ${remoteArg(sourceStamp)} ] || { echo '源文件已变化'; exit 1; }\n" else ""
    if (existing.isNotEmpty()) {
        checked(checks + "[ \"\$($stampCommand $to)\" = ${remoteArg(existing)} ] || { echo '目标已变动，无法覆盖'; exit 1; }\n" +
            "rename $from $to")
    } else {
        // Keep the zero-byte reservation open. Its identity survives ENOSPC without writing a marker on the full volume.
        // mksh's rename builtin issues rename(2) directly, so a cross-mount error cannot trigger a partial copy.
        checked(checks + "dst=$to\nhold=''\n" +
            "ours() { [ -n \"\$hold\" ] && [ \"\$($stampCommand \"\$dst\" 2>/dev/null)\" = \"\$hold\" ]; }\n" +
            "trap 'if ours; then rm -- \"\$dst\"; fi' EXIT\ntrap 'exit 1' HUP INT TERM\n" +
            "[ ! -e \"\$dst\" ] && [ ! -L \"\$dst\" ] || { echo '目标同名文件已存在'; exit 1; }\n" +
            "set -C\nexec 6> \"\$dst\" || exit 1\nfd=/proc/\$BASHPID/fd/6\n" +
            "hold=\$(stat -L -c '%f:%d:%i:%s:%y' -- \"\$fd\") || exit 1\n" +
            "ours || { echo '目标已变动，无法提交'; exit 1; }\n" +
            "rename $from \"\$dst\" || exit 1\ntrap - EXIT HUP INT TERM")
    }
}

internal suspend fun DeviceSession.files(dir: String): List<TargetFile> {
    // Encode the listing as one stream so platform line readers preserve CR and LF inside file names.
    val listing = """
        set -o pipefail
        cd ${remoteArg(dir)} || exit 1
        if find . -maxdepth 0 -printf '' >/dev/null 2>&1; then
            find . -mindepth 1 -maxdepth 1 -type l -exec test -d {} \; -printf 'ld %s %T@\n%f\0' -o -printf '%M %s %T@\n%f\0'
        else
            ls -A -- . >/dev/null || exit 1
            for f in ./* ./.[!.]* ./..?*; do
                [ -e "${'$'}f" ] || [ -L "${'$'}f" ] || continue
                if [ -L "${'$'}f" ] && [ -d "${'$'}f" ]; then stat -c 'ld %s %Y' -- "${'$'}f"
                else stat -c '%f %s %Y' -- "${'$'}f"; fi || exit 1
                printf '%s\0' "${'$'}{f#./}"
            done
        fi
    """.trimIndent()
    val data=StringBuilder()
    val reply=shell("set -o pipefail\n(\n$listing\n) | base64") { line,err -> if(!err) data.append(line) }
    check(reply.exitCode==0) { reply.stderr.trim().ifEmpty { "目录不可读" } }
    return parseFiles(String(Base64.getMimeDecoder().decode(if(data.isEmpty()) reply.stdout else data.toString()), Charsets.UTF_8))
}

internal fun parseFiles(raw: String): List<TargetFile> = raw.split('\u0000').filter { it.isNotEmpty() }.map { entry ->
    val newline = entry.indexOf('\n')
    require(newline >= 0) { "设备返回的目录格式无效" }
    val values = entry.substring(0, newline).trim().split(' ')
    require(values.size == 3) { "缺少文件属性" }
    val mode=values[0]
    val type = when {
        mode=="ld" -> 0xa000
        mode.length<10 -> mode.toInt(16) and 0xf000
        else -> when(mode.first()) { 'd' -> 0x4000; 'l' -> 0xa000; '-' -> 0x8000; else -> 0 }
    }
    TargetFile(entry.substring(newline + 1), type == 0x4000 || mode=="ld", type == 0xa000, values[1].toLong(), values[2].substringBefore('.').toLong(), type == 0x8000)
}.filter { it.name!="." && it.name!=".." }.sortedWith(compareByDescending<TargetFile> { it.directory }.thenBy { it.name })

internal data class DeviceLog(val text: String, val level: Char? = null, val tag: String = "", val pid: String = "",
                              val timestamp: String = "", val tid: String = "")
internal fun logRow(raw:String, fields:IntArray, offset:Int, previous:DeviceLog?):DeviceLog {
    val level=fields[offset]
    if (level == -1 && previous != null) return previous.copy(text = raw)
    if (level <= 0) return DeviceLog(raw)
    return DeviceLog(raw,level.toChar(),raw.substring(fields[offset+3],fields[offset+4]).trim(),
        raw.substring(fields[offset+1],fields[offset+2]),
        if (fields.size == 9) raw.substring(fields[5],fields[6]) else "",
        if (fields.size == 9) raw.substring(fields[7],fields[8]) else "")
}
internal fun parseLog(raw:String, previous:DeviceLog?) = logRow(raw,NativeCore.logFields(arrayOf(raw)),0,previous)
internal const val levels = "VDIWEF"
internal data class LogFilter(val level: Char = 'V', val tag: String = "", val pid: String = "", val text: String = "") {
    fun accepts(row: DeviceLog) = (level == 'V' || levels.indexOf(row.level ?: ' ') >= levels.indexOf(level)) &&
        (tag.isBlank() || row.tag.contains(tag.trim(), true)) && (pid.isBlank() || row.pid == pid.trim()) &&
        (text.isBlank() || row.text.contains(text, true))
}

internal fun byteCount(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.1f KiB".format(Locale.ROOT, n / 1024.0)
    else -> "%.1f MiB".format(Locale.ROOT, n / 1048576.0)
}

internal suspend fun stageStream(input: InputStream, target: File, expected: Long?, progress: (Long) -> Unit): Long {
    val context = currentCoroutineContext()
    var count = 0L
    val bytes = ByteArray(64 * 1024)
    target.outputStream().buffered().use { out ->
        while (true) {
            context.ensureActive()
            val n = input.read(bytes)
            if (n == -1) break
            out.write(bytes, 0, n)
            count += n
            progress(count)
        }
    }
    check(expected == null || count == expected) { "传输不完整，已收到 $count / $expected 字节" }
    return count
}

internal fun apkRedirect(current: URI, location: String): URI {
    val next = try { current.resolve(location) } catch (_: IllegalArgumentException) { error("跳转地址无效") }
    require(next.scheme.equals("http", true) || next.scheme.equals("https", true)) { "跳转仅支持 HTTP 和 HTTPS" }
    check(!(current.scheme.equals("https", true) && next.scheme.equals("http", true))) { "HTTPS 被重定向到明文 HTTP，请重新核对来源" }
    return next
}

internal suspend fun downloadApk(
    text: String, target: File, confirmHttp: suspend (String) -> Unit, progress: (Long, Long?) -> Unit
): String {
    var uri = try { URI(text.trim()) } catch (_: Exception) { error("链接格式无效") }
    repeat(10) {
        currentCoroutineContext().ensureActive()
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "请输入有效的 HTTP 或 HTTPS 直链" }
        if (scheme == "http") confirmHttp(uri.rawAuthority)
        val c = uri.toURL().openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 10000
            c.readTimeout = 10000
            c.setRequestProperty("Accept-Encoding", "identity")
            val status = c.responseCode
            if (status in listOf(301, 302, 303, 307, 308)) {
                uri = apkRedirect(uri, c.getHeaderField("Location") ?: error("链接跳转缺少地址"))
            } else {
                check(status == 200) { "下载失败，HTTP $status" }
                val size = c.contentLengthLong.takeIf { it >= 0 }
                progress(0, size)
                c.inputStream.use { stageStream(it, target, size) { done -> progress(done, size) } }
                return uri.host
            }
        } catch (e: java.io.IOException) {
            error("下载失败（${e.javaClass.simpleName}），请检查来源和网络")
        } finally { c.disconnect() }
    }
    error("重定向次数过多")
}

