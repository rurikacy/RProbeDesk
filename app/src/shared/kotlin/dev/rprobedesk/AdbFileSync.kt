package dev.rprobedesk

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** AOSP sync v1 over an already authenticated device stream. All payloads stay binary. */
internal class AdbFileSync(input: InputStream, private val writer: OutputStream) {
    private val reader = DataInputStream(input.buffered())

    fun receive(path: String, destination: OutputStream, progress: (Long) -> Unit) {
        packet("RECV", path.toByteArray(Charsets.UTF_8))
        val buffer = ByteArray(65536)
        var received = 0L
        while (true) {
            val (type, size) = header()
            when (type) {
                "DONE" -> return
                "DATA" -> {
                    require(size in 0..buffer.size) { "无效的文件数据长度" }
                    reader.readFully(buffer, 0, size)
                    destination.write(buffer, 0, size)
                    received += size
                    progress(received)
                }
                else -> failure(type, size)
            }
        }
    }

    fun send(path: String, source: InputStream, size: Long?, progress: (Long) -> Unit) {
        packet("SEND", "$path,33152".toByteArray(Charsets.UTF_8)) // 0100600, private regular file
        val bytes = ByteArray(32768)
        var total = 0L
        while (true) {
            val count = source.read(bytes)
            if (count < 0) break
            if (count == 0) continue
            packet("DATA", bytes, count)
            total += count
            progress(total)
        }
        check(size == null || total == size) { "本机源文件长度已变化" }
        writeHeader("DONE", (System.currentTimeMillis() / 1000).toInt())
        writer.flush()
        val (type, length) = header()
        if (type != "OKAY") failure(type, length)
    }

    private fun packet(type: String, bytes: ByteArray, count: Int = bytes.size) {
        writeHeader(type, count)
        writer.write(bytes, 0, count)
        writer.flush()
    }
    private fun writeHeader(type: String, value: Int) {
        writer.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put(type.toByteArray(Charsets.US_ASCII)).putInt(value).array())
    }
    private fun header(): Pair<String, Int> {
        val kind = ByteArray(4)
        reader.readFully(kind)
        return String(kind, Charsets.US_ASCII) to Integer.reverseBytes(reader.readInt())
    }
    private fun failure(type: String, size: Int): Nothing {
        if (type == "FAIL" && size in 0..65536) {
            val message = ByteArray(size)
            reader.readFully(message)
            throw IOException(String(message, Charsets.UTF_8))
        }
        throw IOException("未知的文件同步响应 $type")
    }
}
