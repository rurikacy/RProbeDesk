package dev.rprobedesk

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class WorkspacesTest {
    @Test fun bulkDirectoryRecordsKeepDirectoryLinksAndTimestamps() {
        val rows=parseFiles("drwxrws--- 4096 145.9\nDownload\u0000ld 20 146.1\nsdcard\u0000-rw-r----- 80 150.0\n报 告\u0000")
        assertEquals(listOf("Download","sdcard","报 告"),rows.map { it.name })
        assertTrue(rows[1].directory && rows[1].link && !rows[1].regular)
        assertEquals(145L,rows[0].modified)
    }

    @Test fun fileNamesAndArgumentsKeepBoundaries() {
        val rows = parseFiles("81a4 33 120\n测试 'a'\n b\u000040ed 0 130\nfolder\u0000")
        assertTrue(rows.first().directory)
        assertEquals("测试 'a'\n b", rows.last().name)
        assertEquals(33L, rows.last().bytes)
        assertEquals("/dir/测试 'a'\n b", remotePath("/dir/", rows.last().name))
        assertEquals("'a'\\''b'", remoteArg("a'b"))
        assertFailsWith<IllegalArgumentException> { remotePath("/", "../out") }
        assertFailsWith<IllegalArgumentException> { remotePath("/", "..") }
        val types = parseFiles("a1ff 3 1\nlink\u000010a4 0 1\nfifo\u000081a4 2 1\nplain\u0000").associateBy { it.name }
        assertTrue(types.getValue("link").link)
        assertFalse(types.getValue("link").regular)
        assertFalse(types.getValue("fifo").regular)
        assertTrue(types.getValue("plain").regular)
    }

    @Test fun logFiltersUsePriorityTagPidAndStackTrace() {
        val line = parseLog("11-20 20:00:01.023   111  222 E Probe   : sample", null)
        assertEquals('E', line.level)
        assertEquals("Probe", line.tag)
        assertTrue(LogFilter('W', "pro", "111", "SAMPLE").accepts(line))
        assertFalse(LogFilter(pid = "222").accepts(line))
        assertTrue(LogFilter('E', "probe").accepts(parseLog("    at Some.test()", line)))
        assertFalse(LogFilter('E').accepts(parseLog("-------- beginning of main", line)))
    }

    @Test fun directLinkRequiresConsentAtEachCleartextHop(): Unit = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val body = byteArrayOf(0, 1, 2, -1)
        server.createContext("/redirect") { req ->
            req.responseHeaders.add("Location", "/test"); req.sendResponseHeaders(302, -1); req.close()
        }
        server.createContext("/test") { req ->
            req.sendResponseHeaders(200, body.size.toLong()); req.responseBody.use { it.write(body) }
        }
        val target = File.createTempFile("rpd-test-", ".apk")
        try {
            server.start()
            var confirmations = 0
            val url = "http://127.0.0.1:${server.address.port}/redirect"
            val domain = downloadApk(url, target, { confirmations++ }) { _, _ -> }
            assertEquals("127.0.0.1", domain)
            assertEquals(2, confirmations)
            assertContentEquals(body, target.readBytes())
            assertFails { downloadApk("file:///tmp/file.apk", target, {}) { _, _ -> } }
            val https = URI("https://example.test/test.apk")
            assertEquals(URI("https://example.test/next"), apkRedirect(https, "/next"))
            assertFails { apkRedirect(https, "HTTP://example.test/unsafe") }
            assertFails { apkRedirect(https, "file:///unsafe.apk") }
        } finally { target.delete(); server.stop(0) }
    }

    @Test fun readsAndroidCompiledManifestWithUnicodeVersion() {
        // aapt2 link of the manifest for dev.example.probe with versionCode 7 and versionName 三.二.
        val bytes = requireNotNull(javaClass.getResourceAsStream("/apk/test.axml")).use { it.readBytes() }
        assertEquals(ApkDetails("dev.example.probe", 7, "三.二"), readManifest(bytes))
        assertFails { readManifest(bytes.copyOfRange(0, 80)) }
        assertFails { readManifest("<html>".toByteArray()) }
        val split = requireNotNull(javaClass.getResourceAsStream("/apk/split.axml")).use { it.readBytes() }
        assertContains(assertFails { readManifest(split) }.message.orEmpty(), "分包")
    }
}

