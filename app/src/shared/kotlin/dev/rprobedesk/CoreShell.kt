package dev.rprobedesk

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

internal class ShellStreams(
    input: InputStream, output: OutputStream, command: String,
    private val stdout: (ByteArray, Int) -> Unit, private val stderr: (ByteArray, Int) -> Unit,
) {
    private val script = (command + "\n").toByteArray(Charsets.UTF_8)
    private val reader = DataInputStream(input.buffered())
    private val writer = DataOutputStream(output.buffered())
    private val chunk = ByteArray(32768)

    init { require('\u0000' !in command) { "无效字符" } }

    fun run() = NativeCore.shell(this, script.size)
    fun writeInput(kind: Int, offset: Int, count: Int) {
        writer.writeByte(kind)
        writer.writeInt(Integer.reverseBytes(count))
        if (count > 0) writer.write(script, offset, count)
        if (kind == 4) writer.flush()
    }
    fun readHeader(): Long {
        val kind = reader.readUnsignedByte().toLong()
        return (kind shl 32) or Integer.toUnsignedLong(Integer.reverseBytes(reader.readInt()))
    }
    fun exitCode() = reader.readUnsignedByte()
    fun consume(kind: Int, count: Int) {
        reader.readFully(chunk, 0, count)
        when (kind) {
            1 -> stdout(chunk, count)
            2 -> stderr(chunk, count)
        }
    }
}

internal class ShellText(private val line: ((String) -> Unit)? = null) {
    private val text = ByteArrayOutputStream()
    private val pending = ByteArrayOutputStream()
    private var truncated = false

    fun append(bytes: ByteArray, count: Int) {
        val keep = minOf(count, 2_097_152 - text.size())
        text.write(bytes, 0, keep)
        if (keep < count) truncated = true
        if (line == null) return
        var start = 0
        while (start < count) {
            val end = minOf(count, start + 65536 - pending.size())
            var pos = start
            while (pos < end && bytes[pos] != 10.toByte()) pos++
            pending.write(bytes, start, pos-start)
            if (pos < end) { emit(); start = pos + 1 }
            else { if (pending.size() == 65536) emit(); start = end }
        }
    }
    private fun emit() {
        line?.invoke(pending.toString("UTF-8").removeSuffix("\r"))
        pending.reset()
    }
    fun finish(): String {
        if (pending.size() > 0) emit()
        return text.toString("UTF-8") + if (truncated) "\n[输出已截断]" else ""
    }
}

internal fun shellResult(input: InputStream, output: OutputStream, command: String,
                         onLine: ((String, Boolean) -> Unit)?): ShellResult {
    val out = ShellText(onLine?.let { { line -> it(line, false) } })
    val err = ShellText(onLine?.let { { line -> it(line, true) } })
    val exit = ShellStreams(input, output, command, out::append, err::append).run()
    return ShellResult(out.finish(), err.finish(), exit)
}

internal fun rawLogResult(input: InputStream, output: OutputStream, command: String, append: (ByteArray, Int) -> Unit): ShellResult {
    val errors = ShellText()
    val code = ShellStreams(input, output, command, append, errors::append).run()
    return ShellResult("", errors.finish(), code)
}

internal fun shellScreenshot(input: InputStream, output: OutputStream, destination: OutputStream) {
    val errors = ShellText()
    val code = ShellStreams(input, output, "screencap -p",
        { bytes, count -> destination.write(bytes, 0, count) }, errors::append).run()
    check(code == 0) { errors.finish().ifBlank { "截图失败 ($code)" } }
}
