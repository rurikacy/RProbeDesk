package dev.rprobedesk

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlin.test.*
import org.junit.Test

class EngineeringRegressionTest {
    @Test fun discoverySharesSourcesAndAppliesAddressRemoval() = runBlocking {
        val tcp = DiscoveredDevice("QA", "127.0.0.1", 1001, "_adb._tcp")
        val tls = tcp.copy(service = "_adb-tls-connect._tcp")
        val v6 = tls.copy(ip = "fe80::1%3")
        val pair = v6.copy(service = "_adb-tls-pairing._tcp", port = 1002)
        val starts = AtomicInteger()
        val finish = CompletableDeferred<Unit>()
        val seen = CompletableDeferred<Unit>()
        val shared = DiscoveryAggregator(
            { update ->
                starts.incrementAndGet()
                update(listOf(tls, v6, tcp))
                finish.await()
                listOf(v6)
            },
            { update ->
                val rows = listOf(tls, pair, v6.copy(ip = "fe80::1%4"))
                update(rows)
                finish.await()
                rows
            },
            { throw IOException("one backend unavailable") }
        )
        withTimeout(5000) {
            val first = async { shared.discover { if (it.size == 5) seen.complete(Unit) } }
            seen.await()
            val attached = CompletableDeferred<Unit>()
            val second = async { shared.discover { attached.complete(Unit) } }
            attached.await()
            first.cancelAndJoin()
            finish.complete(Unit)
            assertEquals(setOf(tls, pair, v6, v6.copy(ip = "fe80::1%4")), second.await().toSet())
            assertEquals(1, starts.get())
        }
    }

    @Test fun lastSubscriberReleasesPlatformListener() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val scan = DiscoveryAggregator({ started.complete(Unit); try { awaitCancellation() } finally { released.complete(Unit) } })
        withTimeout(3000) {
            val caller = launch { scan.discover {} }
            started.await()
            caller.cancelAndJoin()
            released.await()
        }
    }

    @Test fun blockedStreamsLeaveCapacityForCommandsControlAndProcessPipes() = runBlocking {
        val entered = CountDownLatch(WindowsIo.streams.limit)
        val release = CountDownLatch(1)
        val readers = List(WindowsIo.streams.limit) { WindowsIo.streams.submit { entered.countDown(); release.await(15, TimeUnit.SECONDS) } }
        val priorChildren = WindowsIo.children.get()
        val group = ProcessGroup { ProcessBuilder(it) }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertEquals(TransportError.Exhausted, assertFailsWith<TransportFailure> { WindowsIo.streams.execute {} }.kind)
            assertEquals(0L, WindowsIo.snapshot()["waiting"])
            assertEquals("ok", WindowsIo.control.run { "ok" })
            assertEquals(42, WindowsIo.short.run { 42 })
            val reply = withTimeout(10_000) { group.text(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "[Console]::Out.WriteLine(('a'*100000));[Console]::Error.WriteLine(('b'*100000))")) }
            assertEquals(100000, reply.stdout.trimEnd().length)
            assertEquals(100000, reply.stderr.trimEnd().length)
            val childStarted = CompletableDeferred<Unit>()
            val child = launch { group.use(listOf("powershell.exe", "-NoProfile", "-Command", "Start-Sleep 30")) { childStarted.complete(Unit); it.waitFor() } }
            withTimeout(3000) { childStarted.await(); child.cancelAndJoin() }
            withTimeout(3000) { while (WindowsIo.children.get() > priorChildren) delay(20) }
        } finally { group.close(); release.countDown(); readers.forEach { it.get(2, TimeUnit.SECONDS) } }
    }

    @Test fun silentDialsCannotStarveVerifiedHealthAndCancellation() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("RPROBE_QA_SWITCH_B") == "127.0.0.1:5591")
        val adb = requireNotNull(System.getenv("RPROBE_QA_ADB"))
        val peer = connectWindows(adb, "127.0.0.1", 5591)
        try {
            assertEquals("RProbeDesk-qa-target-5590", peer.checked("cat /data/local/tmp/rprobedesk-qa-target").trim())
            java.net.ServerSocket(0, 12, java.net.InetAddress.getByName("127.0.0.1")).use { listener ->
                listener.soTimeout = 80
                val clients = mutableListOf<java.net.Socket>()
                val refused = AtomicInteger()
                val wireless = WindowsWireless({ error("Unexpected pairing") })
                val jobs = List(6) { launch(Dispatchers.IO) {
                    val stalled = WindowsSession(adb, "127.0.0.1:" + listener.localPort)
                    try { wireless.verify(stalled, "127.0.0.1", null) }
                    catch (e: TransportFailure) { assertEquals(TransportError.Exhausted, e.kind); refused.incrementAndGet() }
                    finally { stalled.close() }
                } }
                try {
                    withTimeout(4000) {
                        while (clients.size + refused.get() < jobs.size) {
                            try { clients.add(listener.accept()) } catch (_: java.net.SocketTimeoutException) { yield() }
                        }
                    }
                    assertEquals(6, WindowsIo.dial.limit + WindowsIo.control.limit)
                    assertEquals(WindowsIo.dial.limit, clients.size)
                    assertEquals(jobs.size - clients.size, refused.get())
                    assertEquals("RProbeDesk", withTimeout(1500) { peer.probe() }.stdout.trim())
                } finally {
                    jobs.forEach { it.cancel() }
                    withTimeout(2500) { jobs.joinAll() }
                    clients.forEach { it.use { socket ->
                        socket.soTimeout = 1500
                        assertTrue(socket.getInputStream().readBytes().isNotEmpty())
                    } }
                }
                assertEquals("ok", peer.checked("echo ok").trim())
            }
        } finally { peer.close() }
    }

    @Test fun interruptedWorkerReturnsItsPermit() = runBlocking {
        val lane = WindowsIo.Lane("test-cancel", 1)
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        try {
            val waiting = launch { lane.run { started.complete(Unit); try { CountDownLatch(1).await() } finally { stopped.complete(Unit) } } }
            withTimeout(3000) {
                started.await()
                waiting.cancelAndJoin()
                stopped.await()
                while (lane.active != 0) delay(10)
            }
            assertEquals(1, lane.run { 1 })
            withTimeout(13_000) { while (lane.threads != 0) delay(50) }
        } finally { lane.close() }
    }

    @Test fun privateServerAndFallbackWorkWithBundledAdb() = runBlocking {
        val resources = File(System.getProperty("compose.application.resources.dir"))
        val adb = resources.resolve("tools/scrcpy/adb.exe")
        assertTrue(adb.isFile)
        verifyWindowsTool("adb", adb.absolutePath, pairing = true, probePrivate = true)
        val incompatible = File(System.getenv("WINDIR"), "System32/whoami.exe")
        val manager = ToolManager(resources) { incompatible.absolutePath }
        assertEquals(adb.absolutePath, manager.verifiedPath("adb", true))
        assertContains(manager.diagnostics.getValue("adb"), "已回退")
    }

    @Test fun stableCodesRetainPolicyThroughWrappedExceptions() {
        val actions = listOf(RecoveryAction.Retry, RecoveryAction.WaitForNetwork, RecoveryAction.Rediscover,
            RecoveryAction.StopSession, RecoveryAction.ConfirmIdentity, RecoveryAction.Reconnect,
            RecoveryAction.StopSession, RecoveryAction.StopOperation, RecoveryAction.RecoverBackend, RecoveryAction.WaitForResources)
        actions.forEachIndexed { index, action ->
            assertEquals(action, recoveryAction(IOException("outer", TransportFailure(index + 1, "same diagnostic"))))
        }
    }

    @Test fun perDeviceStreamBudgetPreservesHealthAndAnotherDevice() = runBlocking {
        val port = System.getenv("RPROBE_QA_PORT")
        org.junit.Assume.assumeTrue(port == "5587" && System.getenv("RPROBE_QA_SWITCH_B") == "127.0.0.1:5591")
        val adb = requireNotNull(System.getenv("RPROBE_QA_ADB"))
        val first = connectWindows(adb, "127.0.0.1", 5587)
        try {
            val other = connectWindows(adb, "127.0.0.1", 5591)
            try {
                assertEquals("RProbeDesk-qa-target-5586", first.checked("cat /data/local/tmp/rprobedesk-qa-target").trim())
                assertEquals("RProbeDesk-qa-target-5590", other.checked("cat /data/local/tmp/rprobedesk-qa-target").trim())
                val started = List(3) { CompletableDeferred<Unit>() }
                val readers = started.map { ready -> launch {
                    first.collectLog("echo ready; sleep 10") { _, _ -> ready.complete(Unit) }
                } }
                try {
                    withTimeout(5000) { started.awaitAll() }
                    assertEquals(TransportError.Exhausted, assertFailsWith<TransportFailure> { first.collectLog("echo excess") { _, _ -> } }.kind)
                    assertEquals("RProbeDesk", first.probe().stdout.trim())
                    assertEquals("ok", other.checked("echo ok").trim())
                } finally { readers.forEach { it.cancel() }; readers.joinAll() }
                assertEquals("done", first.checked("echo done").trim())
            } finally { other.close() }
        } finally { first.close() }
    }

    @Test fun pagedJniRepresentsEvictionAndEveryRetainedSequence() {
        val handle = NativeCore.openLog()
        try {
            val chunk = (1..150).joinToString("") { "中文💻-" + it + "\n" }.toByteArray()
            repeat(160) { NativeCore.appendLog(handle, chunk, chunk.size) }
            var cursor = 0L
            var count = 0
            do {
                val page = ByteBuffer.wrap(NativeCore.readLogPage(handle, cursor))
                assertTrue(page.remaining() <= 256 * 1024)
                val end = page.long
                val evicted = page.long
                val latest = page.long
                assertEquals(24_000L, latest)
                assertEquals(22_500L, evicted)
                var seq = maxOf(cursor, evicted)
                while (page.hasRemaining()) {
                    val size = page.int
                    page.position(page.position() + 36)
                    val bytes = ByteArray(size).also(page::get)
                    assertEquals("中文💻-" + (seq % 150 + 1), bytes.decodeToString())
                    seq++; count++
                }
                assertEquals(seq, end)
                assertTrue(end - maxOf(cursor, evicted) in 1..256)
                cursor = end
            } while (cursor < 24_000)
            assertEquals(1500, count)
            assertTrue(NativeCore.readLogPage(handle, cursor).isEmpty())
        } finally { NativeCore.closeLog(handle) }
    }
}
