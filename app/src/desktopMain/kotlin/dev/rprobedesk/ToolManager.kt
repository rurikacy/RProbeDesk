package dev.rprobedesk

import java.io.File

internal class ToolManager(private val resources: File?, private val overridePath: (String) -> String?) {
    fun bundledServer(executable: String): File? {
        val dir = resources?.resolve("tools/scrcpy") ?: return null
        if (File(executable) != dir.resolve("scrcpy.exe").absoluteFile) return null
        return dir.resolve("scrcpy-server").also { check(it.isFile) { "缺少内置 scrcpy-server，请修复安装" } }
    }
    fun path(tool: String): String? {
        val name = tool.removeSuffix(".exe")
        require(name == "adb" || name == "scrcpy")
        val selected = overridePath(name)?.let(::File)
        if (selected != null && selected.isAbsolute && selected.isFile) return selected.absolutePath
        return resources?.resolve("tools/scrcpy/$name.exe")?.takeIf(File::isFile)?.absolutePath
    }
}
