package dev.rprobedesk

import java.awt.Frame
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class WindowsSwitchTest {
    @get:Rule val temp = TemporaryFolder()

    private suspend fun awaitState(reason: String, condition: () -> Boolean) {
        check(withTimeoutOrNull(30_000) { while (!condition()) delay(50); true } == true) { reason }
    }

    @Test fun droppedTcpAliasThenSwitchKeepsTasksOnTheirVerifiedTarget() = runBlocking {
        val a = System.getenv("RPROBE_QA_SWITCH_A")
        val b = System.getenv("RPROBE_QA_SWITCH_B")
        assumeTrue("Requires two explicitly selected disposable emulator TCP aliases", a != null && b != null)
        require(a == "127.0.0.1:5587" && b == "127.0.0.1:5591")
        val frame = Frame()
        val native = WindowsHost(frame)
        val adb = System.getenv("RPROBE_QA_ADB") ?: requireNotNull(native.toolPath("adb"))
        requireNotNull(native.toolPath("scrcpy")) { "Put the QA scrcpy.exe directory on PATH" }
        val settings = temp.newFolder("isolated-settings")
        val cache = temp.newFolder("isolated-cache")
        val waitingForInput = CompletableDeferred<Unit>()
        val releaseInput = CountDownLatch(1)
        val marker = UUID.randomUUID().toString()
        val dir = "/data/local/tmp/rpd-switch-$marker"
        val file = "transfer-$marker.bin"
        val bridge = object : HostBridge by native {
            override val settingsDir = settings
            override val cacheDir = cache
            override suspend fun connect(address: String, port: Int) = connectWindows(adb, address, port)
            override suspend fun pickOpen(suffix: String) = object : LocalDoc {
                override val name = file
                override val size = 65_536L
                override fun openInput() = object : InputStream() {
                    private var first = true
                    override fun read() = error("Expected buffered transfer")
                    override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                        if (first) {
                            first = false
                            val count = minOf(len, 32_768)
                            bytes.fill(0x41.toByte(), off, off + count)
                            return count
                        }
                        waitingForInput.complete(Unit)
                        check(releaseInput.await(60, TimeUnit.SECONDS)) { "Upload did not release its input" }
                        return -1
                    }
                    override fun close() { releaseInput.countDown() }
                }
                override suspend fun writeFrom(file: File) = error("Read-only input")
            }
        }
        val prefs = DeskPreferences(settings)
        val first = prefs.saveDevice(null, "QA-A", "127.0.0.1", 5587)
        val second = prefs.saveDevice(null, "QA-B", "127.0.0.1", 5591)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val desk = DeskController(bridge, prefs, scope)
        var created = false
        suspend fun connect(id: String): DeviceSession {
            desk.connect(prefs.devices.single { it.id == id })
            awaitState("Connect $id failed: ${desk.message}") { desk.session != null || desk.connecting == null }
            assertNull(desk.review)
            return assertNotNull(desk.session, desk.message)
        }
        try {
            withContext(Dispatchers.Main) {
                val targetA = connect(first)
                assertEquals(a, targetA.serial)
                assertEquals("1", targetA.checked("getprop ro.kernel.qemu").trim())
                assertEquals("RProbeDesk-qa-target-5586", targetA.checked("cat /data/local/tmp/rprobedesk-qa-target").trim())
                val digestA = assertNotNull(prefs.devices.single { it.id == first }.deviceDigest)
                targetA.checked("mkdir -- " + remoteArg(dir))
                created = true
                val workA = desk.workspace
                workA.browse(dir)
                awaitState("A directory did not open") { !workA.busy }
                assertEquals(dir, workA.directory)
                workA.startLogs()
                withTimeout(15_000) {
                    while (workA.logs.none { marker in it.text }) {
                        targetA.checked("log -t RProbeSwitch " + remoteArg(marker))
                        delay(300)
                    }
                }
                desk.mirror()
                awaitState("A mirror did not open") { !desk.mirrorBusy }
                assertTrue(bridge.mirrorActive.value, desk.message)
                workA.upload()
                withTimeout(15_000) { waitingForInput.await() }
                assertTrue(workA.busy)
                val group = ProcessGroup()
                try { group.text(listOf(adb, "disconnect", requireNotNull(a))).checked() }
                finally { group.close() }
                withTimeout(20_000) { desk.watch(targetA) }
                assertNull(desk.session)
                assertFalse(bridge.mirrorActive.value)
                val offline = desk.workspace
                assertFalse(offline.logRunning)
                assertTrue(offline.logs.any { marker in it.text })
                val reminder = assertNotNull(offline.reminders.retry)
                assertContains(reminder, "$dir/$file")
                assertNotNull(offline.reminders.logEnd)

                val targetB = connect(second)
                assertEquals(b, targetB.serial)
                assertEquals("1", targetB.checked("getprop ro.kernel.qemu").trim())
                assertNotEquals(digestA, prefs.devices.single { it.id == second }.deviceDigest)
                assertEquals("", targetB.fileStamp(dir))
                assertEquals(SessionReminders(), desk.workspace.reminders)
                assertTrue(desk.workspace.logs.isEmpty() && !desk.workspace.logRunning)
                assertFalse(bridge.mirrorActive.value)
                assertFalse(desk.workspace.busy)
                val sampler = scope.launch { desk.sample(targetB) }
                try { awaitState("B metrics not sampled") { desk.lastSampleDevice?.id == second && desk.metrics != null } }
                finally { sampler.cancelAndJoin() }
                assertTrue(offline.logs.any { marker in it.text })

                val restored = connect(first)
                assertEquals(reminder, desk.workspace.reminders.retry)
                assertNotNull(desk.workspace.reminders.logEnd)
                assertFalse(desk.workspace.logRunning)
                assertFalse(bridge.mirrorActive.value)
                assertEquals("", restored.fileStamp("$dir/$file"))
                awaitState("A upload staging was not recovered") { WorkTicket.records(settings, "upload").isEmpty() }
                assertTrue(restored.files(dir).isEmpty())
            }
        } finally {
            releaseInput.countDown()
            withContext(Dispatchers.Main) { desk.close(); scope.cancel() }
            frame.dispose()
            if (created) {
                val cleanup = connectWindows(adb, "127.0.0.1", 5587)
                try {
                    assertEquals("RProbeDesk-qa-target-5586", cleanup.checked("cat /data/local/tmp/rprobedesk-qa-target").trim())
                    recoverUploads(settings, first, cleanup)
                    cleanup.checked("rm -f -- " + remoteArg("$dir/$file") + " && rmdir -- " + remoteArg(dir))
                } finally { cleanup.close() }
            }
        }
    }
}
