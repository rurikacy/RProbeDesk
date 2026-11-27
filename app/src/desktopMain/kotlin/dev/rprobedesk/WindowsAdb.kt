package dev.rprobedesk

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

import java.io.File
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

import java.util.concurrent.atomic.AtomicReference


private fun adbClient(args: List<String>) = WindowsAdbServer.forTool(args.first()).client(args)

/** One owner for every subprocess, including streaming operations waiting for output. */
internal class ProcessGroup(private val client: (List<String>) -> ProcessBuilder = ::adbClient) {
    private val children = mutableSetOf<Process>()
    private var closed = false

    private fun start(args: List<String>): Process {
        synchronized(this) { if (closed) throw TransportFailure(6, "设备会话已关闭") }
        val process = client(args).start()
        synchronized(this) {
            if (!closed) { children.add(process); return process }
        }
        process.destroyForcibly()
        throw TransportFailure(6, "设备会话已关闭")
    }

    suspend fun <T> use(args: List<String>, block: (Process) -> T): T =
        suspendCancellableCoroutine { pending ->
            val active = AtomicReference<Process?>()
            pending.invokeOnCancellation { active.get()?.destroyForcibly() }
            WindowsIo.processes.execute {
                if (!pending.isActive) return@execute
                val result = runCatching {
                    val process = start(args)
                    WindowsIo.children.incrementAndGet()
                    active.set(process)
                    try {
                        check(pending.isActive) { "操作已取消" }
                        block(process)
                    } finally {
                        process.destroyForcibly()
                        synchronized(this) { children.remove(process) }
                        active.set(null)
                        WindowsIo.children.decrementAndGet()
                    }
                }
                if (pending.isActive) pending.resumeWith(result)
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
    val err = WindowsIo.pipes.submit<String> { readText(process.errorStream, onLine?.let { { s -> it(s, true) } }) }
    val out = WindowsIo.pipes.submit<String> { readText(process.inputStream, onLine?.let { { s -> it(s, false) } }) }
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

internal class AdbBinding(val server: WindowsAdbServer, val tracker: Long, private val release: () -> Unit) {
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    val transportId = NativeCore.transportId(tracker)
    val ended = CompletableDeferred<Unit>()
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { NativeCore.closeTracker(tracker) } finally { release(); ended.complete(Unit) }
    }
}

internal class WindowsSession(val adb: String, override val serial: String) : DeviceSession, SessionHealth, LogSource {
    private val bindingLock = kotlinx.coroutines.sync.Mutex()
    @Volatile private var binding: AdbBinding? = null
    val transportId get() = binding?.transportId?.toString() ?: "STLS"
    private val streams = mutableSetOf<Socket>()
    private val commands = Semaphore(6)
    private val streaming = Semaphore(3)
    init { WindowsIo.sessions.incrementAndGet() }
    private var closed = false
    private var closeReason = "设备会话已关闭"
    @Volatile private var wireless = 0L
    override val transportFailure = CompletableDeferred<IOException>()
    override var verifiedPeerKey: String? = null
        private set

    @Synchronized fun attachWireless(handle: Long) {
        try {
            check(!closed && wireless == 0L) { closeReason }
            verifiedPeerKey = NativeCore.directPeer(handle)
            wireless = handle
            NativeCore.onTransportEnd(handle) { code, reason -> invalidate(TransportFailure(code, reason)) }
        } catch(e: Throwable) { NativeCore.directClose(handle); throw e }
    }
    suspend fun compatibility(): AdbBinding = bindingLock.withLock {
        synchronized(this) {
            if (closed) throw IOException(closeReason)
            binding?.let { return@withLock it }
        }
        val new = openBinding(adb, serial)
        synchronized(this) {
            if (closed) { new.close(); throw IOException(closeReason) }
            binding = new
        }
        try { WindowsIo.trackers.execute {
            val failure = try { TransportFailure(TransportError.Closed.code, NativeCore.waitForDisconnect(new.tracker)) }
                catch (e: IOException) { e }
            val terminate = synchronized(this) {
                if (binding !== new) return@execute
                binding = null
                wireless == 0L || failure is PeerRejected
            }
            new.close()
            if (terminate) invalidate(failure)
        } } catch (e: TransportFailure) {
            synchronized(this) { if (binding === new) binding = null }
            new.close()
            throw e
        }
        new
    }

    override suspend fun probe(): ShellResult {
        val name = "shell,v2,raw:sh -s"
        val block = { input: InputStream, output: OutputStream -> shellResult(input, output, "echo RProbeDesk", null) }
        val handle = wireless
        return if (handle == 0L) compatibilityService(compatibility(), name, 10_000, WindowsIo.control, block)
        else wirelessService(handle, name, 10_000, WindowsIo.control, block)
    }

    override suspend fun collectLog(command: String, append: (ByteArray, Int) -> Unit) =
        service("shell,v2,raw:sh -s", 0) { reader, writer -> rawLogResult(reader, writer, command, append) }

    override suspend fun shell(command: String, onLine: ((String, Boolean) -> Unit)?): ShellResult {
        // Keep the script on its own descriptor. Commands reading stdin must receive EOF.
        val idleMs = if (onLine != null && command.startsWith("logcat ")) 0 else 60_000
        return service("shell,v2,raw:cat | sh /proc/self/fd/3 3<&0 </dev/null", idleMs) { input, output ->
            shellResult(input, output, command, onLine)
        }
    }

    private suspend fun <T> service(name: String, idleMs: Int = 60_000, block: (InputStream, OutputStream) -> T): T {
        val stream = idleMs == 0 || name == "sync:"
        val slots = if (stream) streaming else commands
        if (!slots.tryAcquire()) WindowsIo.exhausted("设备 I/O")
        val lane = if (stream) WindowsIo.streams else WindowsIo.short
        WindowsIo.channels.incrementAndGet()
        try {
            val handle = wireless
            return if (handle == 0L) compatibilityService(compatibility(), name, idleMs, lane, block) else wirelessService(handle, name, idleMs, lane, block)
        } finally { WindowsIo.channels.decrementAndGet(); slots.release() }
    }

    private suspend fun <T> compatibilityService(link: AdbBinding, name: String, idleMs: Int, lane: WindowsIo.Lane = WindowsIo.control, block: (InputStream, OutputStream) -> T): T =
        suspendCancellableCoroutine { pending ->
            val socket = Socket()
            pending.invokeOnCancellation { socket.close() }
            lane.execute {
                val result = runCatching {
                    socket.use {
                        synchronized(this) {
                            check(!closed && pending.isActive) { closeReason }
                            streams.add(socket)
                        }
                        socket.connect(InetSocketAddress("127.0.0.1", link.server.port), 10000)
                        socket.soTimeout = 10000
                        val input = DataInputStream(socket.getInputStream())
                        val out = socket.getOutputStream()
                        fun text(count: Int) = ByteArray(count).also(input::readFully).toString(Charsets.UTF_8)
                        for (request in listOf("host:transport-id:${link.transportId}", name)) {
                            val data = request.toByteArray(Charsets.US_ASCII)
                            out.write(data.size.toString(16).padStart(4, '0').toByteArray(Charsets.US_ASCII))
                            out.write(data)
                            when (text(4)) {
                                "OKAY" -> Unit
                                "FAIL" -> throw IOException(text(text(4).toInt(16)))
                                else -> throw TransportFailure(TransportError.Protocol.code, "ADB server 返回无效响应")
                            }
                        }
                        socket.soTimeout = idleMs
                        block(input, out)
                    }
                }
                synchronized(this) { streams.remove(socket) }
                if (pending.isActive) pending.resumeWith(result)
            }
        }

    override suspend fun pull(path: String, output: OutputStream, progress: (Long) -> Unit) =
        transferWithProgress(progress) { update ->
            service("sync:") { reader, writer -> AdbFileSync(reader, writer).receive(path, output, update) }
        }

    override suspend fun push(path: String, input: InputStream, size: Long?, progress: (Long) -> Unit) =
        transferWithProgress(progress) { update ->
            service("sync:") { reader, writer -> AdbFileSync(reader, writer).send(path, input, size, update) }
        }
    override suspend fun screenshot(output: OutputStream) =
        service("shell,v2,raw:sh -s") { reader, writer -> shellScreenshot(reader, writer, output) }

    /** Bind the official mirror backend to the peer just authenticated on the native data path. */
    suspend fun verifyMirrorTransport(): AdbBinding {
        val link = compatibility()
        if (wireless == 0L) return link
        val path = "/data/local/tmp/rprobe-mirror-" + java.util.UUID.randomUUID()
        val nonce = java.util.UUID.randomUUID().toString()
        try {
            checked("umask 077; set -C; printf %s '$nonce' > '$path'")
            val reply = compatibilityService(link, "shell,v2,raw:sh -s", 10_000) { input, output ->
                shellResult(input, output, "cat '$path'", null)
            }
            if (reply.exitCode != 0 || reply.stdout != nonce)
                throw PeerRejected("镜像后端无法核对当前设备身份")
            return link
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                kotlinx.coroutines.withTimeoutOrNull(3000) { runCatching { shell("rm -f '$path'") } }
            }
        }
    }

    private fun invalidate(failure: IOException) {
        val sockets: List<Socket>
        val handle: Long
        val official: AdbBinding?
        synchronized(this) {
            if (closed) return
            closed = true
            WindowsIo.sessions.decrementAndGet()
            closeReason = failure.localizedMessage ?: "设备会话已关闭"
            sockets = streams.toList()
            streams.clear()
            handle = wireless
            wireless = 0
            official = binding
            binding = null
        }
        sockets.forEach { runCatching { it.close() } }
        if (handle != 0L) NativeCore.directClose(handle)
        official?.close()
        transportFailure.complete(failure)
    }
    override fun close() = invalidate(IOException("设备会话已关闭"))
}

internal suspend fun connectWindows(adb: String, address: String, port: Int): WindowsSession {
    val session = WindowsSession(adb, targetAddress(address, port))
    try { session.compatibility(); return session } catch (e: Throwable) { session.close(); throw e }
}

private suspend fun openBinding(adb: String, endpoint: String): AdbBinding = withTimeout(30000) {
    val server = WindowsIo.control.run { WindowsAdbServer.forTool(adb) }
    val owner = server.retain(endpoint)
    val released = java.util.concurrent.atomic.AtomicBoolean()
    val release = { if (released.compareAndSet(false, true)) server.release(adb, endpoint, owner) }
    try {
        owner.operations.withLock {
            val host = ProcessGroup(server::client)
            try {
                val reply = host.text(listOf(adb, "connect", endpoint))
                if (reply.exitCode != 0) throw IOException((reply.stdout + "\n" + reply.stderr).trim().ifBlank { "设备暂时不可达" })
            } finally { host.close() }
            // Track the exact server incarnation. If caller cancellation wins delivery,
            // release both the native tracker and this claim on the server transport.
            suspendCancellableCoroutine { pending ->
                WindowsIo.control.execute {
                    try {
                        val link = AdbBinding(server, NativeCore.track(endpoint, server.port), release)
                        pending.resume(link) { _, unclaimed, _ -> unclaimed.close() }
                    } catch (e: Exception) { if (pending.isActive) pending.resumeWith(Result.failure(e)) }
                }
            }
        }
    } catch (e: Throwable) { release(); throw e }
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
    return mergeDiscovery(listOf(found))
}
