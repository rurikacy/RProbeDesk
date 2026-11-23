package dev.rprobedesk

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class WorkspaceRecoveryTest {
    private class MemoryHost(override val cacheDir: File) : HostBridge {
        override val appVersion = "1.1.1"
        override fun installUpdate(file: File) = error("Unexpected update")
        override val desktop = false
        override val settingsDir = cacheDir
        override val foreground = MutableStateFlow(true)
        override val reducedMotion = MutableStateFlow(false)
        override val mirrorActive = MutableStateFlow(false)
        var published = "original".toByteArray()
        var picked: ByteArray? = null
        var saveGate: CompletableDeferred<Unit>? = null
        override suspend fun pickSave(name: String): LocalDoc {
            saveGate?.await()
            return object : LocalDoc {
                override val name = name
                override val size: Long? = null
                override fun openInput() = error("save-only")
                override suspend fun writeFrom(file: File) { published = file.readBytes() }
            }
        }
        override suspend fun pickOpen(suffix: String): LocalDoc? = picked?.let { bytes -> object : LocalDoc {
            override val name = "local.apk"
            override val size = bytes.size.toLong()
            override fun openInput() = bytes.inputStream()
            override suspend fun writeFrom(file: File) = error("read-only")
        } }
        override suspend fun connect(address: String, port: Int): DeviceSession = error("unused")
        override suspend fun pair(address: String, port: Int, code: String) = error("unused")
        override suspend fun selectTool(tool: String) = error("unused")
        override fun toolPath(tool: String): String? = null
        override suspend fun startMirror(session: DeviceSession) = error("unused")
        override fun stopMirror() = Unit
        override fun copy(text: String) = Unit
    }
    private class MemorySession : DeviceSession {
        override val serial = "test"
        var user = 10
        var uninstallCount = 0
        var catalogCalls=mutableListOf<String>()
        var packageVersion=7
        var listings = 0
        var installs = 0
        var overwrite = false
        var pushPayload: ByteArray? = null
        var failPush = false
        var publishes = 0
        var deniedLogs = false
        var markerMatches = true
        var interruptPull = false
        var fileBytes = byteArrayOf(1, 2, 3)
        var liveLog: ((String, Boolean) -> Unit)? = null
        override suspend fun shell(command: String, onLine: ((String, Boolean) -> Unit)?): ShellResult {
            if ("dev.rprobedesk.catalog.AppCatalog" in command) {
                catalogCalls.add(command)
                onLine?.invoke("dev.example\tVGVzdA==\t",false)
                return ShellResult("","",0)
            }
            if (command.contains("find . -mindepth")) {
                listings++
                val raw="drwxrws--- 4096 145.9\nDownload\u0000"
                return ShellResult(java.util.Base64.getEncoder().encodeToString(raw.toByteArray()),"",0)
            }
            if (command.startsWith("logcat -v")) {
                if (deniedLogs) return ShellResult("", "Permission denied", 1)
                liveLog = onLine
                try { awaitCancellation() } finally { liveLog = null }
            }
            if ("仅支持同一卷内移动" in command) {
                publishes++
                val reason = if ("/second volume" in command) "仅支持同一卷内移动" else "目标已变动，无法覆盖"
                return ShellResult("$reason\n__RPD_STATUS__1\n", "", 0)
            }
            if ("cat owner" in command && !markerMatches) return ShellResult("暂存目录的归属待核实\n__RPD_STATUS__1\n", "", 0)
            val out = when {
                "am get-current-user" in command -> user.toString()
                "pm list packages --show-versioncode" in command -> "package:dev.example versionCode:$packageVersion"
                "pm list packages -d" in command || "pm list packages -s" in command -> ""
                "pm uninstall" in command -> { uninstallCount++; "Success" }
                "if [ -e " in command && "/second volume" !in command && ("/local.apk" !in command || overwrite) ->
                    "81a4:1:2:42:2026-11-21 00:00:00.012345678"
                "stat -c %s" in command -> (pushPayload?.size ?: 0).toString()
                else -> ""
            }
            return ShellResult(out + "\n__RPD_STATUS__0\n", "", 0)
        }
        override suspend fun pull(path: String, output: OutputStream, progress: (Long) -> Unit) {
            assertEquals("/sdcard/报 告.bin", path)
            output.write(fileBytes)
            if (interruptPull) error("中断")
            progress(fileBytes.size.toLong())
        }
        override suspend fun push(path: String, input: InputStream, size: Long?, progress: (Long) -> Unit) {
            if(path.endsWith(".jar")) return
            check(path.endsWith("/payload"))
            if (failPush) throw java.io.IOException("No space left on device")
            pushPayload = input.readBytes()
            progress(requireNotNull(pushPayload).size.toLong())
        }
        override suspend fun install(apk: File, userId: Int, transferred:(Long)->Unit, installing:()->Unit): String {
            installs++
            return "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]"
        }
        override suspend fun screenshot(output: OutputStream) = error("unused")
        override fun close() = Unit
    }

    private suspend fun awaitUi(done: () -> Boolean) = withTimeout(5000) {
        while (!done()) delay(10)
    }

    private fun withHost(case: suspend (MemoryHost, MemorySession) -> Unit): Unit = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("rpd-workspace-test-").toFile()
        val host = MemoryHost(dir)
        val session = MemorySession()
        try {
            withContext(Dispatchers.Main) { case(host, session) }
        } finally {
            // The test owns exactly these flat temporary files.
            dir.listFiles()?.forEach { assertTrue(it.delete()) }
            assertTrue(dir.delete())
        }
    }

    private fun withModel(case: suspend (WorkspaceModel, MemoryHost, MemorySession) -> Unit) = withHost { host, session ->
        val m = WorkspaceModel(host, session, DeskPreferences(host.settingsDir), "测试目标")
        try { case(m, host, session) } finally { m.close() }
    }

    @Test fun unknownShellAndLogGapFollowTheVerifiedPeerWithoutReplaying() = withHost { host, base ->
        val prefs = DeskPreferences(host.settingsDir)
        val a = prefs.saveDevice(null, "甲", "127.0.0.1", 7001)
        val b = prefs.saveDevice(null, "乙", "127.0.0.1", 7002)
        var firstKey = "peer-a"
        var online = true
        var submissions = 0
        val shellStarted = CompletableDeferred<Unit>()
        val bridge = object : HostBridge by host {
            override suspend fun connect(address: String, port: Int) = object : DeviceSession by base {
                override val verifiedPeerKey = if (port == 7001) firstKey else "peer-b"
                override suspend fun shell(command: String, onLine: ((String, Boolean) -> Unit)?): ShellResult = when (command) {
                    probeCommand(false) -> ShellResult("model=QA\nrelease=15\nuser=0", "", 0)
                    "echo RProbeDesk" -> if (online) ShellResult("RProbeDesk", "", 0) else error("offline")
                    "long-qa-command" -> {
                        submissions++
                        shellStarted.complete(Unit)
                        awaitCancellation()
                    }
                    else -> base.shell(command, onLine)
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val desk = DeskController(bridge, prefs, scope)
        suspend fun connect(id: String) {
            desk.connect(prefs.devices.single { it.id == id })
            awaitUi { desk.connecting == null }
            assertEquals(id, desk.activeDevice?.id, desk.message)
        }
        try {
            connect(a)
            desk.workspace.startLogs()
            awaitUi { base.liveLog != null }
            base.liveLog!!.invoke("11-20 11:12:00.000 10 11 I QA: peer-a-only", false)
            desk.workspace.runShell("long-qa-command")
            withTimeout(5000) { shellStarted.await() }
            online = false
            desk.watch(requireNotNull(desk.session))
            assertNull(desk.session)
            assertTrue(desk.workspace.reminders.checks.single().startsWith("Shell · "))
            assertTrue(desk.workspace.logs.any { "peer-a-only" in it.text })
            assertTrue(desk.workspace.logs.last().text.contains("设备连接已结束"))
            online = true
            connect(b)
            assertEquals(SessionReminders(), desk.workspace.reminders)
            assertTrue(desk.workspace.logs.isEmpty())
            firstKey = "replacement-peer"
            connect(a)
            assertEquals(SessionReminders(), desk.workspace.reminders)
            desk.disconnect()
            firstKey = "peer-a"
            connect(a)
            assertTrue(desk.workspace.reminders.checks.single().startsWith("Shell · "))
            assertTrue(desk.workspace.logs.isEmpty())
            desk.workspace.startLogs()
            assertTrue("离线期间未采集" in desk.workspace.logs.first().text)
            desk.workspace.acknowledgeChecks()
            connect(b)
            connect(a)
            assertTrue(desk.workspace.reminders.checks.isEmpty())
            assertEquals(1, submissions)
        } finally { desk.close(); scope.cancel() }
    }

    @Test fun appCatalogPreloadsOnceAndRefreshesChangedPackagesOnly() = withModel { m, _, session ->
        m.prefetchApps()
        awaitUi { m.appList.singleOrNull()?.label=="Test" }
        assertFalse(m.busy)
        m.appAction(m.appList.single(),"停止")
        awaitUi { m.question!=null }
        m.respond(true)
        awaitUi { !m.busy }
        assertFalse(m.problem,m.status)
        assertEquals("Test",m.appList.single().label)
        m.refreshApps()
        awaitUi { !m.busy }
        assertEquals(1,session.catalogCalls.size)
        session.packageVersion++
        m.refreshApps()
        awaitUi { m.appList.single().version=="8" && m.appList.single().label=="Test" }
        assertEquals(2,session.catalogCalls.size)
        assertTrue(session.catalogCalls.last().endsWith("'dev.example'"))
    }

    @Test fun canceledOrChangedUserCannotUninstall() = withModel { m, _, session ->
        m.refreshApps()
        awaitUi { !m.busy }
        val app = m.appList.single()
        m.appAction(app, "卸载")
        awaitUi { m.question != null }
        m.respond(false)
        awaitUi { !m.busy }
        assertEquals(0, session.uninstallCount)
        m.appAction(app, "卸载")
        awaitUi { m.question != null }
        session.user = 11
        m.respond(true)
        awaitUi { !m.busy }
        assertTrue(m.problem)
        assertEquals(0, session.uninstallCount)
    }

    @Test fun directoryVisitsReuseCacheAndRefreshRereads() = withModel { m, _, session ->
        for ((path,reads) in listOf("/sdcard" to 1,"/sdcard/Download/" to 2,"/sdcard" to 2)) {
            m.browse(path)
            awaitUi { !m.busy }
            assertEquals(reads,session.listings)
            assertEquals("Download",m.fileList.single().name)
        }
        m.browse(m.directory,refresh=true)
        awaitUi { !m.busy }
        assertEquals(3,session.listings)
    }

    @Test fun interruptedOrShortDownloadNeverReplacesExistingContentAndZeroLengthSucceeds() = withModel { m, host, session ->
        val f = TargetFile("报 告.bin", false, false, 3, 1)
        session.interruptPull = true
        m.download(f)
        awaitUi { !m.busy }
        assertContentEquals("original".toByteArray(), host.published)
        assertTrue(m.problem)
        session.interruptPull = false
        m.download(f.copy(bytes = 12))
        awaitUi { !m.busy }
        assertContentEquals("original".toByteArray(), host.published)
        m.download(f)
        awaitUi { !m.busy }
        assertContentEquals(session.fileBytes, host.published)
        session.fileBytes = byteArrayOf()
        m.download(f.copy(bytes = 0))
        awaitUi { !m.busy }
        assertFalse(m.problem)
        assertContentEquals(byteArrayOf(), host.published)
    }

    @Test fun logPauseFreezesDisplayAndBackgroundClosesTheSessionStream() = withModel { m, host, session ->
        m.startLogs()
        awaitUi { session.liveLog != null }
        val send = requireNotNull(session.liveLog)
        send("11-20 11:12:00.000 10 11 I Demo: before", false)
        awaitUi { m.logs.any { "before" in it.text } }
        m.pauseDisplay()
        repeat(1550) { send("11-20 11:12:00.001 10 11 E Demo: after $it", false) }
        awaitUi { m.dropped > 0 }
        assertTrue(m.visibleLogs.none { "after" in it.text })
        assertTrue(m.logs.size <= 1500)
        m.pauseDisplay()
        assertTrue(m.visibleLogs.any { "after 1549" in it.text })
        host.foreground.value = false
        awaitUi { !m.logRunning && session.liveLog == null }
        val size = m.logs.size
        send("old callback", false)
        assertEquals(size, m.logs.size)
        assertTrue(m.logs.last().text.contains("后台"))
        host.foreground.value = true
        assertFalse(m.logRunning)
        m.clearLogs()
        assertTrue(m.logs.isEmpty())
    }

    @Test fun cleanupSkipsLiveStagesAndOnlyClaimsOriginalUploads() = withHost { host, session ->
        val stage = WorkTicket.create(host.cacheDir, "local", mapOf("suffix" to "apk"))
        stage.localFile.writeBytes(byteArrayOf(3, 4))
        recoverLocalStages(host.cacheDir)
        assertTrue(stage.localFile.exists())
        stage.release(false)
        recoverLocalStages(host.cacheDir)
        assertFalse(stage.localFile.exists())
        assertTrue(WorkTicket.records(host.cacheDir, "local").isEmpty())

        val upload = WorkTicket.create(host.settingsDir, "upload", mapOf("profile" to "A", "directory" to "/sdcard", "marker" to "test-token"))
        upload.release(false)
        recoverUploads(host.settingsDir, "B", session)
        assertEquals(1, WorkTicket.records(host.settingsDir, "upload").size)
        session.markerMatches = false
        assertEquals(listOf(remoteStage(upload)), recoverUploads(host.settingsDir, "A", session))
        assertEquals(1, WorkTicket.records(host.settingsDir, "upload").size)
        session.markerMatches = true
        recoverUploads(host.settingsDir, "A", session)
        assertTrue(WorkTicket.records(host.settingsDir, "upload").isEmpty())
    }

    @Test fun apkChecksBeforeConfirmationAndSurfacesSignatureRejection() = withModel { m, host, session ->
        fun archive(resource: String): ByteArray {
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                requireNotNull(javaClass.getResourceAsStream(resource)).use { it.copyTo(zip) }
                zip.closeEntry()
            }
            return bytes.toByteArray()
        }
        host.picked = "<html>not an APK</html>".toByteArray()
        m.installApk()
        awaitUi { !m.busy }
        assertTrue(m.problem)
        host.picked = archive("/apk/split.axml")
        m.installApk()
        awaitUi { !m.busy }
        assertContains(m.status, "分包")
        host.picked = archive("/apk/test.axml")
        val dropped=requireNotNull(host.pickOpen(".apk"))
        host.picked=null
        var released=false
        m.installApk(dropped=dropped,release={ released=true })
        awaitUi { m.question != null }
        assertContains(requireNotNull(m.question).detail, "dev.example.probe")
        assertEquals(0, session.installs)
        m.respond(false)
        awaitUi { !m.busy }
        assertEquals(0, session.installs)
        assertTrue(released)
        host.picked=archive("/apk/test.axml")
        m.installApk()
        awaitUi { m.question != null }
        m.respond(true)
        awaitUi { !m.busy }
        assertEquals(1, session.installs)
        assertTrue(m.problem)
        assertContains(m.status, "INSTALL_FAILED_UPDATE_INCOMPATIBLE")
        assertTrue(WorkTicket.records(host.cacheDir, "local").isEmpty())
    }

    @Test fun uploadConflictAndCrossVolumeFailureAreShownToTheCaller() = withModel { m, host, session ->
        host.picked = byteArrayOf(7)
        session.overwrite = true
        m.upload()
        awaitUi { m.question != null }
        m.respond(false)
        awaitUi { !m.busy }
        assertNull(session.pushPayload)
        m.upload()
        awaitUi { m.question != null }
        m.respond(true)
        awaitUi { !m.busy }
        assertContentEquals(host.picked, session.pushPayload)
        assertContains(m.status, "目标已变动")
        assertTrue(WorkTicket.records(host.settingsDir, "upload").isEmpty())
        m.move(TargetFile("a\n'中文'", false, false, 7, 0), "/second volume")
        awaitUi { m.question != null }
        assertContains(requireNotNull(m.question).detail, "/second volume/a\n'中文'")
        m.respond(true)
        awaitUi { !m.busy }
        assertContains(m.status, "同一卷")
    }

    @Test fun deniedLogSourceShowsTargetErrorAndStopsCapture() = withModel { m, _, session ->
        session.deniedLogs = true
        m.startLogs()
        awaitUi { !m.logRunning }
        assertTrue(m.logs.any { it.text.contains("Permission denied") })
    }

    @Test fun deviceOutOfSpaceBeforePublishPreservesTheConfirmedDestination() = withModel { m, host, session ->
        host.picked = byteArrayOf(9, 8, 7)
        session.overwrite = true
        session.failPush = true
        m.upload()
        awaitUi { m.question != null }
        m.respond(true)
        awaitUi { !m.busy }
        assertTrue(m.problem)
        assertContains(m.status, "No space left on device")
        assertEquals(0, session.publishes)
        assertTrue(WorkTicket.records(host.settingsDir, "upload").isEmpty())
    }

    @Test fun exportingPausedLogsKeepsTheFilteredSnapshotFromTheClick() = withModel { m, host, session ->
        val before = "11-20 11:12:00.000 22 33 E Network: timeout before pause"
        val after = "11-20 11:12:01.000 22 33 E Network: timeout after pause"
        m.startLogs()
        awaitUi { session.liveLog != null }
        val emit = requireNotNull(session.liveLog)
        emit(before, false)
        emit(before.replace(" E ", " I "), false)
        emit(before.replace("Network", "Other"), false)
        emit(before.replace("22 33", "44 33"), false)
        emit(before.replace("timeout", "connected"), false)
        awaitUi { m.logs.any { "connected" in it.text } }
        m.filter = LogFilter('W', "Network", "22", "timeout")
        m.pauseDisplay()
        emit(after, false)
        awaitUi { m.logs.any { it.text == after } }

        val picker = CompletableDeferred<Unit>()
        host.saveGate = picker
        m.exportLogs()
        m.pauseDisplay()
        m.filter = LogFilter()
        picker.complete(Unit)
        awaitUi { !m.busy }
        assertFalse(m.problem)
        assertEquals(listOf(before), host.published.decodeToString().lines().filter(String::isNotEmpty))
    }
}

