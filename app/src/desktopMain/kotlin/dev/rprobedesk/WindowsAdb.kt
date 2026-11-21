package dev.rprobedesk

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
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

internal fun localAdbProcess(args: List<String>) = ProcessBuilder(args).apply {
    environment()["ADB_SERVER_SOCKET"] = "tcp:127.0.0.1:5037"
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

    suspend fun <T> use(args: List<String>, block: (Process) -> T): T =
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

private fun InputStream.transfer(out: OutputStream, progress: (Long) -> Unit) {
    val chunk = ByteArray(64 * 1024)
    var count = 0L
    while (true) {
        val n = read(chunk)
        if (n < 0) break
        out.write(chunk, 0, n)
        count += n
        progress(count)
    }
    out.flush()
}

internal class WindowsSession(val adb: String, override val serial: String, val transportId: String) : DeviceSession {
    private val children = ProcessGroup()
    private val target = listOf(adb, "-t", transportId)
    private val streams = mutableSetOf<Socket>()
    private var closed = false
    // A transport ID belongs to one adb server lifetime. Its waiter also exits if that server goes away.
    private val disconnect = localAdbProcess(target + "wait-for-disconnect").start().also { waiter ->
        waiter.outputStream.close()
        waiter.onExit().thenRun { invalidate() }
    }

    override suspend fun shell(command: String, onLine: ((String, Boolean) -> Unit)?): ShellResult {
        require('\u0000' !in command) { "无效字符" }
        // A pipe gives sh a script descriptor independent of its /dev/null stdin, without Windows argv quoting or size limits.
        return children.text(target + listOf("shell", "-T", "cat | sh /proc/self/fd/3 3<&0 </dev/null"), command + "\n", onLine)
    }

    private suspend fun sync(block: (AdbFileSync) -> Unit) = suspendCancellableCoroutine<Unit> { pending ->
        val socket = Socket()
        pending.invokeOnCancellation { socket.close() }
        processIo.execute {
            val result = runCatching {
                socket.use {
                    synchronized(this) {
                        check(!closed && pending.isActive) { "设备会话已关闭" }
                        streams.add(socket)
                    }
                    socket.connect(InetSocketAddress("127.0.0.1", 5037), 10000)
                    val input = DataInputStream(socket.getInputStream())
                    val out = socket.getOutputStream()
                    fun text(count: Int) = ByteArray(count).also(input::readFully).toString(Charsets.UTF_8)
                    for (request in listOf("host:transport-id:$transportId", "sync:")) {
                        val data = request.toByteArray(Charsets.US_ASCII)
                        out.write(data.size.toString(16).padStart(4, '0').toByteArray(Charsets.US_ASCII))
                        out.write(data)
                        when (text(4)) {
                            "OKAY" -> Unit
                            "FAIL" -> throw IOException(text(text(4).toInt(16)))
                            else -> throw IOException("ADB server 返回无效响应")
                        }
                    }
                    block(AdbFileSync(input, out))
                }
            }
            synchronized(this) { streams.remove(socket) }
            if (pending.isActive) pending.resumeWith(result)
        }
    }

    override suspend fun pull(path: String, output: OutputStream, progress: (Long) -> Unit) =
        sync { it.receive(path, output, progress) }

    override suspend fun push(path: String, input: InputStream, size: Long?, progress: (Long) -> Unit) =
        sync { it.send(path, input, size, progress) }



    override suspend fun screenshot(output: OutputStream) {
        children.use(target + listOf("exec-out", "screencap", "-p")) { process ->
            val err = processIo.submit<String> { readText(process.errorStream, null) }
            process.outputStream.close()
            process.inputStream.use { it.transfer(output) {} }
            check(process.waitFor() == 0) { err.get().trim().ifEmpty { "设备截图失败" } }
        }
    }
    @Synchronized
    private fun invalidate() {
        closed = true
        streams.forEach { runCatching { it.close() } }
        streams.clear()
        children.close()
    }
    override fun close() {
        invalidate()
        disconnect.destroyForcibly()
    }
}

internal suspend fun connectWindows(adb: String, address: String, port: Int): WindowsSession = withTimeout(30000) {
    val endpoint = targetAddress(address, port)
    val host = ProcessGroup()
    try {
        val result = host.text(listOf(adb, "connect", endpoint)).checked()
        check(result.startsWith("connected to ") || result.startsWith("already connected to ")) { result }
        val devices = host.text(listOf(adb, "devices", "-l")).checked()
        val fields = devices.lineSequence().map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size >= 2 && it.first() == endpoint }
            ?: error("没有找到刚连接的设备，请核对无线调试主页的连接端口")
        check(fields[1] == "device") { "设备状态为 ${fields[1]}，请在目标上检查授权" }
        val id = fields.firstOrNull { it.startsWith("transport_id:") }?.substringAfter(':')
        check(id != null && id.toLongOrNull() != null) { "需要支持 transport ID 的 Android Platform-Tools" }
        WindowsSession(adb, endpoint, id)
    } finally { host.close() }
}
