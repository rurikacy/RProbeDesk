package dev.rprobedesk

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Test
import kotlin.test.*

class RuntimeTest {
    @Test fun generationOwnsExactlyOneAdapterAndIgnoresOldRelease() {
        val lease = NativeCore.openSession()
        val count = AtomicInteger()
        try {
            assertTrue(NativeCore.signal(lease, SessionEvent.Verified.code, 0) < 0)
            for (event in listOf(SessionEvent.Connect, SessionEvent.Authenticate))
                assertTrue(NativeCore.signal(lease, event.code, 0) >= 0)
            assertEquals(0, NativeCore.bindSession(lease, "peer"))
            val first = NativeCore.ownTransport(lease) { count.incrementAndGet() }
            assertTrue(first > 0)
            assertTrue(NativeCore.ownTransport(lease) { fail("duplicate owner") } < 0)
            NativeCore.releaseTransport(lease, first)
            assertEquals(0, NativeCore.bindSession(lease, "peer"))
            assertTrue(NativeCore.bindSession(lease, "changed") < 0)
            val second = NativeCore.ownTransport(lease) { count.incrementAndGet() }
            assertNotEquals(first, second)
            NativeCore.releaseTransport(lease, first)
            assertEquals(1, count.get())
        } finally { NativeCore.closeSession(lease); NativeCore.closeSession(lease) }
        assertEquals(2, count.get())
    }

    @Test fun backoffWakesOnNetworkChangeAndCloseCancelsPendingTimer() = runBlocking {
        val lease = DeviceSessionLease()
        try {
            lease.observe(SessionEvent.Connect)
            repeat(6) { lease.observe(SessionEvent.WaitToRetry); lease.observe(SessionEvent.Retry) }
            assertTrue(lease.observe(SessionEvent.WaitToRetry) >= 16_000)
            val wake = async(start = CoroutineStart.UNDISPATCHED) { lease.awaitNext() }
            NativeCore.networkChanged()
            withTimeout(2000) { wake.await() }
            lease.observe(SessionEvent.Retry)
            lease.observe(SessionEvent.WaitToRetry)
            val pending = async(start = CoroutineStart.UNDISPATCHED) { lease.awaitNext() }
            lease.close()
            withTimeout(1000) { pending.join() }
            assertTrue(pending.isCancelled)
        } finally { lease.close() }
    }

    @Test fun cancellationInterruptsSilentNativeHandshake() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 3000
            val dial = NativeCore.openDial()
            try {
                val finished = async(Dispatchers.IO) {
                    assertFailsWith<TransportFailure> { NativeCore.directConnect(dial, "127.0.0.1:${listener.localPort}", ".", "") }
                }
                withContext(Dispatchers.IO) { listener.accept() }.use {
                    NativeCore.cancelDial(dial)
                    assertEquals(TransportError.Cancelled, withTimeout(1500) { finished.await() }.kind)
                }
            } finally { NativeCore.cancelDial(dial) }
        }
    }

    @Test fun logCursorAndInvalidJniInputs() {
        val handle = NativeCore.openLog()
        val line = "11-27 13:13:13.123 23 45 E 示例: 💻"
        try {
            val raw = "$line\n".toByteArray()
            raw.asList().chunked(3).forEach { part -> NativeCore.appendLog(handle, part.toByteArray(), part.size) }
            val packet = ByteBuffer.wrap(NativeCore.readLogPage(handle, 0))
            assertEquals(1L, packet.long)
            assertEquals(0L, packet.long)
            assertEquals(1L, packet.long)
            val size = packet.int
            val fields = IntArray(9) { packet.int }
            assertEquals(line, String(packet.array(), packet.position(), size, Charsets.UTF_8))
            assertEquals("示例", line.substring(fields[3], fields[4]))
            val row = logRow(line, fields, 0, null)
            assertEquals("11-27 13:13:13.123", row.timestamp)
            assertEquals("45", row.tid)
            assertTrue(NativeCore.readLogPage(handle,1).isEmpty())
            assertFailsWith<IOException> { NativeCore.appendLog(handle, ByteArray(1), 65536) }
            assertFailsWith<IOException> { NativeCore.readLogPage(handle, -1) }
            assertFailsWith<TransportFailure> { NativeCore.directRead(-1, -1, ByteArray(4), Int.MAX_VALUE, 4, 10) }
        } finally { NativeCore.closeLog(handle) }
        assertFailsWith<IOException> { NativeCore.readLogPage(handle, 0) }
    }

    @Test fun idleTransferCancelsItsWorker() = runBlocking {
        val cleaned = CompletableDeferred<Unit>()
        assertFailsWith<java.net.SocketTimeoutException> {
            transferWithProgress({}, 80) {
                try { awaitCancellation() } finally { cleaned.complete(Unit) }
            }
        }
        assertTrue(cleaned.isCompleted)
    }

    @Test fun mediumTransferMatchesDigestAndCancellingPullPreservesSession() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("RPROBE_QA_PORT") == "5587")
        val adb = requireNotNull(System.getenv("RPROBE_QA_ADB"))
        val target = connectWindows(adb, "127.0.0.1", 5587)
        val remote = "/data/local/tmp/rprobe-transfer-" + java.util.UUID.randomUUID()
        try {
            assertEquals("RProbeDesk-qa-target-5586", target.checked("cat /data/local/tmp/rprobedesk-qa-target").trim())
            val size = 32L * 1024 * 1024
            val source = object : java.io.InputStream() {
                val random = java.util.Random(121)
                var remaining = size
                override fun read() = error("Expected buffered reads")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (remaining == 0L) return -1
                    val count = minOf(remaining, len.toLong()).toInt()
                    java.util.Arrays.fill(b, off, off + count, random.nextInt().toByte())
                    remaining -= count
                    return count
                }
            }
            val sent = java.security.MessageDigest.getInstance("SHA-256")
            val received = java.security.MessageDigest.getInstance("SHA-256")
            var pushed = 0L
            var pulled = 0L
            val start = System.nanoTime()
            target.push(remote, java.security.DigestInputStream(source, sent), size) { bytes ->
                assertTrue(bytes > pushed); pushed = bytes
            }
            val uploaded = System.nanoTime()
            val sink = java.io.OutputStream.nullOutputStream()
            target.pull(remote, java.security.DigestOutputStream(sink, received)) { bytes ->
                assertTrue(bytes > pulled); pulled = bytes
            }
            println("32MiB push_ms=${(uploaded-start)/1_000_000} pull_ms=${(System.nanoTime()-uploaded)/1_000_000}")
            assertEquals(size, pushed); assertEquals(size, pulled)
            assertContentEquals(sent.digest(), received.digest())
            val job = launch {
                val transfer = coroutineContext.job
                target.pull(remote, sink) { if (it >= 131_072) transfer.cancel() }
            }
            withTimeout(3000) { job.join() }
            assertTrue(job.isCancelled)
            assertEquals("alive", target.checked("echo alive").trim())
        } finally {
            try { target.checked("rm -f " + remoteWord(remote)) } finally { target.close() }
        }
    }
}
