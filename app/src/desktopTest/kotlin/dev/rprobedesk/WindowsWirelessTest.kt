package dev.rprobedesk

import java.awt.Frame
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class WindowsWirelessTest {
    @Test fun pairAndReconnectToVerifiedTestTarget() = runBlocking {
        val codePath = System.getenv("RPROBE_QA_PAIR_FILE")?.let { java.io.File(it).toPath() }
        assumeTrue("Requires a fresh code from the disposable emulator's wireless debugging panel", codePath != null)
        val path = requireNotNull(codePath)
        val pairPort = requireNotNull(System.getenv("RPROBE_QA_PAIR_PORT")).toInt()
        val connectPort = requireNotNull(System.getenv("RPROBE_QA_TLS_PORT")).toInt()
        val frame = Frame()
        try {
            val host = WindowsHost(frame)
            assertFails { host.pair("127.0.0.1", pairPort, "12345") }
            val code = withTimeout(180000) {
                while (!Files.isRegularFile(path) || Files.size(path) < 6) delay(250)
                Files.readString(path).trim()
            }
            Files.delete(path)
            host.pair("127.0.0.1", pairPort, code)
            repeat(2) {
                val target = host.connect("127.0.0.1", connectPort)
                try {
                    assertEquals("1", target.checked("getprop ro.kernel.qemu").trim())
                    assertEquals("RProbeDesk-qa-target-5586", target.checked("cat /data/local/tmp/rprobedesk-qa-target").trim())
                } finally { target.close() }
            }
        } finally {
            Files.deleteIfExists(path)
            frame.dispose()
        }
    }
}
