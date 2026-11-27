package dev.rprobedesk

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

class NativeCoreTest {
    @Test fun malformedRepliesAreRejectedAndJavaIoErrorsSurviveJni() {
        val path = "/test"
        val payload = ByteArrayOutputStream()
        fun receive(wire: ByteArray) =
            AdbFileSync(wire.inputStream(), ByteArrayOutputStream()).receive(path, payload) {}
        assertFailsWith<IOException> { receive("DATA".toByteArray() + byteArrayOf(1, 0, 1, 0)) }
        assertFailsWith<java.io.EOFException> { receive("DATA".toByteArray() + byteArrayOf(1, 0, 0, 0)) }
        assertContains(assertFailsWith<IOException> {
            receive("FAIL".toByteArray() + byteArrayOf(6, 0, 0, 0) + "denied".toByteArray())
        }.message.orEmpty(), "denied")
        assertEquals(0, payload.size())
    }

    @Test fun nativeSessionsKeepIndependentFailureCountsAndInvalidateClosedHandles() {
        DeviceSessionLease().use { first ->
            DeviceSessionLease().use { second ->
                for (owner in listOf(first, second)) owner.observe(SessionEvent.Verified)
                repeat(3) { first.observe(SessionEvent.ProbeFailed) }
                assertEquals(SessionPhase.DeviceUnavailable, first.phase.value)
                assertEquals(SessionPhase.Connected, second.phase.value)
                first.close()
                assertFailsWith<IllegalStateException> { first.observe(SessionEvent.Verified) }
            }
        }
    }

    /** RPROBE_QA_TARGET is an explicitly authorized TCP device, not the first discovered device. */
    @Test fun liveBundledToolsAndNativeStreams() = runBlocking {
        val endpoint = System.getenv("RPROBE_QA_TARGET")
        assumeTrue("Set RPROBE_QA_TARGET for real device I/O", endpoint != null)
        val root = File(System.getProperty("compose.application.resources.dir"))
        val tools = ToolManager(root) { null }
        val adb = verifyWindowsTool("adb", requireNotNull(tools.path("adb")), true)
        verifyWindowsTool("scrcpy", requireNotNull(tools.path("scrcpy")))
        val port = endpoint!!.substringAfterLast(':').toInt()
        val session = connectWindows(adb, endpoint.substringBeforeLast(':'), port)
        val remote = "/data/local/tmp/rprobe-core-qa-" + UUID.randomUUID()
        try {
            val peer = parseProbe(session.shell(probeCommand(true)).stdout)
            val metrics = parseMetrics(session.shell(metricCommand).stdout, null, System.currentTimeMillis())
            assertTrue(metrics.memoryPercent != null)
            val lines = mutableListOf<String>()
            val reply = session.shell("printf '输出\\n'; printf 'error\\n' >&2; exit 7") { s, _ -> lines.add(s) }
            assertEquals(7, reply.exitCode)
            assertEquals(setOf("输出", "error"), lines.toSet())
            val bytes = ByteArray(4 * 1024 * 1024) { (it * 31).toByte() }
            val start = System.nanoTime()
            session.push(remote, bytes.inputStream(), bytes.size.toLong())
            val uploaded = System.nanoTime()
            val actual = ByteArrayOutputStream()
            session.pull(remote, actual)
            val downloaded = System.nanoTime()
            assertContentEquals(bytes, actual.toByteArray())
            val logs = session.shell("logcat -d -t 20 -v threadtime")
            assertEquals(0, logs.exitCode)
            assertTrue(logs.stdout.lines().any { parseLog(it, null).level != null })
            val png = ByteArrayOutputStream()
            session.screenshot(png)
            val screenshot = javax.imageio.ImageIO.read(png.toByteArray().inputStream())
            assertTrue(screenshot.width > 0)
            println("model=${peer.model} sdk=${peer.facts["SDK"]} bytes=${bytes.size} " +
                "pushMs=${(uploaded-start)/1_000_000} pullMs=${(downloaded-uploaded)/1_000_000} " +
                "screenshot=${screenshot.width}x${screenshot.height}")
        } finally {
            try { session.checked("rm -f -- " + remoteWord(remote)) } finally { session.close() }
        }
        assertFails { session.shell("true") }
        Unit
    }
}
