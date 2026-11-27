package dev.rprobedesk

import android.graphics.BitmapFactory
import android.net.InetAddresses
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidDeviceTest {
    private val runner get() = InstrumentationRegistry.getInstrumentation()

    @Test fun findsAdvertisedAdbEndpoint() = runBlocking {
        val context=runner.targetContext
        val manager=context.getSystemService(android.net.nsd.NsdManager::class.java)
        val registered=CompletableDeferred<String>()
        val listener=object:android.net.nsd.NsdManager.RegistrationListener {
            override fun onServiceRegistered(info:android.net.nsd.NsdServiceInfo) { registered.complete(info.serviceName) }
            override fun onRegistrationFailed(info:android.net.nsd.NsdServiceInfo,code:Int) { registered.completeExceptionally(AssertionError("Registration failed: $code")) }
            override fun onServiceUnregistered(info:android.net.nsd.NsdServiceInfo) {}
            override fun onUnregistrationFailed(info:android.net.nsd.NsdServiceInfo,code:Int) {}
        }
        val info=android.net.nsd.NsdServiceInfo().apply {
            serviceName="RProbeDesk-QA-"+UUID.randomUUID().toString().take(8)
            serviceType="_adb._tcp"
            port=41555
        }
        manager.registerService(info,android.net.nsd.NsdManager.PROTOCOL_DNS_SD,listener)
        try {
            val name=withTimeout(10_000) { registered.await() }
            assertTrue(androidDiscovery(context).discover {}.any { it.name==name && it.port==41555 && !it.pairing })
        } finally { manager.unregisterService(listener) }
    }

    @Test fun launcherOpensAttachedWindow() {
        val context = runner.targetContext
        val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        val activity = runner.startActivitySync(intent)
        try {
            runner.waitForIdleSync()
            runner.runOnMainSync {
                assertTrue(activity is MainActivity)
                assertTrue(activity.window.decorView.isAttachedToWindow)
                assertNotNull(activity.window.decorView.windowToken)
            }
        } finally {
            runner.runOnMainSync { activity.finish() }
        }
    }

    private fun onTestTarget(block:suspend (DeviceSession)->Unit) = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val host=requireNotNull(args.getString("targetIp")) { "指定已由本主控配对的测试模拟器 targetIp" }
        val port=parsePort(requireNotNull(args.getString("targetPort")))
        val marker=requireNotNull(args.getString("qaMarker"))
        require(InetAddresses.isNumericAddress(host) && marker.matches(Regex("[a-zA-Z0-9-]{8,64}")))
        val adb=AndroidAdb(runner.targetContext) { error("测试只使用已在主控 UI 核对的目标") }
        val target=adb.connect(host,port)
        try {
            withTimeout(100_000) {
                check(target.checked("getprop ro.kernel.qemu").trim()=="1") { "拒绝操作非测试模拟器" }
                check(target.checked("cat /data/local/tmp/rprobedesk-qa-target").trim()==marker) { "目标缺少本轮模拟器标记" }
                block(target)
            }
        } finally { target.close() }
    }

    @Test fun shellUserAndPanel() = onTestTarget { device ->
        val peer=parseProbe(device.checked(probeCommand(false)))
        val user=device.currentUser()
        assertEquals(user.toString(),peer.facts["Android 用户"])
        val reply=device.shell("printf '正常输出\\n'; printf '标准错误\\n' >&2; exit 7")
        assertEquals("正常输出\n",reply.stdout)
        assertEquals("标准错误\n",reply.stderr)
        assertEquals(7,reply.exitCode)
        val key="rprobedesk_qa_"+UUID.randomUUID().toString().replace("-","")
        val settings="settings --user $user"
        assertEquals("null",device.checked("$settings get secure $key").trim())
        try {
            device.checked("$settings put secure $key rprobe-fixture")
            assertEquals("rprobe-fixture",device.checked("$settings get secure $key").trim())
        } finally { device.checked("$settings delete secure $key") }
        assertEquals("null",device.checked("$settings get secure $key").trim())
        val a=parseMetrics(device.checked(metricCommand),null,SystemClock.elapsedRealtime())
        delay(350)
        val b=parseMetrics(device.checked(metricCommand),a.ticks,SystemClock.elapsedRealtime())
        assertTrue(b.captured>a.captured)
        assertTrue("整机内存应可读",(b.memory.endsWith(" GB") || b.memory.endsWith(" MB")) && b.memoryPercent != null)
        assertTrue(b.temperature=="—" || b.temperature.contains("°C"))
        assertTrue(b.cpu?.let { it in 0.0..100.0 } ?: b.cpuSource.isNotBlank())
    }

    @Test fun binaryFilesAndRealScreenshot() = onTestTarget { device ->
        val dir="/data/local/tmp/rprobedesk-qa-"+UUID.randomUUID()
        val zero="$dir/空文件"
        val source="$dir/空 格和'引号'.bin"
        val moved="$dir/已移动.bin"
        device.checked("mkdir -- ${remoteArg(dir)}")
        try {
            for ((path,bytes) in listOf(zero to ByteArray(0),source to ByteArray(160_019) { ((it*31) xor (it ushr 3)).toByte() })) {
                var written=0L
                bytes.inputStream().use { device.push(path,it,bytes.size.toLong()) { count -> written=count } }
                assertEquals(bytes.size.toLong(),written)
                var received=0L
                val output=ByteArrayOutputStream()
                device.pull(path,output) { count -> received=count }
                assertEquals(bytes.size.toLong(),received)
                assertArrayEquals(bytes,output.toByteArray())
            }
            assertTrue(device.files(dir).any { it.name==source.substringAfterLast('/') && it.bytes==160_019L })
            device.publishFile(source, moved, "", device.fileStamp(source))
            val names=device.files(dir).map { it.name }
            assertTrue(moved.substringAfterLast('/') in names)
            assertFalse(source.substringAfterLast('/') in names)
            val png=ByteArrayOutputStream()
            device.screenshot(png)
            val bytes=png.toByteArray()
            assertArrayEquals(byteArrayOf(-119,80,78,71,13,10,26,10),bytes.take(8).toByteArray())
            val size=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,size)
            assertTrue(size.outWidth>0 && size.outHeight>0)
            assertEquals("image/png",size.outMimeType)
        } finally {
            device.checked("rm -f -- ${listOf(zero,source,moved).joinToString(" ",transform=::remoteArg)}")
            device.checked("rmdir -- ${remoteArg(dir)}")
        }
    }

    @Test fun fixturePackageLifecycle() = onTestTarget { device ->
        val user=device.currentUser()
        val apk=File.createTempFile("rprobe-owned-", ".apk", runner.targetContext.cacheDir)
        val fixture="dev.rprobedesk.fixture"
        var installed=false
        try {
            val original=runner.context.assets.open("fixture-debug.apk").use { it.readBytes() }
            coroutineScope {
                ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { server ->
                    server.soTimeout=10_000
                    val serve=async(Dispatchers.IO) {
                        server.accept().use { peer ->
                            peer.soTimeout=10_000
                            val request=peer.getInputStream().bufferedReader(Charsets.US_ASCII)
                            check(request.readLine().startsWith("GET /fixture.apk "))
                            while(!request.readLine().isNullOrEmpty()) { /* consume request headers */ }
                            peer.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Length: ${original.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                                write(original)
                                flush()
                            }
                        }
                    }
                    val authority="127.0.0.1:${server.localPort}"
                    var consent=0
                    val origin=downloadApk("http://$authority/fixture.apk",apk,{ site ->
                        assertEquals(authority,site)
                        consent++
                    },{ _,_ -> })
                    assertEquals("127.0.0.1",origin)
                    assertEquals(1,consent)
                    serve.await()
                }
            }
            assertArrayEquals(original,apk.readBytes())
            assertEquals(fixture,readApk(apk).packageName)
            @Suppress("DEPRECATION")
            val archive=runner.targetContext.packageManager.getPackageArchiveInfo(apk.absolutePath,0)
            assertEquals(fixture,archive?.packageName)
            assertTrue(device.install(apk,user).lineSequence().any { it.trim()=="Success" })
            installed=true
            val record=device.apps(user).single { it.name==fixture }
            assertFalse(record.system)
            device.checked("pm enable --user $user $fixture")
            val started=device.checked("am start -W --user $user -n $fixture/.TargetsActivity")
            assertTrue(started,started.contains("Status: ok",ignoreCase=true))
            assertTrue(device.checked("pidof $fixture").isNotBlank())
            device.checked("am force-stop --user $user $fixture")
            withTimeout(5_000) { while(device.shell("pidof $fixture").stdout.isNotBlank()) delay(150) }
            device.checked("pm disable-user --user $user $fixture")
            assertTrue(device.apps(user).single { it.name==fixture }.frozen)
            device.checked("pm enable --user $user $fixture")
            assertFalse(device.apps(user).single { it.name==fixture }.frozen)
            assertEquals("Success",device.checked("pm uninstall --user $user $fixture").trim())
            installed=false
            assertFalse(device.apps(user).any { it.name==fixture })
        } finally {
            if(installed) device.shell("pm uninstall --user $user $fixture")
            apk.delete()
        }
    }

    @Test fun httpsInstallFromReleaseWorkspace() = onTestTarget { device ->
        val args = InstrumentationRegistry.getArguments()
        val port = parsePort(requireNotNull(args.getString("tlsPort")))
        val cert = android.util.Base64.decode(requireNotNull(args.getString("qaCaDer")), android.util.Base64.DEFAULT)
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("ephemeral-qa", CertificateFactory.getInstance("X.509").generateCertificate(cert.inputStream()))
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val tls = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        val original = HttpsURLConnection.getDefaultSSLSocketFactory()
        val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
        val context = runner.targetContext
        val activity = runner.startActivitySync(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))) as MainActivity
        val host = MainActivity::class.java.getDeclaredField("host").apply { isAccessible = true }.get(activity) as AndroidHost
        val model = withContext(Dispatchers.Main) { WorkspaceModel(host, device, DeskPreferences(host.settingsDir), "HTTPS QA target") }
        val user = device.currentUser()
        val pkg = "dev.rprobedesk.fixture"
        var initiallyAbsent = false
        suspend fun submit(server: String) = withContext(Dispatchers.Main) {
            model.installApk("https://$server:$port/fixture.apk")
            withTimeout(20_000) { while (model.busy && model.question == null) delay(50) }
        }
        fun evidence(text: String) {
            runner.sendStatus(0, android.os.Bundle().apply { putString("q05", text) })
        }
        try {
            initiallyAbsent = device.apps(user).none { it.name == pkg }
            check(initiallyAbsent) { "专用测试包已存在" }
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
            submit("localhost")
            assertNull(model.question)
            assertTrue(model.status, model.problem && model.status.contains("SSL"))
            assertTrue(device.apps(user).none { it.name == pkg })
            evidence("wrong hostname rejected: ${model.status}; installation confirmation absent")

            submit("127.0.0.1")
            val confirmation = requireNotNull(model.question) { model.status }
            assertEquals("安装", confirmation.action)
            assertTrue(confirmation.detail, pkg in confirmation.detail && "(1)" in confirmation.detail && "127.0.0.1" in confirmation.detail)
            assertTrue(device.apps(user).none { it.name == pkg })
            withContext(Dispatchers.Main) {
                model.respond(true)
                withTimeout(30_000) { while (model.busy) delay(50) }
            }
            assertFalse(model.status, model.problem)
            val result = device.apps(user).single { it.name == pkg }
            assertEquals("1", result.version)
            evidence("confirmed and installed $pkg version ${result.version} for Android user $user")
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(original)
            assertSame(original, HttpsURLConnection.getDefaultSSLSocketFactory())
            assertSame(verifier, HttpsURLConnection.getDefaultHostnameVerifier())
            runner.runOnMainSync { model.close(); activity.finish() }
            if (initiallyAbsent && device.apps(user).any { it.name == pkg }) {
                assertEquals("Success", device.checked("pm uninstall --user $user $pkg").trim())
                assertTrue(device.apps(user).none { it.name == pkg })
                evidence("temporary fixture removed; original TLS factory and verifier restored")
                val packages = device.apps(user).sortedBy { it.name }
                val snapshot = packages.joinToString("\n") { "${it.name}|${it.version}|${it.system}|${it.frozen}" }
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(snapshot.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                evidence("Q01 release=${device.checked("getprop ro.build.version.release").trim()} user=$user packages=${packages.size} sha256=$digest")
            }
        }
    }

    @Test fun liveFilteredLogcat() = onTestTarget { device -> coroutineScope {
        val tag="RProbeDeskQA"
        val token="rprobe-"+UUID.randomUUID()
        val since=device.checked("date +%s.%N").trim()
        require(since.matches(Regex("[0-9]+\\.[0-9]+")))
        val ready=CompletableDeferred<Unit>()
        val record=CompletableDeferred<DeviceLog>()
        val stream=async {
            device.shell("echo READY; logcat -v threadtime -b main -T ${remoteArg(since)} -m 1 -e $token $tag:I '*:S'") { raw,err ->
                if(!err && raw=="READY") ready.complete(Unit)
                if(!err && token in raw) record.complete(parseLog(raw,null))
            }
        }
        withTimeout(12_000) {
            ready.await()
            device.checked("log -p i -t $tag $token")
            val row=record.await()
            assertEquals('I',row.level)
            assertEquals(tag,row.tag)
            assertTrue(row.pid.toInt()>0)
            assertTrue(LogFilter('I',tag,row.pid,token).accepts(row))
            assertFalse(LogFilter('E',tag,row.pid,token).accepts(row))
            assertEquals(0,stream.await().exitCode)
        }
    } }

    @Test fun systemPickerPreservesTransportAndMirrorRendersAfterReturning() = onTestTarget { device -> coroutineScope {
        val context = runner.targetContext
        val activity = runner.startActivitySync(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))) as MainActivity
        val host = MainActivity::class.java.getDeclaredField("host").apply { isAccessible = true }.get(activity) as AndroidHost
        suspend fun until(condition: () -> Boolean) = withTimeout(20_000) { while (!condition()) delay(100) }
        try {
            val picker = async { host.pickOpen("") }
            until { runner.uiAutomation.rootInActiveWindow?.packageName?.toString()?.endsWith("documentsui") == true }
            until { !host.foreground.value }
            val manager = context.getSystemService(android.app.ActivityManager::class.java)
            @Suppress("DEPRECATION")
            fun pickerRunning() = manager.getRunningServices(30).any { it.service.className == TransferPickerService::class.java.name && it.foreground }
            until { pickerRunning() }
            assertEquals("during",device.checked("echo during").trim())
            runner.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            assertNull(withTimeout(15_000) { picker.await() })
            until { host.foreground.value && !pickerRunning() }
            assertEquals("after",device.checked("echo after").trim())
            device.checked("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; am start -W -a android.settings.SETTINGS")
            host.startMirror(device)
            assertTrue(host.mirrorActive.value)
            val window = AndroidHost::class.java.getDeclaredField("mirror").apply { isAccessible = true }.get(host)
            val surface = MirrorWindow::class.java.getDeclaredField("video").apply { isAccessible = true }.get(window) as android.view.SurfaceView
            val picture = android.graphics.Bitmap.createBitmap(32,32,android.graphics.Bitmap.Config.ARGB_8888)
            try {
                withTimeout(25_000) {
                    while (true) {
                        delay(300)
                        val copied = CompletableDeferred<Int>()
                        android.view.PixelCopy.request(surface,picture,{ copied.complete(it) },android.os.Handler(android.os.Looper.getMainLooper()))
                        if (copied.await() != android.view.PixelCopy.SUCCESS) continue
                        val colors = IntArray(1024)
                        picture.getPixels(colors,0,32,0,0,32,32)
                        if (colors.toSet().size >= 8) break
                    }
                }
            } finally { picture.recycle() }
            host.stopMirror()
            until { !host.mirrorActive.value }
            assertEquals("retained",device.checked("echo retained").trim())
        } finally {
            host.stopMirror()
            runner.runOnMainSync { activity.finish() }
        }
    } }
}
