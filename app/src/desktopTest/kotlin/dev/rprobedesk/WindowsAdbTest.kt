package dev.rprobedesk

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class WindowsAdbTest {
    @Test fun externalToolsAreCheckedBeforeUse() = runBlocking {
        val legacy = "Android Debug Bridge version 1.0.41\n -t ID   use transport id"
        checkWindowsTool("adb", legacy)
        assertTrue(assertFails { checkWindowsTool("adb", legacy, pairing = true) }.message!!.contains("无线配对"))
        checkWindowsTool("adb", "$legacy\n pair HOST[:PORT] [PAIRING CODE]", pairing = true)
        assertFails { checkWindowsTool("adb", "Android Debug Bridge version 1.0.39\n -s SERIAL") }
        val mirror = "scrcpy 3.3.4\n    -s, --serial=serial\n    --window-title=text\n    --no-clipboard-autosync\n"
        checkWindowsTool("scrcpy", mirror + "    --no-audio\n")
        assertFails { checkWindowsTool("scrcpy", mirror + "    --no-audio-playback\n") }
        val wrongTool = System.getenv("SystemRoot")?.let { File(it, "System32/whoami.exe") } ?: File("/usr/bin/false")
        val temp = java.nio.file.Files.createTempDirectory("rpd-tool-test").toFile()
        val wrong = File(temp, "adb.exe")
        try {
            wrongTool.copyTo(wrong)
            assertTrue(assertFails { verifyWindowsTool("adb", wrong.path) }.message!!.contains("重新选择"))
            assertTrue(wrong.delete())
            assertTrue(assertFails { verifyWindowsTool("adb", wrong.path) }.message!!.contains("重新选择"))
        } finally { wrong.delete(); temp.delete() }
        Unit
    }

    @Test fun endpointAndShellArgument() {
        assertEquals("127.0.0.1:5555", targetAddress("127.0.0.1", 5555))
        assertEquals("[::1]:60000", targetAddress("::1", 60000))
        assertEquals("'a'\\''b'", remoteWord("a'b"))
        assertFails { remoteWord("bad\u0000name") }
        assertFails { targetAddress("127.0.0.1", 65536) }
        assertEquals("报 告.bin", windowsSaveName("报 告.bin"))
        assertEquals(".._escape.txt", windowsSaveName("..\\escape.txt"))
        assertEquals("C__outside_.bin", windowsSaveName("C:\\outside\n.bin"))
        assertEquals("_NUL.txt", windowsSaveName("NUL.txt"))
    }

    @Test fun targetPublishPreservesConflictsAndMovesWithinSharedStorage() = runBlocking {
        val port = System.getenv("RPROBE_QA_PORT")?.toIntOrNull()
        assumeTrue("Set RPROBE_QA_PORT for the disposable emulator", port != null)
        val session = connectWindows(requireNotNull(System.getenv("RPROBE_QA_ADB")), "127.0.0.1", requireNotNull(port))
        try {
            assertEquals("1", session.checked("getprop ro.kernel.qemu").trim())
            val quoted = "报 告 \"A\" 'B' \u0024PATH"
            val script = "# " + "q".repeat(150_000) + "\n" +
                "read input && exit 8\nprintf '%s\\n' ${remoteWord(quoted)}\nprintf 'stderr-text\\n' >&2\nexit 7"
            val reply = session.shell(script)
            assertEquals(quoted, reply.stdout.trimEnd())
            assertEquals("stderr-text", reply.stderr.trimEnd())
            assertEquals(7, reply.exitCode)
            for (parent in listOf("/data/local/tmp", "/sdcard/Download")) {
                val dir = "$parent/rpd-publish-" + UUID.randomUUID()
                val src = if (parent == "/data/local/tmp") "$dir/报 告 'a'\n.bin" else "$dir/报 告 'a'.bin"
                val peer = "$dir/peer"
                val dst = "$dir/output"
                session.checked("mkdir -- " + remoteWord(dir))
                try {
                    val from = remoteWord(src)
                    val to = remoteWord(dst)
                    session.checked("printf 'source' > $from")
                    if (parent == "/data/local/tmp") {
                        session.checked("ln -s -- missing $to")
                        assertFails { session.publishFile(src, dst, "") }
                        assertEquals("missing", session.checked("readlink -- $to").trim())
                        assertEquals("source", session.checked("cat -- $from").trim())
                        session.checked("rm -- $to")
                        val otherVolume = "/sdcard/Download/rpd-volume-" + UUID.randomUUID()
                        assertEquals("", session.fileStamp(otherVolume))
                        try {
                            assertFails { session.publishFile(src, otherVolume, "") }
                            assertEquals("source", session.checked("cat -- $from").trim())
                            assertEquals("", session.fileStamp(otherVolume))
                        } finally { session.checked("rm -f -- " + remoteWord(otherVolume)) }
                    }
                    session.checked("printf existing > $to")
                    assertFails { session.publishFile(src, dst, "") }
                    val existing = session.fileStamp(dst)
                    session.checked("printf changed-after-consent > $to")
                    assertFails { session.publishFile(src, dst, existing) }
                    assertEquals("changed-after-consent", session.checked("cat -- $to").trim())
                    session.publishFile(src, dst, session.fileStamp(dst), session.fileStamp(src))
                    assertEquals("", session.fileStamp(src))
                    assertEquals("source", session.checked("cat -- $to").trim())
                    session.publishFile(dst, src, "", session.fileStamp(dst))
                    assertEquals("", session.fileStamp(dst))
                    assertEquals("source", session.checked("cat -- $from").trim())
                    session.checked("printf 'second' > " + remoteWord(peer))
                    val outcomes = coroutineScope {
                        listOf(src, peer).map { input ->
                            async { runCatching { session.publishFile(input, dst, "") }.isSuccess }
                        }.awaitAll()
                    }
                    assertEquals(1, outcomes.count { it })
                    val winner = if (outcomes.first()) "source" else "second"
                    val remaining = if (outcomes.first()) peer else src
                    assertEquals(winner, session.checked("cat -- $to").trim())
                    assertTrue(session.fileStamp(remaining).isNotEmpty())
                } finally {
                    session.checked("rm -f -- " + listOf(src, dst, peer).joinToString(" ", transform = ::remoteWord) +
                        " && rmdir -- " + remoteWord(dir))
                }
            }
        } finally { session.close() }
    }

    @Test fun windowsOutputPreservesDestinationIfChanged() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("rprobe-doc-test").toFile()
        val source = File(root, "source").apply { writeText("new") }
        val dest = File(root, "target").apply { writeText("old") }
        try {
            val timestamp = java.nio.file.Files.getLastModifiedTime(dest.toPath())
            val doc = WindowsDoc.forSave(dest)
            dest.writeText("alt")
            java.nio.file.Files.setLastModifiedTime(dest.toPath(), timestamp)
            assertFails { doc.writeFrom(source) }
            assertEquals("alt", dest.readText())
            WindowsDoc.forSave(dest).writeFrom(source)
            assertEquals("new", dest.readText())
        } finally {
            source.delete()
            dest.delete()
            root.delete()
        }
        Unit
    }

    /** Opt in only for an emulator created for this suite; package actions target the QA fixture. */
    @Test fun authorizedEmulatorRoundTrip() = runBlocking {
        val port = System.getenv("RPROBE_QA_PORT")?.toIntOrNull()
        assumeTrue("Set RPROBE_QA_PORT and RPROBE_QA_FIXTURE for the disposable emulator", port != null)
        val fixture = File(requireNotNull(System.getenv("RPROBE_QA_FIXTURE")))
        val adb = requireNotNull(System.getenv("RPROBE_QA_ADB"))
        val session = connectWindows(adb, "127.0.0.1", requireNotNull(port))
        try {
            assertEquals("1", session.checked("getprop ro.kernel.qemu").trim())
            val user = session.currentUser()
            assertTrue(session.checked("getprop ro.product.model").isNotBlank())
            val failure = session.shell("echo expected-error >&2; exit 7")
            assertEquals(7, failure.exitCode)
            assertTrue("expected-error" in failure.stderr)
            val scratch = "/data/local/tmp/rprobedesk-qa-${UUID.randomUUID()}"
            val remote = "$scratch/空 格 ' 文件.bin"
            session.checked("mkdir " + remoteWord(scratch))
            try {
                for (bytes in listOf(byteArrayOf(), ByteArray(300_003) { (it * 37).toByte() })) {
                    session.push(remote, bytes.inputStream(), bytes.size.toLong())
                    val actual = ByteArrayOutputStream()
                    session.pull(remote, actual)
                    assertContentEquals(bytes, actual.toByteArray())
                }
                session.checked("mv " + remoteWord(remote) + " " + remoteWord("$scratch/moved"))
                assertEquals(300003L, session.checked("stat -c %s " + remoteWord("$scratch/moved")).trim().toLong())
            } finally {
                session.shell("rm -f " + remoteWord(remote) + " " + remoteWord("$scratch/moved") + " && rmdir " + remoteWord(scratch))
            }
            val png = ByteArrayOutputStream()
            session.screenshot(png)
            val image = ImageIO.read(png.toByteArray().inputStream())
            assertTrue(image.width > 0 && image.height > 0)
            val pkg = "dev.rprobedesk.fixture"
            session.install(fixture, user)
            try {
                assertTrue(session.apps(user).any { it.name == pkg })
                session.checked("am start --user $user -n $pkg/.TargetsActivity")
                session.checked("am force-stop --user $user $pkg")
                session.checked("pm disable-user --user $user $pkg")
                assertTrue(session.apps(user).single { it.name == pkg }.frozen)
                session.checked("pm enable --user $user $pkg")
                assertTrue(!session.apps(user).single { it.name == pkg }.frozen)
                val marker = UUID.randomUUID().toString()
                session.checked("log -t RProbeQA " + remoteWord(marker))
                val logs = withTimeout(10000) { session.checked("logcat -d -v threadtime RProbeQA:I '*:S'") }
                assertTrue(logs.lineSequence().map { parseLog(it, null) }.any { it.tag == "RProbeQA" && marker in it.text })
            } finally {
                session.checked("pm uninstall --user $user $pkg")
            }
            assertTrue(session.apps(user).none { it.name == pkg })
        } finally { session.close() }
        assertFails { session.shell("true") }
        Unit
    }
}
