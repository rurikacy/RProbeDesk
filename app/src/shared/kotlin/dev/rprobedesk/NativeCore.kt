package dev.rprobedesk

import java.io.File

/** JNI references and buffers are scoped to the calling coroutine's blocking I/O operation. */
internal object NativeCore {
    init {
        val resources = System.getProperty("compose.application.resources.dir")
        if (resources == null) System.loadLibrary("rprobe_core")
        else System.load(File(resources, "native/rprobe_core.dll").absolutePath)
    }
    fun ensureLoaded() = Unit

    @JvmStatic external fun track(serial: String): Long
    @JvmStatic external fun transportId(handle: Long): Long
    @JvmStatic external fun waitForDisconnect(handle: Long): String
    @JvmStatic external fun closeTracker(handle: Long)
    @JvmStatic external fun directConnect(endpoint: String, keyDir: String, expected: String): Long
    @JvmStatic external fun directPeer(handle: Long): String
    @JvmStatic external fun directOpen(handle: Long, name: String): Long
    @JvmStatic external fun directRead(handle: Long, channel: Long, bytes: ByteArray, offset: Int, length: Int): Int
    @JvmStatic external fun directWrite(handle: Long, channel: Long, bytes: ByteArray, offset: Int, length: Int)
    @JvmStatic external fun directCloseStream(handle: Long, channel: Long)
    @JvmStatic external fun directClose(handle: Long)
    @JvmStatic external fun fingerprint(spki: ByteArray): String
    @JvmStatic external fun trust(expected: String, current: String, previous: String,
        known: Boolean, paired: Boolean, requirePair: Boolean): Int
    @JvmStatic external fun metrics(text: String, previous: LongArray): MonitorReading
    @JvmStatic external fun logFields(lines: Array<String>): IntArray
    @JvmStatic external fun discover(result: DiscoveryResult)
    @JvmStatic external fun openSession(): Long
    @JvmStatic external fun closeSession(handle: Long)
    @JvmStatic external fun signal(handle: Long, event: Int, entropy: Int): Long
    @JvmStatic external fun shell(io: ShellStreams, scriptSize: Int): Int
    @JvmStatic external fun transfer(io: SyncStreams, upload: Boolean, nameLength: Int, expected: Long)
}
