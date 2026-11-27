package dev.rprobedesk

import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

internal enum class SessionPhase {
    Disconnected, Discovering, Pairing, Authenticating, Connecting, Connected,
    Suspect, Reconnecting, Backoff, AuthenticationFailed, DeviceUnavailable
}
internal enum class SessionEvent {
    Discover, Pair, Connect, Authenticate, Verified, ProbeFailed, WaitToRetry, Retry, Rejected, Unavailable
}
internal class PeerRejected @JvmOverloads constructor(message: String, cause: Throwable? = null) : IOException(message, cause)

internal fun needsDeviceConfirmation(expected:String?, current:String?, previous:String?, known:Boolean,
                                     paired:Boolean, requirePair:Boolean):Boolean =
    when (NativeCore.trust(expected.orEmpty(),current.orEmpty(),previous.orEmpty(),known,paired,requirePair)) {
        0 -> false
        1 -> true
        2 -> throw PeerRejected("目标身份尚未确认，请在目标设备重新配对并核对通知")
        3 -> throw PeerRejected("目标 TLS 身份已变更，请手动核对设备")
        else -> throw PeerRejected("该设备此前使用 TLS，请核对目标的无线调试端口")
    }

/** One non-reusable native handle per connection lifecycle, independent of IP and port. */
internal class DeviceSessionLease : Closeable {
    private val handle = NativeCore.openSession().also { check(it != 0L) }
    private val current = MutableStateFlow(SessionPhase.Disconnected)
    val phase = current.asStateFlow()

    fun observe(event: SessionEvent): Long {
        val result = NativeCore.signal(handle, event.ordinal, Random.nextInt())
        check(result >= 0) { "Invalid session event ${current.value} / $event" }
        current.value = SessionPhase.entries[(result ushr 32).toInt()]
        return result and 0xffffffffL
    }

    override fun close() {
        NativeCore.closeSession(handle)
        current.value = SessionPhase.Disconnected
    }
}
