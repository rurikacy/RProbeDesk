package dev.rprobedesk

import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

internal enum class SessionPhase(val code: Int) {
    Disconnected(0), Discovering(1), Pairing(2), Authenticating(3), Connecting(4), Connected(5), Suspect(6), Reconnecting(7), Backoff(8), AuthenticationFailed(9), DeviceUnavailable(10), Closing(11);
    companion object {
        private val byCode = entries.associateBy { it.code }
        fun decode(code: Int) = byCode[code] ?: throw IOException("未知会话状态 ($code)")
    }
}
internal enum class SessionEvent(val code: Int) {
    Discover(0), Pair(1), Connect(2), Authenticate(3), Verified(4), ProbeFailed(5), WaitToRetry(6), Retry(7), Rejected(8), Unavailable(9), Close(10)
}
internal enum class TransportError(val code: Int, val retry: Boolean, val label: String) {
    Timeout(1,true,"连接超时"), Network(2,true,"网络暂时不可用"), Refused(3,true,"连接端口不可用"),
    Authentication(4,false,"身份认证失败"), Identity(5,false,"设备身份已变化"), Closed(6,true,"传输已关闭"),
    Protocol(7,false,"设备协议不兼容"), Cancelled(8,false,"操作已取消"), Backend(9,true,"调试后端不可用"),
    Exhausted(10,true,"可用通道已满");
    companion object {
        private val byCode = entries.associateBy { it.code }
        fun decode(code: Int) = byCode[code] ?: Protocol
    }
}
internal open class TransportFailure(val code: Int, detail: String, cause: Throwable? = null) : IOException(TransportError.decode(code).label + " · " + detail,cause) {
    constructor(code: Int, detail: String) : this(code, detail, null)
    val kind get() = TransportError.decode(code)
}
internal class PeerRejected : TransportFailure {
    @JvmOverloads constructor(message: String, cause: Throwable? = null) : super(TransportError.Authentication.code,message,cause)
    constructor(code: Int, message: String) : super(code,message)
}


internal fun needsDeviceConfirmation(expected:String?, current:String?, previous:String?, known:Boolean,
                                     paired:Boolean, requirePair:Boolean):Boolean =
    when (NativeCore.trust(expected.orEmpty(),current.orEmpty(),previous.orEmpty(),known,paired,requirePair)) {
        0 -> false
        1 -> true
        2 -> throw PeerRejected("目标身份尚未确认，请在目标设备重新配对并核对通知")
        3 -> throw PeerRejected(TransportError.Identity.code, "目标 TLS 身份已变更，请手动核对设备")
        else -> throw PeerRejected(TransportError.Identity.code, "该设备此前使用 TLS，请核对目标的无线调试端口")
    }

/** One non-reusable native handle per connection lifecycle, independent of IP and port. */
internal class DeviceSessionLease : Closeable {
    private val handle = NativeCore.openSession().also { if (it == 0L) throw IOException("会话数量已达上限") }
    private var sequence = 0L
    private var waiting: kotlinx.coroutines.CancellableContinuation<Unit>? = null
    private var closed = false
    private val current = MutableStateFlow(SessionPhase.Disconnected)
    val phase = current.asStateFlow()

    fun observe(event: SessionEvent): Long {
        val result = NativeCore.signal(handle, event.code, Random.nextInt())
        check(result >= 0) { "Invalid session event ${current.value} / $event" }
        current.value = SessionPhase.decode((result ushr 32).toInt())
        return result and 0xffffffffL
    }

    private var transport = 0L
    fun own(session: DeviceSession) {
        val epoch = NativeCore.ownTransport(handle, session::close)
        if (epoch <= 0L) throw IOException("设备会话已关闭")
        transport = epoch
    }
    fun releaseTransport() {
        val epoch = transport
        transport = 0L
        if (epoch > 0L) NativeCore.releaseTransport(handle, epoch)
    }

    fun bind(endpoint: String, identity: String?) {
        if (NativeCore.bindSession(handle, endpoint, identity.orEmpty()) != 0) throw PeerRejected("目标身份已变更，请手动核对设备")
    }

    suspend fun awaitNext() = kotlinx.coroutines.suspendCancellableCoroutine<Unit> { pending ->
        val ticket: Long
        val status = synchronized(this) {
            ticket = ++sequence
            if (closed) return@synchronized -2
            if (waiting != null) return@synchronized -4
            waiting = pending
            NativeCore.schedule(handle, ticket, Runnable {
                val deliver = synchronized(this) {
                    (waiting === pending).also { if (it) waiting = null }
                }
                if (deliver) pending.resumeWith(Result.success(Unit))
            }).also { if (it != 0) waiting = null }
        }
        if (status != 0) {
            pending.resumeWith(Result.failure(IOException("会话定时器不可用 ($status)")))
        } else pending.invokeOnCancellation {
            synchronized(this) {
                if (waiting === pending) { waiting = null; NativeCore.cancelTimer(handle, ticket) }
            }
        }
    }

    fun wake() {
        val pending = synchronized(this) {
            val active = waiting ?: return
            waiting = null
            NativeCore.cancelTimer(handle, sequence)
            active
        }
        pending.resumeWith(Result.success(Unit))
    }

    override fun close() {
        val pending = synchronized(this) {
            if (closed) return
            closed = true
            waiting.also { waiting = null }
        }
        pending?.cancel()
        NativeCore.closeSession(handle)
        current.value = SessionPhase.Disconnected
    }
}
