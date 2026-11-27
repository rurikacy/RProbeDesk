package dev.rprobedesk

import java.awt.Frame
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class WindowsFaultTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun savedDeviceFollowsVerifiedAddressAndPortThenSurvivesAdbdRestart() = runBlocking {
        assumeTrue("Requires the two loopback QA35 routes", System.getenv("RPROBE_QA_FAULTS") == "qa35")
        val frame = Frame()
        val windows = WindowsHost(frame)
        val adb = requireNotNull(windows.toolPath("adb"))
        val group = ProcessGroup { args ->
            if (args.getOrNull(1) == "-s") ProcessBuilder(args).apply { environment()["ADB_SERVER_SOCKET"] = "tcp:127.0.0.1:5037" }
            else WindowsAdbServer.forTool(adb).client(args)
        }
        suspend fun console(vararg args: String) = group.text(listOf(adb,"-s","emulator-5594") + args).checked()
        assertContains(console("emu","avd","name"), "RProbeDesk_QA35")
        val marker = "RProbeDesk-qa-target-5594"
        val command = "cat /data/local/tmp/rprobedesk-qa-target"
        assertEquals(marker, console("shell",command))
        var route = DiscoveredDevice("RProbeDesk-QA35", "127.0.0.1", 48481, "_adb._tcp")
        var observed = 0
        val host = object : HostBridge by windows {
            override val settingsDir: File = temp.newFolder("fault-settings")
            override val cacheDir: File = temp.newFolder("fault-cache")
            override suspend fun discoverDevices(): List<DiscoveredDevice> {
                observed++
                return if (route.port == 48481) listOf(route) else listOf(route.copy(port=1), route)
            }
        }
        val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        val prefs = DeskPreferences(host.settingsDir)
        val desk = DeskController(host,prefs,scope)
        val id = prefs.saveDevice(null,"QA35",route.ip,route.port)
        suspend fun ready(condition: () -> Boolean) = withTimeout(60_000) { while (!condition()) delay(100) }
        try {
            withContext(Dispatchers.Main) {
                desk.connect(prefs.devices.single())
                ready { desk.session != null && observed > 0 }
                val original = requireNotNull(desk.session)
                assertEquals(marker,original.checked(command).trim())
                desk.workspace.startLogs()
                route = route.copy(ip="127.0.0.2",port=48482)
                group.text(listOf(adb,"disconnect","127.0.0.1:48481")).checked()
                val movedAt = System.nanoTime()
                withTimeout(30_000) { desk.watch(original) }
                ready { desk.session != null && desk.session !== original }
                val moved = requireNotNull(desk.session)
                assertEquals(marker,moved.checked(command).trim())
                assertEquals(route.address,prefs.devices.single { it.id == id }.address)
                assertTrue(desk.workspace.logRunning)
                println("verified_relocation_ms=${(System.nanoTime()-movedAt)/1_000_000}")
                console("shell","input keyevent KEYCODE_SLEEP")
                try {
                    delay(1000)
                    assertEquals(marker,moved.checked(command).trim())
                } finally { console("shell","input keyevent KEYCODE_WAKEUP"); console("shell","wm dismiss-keyguard") }
                assertEquals(marker,moved.checked(command).trim())
                // Interrupt only adbd in the dedicated userdebug emulator, preserving its console.
                val reset = System.nanoTime()
                val scansBeforeReset = observed
                var stoppedMs: Long? = null
                val capture = scope.launch { while (isActive) {
                    if (!desk.workspace.logRunning && stoppedMs == null) stoppedMs = (System.nanoTime()-reset)/1_000_000
                    delay(50)
                } }
                val oldPid = console("shell","pidof adbd").toInt()
                assertEquals("0",console("shell","su 0 id -u"))
                val watcher = scope.launch { desk.watch(moved) }
                group.text(listOf(adb,"-s","emulator-5594","shell","su 0 kill -9 $oldPid"))
                val newPid = withTimeout(40_000) {
                    var pid: Int? = null
                    while (pid == null || pid == oldPid) {
                        delay(300)
                        pid = group.text(listOf(adb,"-s","emulator-5594","shell","pidof adbd")).stdout.trim().toIntOrNull()
                    }
                    pid
                }
                assertNotEquals(oldPid,newPid)
                val daemonMs = (System.nanoTime()-reset)/1_000_000
                val token = "rprobe-recovery-" + java.util.UUID.randomUUID()
                withTimeout(60_000) {
                    while (desk.workspace.logs.none { token in it.text }) {
                        desk.session?.let { runCatching { it.checked("log -t RProbeFault $token") } }
                        delay(300)
                    }
                }
                assertEquals(marker,requireNotNull(desk.session).checked(command).trim())
                assertTrue(desk.workspace.logRunning)
                watcher.cancel()
                capture.cancel()
                println("adbd_ready_ms=$daemonMs log_stopped_ms=$stoppedMs scans=${observed-scansBeforeReset}")
                println("adbd_restart_recovery_ms=${(System.nanoTime()-reset)/1_000_000} new_session=${desk.session !== moved}")
            }
        } finally {
            withContext(Dispatchers.Main) { desk.close();scope.cancel() }
            group.close()
            frame.dispose()
        }
    }
}
