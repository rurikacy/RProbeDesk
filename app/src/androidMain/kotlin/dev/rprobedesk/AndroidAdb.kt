package dev.rprobedesk

import android.content.Context
import android.net.InetAddresses
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.PairingConnectionCtx
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
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
    private val lock = Mutex()
    private val paired = mutableMapOf<String, Long>()
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

    suspend fun pair(address: String, port: Int, code: String) = lock.withLock {
        val host = validate(address, port)
        require(code.matches(Regex("[0-9]{6}"))) { "请输入目标显示的六位配对码" }
        paired.remove(host)
        runInterruptible(Dispatchers.IO) {
            check(Conscrypt.isAvailable()) { "当前设备无法加载配对所需的 TLS 组件" }
            PairingConnectionCtx(host, port, code.toByteArray(Charsets.US_ASCII),
                key.privateKey, key.certificate, "RProbeDesk").use { it.start() }
        }
        paired[host] = SystemClock.elapsedRealtime()
    }

    suspend fun connect(address: String, port: Int): DeviceSession = lock.withLock {
        val host = validate(address, port)
        val serial = if (':' in host) "[$host]:$port" else "$host:$port"
        withContext(Dispatchers.IO) {
            check(Conscrypt.isAvailable()) { "无法加载无线调试的 TLS 组件" }
            val connection = AdbConnection.create(host, port, key.privateKey, key.certificate, 30)
            try {
                check(connection.connect(12, TimeUnit.SECONDS, true)) { "目标连接超时，请核对无线调试主页端口" }
                val pin = peerKey(connection)
                val session = AndroidSession(serial, connection, pin)
                val previous = pins.getString("endpoint:$serial", null)
                if (previous != pin) {
                    val known = previous == null && pins.getStringSet("address:$host", emptySet()) == setOf(pin)
                    if (!known) {
                        val time = paired.remove(host)
                        check(time != null && SystemClock.elapsedRealtime() - time < 300_000) {
                            "目标身份尚未确认或已更改。请在目标设备重新配对，再使用连接端口连接。"
                        }
                        val nonce = random.nextInt(100_000_000).toString().padStart(8, '0')
                        val posted = session.shell("cmd notification post -t RProbeDesk RProbeDesk-confirm $nonce")
                        check(posted.exitCode == 0) { "目标无法显示设备确认通知，请查看目标通知设置" }
                        val entered = withTimeout(90_000) { readCode(serial) }
                        check(entered == nonce) { "设备确认未通过，请重新配对" }
                    }
                    check(pins.edit().putString("endpoint:$serial", pin)
                        .putStringSet("address:$host", pins.getStringSet("address:$host", emptySet()).orEmpty() + pin)
                        .commit()) { "无法保存已确认设备" }
                }
                session
            } catch (error: Throwable) {
                connection.close()
                if (error is AdbPairingRequiredException || error is AdbAuthenticationFailedException) {
                    throw IOException("目标未授权此主控。请在目标设备打开无线调试的配对码窗口，重新配对。", error)
                }
                throw error
            }
        }
    }

    private fun validate(address: String, port: Int): String {
        val host = address.removeSurrounding("[", "]")
        require(InetAddresses.isNumericAddress(host) && port in 1..65535) { "IP 地址或端口无效" }
        return host
    }

    // libadb 3.1.1 only exposes the I/O streams. Read the *same* packaged Conscrypt socket before opening any service.
    private fun peerKey(connection: AdbConnection): String {
        val stream = AdbConnection::class.java.getDeclaredField("mTlsInputStream").apply { isAccessible = true }.get(connection)
        check(stream != null && stream.javaClass.name.startsWith("org.conscrypt.")) { "请选择无线调试的 TLS 连接端口" }
        val socketField = stream.javaClass.declaredFields.singleOrNull { SSLSocket::class.java.isAssignableFrom(it.type) }
        check(socketField != null) { "当前 TLS 组件无法核对目标身份" }
        val socket = socketField.apply { isAccessible = true }.get(stream) as SSLSocket
        val cert = socket.session.peerCertificates.firstOrNull() ?: error("目标没有提供 TLS 身份")
        return Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded), Base64.NO_WRAP)
    }
}
