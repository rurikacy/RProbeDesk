package dev.rprobedesk

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidWirelessTest {
    /** The companion host reads the physical confirmation from a separate emulator console. */
    @Test fun pairAndCheckDeviceIdentity() = runBlocking {
        val runner = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        require(args.getString("qaWireless")=="emulator-5586")
        val files=runner.targetContext.cacheDir
        val code=File(files,"qa-wireless-code")
        val answer=File(files,"qa-wireless-answer")
        val request=File(files,"qa-wireless-confirm")
        val name="rprobe-wireless-qa-"+UUID.randomUUID()
        val context=object:ContextWrapper(runner.targetContext) {
            override fun getSharedPreferences(key:String, mode:Int) =
                baseContext.getSharedPreferences(if(key=="verified-adb-peers") name else key,mode)
        }
        val adb=AndroidAdb(context) {
            request.writeText("waiting")
            withTimeout(30_000) {
                while (!answer.isFile || answer.length()<8) delay(100)
                answer.readText().trim()
            }
        }
        val ip="10.0.2.2"
        val port=48372
        try {
            val pairingCode=code.readText().trim()
            check(code.delete())
            adb.pair(ip,48371,pairingCode)
            val first=adb.connect(ip,port)
            val pin=try {
                assertEquals("RProbeDesk-qa-target-5586",first.checked("cat /data/local/tmp/rprobedesk-qa-target").trim())
                requireNotNull(first.verifiedPeerKey).also { assertTrue(it.startsWith("tls:")) }
            } finally {first.close()}
            val second=adb.connect(ip,port,pin)
            try {assertEquals("verified",second.checked("echo verified").trim())} finally {second.close()}
            if (args.getString("qaFaults") == "true") {
                val phase = File(files,"qa-wireless-phase")
                val ready = File(files,"qa-wireless-ready")
                suspend fun switch(name: String) {
                    ready.delete()
                    phase.writeText(name)
                    withTimeout(90_000) { while (!ready.isFile || ready.readText() != name) delay(150) }
                }
                try {
                    switch("wifi-cycle")
                    val restored = adb.connect(ip,port,pin)
                    try {
                        assertEquals("RProbeDesk-qa-target-5586",restored.checked("cat /data/local/tmp/rprobedesk-qa-target").trim())
                        switch("impaired")
                        val bytes=ByteArray(1024*1024) { (it*17).toByte() }
                        val path="/data/local/tmp/rprobe-fault-"+UUID.randomUUID()
                        try {
                            restored.push(path,bytes.inputStream(),bytes.size.toLong())
                            val out=java.io.ByteArrayOutputStream()
                            restored.pull(path,out)
                            assertArrayEquals(bytes,out.toByteArray())
                        } finally { restored.checked("rm -f $path") }
                    } finally { restored.close() }
                } finally { phase.delete(); ready.delete() }
            }
            try {
                adb.connect(ip,port,"tls:"+"A".repeat(43)+"=").close()
                fail("A changed TLS pin must be rejected")
            } catch (_:PeerRejected) { }
        } finally {
            code.delete()
            request.delete()
            answer.delete()
            runner.targetContext.deleteSharedPreferences(name)
        }
    }
}
