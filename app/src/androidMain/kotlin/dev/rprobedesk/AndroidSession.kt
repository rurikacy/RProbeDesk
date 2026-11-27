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
            open("shell,v2,raw:sh -s").use { channel ->
                shellResult(channel.openInputStream(), channel.openOutputStream(), command, onLine)
            }
        }

    override suspend fun screenshot(output: OutputStream) = runInterruptible(Dispatchers.IO) {
        open("shell,v2,raw:sh -s").use { channel ->
            shellScreenshot(channel.openInputStream(), channel.openOutputStream(), output)
        }
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
