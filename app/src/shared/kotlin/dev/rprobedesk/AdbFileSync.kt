package dev.rprobedesk

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

internal class AdbFileSync(private val input: InputStream, private val output: OutputStream) {
    fun receive(path: String, destination: OutputStream, progress: (Long) -> Unit) {
        val streams = SyncStreams(input, output, path, null, destination, progress)
        NativeCore.transfer(streams, false, streams.nameLength, -1)
    }

    fun send(path: String, source: InputStream, size: Long?, progress: (Long) -> Unit) {
        // 0100600, a private regular file.
        val streams = SyncStreams(input, output, "$path,33152", source, null, progress)
        NativeCore.transfer(streams, true, streams.nameLength, size ?: -1)
    }
}

/** Platform streams (including Android SAF) remain on the JVM. Rust drives the wire protocol. */
internal class SyncStreams(
    input: InputStream, output: OutputStream, name: String,
    private val source: InputStream?, private val destination: OutputStream?,
    private val progress: (Long) -> Unit,
) {
    private val reader = DataInputStream(input.buffered())
    private val writer = DataOutputStream(output.buffered())
    private val nameBytes = name.toByteArray(Charsets.UTF_8)
    val nameLength get() = nameBytes.size
    private val chunk = ByteArray(65536)
    private var request = true
    private var total = 0L

    fun readHeader(): Long = reader.readLong()
    fun readSource(): Int = checkNotNull(source).read(chunk, 0, 32768)
    fun failureMessage(count: Int): String {
        reader.readFully(chunk, 0, count)
        return String(chunk, 0, count, Charsets.UTF_8)
    }

    fun writePacket(header: Long, payload: Boolean) {
        val size = Integer.reverseBytes(header.toInt())
        writer.writeLong(header)
        if (payload) writer.write(if (request) nameBytes else chunk, 0, size)
        writer.flush()
        if (payload && !request) advanced(size)
        request = false
    }

    fun receiveChunk(count: Int) {
        reader.readFully(chunk, 0, count)
        checkNotNull(destination).write(chunk, 0, count)
        advanced(count)
    }

    private fun advanced(count: Int) {
        total += count
        progress(total)
    }
}
