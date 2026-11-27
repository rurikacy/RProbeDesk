package dev.rprobedesk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.io.IOException

internal fun deskTime(millis: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))

internal class DeskController(val host: HostBridge, val prefs: DeskPreferences, val scope: CoroutineScope) {
    val updates = UpdateSettingsState()
    var page by mutableStateOf("设备")
    val missingTools = mutableStateListOf<String>().apply {
        if (host.desktop) addAll(listOf("adb", "scrcpy").filterNot(host::hasSavedToolPath))
    }
    var toolToConfigure by mutableStateOf<String?>(null)
    fun openToolSettings(tool:String) {
        toolToConfigure=tool
        page="设置"
    }
    var session by mutableStateOf<DeviceSession?>(null)
        private set
    var workspace by mutableStateOf(WorkspaceModel(host, null, prefs))
        private set
    private val sessionNotes = mutableMapOf<Pair<String, String>, SessionReminders>()
    private var verifiedTarget: Pair<String, String>? = null
    private var activeRecord by mutableStateOf<DeskDevice?>(null)
    val activeDevice: DeskDevice? get() = activeRecord?.let { record ->
        prefs.devices.firstOrNull { it.id == record.id && it.address == record.address } ?: record
    }
    var lastSampleDevice by mutableStateOf<DeskDevice?>(null)
        private set
    var connecting by mutableStateOf<DeskDevice?>(null)
        private set
    var review by mutableStateOf<String?>(null)
        private set
    private var reviewAnswer:CompletableDeferred<Boolean>? = null
    var message by mutableStateOf<String?>(prefs.error)
    var metrics by mutableStateOf<DeskMetrics?>(null)
    var facts by mutableStateOf<Map<String, String>>(emptyMap())
    var panelDetails by mutableStateOf<PanelDetails?>(null)
    var samplingError by mutableStateOf<String?>(null)
    var snapshotBusy by mutableStateOf(false)
    var mirrorBusy by mutableStateOf(false)
    private var generation = 0L
    private var connectionJob: Job? = null
    private var lease: DeviceSessionLease? = null
    private var healthJob: Job? = null
    private var lastTraffic = 0L
    private var recoveryJob: Job? = null
    private var discoveryJob: Job? = null
    private var serviceName: String? = null
    private var mediaJob: Job? = null
    private var ownsMirror = false
    var selectedInWorkbench = true
    private var screenshotJob: Job? = null
    val chosen get() = activeDevice ?: prefs.devices.firstOrNull { it.id == prefs.selectedId } ?: prefs.devices.firstOrNull()

    fun disconnect() {
        recoveryJob?.cancel()
        recoveryJob = null
        discoveryJob?.cancel()
        serviceName = null
        try { dropTransport() } finally { lease?.close(); lease = null }
    }

    private fun dropTransport() {
        generation++
        healthJob?.cancel()
        healthJob = null
        reviewAnswer?.cancel()
        reviewAnswer=null
        review=null
        connectionJob?.cancel()
        mediaJob?.cancel()
        screenshotJob?.cancel()
        if (ownsMirror) {
            host.stopMirror()
            ownsMirror = false
        }
        val old = session
        if (old != null) {
            workspace = workspace.disconnectedView()
            verifiedTarget?.let { key ->
                if (workspace.reminders == SessionReminders()) sessionNotes.remove(key)
                else sessionNotes[key] = workspace.reminders
            }
            verifiedTarget = null
        }
        session = null
        try { lease?.releaseTransport() } catch (e: Exception) { message = e.localizedMessage ?: "关闭会话失败" }
        connecting = null
        activeRecord = null
        mirrorBusy = false
        snapshotBusy = false
    }

    fun close() {
        disconnect()
        workspace.close()
    }

    fun connect(device: DeskDevice) {
        if (session != null && activeDevice?.id == device.id && activeDevice?.address == device.address) {
            page = "面板"
            return
        }
        disconnect()
        val attempt = generation
        metrics = null
        lastSampleDevice = null
        facts = emptyMap()
        panelDetails = null
        message = null
        samplingError = null
        connecting = device
        connectionJob = scope.launch {
            var pendingLease: DeviceSessionLease? = null
            try {
                withContext(Dispatchers.IO) { NativeCore.ensureLoaded() }
                val owner = DeviceSessionLease()
                pendingLease = owner
                lease = owner
                open(device, attempt, owner)
            } catch (e: TimeoutCancellationException) {
                if (generation == attempt) message = "连接超时，请检查无线调试主页中的连接端口"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == attempt) message = e.localizedMessage ?: "连接失败" }
            catch (e: LinkageError) { if (generation == attempt) message = "无法加载设备核心，请修复安装 · ${e.localizedMessage}" }
            finally {
                if (generation == attempt && session == null) {
                    pendingLease?.close()
                    lease = null
                    connecting = null
                }
            }
        }
    }

    private suspend fun open(device: DeskDevice, attempt: Long, owner: DeviceSessionLease,
                             expectedPeer: String? = null, restoreLogs: Boolean = false) {
        var pending: DeviceSession? = null
        try {
            owner.observe(SessionEvent.Connect)
            val connection = if (expectedPeer == null) host.connect(device.ip, device.port)
                else host.reconnect(device.ip, device.port, expectedPeer)
            pending = connection
            currentCoroutineContext().ensureActive()
            if (generation != attempt) return
            owner.observe(SessionEvent.Authenticate)
            val profile = prefs.devices.firstOrNull { it.id == device.id } ?: device
            val checkIdentifiers = connection.verifiedPeerKey == null
            if (!checkIdentifiers && expectedPeer != null && expectedPeer != connection.verifiedPeerKey)
                throw PeerRejected(TransportError.Identity.code, "目标 TLS 身份已变更，请手动核对设备")
            val reply = withTimeout(12_000) { connection.shell(probeCommand(checkIdentifiers)) }
            val peer = parseProbe(reply.stdout)
            val identity = connection.verifiedPeerKey ?: peer.digest
            if (generation != attempt) return
            if (expectedPeer != null) {
                if (identity != expectedPeer) throw PeerRejected(TransportError.Identity.code, "目标身份已变更，请手动核对设备")
            } else peer.review(profile,checkIdentifiers)?.let { reason ->
                val answer=CompletableDeferred<Boolean>()
                reviewAnswer=answer
                review=reason
                if(!answer.await() || generation!=attempt) return
            }
            owner.bind(identity)
            prefs.markConnected(device.id,peer.digest,peer.model,device.address,
                moved = device.takeIf { expectedPeer != null && it.address != profile.address }, select = selectedInWorkbench)
            verifiedTarget = identity?.let { device.id to it }
            val filter = workspace.filter
            val buffer = workspace.logBuffer
            workspace.close()
            workspace = WorkspaceModel(host, connection, prefs, if (device.name.isBlank()) device.address else "${device.name} · ${device.address}", device.id) {
                if (session === connection && lease === owner) {
                    lastTraffic = 0L
                    owner.wake()
                }
            }.also { work ->
                work.prefetchApps()
                verifiedTarget?.let(sessionNotes::remove)?.let(work::restoreReminders)
                if (restoreLogs) {
                    work.filter = filter
                    work.logBuffer = buffer
                    work.startLogs()
                }
            }
            facts=peer.facts + ("IP 地址" to device.ip)
            owner.own(connection)
            session = connection
            activeRecord = device
            pending = null
            owner.observe(SessionEvent.Verified)
            lastTraffic = if (connection is SessionHealth) System.nanoTime() else 0L
            healthJob = scope.launch {
                host.foreground.collectLatest { active ->
                    if (active) monitor(connection, owner) else lastTraffic = 0L
                }
            }
            if (expectedPeer == null) page = "面板"
            if (serviceName == null && identity != null) {
                discoveryJob = scope.launch {
                    val matches = try { withTimeout(8000) { host.discoverDevices() } }
                        catch (e: TimeoutCancellationException) { emptyList() }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { emptyList() }
                    if (lease === owner && session === connection) {
                        serviceName = matches.singleOrNull { !it.pairing && it.ip == device.ip &&
                            it.port == device.port && it.name.isNotBlank() }?.name
                    }
                }
            }
        } finally {
            runCatching { pending?.close() }
            if (generation == attempt) {
                connecting=null
                review=null
                reviewAnswer=null
            }
        }
    }

    fun reviewDevice(accept:Boolean) { reviewAnswer?.complete(accept) }

    suspend fun pair(address: String, port: Int, code: String) {
        withContext(Dispatchers.IO) { NativeCore.ensureLoaded() }
        DeviceSessionLease().use { owner ->
            owner.observe(SessionEvent.Pair)
            try { host.pair(address, port, code) }
            catch (e: PeerRejected) { owner.observe(SessionEvent.Rejected); throw e }
        }
    }

    fun leaveWorkspace() {
        if (connecting != null && session == null && review != null) reviewDevice(false)
        workspace.cancelTask()
        mediaJob?.cancel()
        screenshotJob?.cancel()
        if (ownsMirror) {
            host.stopMirror()
            ownsMirror = false
        }
    }

    fun mirror() {
        val target = session ?: return
        if (mirrorBusy) return
        ownsMirror = true
        mirrorBusy = true
        val attempt = generation
        mediaJob = scope.launch {
            try { host.startMirror(target) }
            catch (e: TimeoutCancellationException) { if (generation == attempt) message = "打开镜像超时，请重试" }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == attempt) message = e.localizedMessage ?: "打开镜像失败" }
            finally { if (generation == attempt) mirrorBusy = false }
        }
    }

    fun screenshot() {
        val target = session ?: return
        if (snapshotBusy) return
        snapshotBusy = true
        val attempt = generation
        screenshotJob = scope.launch {
            var temp: File? = null
            try {
                val local = host.pickSave("RProbeDesk-${System.currentTimeMillis()}.png") ?: return@launch
                check(session === target)
                host.cacheDir.mkdirs()
                val file = File.createTempFile("screenshot-", ".png", host.cacheDir)
                temp = file
                withContext(Dispatchers.IO) { file.outputStream().use { target.screenshot(it) } }
                val header = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
                check(file.inputStream().use { input -> header.all { input.read() == (it.toInt() and 255) } }) { "设备返回的截图格式无效" }
                local.writeFrom(file)
                if (session === target) message = "截图已保存至 ${local.name}"
            } catch (e: TimeoutCancellationException) { if (generation == attempt) message = "截图超时，请重试" }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == attempt) message = e.localizedMessage ?: "截图失败" }
            finally { temp?.delete(); if (generation == attempt) snapshotBusy = false }
        }
    }

    suspend fun sample(target: DeviceSession) {
        var previous: CpuTicks? = metrics?.ticks
        while (currentCoroutineContext().isActive && session === target) {
            try {
                val values = withTimeoutOrNull(12_000) { target.shell(metricCommand) } ?: error("采样超时")
                val sample = parseMetrics(values.stdout, previous, System.currentTimeMillis())
                if (session !== target) return
                lastTraffic = System.nanoTime()
                previous = sample.ticks
                lastSampleDevice = activeDevice
                metrics = sample
                samplingError = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (session === target) samplingError = e.localizedMessage ?: "采样失败" }
            delay(2500)
        }
    }

    suspend fun watch(target: DeviceSession) {
        if (session === target) {
            lastTraffic = 0L
            lease?.wake()
            healthJob?.join()
        }
    }

    private suspend fun monitor(target: DeviceSession, owner: DeviceSessionLease) {
        while (currentCoroutineContext().isActive && session === target) {
            val health = target as? SessionHealth
            val ended = health?.transportFailure
            try {
                if (ended?.isCompleted == true) throw ended.await()
                if (health?.transportAlive == false) throw TransportFailure(TransportError.Closed.code, "设备连接已关闭")
                if (System.nanoTime() - lastTraffic >= 12_000_000_000L || owner.phase.value == SessionPhase.Suspect) {
                    val result = withTimeoutOrNull(10_000) { health?.probe() ?: target.shell("echo RProbeDesk") } ?: throw java.net.SocketTimeoutException("设备探活超时")
                    if (result.exitCode != 0 || result.stdout.trim() != "RProbeDesk")
                        throw IOException(result.stderr.trim().ifEmpty { "设备探活响应异常" })
                    lastTraffic = System.nanoTime()
                }
                if (session !== target) return
                if (workspace.logRequested && !workspace.logRunning) workspace.startLogs()
                owner.observe(SessionEvent.Verified)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (session !== target) return
                val readerFailure = ended?.takeIf { it.isCompleted }?.await()
                val failure = readerFailure ?: e
                val transportEnded = readerFailure != null || health?.transportAlive == false
                val action = recoveryAction(failure, transportEnded)
                val terminal = action == RecoveryAction.ConfirmIdentity || action == RecoveryAction.StopSession
                when (action) {
                    RecoveryAction.StopOperation, RecoveryAction.WaitForResources -> { delay(1000); continue }
                    RecoveryAction.StopSession, RecoveryAction.ConfirmIdentity -> owner.observe(SessionEvent.Rejected)
                    RecoveryAction.Reconnect, RecoveryAction.Rediscover -> owner.observe(SessionEvent.Unavailable)
                    else -> owner.observe(if (transportEnded)
                        SessionEvent.Unavailable else SessionEvent.ProbeFailed)
                }
                if (terminal || owner.phase.value == SessionPhase.DeviceUnavailable) {
                    val device = activeDevice ?: return
                    val peer = verifiedTarget?.second
                    val logs = workspace.logRequested
                    dropTransport()
                    message = "设备连接已中断\n${failure.localizedMessage ?: failure.javaClass.simpleName}"
                    if (!terminal && peer != null) recover(device, peer, owner, logs, action)
                    else { owner.close(); lease = null }
                    return
                }
            }
            if (ended == null) owner.awaitNext() else coroutineScope {
                val timer = async(start = CoroutineStart.UNDISPATCHED) { owner.awaitNext() }
                try {
                    kotlinx.coroutines.selects.select<Unit> {
                        timer.onAwait {}
                        ended.onAwait {}
                    }
                } finally { timer.cancelAndJoin() }
            }
        }
    }

    private fun recover(device: DeskDevice, peer: String, owner: DeviceSessionLease, logs: Boolean, initialAction: RecoveryAction) {
        val attempt = generation
        connecting = device
        recoveryJob = scope.launch {
            // The Activity owns retries. Leaving the foreground cancels even an in-flight connect.
            var action = initialAction
            var timedOutAttempts = 0
            host.foreground.collectLatest { foreground ->
                if (!foreground) return@collectLatest
                while (isActive && lease === owner && session == null) {
                    val profile = prefs.devices.firstOrNull { it.id == device.id && it.address == device.address }
                    if (profile == null) { disconnect(); return@collectLatest }
                    if (action == RecoveryAction.WaitForResources) {
                        delay(1000)
                    } else {
                        owner.observe(SessionEvent.Unavailable)
                        owner.observe(SessionEvent.WaitToRetry)
                        owner.awaitNext()
                        owner.observe(SessionEvent.Retry)
                    }
                    try {
                        val timedOut = action == RecoveryAction.Retry && timedOutAttempts >= 2
                        val scan = timedOut || action == RecoveryAction.Rediscover ||
                            action == RecoveryAction.Reconnect || action == RecoveryAction.WaitForNetwork
                        val name = serviceName
                        val discovered = if (scan && (name != null || timedOut)) {
                            owner.observe(SessionEvent.Discover)
                            withTimeoutOrNull(8000) { host.discoverDevices() }.orEmpty()
                                .filter { !it.pairing && (name?.equals(it.name) ?: (it.ip == profile.ip)) &&
                                    (!peer.startsWith("tls:") || it.service == "_adb-tls-connect._tcp") }
                                .sortedWith(compareBy({ timedOut && it.address == profile.address }, { it.ip != device.ip }))
                                .map { profile.copy(ip = it.ip, port = it.port) }
                        } else emptyList()
                        val candidates = (discovered.distinctBy { it.address }.take(3) + profile).distinctBy { it.address }
                        var failed: IOException? = null
                        for (candidate in candidates) {
                            try {
                                withTimeout(15_000) { open(candidate, attempt, owner, peer, logs) }
                                if (session != null) break
                            } catch (e: TimeoutCancellationException) { failed = TransportFailure(TransportError.Timeout.code, "设备重连超时", e) }
                            catch (e: IOException) {
                                when (recoveryAction(e)) {
                                    RecoveryAction.ConfirmIdentity, RecoveryAction.StopSession,
                                    RecoveryAction.StopOperation, RecoveryAction.WaitForResources -> throw e
                                    else -> failed = e
                                }
                            }
                        }
                        if (session == null) throw failed ?: IOException("设备暂时不可达")
                        if (session != null) {
                            message = "设备已重新连接"
                            this@launch.cancel()
                            return@collectLatest
                        }
                    } catch (e: TimeoutCancellationException) {
                        action = RecoveryAction.Retry
                        message = "设备重连超时"
                    } catch (e: CancellationException) { throw e }
                    catch (e: IOException) {
                        message = e.localizedMessage
                        action = recoveryAction(e)
                        when (action) {
                            RecoveryAction.StopOperation -> { disconnect(); return@collectLatest }
                            RecoveryAction.ConfirmIdentity, RecoveryAction.StopSession -> {
                                if (owner.phase.value != SessionPhase.AuthenticationFailed) owner.observe(SessionEvent.Rejected)
                                disconnect()
                                return@collectLatest
                            }
                            else -> Unit // Rust's single backoff timer also wakes on network changes.
                        }
                    } catch (e: Exception) {
                        message = e.localizedMessage ?: "设备重连失败，请手动核对"
                        disconnect()
                        return@collectLatest
                    }
                    if (action == RecoveryAction.Retry) {
                        if (++timedOutAttempts >= 3) {
                            message = "连续三次重连未成功，请检查设备并手动连接"
                            disconnect()
                            return@collectLatest
                        }
                    } else if (action != RecoveryAction.WaitForResources) {
                        timedOutAttempts = 0
                    }
                    connecting = profile
                }
            }
        }
    }
}
