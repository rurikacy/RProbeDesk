package dev.rprobedesk

import java.io.File

internal class ToolManager(private val resources: File?, private val overridePath: (String) -> String?) {
    fun bundledServer(executable: String): File? {
        val dir = resources?.resolve("tools/scrcpy") ?: return null
        if (File(executable) != dir.resolve("scrcpy.exe").absoluteFile) return null
        return dir.resolve("scrcpy-server").also { check(it.isFile) { "缺少内置 scrcpy-server，请修复安装" } }
    }
    private data class Verified(val file: File, val bytes: Long, val modified: Long, val pairing: Boolean)
    val diagnostics = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val verified = mutableMapOf<String, Verified>()
    private fun bundled(name: String) = resources?.resolve("tools/scrcpy/$name.exe")?.takeIf(File::isFile)
    suspend fun verifiedPath(name: String, pairing: Boolean = false): String {
        val chosen = path(name)?.let(::File) ?: error("缺少 $name.exe，请修复安装或在设置中选择工具")
        var failure: Exception? = null
        for (candidate in listOfNotNull(chosen, bundled(name)).distinct()) {
            val key = Verified(candidate, candidate.length(), candidate.lastModified(), pairing)
            if (synchronized(verified) { verified[name] == key }) return candidate.absolutePath
            try {
                verifyWindowsTool(name, candidate.absolutePath, pairing, probePrivate = name == "adb" && candidate != bundled(name))
                if (candidate == chosen) diagnostics.remove(name)
                synchronized(verified) { verified[name] = key }
                return candidate.absolutePath
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                if (e is TransportFailure && e.kind == TransportError.Exhausted) throw e
                failure = e
                if (candidate != bundled(name) && bundled(name) != null)
                    diagnostics[name] = "已回退到内置 $name · " + (e.localizedMessage ?: candidate.name)
            }
        }
        throw requireNotNull(failure)
    }

    fun path(tool: String): String? {
        val name = tool.removeSuffix(".exe")
        require(name == "adb" || name == "scrcpy")
        val selected = overridePath(name)?.let(::File)
        if (selected != null && selected.isAbsolute && selected.isFile) return selected.absolutePath
        return bundled(name)?.absolutePath
    }
}
