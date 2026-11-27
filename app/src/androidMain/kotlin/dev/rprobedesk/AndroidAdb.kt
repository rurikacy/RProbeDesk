package dev.rprobedesk

import android.content.Context
import android.net.InetAddresses
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.PairingConnectionCtx
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.conscrypt.Conscrypt

internal class AndroidAdb(context: Context, private val readCode: suspend (String) -> String?) {
    private val pins = context.getSharedPreferences("verified-adb-peers", Context.MODE_PRIVATE)
    private class Entry(val lock: Mutex = Mutex(), var users: Int = 0)
    private val pending = mutableMapOf<String, Entry>()
    private val paired = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private suspend fun <T> forHost(address: String, port: Int, work: suspend (String) -> T): T {
        val host = validate(address, port)
        val entry = synchronized(pending) {
            if (host !in pending && pending.size >= 32) throw TransportFailure(10, "会话数量已达上限")
            pending.getOrPut(host, ::Entry).also { it.users++ }
        }
        try { return entry.lock.withLock { work(host) } }
        finally { synchronized(pending) { if (--entry.users == 0) pending.remove(host) } }
    }
    private val random = SecureRandom()
    private val key: KeyStore.PrivateKeyEntry by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "RProbeDesk-ADB-TLS"
        if (!store.containsAlias(alias)) {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                // Conscrypt signs TLS 1.3 CertificateVerify using a padded digest and an RSA private-key operation.
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .build()
            KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        }
        store.getEntry(alias, null) as KeyStore.PrivateKeyEntry
    }

    suspend fun pair(address: String, port: Int, code: String) = forHost(address, port) { host ->
        require(code.matches(Regex("[0-9]{6}"))) { "请输入目标显示的六位配对码" }
        paired.remove(host)
        runInterruptible(Dispatchers.IO) {
            check(Conscrypt.isAvailable()) { "当前设备无法加载配对所需的 TLS 组件" }
            PairingConnectionCtx(host, port, code.toByteArray(Charsets.US_ASCII),
                key.privateKey, key.certificate, "RProbeDesk").use { it.start() }
        }
        paired[host] = SystemClock.elapsedRealtime()
    }

    suspend fun connect(address: String, port: Int, expectedPeer: String? = null): DeviceSession = forHost(address, port) { host ->
        val serial = if (':' in host) "[$host]:$port" else "$host:$port"
        var acquired: AdbConnection? = null
        try { withContext(Dispatchers.IO) {
            check(Conscrypt.isAvailable()) { "无法加载无线调试的 TLS 组件" }
            val connection = AdbConnection.create(host, port, key.privateKey, key.certificate, 30)
            acquired = connection
            try {
                if (!runInterruptible { connection.connect(15, TimeUnit.SECONDS, false) })
                    throw java.net.SocketTimeoutException("连接超时，请检查地址、端口，并在目标设备上允许调试")
                val pin = peerKey(connection)
                val session = AndroidSession(serial, connection, pin?.let { "tls:$it" })
                val previous = pins.getString("endpoint:$serial", null)
                val recent = paired[host]?.let { SystemClock.elapsedRealtime() - it < 300_000 } == true
                val known = pin != null && pins.getStringSet("address:$host", emptySet()) == setOf(pin)
                val confirm = needsDeviceConfirmation(expectedPeer, session.verifiedPeerKey,
                    previous?.let { "tls:$it" }, known, recent, requirePair = true)
                if (confirm) {
                    paired.remove(host)
                    val nonce = random.nextInt(100_000_000).toString().padStart(8, '0')
                    val posted = session.shell("cmd notification post -t RProbeDesk RProbeDesk-confirm $nonce")
                    check(posted.exitCode == 0) { "目标无法显示设备确认通知，请查看目标通知设置" }
                    val entered = withTimeout(90_000) { readCode(serial) }
                    if (entered != nonce) throw PeerRejected("设备确认未通过，请重新配对")
                }
                if (pin != null && previous != pin) {
                    check(pins.edit().putString("endpoint:$serial", pin)
                        .putStringSet("address:$host", pins.getStringSet("address:$host", emptySet()).orEmpty() + pin)
                        .commit()) { "无法保存已确认设备" }
                }
                session
            } catch (error: Throwable) {
                if (error is AdbAuthenticationFailedException) throw PeerRejected("请在目标设备上允许此主控调试后重试",error)
                if (error is AdbPairingRequiredException) {
                    throw PeerRejected("目标未授权此主控。请在目标设备打开无线调试的配对码窗口，重新配对。", error)
                }
                throw error
            }
        } } catch (e: Throwable) {
            acquired?.close()
            when (e) {
                is java.net.SocketTimeoutException -> throw TransportFailure(1, e.message.orEmpty(), e)
                is java.net.NoRouteToHostException -> throw TransportFailure(2, e.message.orEmpty(), e)
                is java.net.ConnectException -> throw TransportFailure(3, e.message.orEmpty(), e)
                else -> throw e
            }
        }
    }

    private fun validate(address: String, port: Int): String {
        val host = address.removeSurrounding("[", "]")
        require(InetAddresses.isNumericAddress(host) && port in 1..65535) { "IP 地址或端口无效" }
        return host
    }

    // libadb 3.1.1 only exposes the I/O streams. Read the *same* packaged Conscrypt socket before opening any service.
    private fun peerKey(connection: AdbConnection): String? {
        val stream = AdbConnection::class.java.getDeclaredField("mTlsInputStream").apply { isAccessible = true }.get(connection)
        if (stream == null) return null // Classic TCP ADB has no server certificate.
        check(stream.javaClass.name.startsWith("org.conscrypt.")) { "当前 TLS 组件无法核对目标身份" }
        val socketField = stream.javaClass.declaredFields.singleOrNull { SSLSocket::class.java.isAssignableFrom(it.type) }
        check(socketField != null) { "当前 TLS 组件无法核对目标身份" }
        val socket = socketField.apply { isAccessible = true }.get(stream) as SSLSocket
        val cert = socket.session.peerCertificates.firstOrNull() ?: error("目标没有提供 TLS 身份")
        return NativeCore.fingerprint(cert.publicKey.encoded)
    }
}
