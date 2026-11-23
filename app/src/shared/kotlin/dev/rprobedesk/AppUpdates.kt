package dev.rprobedesk

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.toByteString

private const val releases = "https://github.com/rurikacy/RProbeDesk/releases"
private val http = OkHttpClient.Builder().followSslRedirects(false).build()
private val versionPattern = Regex("""[0-9]+\.[0-9]+\.[0-9]+""")

internal fun newerRelease(current: String, location: String): String? {
    require(location.startsWith("$releases/tag/v")) { "无法识别发布版本" }
    val version = location.removePrefix("$releases/tag/v")
    require(versionPattern.matches(version) && versionPattern.matches(current)) { "无法识别发布版本" }
    val installed = current.split('.').map(String::toInt)
    val available = version.split('.').map(String::toInt)
    val difference = installed.indices.firstOrNull { installed[it] != available[it] } ?: return null
    return version.takeIf { available[difference] > installed[difference] }
}

internal fun checkRelease(current: String): String? {
    val request = Request.Builder().url("$releases/latest").head().build()
    http.newCall(request).execute().use { response ->
        check(response.isSuccessful) { "检查更新失败，HTTP ${response.code}" }
        return newerRelease(current, response.request.url.toString())
    }
}

internal fun releaseChecksum(manifest: String, name: String): String {
    val entry = Regex("""([0-9a-fA-F]{64})\s+\*?${Regex.escape(name)}""")
    return manifest.lineSequence().mapNotNull { entry.matchEntire(it.trim()) }.singleOrNull()
        ?.groupValues?.get(1)?.lowercase() ?: error("发布包缺少有效的 SHA-256 校验值")
}

internal suspend fun downloadRelease(version: String, desktop: Boolean, directory: File, progress: (Long, Long?) -> Unit): File {
    require(versionPattern.matches(version))
    val name = "RProbeDesk-$version-" + if (desktop) "windows-x64.msi" else "android.apk"
    val base = "$releases/download/v$version"
    fun get(asset: String) = http.newCall(Request.Builder().url("$base/$asset").build()).execute()
    val expected = get("SHA256SUMS.txt").use {
        check(it.isSuccessful) { "下载失败，HTTP ${it.code}" }
        releaseChecksum(requireNotNull(it.body).string(), name)
    }
    directory.mkdirs()
    val temp = File.createTempFile("download-", ".part", directory)
    try {
        get(name).use { response ->
            check(response.isSuccessful) { "下载失败，HTTP ${response.code}" }
            val body = requireNotNull(response.body)
            val length = body.contentLength().takeIf { it >= 0 }
            val hash = MessageDigest.getInstance("SHA-256")
            DigestInputStream(body.byteStream(), hash).use { input ->
                stageStream(input, temp, length) { progress(it, length) }
            }
            check(hash.digest().toByteString().hex() == expected) { "安装包校验失败，请重试" }
        }
        val installer = File(directory, "RProbeDesk-update." + if (desktop) "msi" else "apk")
        Files.move(temp.toPath(), installer.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return installer
    } finally { temp.delete() }
}
