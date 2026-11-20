package dev.rprobedesk

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal val deskPages = listOf("设备", "面板", "应用", "文件", "Shell", "镜像", "日志")

@Composable
fun DeskApp(host: HostBridge) {
    val scope = rememberCoroutineScope()
    val prefs = remember(host) { DeskPreferences(host.settingsDir) }
    val desk = remember(host) { DeskController(host, prefs, scope) }
    val session = desk.session
    val workspace = desk.workspace
    DisposableEffect(desk) { onDispose { desk.close() } }
    val foreground by host.foreground.collectAsState()
    val mirrorOn by host.mirrorActive.collectAsState()
    LaunchedEffect(session, foreground) {
        if (session != null && foreground) desk.watch(session)
        if (!foreground && !host.desktop) host.stopMirror()
    }
    LaunchedEffect(session, desk.page, foreground) {
        if (session != null && foreground && desk.page == "面板") desk.sample(session)
    }
    var editing by remember { mutableStateOf(false) }
    var edited by remember { mutableStateOf<DeskDevice?>(null) }
    var pairing by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<DeskDevice?>(null) }
    var interrupt by remember { mutableStateOf<(() -> Unit)?>(null) }
    val guard: (() -> Unit) -> Unit = { action ->
        if (workspace.busy || desk.snapshotBusy) interrupt = action else action()
    }
    val edit: (DeskDevice?) -> Unit = { edited = it; editing = true }
    val connect: (DeskDevice) -> Unit = { device -> guard { desk.connect(device) } }
    val logs: () -> Unit = {
        if (!workspace.logRunning) workspace.startLogs()
        desk.page = "日志"
    }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(desk.message) {
        desk.message?.let { m ->
            snack.currentSnackbarData?.dismiss()
            snack.showSnackbar(m, "关闭", SnackbarDuration.Long)
            if (desk.message == m) desk.message = null
        }
    }
    DeskTheme {
        Surface(Modifier.fillMaxSize(),color=DeskBackground) {
            Box(Modifier.fillMaxSize()) {
                DeskFrame(desk,workspace,connect,logs) {
                    when(desk.page) {
                        "设备" -> DevicePage(desk,workspace,logs,edit,{ pairing=true },connect,
                            { guard { desk.disconnect() } },{ remove=it })
                        "面板" -> PanelPage(desk)
                        "镜像" -> MirrorPage(desk,mirrorOn)
                        else -> key(session) { Workspaces(desk.page,workspace) }
                    }
                }
                SnackbarHost(snack,Modifier.align(Alignment.BottomCenter).widthIn(max=640.dp).padding(12.dp))
            }
        }
        WorkspacePrompts(workspace)
        desk.review?.let { details -> DeskConfirm("核对目标设备", details, "已核对，连接", {desk.reviewDevice(false)}) {desk.reviewDevice(true)} }
        if (editing) DeviceForm(edited, prefs, onDismiss={ editing=false }) { id, name, ip, port ->
            val old = desk.activeDevice
            if (id != null && old?.id == id && (old.ip != ip || old.port != port))
                check(!workspace.busy && !desk.snapshotBusy) { "请先结束当前设备上的任务" }
            prefs.saveDevice(id,name,ip,port)
            if (id != null && old?.id == id && (old.ip != ip || old.port != port)) desk.disconnect()
            if (id != null && desk.connecting?.id == id) desk.disconnect()
            editing=false
        }
        if (pairing) PairForm(desk) { pairing=false }
        remove?.let { device -> DeskConfirm("移除设备记录", "${device.label}\n${device.address}", "移除", { remove=null }) {
            remove=null
            val action = {
                try {
                    if (desk.activeDevice?.id == device.id || desk.connecting?.id == device.id) desk.disconnect()
                    prefs.removeDevice(device.id)
                } catch (e: Exception) { desk.message=e.message }
            }
            if (desk.activeDevice?.id == device.id) guard(action) else action()
        } }
        interrupt?.let { action -> DeskConfirm("结束当前任务？", "${desk.activeDevice?.label.orEmpty()}\n${if (desk.snapshotBusy) "保存设备截图" else workspace.status}", "结束并继续", { interrupt=null }) {
            interrupt=null
            action()
        } }
    }
}

