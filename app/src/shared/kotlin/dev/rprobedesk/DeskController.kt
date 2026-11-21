package dev.rprobedesk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.*

internal fun deskTime(millis: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))

internal class DeskController(val host: HostBridge, val prefs: DeskPreferences, val scope: CoroutineScope) {
    var page by mutableStateOf("设备")
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
    var samplingError by mutableStateOf<String?>(null)
    var snapshotBusy by mutableStateOf(false)
    var mirrorBusy by mutableStateOf(false)
    private var generation = 0L
    private var connectionJob: Job? = null
    private var mediaJob: Job? = null
    private var screenshotJob: Job? = null
    val chosen get() = activeDevice ?: prefs.devices.firstOrNull { it.id == prefs.selectedId } ?: prefs.devices.firstOrNull()

    fun disconnect() {
        generation++
        reviewAnswer?.cancel()
        reviewAnswer=null
        review=null
        connectionJob?.cancel()
        mediaJob?.cancel()
        screenshotJob?.cancel()
        host.stopMirror()
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
        try { old?.close() } catch (e: Exception) { message = e.localizedMessage ?: "关闭会话失败" }
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
        message = null
        samplingError = null
        connecting = device
        connectionJob = scope.launch {
            var pending: DeviceSession? = null
            try {
                val connection = host.connect(device.ip, device.port)
                pending = connection
                val profile = prefs.devices.firstOrNull { it.id==device.id } ?: device
                val reply = withTimeout(12_000) { connection.shell(probeCommand(host.desktop)) }
                val peer = parseProbe(reply.stdout)
                if (generation != attempt) return@launch
                peer.review(profile,host.desktop)?.let { reason ->
                    val answer=CompletableDeferred<Boolean>()
                    reviewAnswer=answer
                    review=reason
                    if(!answer.await() || generation!=attempt) return@launch
                }
                prefs.markConnected(device.id,peer.digest,peer.model,device.address)
                verifiedTarget = (connection.verifiedPeerKey ?: peer.digest)?.let { device.id to it }
                workspace.close()
                workspace = WorkspaceModel(host, connection, prefs, if (device.name.isBlank()) device.address else "${device.name} · ${device.address}").also { work ->
                    verifiedTarget?.let(sessionNotes::remove)?.let(work::restoreReminders)
                }
                facts=peer.facts + ("IP 地址" to device.ip)
                session = connection
                activeRecord = device
                pending = null
                page = "面板"
            } catch (e: TimeoutCancellationException) {
                if (generation == attempt) message = "连接超时，请检查无线调试主页中的连接端口"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == attempt) message = e.localizedMessage ?: "连接失败" }
            finally {
                runCatching { pending?.close() }
                if (generation == attempt) {
                    connecting=null
                    review=null
                    reviewAnswer=null
                }
            }
        }
    }

    fun reviewDevice(accept:Boolean) { reviewAnswer?.complete(accept) }

    fun mirror() {
        val target = session ?: return
        if (mirrorBusy) return
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
        var previous: CpuTicks? = null
        while (currentCoroutineContext().isActive && session === target) {
            try {
                val values = withTimeoutOrNull(12_000) { target.shell(metricCommand) } ?: error("采样超时")
                val sample = parseMetrics(values.stdout, previous, System.currentTimeMillis())
                if (session !== target) return
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
        while (currentCoroutineContext().isActive && session === target) {
            try {
                val result = withTimeoutOrNull(10_000) { target.shell("echo RProbeDesk") }
                check(result?.stdout?.trim() == "RProbeDesk")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (session === target) { disconnect(); message = "设备连接已中断，请检查目标后重新连接" }
                return
            }
            delay(12_000)
        }
    }
}
