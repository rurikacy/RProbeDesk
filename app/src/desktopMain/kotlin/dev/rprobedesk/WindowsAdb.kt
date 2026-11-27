package dev.rprobedesk

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

internal val processIo = Executors.newCachedThreadPool { work ->
    Thread(work, "rprobe-process").apply { isDaemon = true }
}
private val processSlots = Semaphore(16)

internal fun localAdbProcess(args: List<String>) = ProcessBuilder(args).apply {
    // ADB only auto-starts the daemon for a local socket specification.
    environment()["ADB_SERVER_SOCKET"] = "tcp:5037"
}

/** One owner for every subprocess, including streaming operations waiting for output. */
internal class ProcessGroup {
    private val children = mutableSetOf<Process>()
    private var closed = false

    @Synchronized
    private fun start(args: List<String>): Process {
        check(!closed) { "设备会话已关闭" }
        return localAdbProcess(args).start().also { children.add(it) }
    }

    suspend fun <T> use(args: List<String>, block: (Process) -> T): T = processSlots.withPermit {
        suspendCancellableCoroutine { pending ->
            val active = AtomicReference<Process?>()
            pending.invokeOnCancellation { active.get()?.destroyForcibly() }
            processIo.execute {
                if (!pending.isActive) return@execute
                val result = runCatching {
                    val process = start(args)
                    active.set(process)
                    try {
                        check(pending.isActive) { "操作已取消" }
                        block(process)
                    } finally {
                        process.destroyForcibly()
                        synchronized(this) { children.remove(process) }
                        active.set(null)
                    }
                }
                if (pending.isActive) pending.resumeWith(result)
            }
        }
    }

    @Synchronized
    fun close() {
        closed = true
        children.forEach { it.destroyForcibly() }
        children.clear()
    }
}

private fun readText(stream: InputStream, onLine: ((String) -> Unit)?): String {
    val result = StringBuilder()
    stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
        lines.forEach { line ->
            onLine?.invoke(line)
            result.append(line).append('\n')
            // Streaming commands deliver full records through onLine; keep only a diagnostic tail.
            if (onLine != null && result.length > 65536) result.delete(0, result.length - 32768)
        }
    }
    return result.toString()
}

internal suspend fun ProcessGroup.text(
    args: List<String>, stdin: String? = null, onLine: ((String, Boolean) -> Unit)? = null
): ShellResult = use(args) { process ->
    val err = processIo.submit<String> { readText(process.errorStream, onLine?.let { { s -> it(s, true) } }) }
    val out = processIo.submit<String> { readText(process.inputStream, onLine?.let { { s -> it(s, false) } }) }
    try { process.outputStream.use { if (stdin != null) it.write(stdin.toByteArray(Charsets.UTF_8)) } }
    catch (e: IOException) { if (stdin == null) throw e } // A script may exit before consuming its final input bytes.
    ShellResult(out.get(), err.get(), process.waitFor())
}

internal fun ShellResult.checked(): String {
    check(exitCode == 0) { stderr.trim().ifEmpty { stdout.trim().ifEmpty { "ADB 返回 $exitCode" } } }
    return stdout.trim()
}

internal fun remoteWord(value: String): String {
    require('\u0000' !in value) { "无效字符" }
    return "'" + value.replace("'", "'\\''") + "'"
}

internal fun targetAddress(ip: String, port: Int): String {
    require(port in 1..65535)
    val raw = ip.removeSurrounding("[", "]")
    require(raw.isNotBlank() && raw.none { it.isWhitespace() } && !raw.startsWith("-")) { "地址无效" }
    return if (':' in raw) "[$raw]:$port" else "$raw:$port"
}

internal class WindowsSession(val adb: String, override val serial: String, private val tracker: Long) : DeviceSession {
    val transportId = NativeCore.transportId(tracker).toString()
    private val streams = mutableSetOf<Socket>()
    private val serviceSlots = Semaphore(16)
    private var closed = false
    private var closeReason = "设备会话已关闭"
    @Volatile private var wireless = 0L
    val disconnected = CompletableDeferred<Unit>()
    override var verifiedPeerKey: String? = null
        private set

    @Synchronized fun attachWireless(handle: Long) {
        try {
            check(!closed && wireless == 0L) { closeReason }
            verifiedPeerKey = NativeCore.directPeer(handle)
            wireless = handle
        } catch(e: Throwable) { NativeCore.directClose(handle); throw e }
    }
    init {
        processIo.execute {
            val reason = try { NativeCore.waitForDisconnect(tracker) }
                catch (e: Exception) { e.localizedMessage ?: "无法读取 ADB 设备状态" }
            invalidate(reason)
        }
    }

    override suspend fun shell(command: String, onLine: ((String, Boolean) -> Unit)?): ShellResult {
        // Keep the script on its own descriptor. Commands reading stdin must receive EOF.
        return service("shell,v2,raw:cat | sh /proc/self/fd/3 3<&0 </dev/null") { input, output ->
            shellResult(input, output, command, onLine)
        }
    }

    private suspend fun <T> service(name: String, block: (InputStream, OutputStream) -> T): T = serviceSlots.withPermit {
        val handle = wireless
        if (handle == 0L) compatibilityService(name, block) else wirelessService(handle, name, block)
    }

    private suspend fun <T> compatibilityService(name: String, block: (InputStream, OutputStream) -> T): T =
        suspendCancellableCoroutine { pending ->
            val socket = Socket()
            pending.invokeOnCancellation { socket.close() }
            processIo.execute {
                val result = runCatching {
                    socket.use {
                        synchronized(this) {
                            check(!closed && pending.isActive) { closeReason }
                            streams.add(socket)
                        }
                        socket.connect(InetSocketAddress("127.0.0.1", 5037), 10000)
                        socket.soTimeout = 10000
                        val input = DataInputStream(socket.getInputStream())
                        val out = socket.getOutputStream()
                        fun text(count: Int) = ByteArray(count).also(input::readFully).toString(Charsets.UTF_8)
                        for (request in listOf("host:transport-id:$transportId", name)) {
                            val data = request.toByteArray(Charsets.US_ASCII)
                            out.write(data.size.toString(16).padStart(4, '0').toByteArray(Charsets.US_ASCII))
                            out.write(data)
                            when (text(4)) {
                                "OKAY" -> Unit
                                "FAIL" -> throw IOException(text(text(4).toInt(16)))
                                else -> throw IOException("ADB server 返回无效响应")
                            }
                        }
                        socket.soTimeout = if (name == "sync:") 60000 else 0
                        block(input, out)
                    }
                }
                synchronized(this) { streams.remove(socket) }
                if (pending.isActive) pending.resumeWith(result)
            }
        }

    override suspend fun pull(path: String, output: OutputStream, progress: (Long) -> Unit) =
        service("sync:") { reader, writer -> AdbFileSync(reader, writer).receive(path, output, progress) }

    override suspend fun push(path: String, input: InputStream, size: Long?, progress: (Long) -> Unit) =
        service("sync:") { reader, writer -> AdbFileSync(reader, writer).send(path, input, size, progress) }
    override suspend fun screenshot(output: OutputStream) =
        service("shell,v2,raw:sh -s") { reader, writer -> shellScreenshot(reader, writer, output) }

    /** Bind the official mirror backend to the peer just authenticated on the native data path. */
    suspend fun verifyMirrorTransport() {
        if (wireless == 0L) return
        val path = "/data/local/tmp/rprobe-mirror-" + java.util.UUID.randomUUID()
        val nonce = java.util.UUID.randomUUID().toString()
        try {
            checked("umask 077; set -C; printf %s '$nonce' > '$path'")
            val reply = compatibilityService("shell,v2,raw:sh -s") { input, output ->
                shellResult(input, output, "cat '$path'", null)
            }
            if (reply.exitCode != 0 || reply.stdout != nonce)
                throw PeerRejected("镜像后端无法核对当前设备身份")
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                kotlinx.coroutines.withTimeoutOrNull(3000) { runCatching { shell("rm -f '$path'") } }
            }
        }
    }

    @Synchronized
    private fun invalidate(reason: String) {
        if (closed) return
        closeReason = reason
        closed = true
        streams.forEach { runCatching { it.close() } }
        streams.clear()
        if (wireless != 0L) NativeCore.directClose(wireless)
        NativeCore.closeTracker(tracker)
        disconnected.complete(Unit)
    }
    override fun close() = invalidate("设备会话已关闭")
}

internal suspend fun connectWindows(adb: String, address: String, port: Int): WindowsSession = withTimeout(30000) {
    val endpoint = targetAddress(address, port)
    val host = ProcessGroup()
    try {
        val reply = host.text(listOf(adb, "connect", endpoint))
        val result = (reply.stdout + "\n" + reply.stderr).trim()
        if (result.contains("authenticate", ignoreCase = true) || result.contains("unauthorized", ignoreCase = true))
            throw PeerRejected(result)
        if (reply.exitCode != 0 || !(result.startsWith("connected to ") || result.startsWith("already connected to ")))
            throw IOException(result.ifBlank { "设备暂时不可达" })
        // Return ownership to the caller or close the subscription if cancellation wins delivery.
        suspendCancellableCoroutine { pending ->
            processIo.execute {
                try {
                    val session = WindowsSession(adb, endpoint, NativeCore.track(endpoint))
                    pending.resume(session) { _, unclaimed, _ -> unclaimed.close() }
                } catch (e: Exception) {
                    if (pending.isActive) pending.resumeWith(Result.failure(e))
                }
            }
        }
    } finally { host.close() }
}

internal fun windowsDiscoveredDevices(services:String,devices:String):List<DiscoveredDevice> {
    val found=mutableListOf<DiscoveredDevice>()
    fun append(name:String,address:String,type:String) {
        val port=address.substringAfterLast(':',"").toIntOrNull() ?: return
        discoveredDevice(name,address.substringBeforeLast(':'),port,type)?.let(found::add)
    }
    for(line in services.lineSequence()) {
        val parts=line.trim().split(Regex("""\s+"""))
        if(parts.size>=3) append(parts.dropLast(2).joinToString(" "),parts.last(),parts[parts.lastIndex-1].trimEnd('.'))
    }
    for(line in devices.lineSequence()) {
        val parts=line.trim().split(Regex("""\s+"""))
        if(parts.size==2 && parts[1]=="device") append("",parts[0],"")
    }
    return found.distinctBy { it.address to it.pairing }
}
