package dev.rprobedesk

import kotlinx.coroutines.*
import java.util.UUID
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.StateFlow

data class ShellResult(val stdout: String, val stderr: String, val exitCode: Int?)

/** A single explicitly selected transport. A closed session must never resolve to a new target. */
interface DeviceSession {
    val serial: String
    val verifiedPeerKey: String? get() = null
    suspend fun shell(command: String, onLine: ((String, Boolean) -> Unit)? = null): ShellResult
    suspend fun pull(path: String, output: OutputStream, progress: (Long) -> Unit = {})
    suspend fun push(path: String, input: InputStream, size: Long?, progress: (Long) -> Unit = {})
    suspend fun install(apk: File, userId: Int, transferred:(Long)->Unit = {}, installing:()->Unit = {}): String {
        require(userId >= 0 && apk.isFile)
        val remote = "/data/local/tmp/rprobedesk-${UUID.randomUUID()}.apk"
        try {
            withContext(Dispatchers.IO) {
                apk.inputStream().use { push(remote,it,apk.length(),transferred) }
            }
            installing()
            val result = shell("pm install -r --user $userId $remote")
            val message = (result.stdout + "\n" + result.stderr).trim()
            check(result.exitCode == 0 && result.stdout.lineSequence().any { it.trim()=="Success" }) {
                message.ifBlank { "设备拒绝安装" }
            }
            return message
        } finally {
            withContext(NonCancellable) {
                withTimeoutOrNull(3000) { runCatching { shell("rm -f $remote") } }
            }
        }
    }
    suspend fun screenshot(output: OutputStream)
    fun close()
}

interface LocalDoc {
    val name: String
    val size: Long?
    fun openInput(): InputStream
    /** Publishes a complete staged file; a failed write preserves existing user content. */
    suspend fun writeFrom(file: File)
}

interface HostBridge {
    val settingsDir: File
    val cacheDir: File
    val foreground: StateFlow<Boolean>
    val reducedMotion: StateFlow<Boolean>
    val mirrorActive: StateFlow<Boolean>
    val desktop: Boolean
    suspend fun discoverDevices():List<DiscoveredDevice> = emptyList()
    suspend fun pair(address: String, port: Int, code: String)
    suspend fun connect(address: String, port: Int): DeviceSession
    suspend fun pickOpen(suffix: String = ""): LocalDoc?
    suspend fun pickSave(name: String): LocalDoc?
    suspend fun selectTool(tool: String)
    suspend fun setToolPath(tool:String,path:String):Unit = error("此平台使用内置工具")
    fun toolPath(tool: String): String?
    fun hasSavedToolPath(tool: String): Boolean = !toolPath(tool).isNullOrBlank()
    suspend fun startMirror(session: DeviceSession)
    fun stopMirror()
    fun copy(text: String)
}
