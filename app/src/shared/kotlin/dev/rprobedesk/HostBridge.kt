package dev.rprobedesk

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
    suspend fun install(apk: File, userId: Int): String
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
    suspend fun pair(address: String, port: Int, code: String)
    suspend fun connect(address: String, port: Int): DeviceSession
    suspend fun pickOpen(suffix: String = ""): LocalDoc?
    suspend fun pickSave(name: String): LocalDoc?
    suspend fun selectTool(tool: String)
    fun toolPath(tool: String): String?
    suspend fun startMirror(session: DeviceSession)
    fun stopMirror()
    fun copy(text: String)
}
