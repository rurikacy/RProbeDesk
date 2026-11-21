package dev.rprobedesk

import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.AdbStream
import java.io.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

internal class AndroidSession(
    override val serial: String,
    private val connection: AdbConnection,
    override val verifiedPeerKey: String?,
) : DeviceSession {
    private val closed = AtomicBoolean()

    @Synchronized
    fun open(service: String): AdbStream {
        check(!closed.get() && connection.isConnectionEstablished) { "设备连接已关闭" }
        return connection.open(service)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) connection.close()
    }

    override suspend fun shell(command: String, onLine: ((String, Boolean) -> Unit)?): ShellResult =
        runInterruptible(Dispatchers.IO) {
            val out = ShellText { onLine?.invoke(it, false) }
            val err = ShellText { onLine?.invoke(it, true) }
            val exit = shellPackets(command, out::append, err::append)
            ShellResult(out.finish(), err.finish(), exit)
        }

    private fun shellPackets(command: String, stdout: (ByteArray) -> Unit, stderr: (ByteArray) -> Unit): Int {
        require('\u0000' !in command)
        open("shell,v2,raw:sh -s").use { channel ->
            // Keep OPEN ASCII. libadb 3.1.1 sizes its OPEN packet by character count, not UTF-8 byte count.
            val script = (command + "\n").toByteArray(Charsets.UTF_8)
            val packet = java.nio.ByteBuffer.allocate(script.size + 10).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .put(0).putInt(script.size).put(script).put(4).putInt(0).array()
            val out = channel.openOutputStream()
            var offset = 0
            while (offset < packet.size) {
                val n = minOf(32768, packet.size - offset)
                out.write(packet, offset, n)
                offset += n
            }
            out.flush()
            val input = DataInputStream(channel.openInputStream().buffered())
            while (true) {
                val type = input.readUnsignedByte()
                val count = Integer.reverseBytes(input.readInt())
                require(count in 0..1_048_576) { "目标返回了无效的 Shell 数据" }
                val bytes = ByteArray(count)
                input.readFully(bytes)
                when (type) {
                    1 -> stdout(bytes)
                    2 -> stderr(bytes)
                    3 -> {
                        require(count == 1) { "缺少 Shell 退出状态" }
                        return bytes[0].toInt() and 255
                    }
                }
            }
        }
    }

    override suspend fun screenshot(output: OutputStream) = runInterruptible(Dispatchers.IO) {
        val err = ShellText {}
        val code = shellPackets("screencap -p", output::write, err::append)
        check(code == 0) { err.finish().ifBlank { "截图失败 ($code)" } }
    }

    override suspend fun pull(path: String, output: OutputStream, progress: (Long) -> Unit) =
        runInterruptible(Dispatchers.IO) {
            open("sync:").use { channel ->
                AdbFileSync(channel.openInputStream(), channel.openOutputStream()).receive(path, output, progress)
            }
        }

    override suspend fun push(path: String, input: InputStream, size: Long?, progress: (Long) -> Unit) =
        runInterruptible(Dispatchers.IO) {
            open("sync:").use { channel ->
                AdbFileSync(channel.openInputStream(), channel.openOutputStream()).send(path, input, size, progress)
            }
        }




}

private class ShellText(private val line: (String) -> Unit) {
    private val text = ByteArrayOutputStream()
    private val pending = ByteArrayOutputStream()
    private var truncated = false

    fun append(bytes: ByteArray) {
        val keep = minOf(bytes.size, 2_097_152 - text.size())
        text.write(bytes, 0, keep)
        if (keep < bytes.size) truncated = true
        for (b in bytes) {
            if (b == 10.toByte()) emit() else {
                pending.write(b.toInt())
                if (pending.size() >= 65536) emit()
            }
        }
    }
    private fun emit() {
        line(pending.toString("UTF-8").removeSuffix("\r"))
        pending.reset()
    }
    fun finish(): String {
        if (pending.size() > 0) emit()
        return text.toString("UTF-8") + if (truncated) "\n[输出已截断]" else ""
    }
}
