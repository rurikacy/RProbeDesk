package dev.rprobedesk

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private interface AddressCallback : StdCallLibrary.StdCallCallback {
    fun invoke(context: Pointer?, row: Pointer?, change: Int)
}
private interface TcpTable : StdCallLibrary {
    fun NotifyUnicastIpAddressChange(family: Short, callback: AddressCallback, context: Pointer?, initial: Byte,
                                     handle: com.sun.jna.ptr.PointerByReference): Int
    fun CancelMibChangeNotify2(handle: Pointer): Int
    fun GetExtendedTcpTable(table: Pointer?, size: IntByReference, order: Int, family: Int, kind: Int, reserved: Int): Int
}

/** TCP_TABLE_OWNER_PID_LISTENER gives us the port atomically allocated by adb -L tcp:0. */
private val tcpTable by lazy { Native.load("iphlpapi", TcpTable::class.java) }
// Windows invokes this callback on changes to Wi-Fi, Ethernet or VPN addresses.
internal object WindowsNetworks {
    private val callback = object : AddressCallback {
        override fun invoke(context: Pointer?, row: Pointer?, change: Int) = NativeCore.networkChanged()
    }
    private val registration by lazy {
        val handle = com.sun.jna.ptr.PointerByReference()
        if (tcpTable.NotifyUnicastIpAddressChange(0, callback, null, 0, handle) == 0) {
            Runtime.getRuntime().addShutdownHook(Thread { tcpTable.CancelMibChangeNotify2(handle.value) })
        }
    }
    fun observe() { registration }
}
private fun acceptsAdb(port: Int): Boolean = try {
    java.net.Socket().use { socket ->
        socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 250)
        socket.soTimeout = 400
        socket.getOutputStream().write("000chost:version".toByteArray(Charsets.US_ASCII))
        val reply = ByteArray(12)
        java.io.DataInputStream(socket.getInputStream()).readFully(reply)
        val text = reply.toString(Charsets.US_ASCII)
        text.startsWith("OKAY0004") && text.substring(8).toIntOrNull(16) != null
    }
} catch (_: IOException) { false }

private fun listener(pid: Long): Int? {
    val size = IntByReference()
    val first = tcpTable.GetExtendedTcpTable(null, size, 0, 2, 3, 0)
    if (first != 122 || size.value < 4) throw IOException("无法读取本地 TCP 监听端口 ($first)")
    val buffer = Memory(size.value.toLong())
    when (val rc = tcpTable.GetExtendedTcpTable(buffer, size, 0, 2, 3, 0)) {
        122 -> return null // The table grew between the two calls.
        0 -> Unit
        else -> throw IOException("无法读取本地 TCP 监听端口 ($rc)")
    }
    for (i in 0 until buffer.getInt(0)) {
        val row = 4L + i * 24L
        if (buffer.getInt(row + 20).toLong() == pid && buffer.getInt(row + 4) == 0x0100007f) {
            val port = java.lang.Short.reverseBytes(buffer.getShort(row + 8)).toInt() and 0xffff
            if (acceptsAdb(port)) return port
        }
    }
    return null
}

internal fun ProcessBuilder.toolDirectory(exe: File): ProcessBuilder = apply {
    directory(exe.absoluteFile.parentFile)
    val windows = File(System.getenv("SystemRoot") ?: "C:/Windows")
    environment()["PATH"] = listOf(exe.absoluteFile.parentFile, windows.resolve("System32"), windows).joinToString(File.pathSeparator)
}

internal class WindowsAdbServer private constructor(val port: Int, private val child: Process) : AutoCloseable {
    override fun close() { child.destroyForcibly() }
    internal class Claim(val operations: kotlinx.coroutines.sync.Mutex = kotlinx.coroutines.sync.Mutex(), var users: Int = 0)
    private val endpoints = mutableMapOf<String, Claim>()
    private val cleanup = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    fun retain(endpoint: String) = synchronized(endpoints) {
        endpoints.getOrPut(endpoint, ::Claim).also { it.users++ }
    }
    fun release(adb: String, endpoint: String, claim: Claim) {
        cleanup.launch {
            claim.operations.withLock {
                if (synchronized(endpoints) { --claim.users != 0 }) return@withLock
                val group = ProcessGroup(::client)
                try {
                    if (child.isAlive) kotlinx.coroutines.withTimeoutOrNull(3000) { group.text(listOf(adb, "disconnect", endpoint)) }
                } catch (_: IOException) { /* A dead private server already released its transports. */ }
                finally {
                    group.close()
                    synchronized(endpoints) { if (claim.users == 0) endpoints.remove(endpoint, claim) }
                }
            }
        }
    }
    fun client(args: List<String>) = ProcessBuilder(args).apply {
        if (!child.isAlive) throw TransportFailure(TransportError.Backend.code, "应用专用 ADB 服务已退出")
        environment()["ADB_SERVER_SOCKET"] = "tcp:127.0.0.1:$port"
        toolDirectory(File(args.first()))
    }

    companion object {
        private val servers = ConcurrentHashMap<File, WindowsAdbServer>()
        init {
            Runtime.getRuntime().addShutdownHook(Thread {
                servers.values.forEach { it.close() }
            })
        }

        fun forTool(path: String): WindowsAdbServer {
            val exe = File(path).canonicalFile
            return servers.compute(exe) { _, previous ->
                if (previous != null && previous.child.isAlive) previous else start(exe)
            }!!
        }

        internal fun probe(exe: File) {
            start(exe).use { server ->
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", server.port), 1000)
                    socket.soTimeout = 1500
                    val request = "host:track-devices-l"
                    socket.getOutputStream().write((request.length.toString(16).padStart(4, '0') + request).toByteArray(Charsets.US_ASCII))
                    val input = java.io.DataInputStream(socket.getInputStream())
                    val status = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                    if (status != "OKAY") throw IOException("ADB 缺少 host:track-devices-l 能力")
                    val size = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII).toIntOrNull(16)
                        ?: throw IOException("ADB 设备订阅返回了无效帧")
                    input.readFully(ByteArray(size))
                }
            }
        }

        internal fun start(exe: File): WindowsAdbServer {
            val child = ProcessBuilder(exe.path, "-L", "tcp:0", "--one-device", "rprobedesk-network-only", "nodaemon", "server").apply {
                toolDirectory(exe)
                environment().remove("ADB_SERVER_SOCKET")
                environment()["ADB_MDNS_AUTO_CONNECT"] = "0"
                environment()["ADB_LOCAL_TRANSPORT_MAX_PORT"] = "0"
                redirectOutput(ProcessBuilder.Redirect.DISCARD)
                redirectError(ProcessBuilder.Redirect.DISCARD)
            }.start().let(WindowsIo::trackChild)
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (child.isAlive && System.nanoTime() < deadline) {
                    listener(child.pid())?.let { return WindowsAdbServer(it, child) }
                    child.waitFor(25, TimeUnit.MILLISECONDS)
                }
                throw IOException("专用 ADB 服务启动失败，请检查工具版本")
            } catch (error: Throwable) {
                child.destroyForcibly()
                throw error
            }
        }
    }
}
