package dev.rprobedesk

import java.awt.Frame
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WindowsWirelessTest {
    @Test fun pairAndReconnectToVerifiedTestTarget() = runBlocking {
        val codePath = System.getenv("RPROBE_QA_PAIR_FILE")?.let { java.io.File(it).toPath() }
        assumeTrue("Requires a fresh code from the disposable emulator's wireless debugging panel", codePath != null)
        val path = requireNotNull(codePath)
        val pairPort = requireNotNull(System.getenv("RPROBE_QA_PAIR_PORT")).toInt()
        val connectPort = requireNotNull(System.getenv("RPROBE_QA_TLS_PORT")).toInt()
        val frame = Frame()
        val pins = Preferences.userRoot().node("dev/rprobedesk/qa/" + UUID.randomUUID())
        try {
            val host = WindowsHost(frame)
            var confirmations = 0
            val adb = requireNotNull(host.toolPath("adb"))
            val setup = ProcessGroup()
            try {
                assertEquals("RProbeDesk-qa-target-5586", setup.text(listOf(adb,"-s","emulator-5586",
                    "shell","cat /data/local/tmp/rprobedesk-qa-target")).checked())
                setup.text(listOf(adb,"-s","emulator-5586","shell","cmd notification post -t RProbeDesk RProbeDesk-confirm waiting")).checked()
            } finally { setup.close() }
            host.wireless = WindowsWireless({
                // Read only the disposable emulator's own notification over its independent console transport.
                val group = ProcessGroup()
                try {
                    confirmations++
                    withTimeout(8000) {
                        var value:String? = null
                        while (value == null) {
                            val key = group.text(listOf(adb,"-s","emulator-5586","shell","cmd notification list"))
                                .checked().lines().firstOrNull { "|RProbeDesk-confirm|" in it }?.trim()
                            if (key != null) {
                                val notification = group.text(listOf(adb,"-s","emulator-5586","shell","cmd notification get " + remoteWord(key))).checked()
                                value = Regex("""android\.text=String \((\d{8})\)""").find(notification)?.groupValues?.get(1)
                            }
                            if (value == null) delay(150)
                        }
                        value
                    }
                } finally { group.close() }
            }, pins)
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
                    assertTrue(target.verifiedPeerKey.orEmpty().startsWith("tls:"))
                    assertEquals("1", target.checked("getprop ro.kernel.qemu").trim())
                    assertEquals("RProbeDesk-qa-target-5586", target.checked("cat /data/local/tmp/rprobedesk-qa-target").trim())
                    if (it == 0) {
                        val file = "/data/local/tmp/rprobe-wireless-" + UUID.randomUUID()
                        val bytes = ByteArray(262147) { index -> (index * 47).toByte() }
                        try {
                            target.push(file,bytes.inputStream(),bytes.size.toLong())
                            val output = java.io.ByteArrayOutputStream()
                            target.pull(file,output)
                            kotlin.test.assertContentEquals(bytes,output.toByteArray())
                            target.verifyMirrorTransport()
                        } finally { target.shell("rm -f '$file'") }
                    }
                } finally { target.close() }
            }
            assertEquals(1, confirmations)
            pins.put("127.0.0.1:$connectPort", "tls:changed-test-key")
            assertFailsWith<PeerRejected> { host.connect("127.0.0.1", connectPort) }
            assertEquals(1, confirmations)
        } finally {
            pins.removeNode()
            Files.deleteIfExists(path)
            frame.dispose()
        }
    }
}
