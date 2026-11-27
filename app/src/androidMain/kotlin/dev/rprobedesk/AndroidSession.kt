package dev.rprobedesk

import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.AdbStream
import java.io.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.runInterruptible

internal class AndroidSession(
    override val serial: String,
    private val connection: AdbConnection,
    override val verifiedPeerKey: String?,
) : DeviceSession, SessionHealth, LogSource {
    private val closed = AtomicBoolean()
    override val transportAlive get() = !closed.get() && connection.isConnectionEstablished

    @Synchronized
    fun open(service: String): AdbStream {
        check(!closed.get() && connection.isConnectionEstablished) { "设备连接已关闭" }
        return connection.open(service)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) connection.close()
    }

    private val slots = kotlinx.coroutines.sync.Semaphore(16)
    private suspend fun <T> service(name: String, block: (InputStream, OutputStream) -> T): T = slots.withPermit {
        kotlinx.coroutines.coroutineScope {
            val channel = java.util.concurrent.atomic.AtomicReference<AdbStream?>()
            val reader = async(Dispatchers.IO) {
                val context = currentCoroutineContext()
                runInterruptible {
                    open(name).use { stream ->
                        channel.set(stream)
                        try {
                            context.ensureActive()
                            block(stream.openInputStream(), stream.openOutputStream())
                        } finally { channel.compareAndSet(stream, null) }
                    }
                }
            }
            try { reader.await() } finally {
                if (!reader.isCompleted) {
                    reader.cancel()
                    kotlinx.coroutines.withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                        channel.getAndSet(null)?.close()
                    }
                }
            }
        }
    }

    override suspend fun collectLog(command: String, append: (ByteArray, Int) -> Unit) =
        service("shell,v2,raw:sh -s") { input, output -> rawLogResult(input, output, command, append) }

    override suspend fun shell(command: String, onLine: ((String, Boolean) -> Unit)?): ShellResult =
        service("shell,v2,raw:sh -s") { input, output -> shellResult(input, output, command, onLine) }

    override suspend fun screenshot(output: OutputStream) =
        service("shell,v2,raw:sh -s") { reader, writer -> shellScreenshot(reader, writer, output) }

    override suspend fun pull(path: String, output: OutputStream, progress: (Long) -> Unit) =
        transferWithProgress(progress) { update ->
            service("sync:") { reader, writer -> AdbFileSync(reader, writer).receive(path, output, update) }
        }

    override suspend fun push(path: String, input: InputStream, size: Long?, progress: (Long) -> Unit) =
        transferWithProgress(progress) { update ->
            service("sync:") { reader, writer -> AdbFileSync(reader, writer).send(path, input, size, update) }
        }
}
