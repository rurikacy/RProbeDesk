package dev.rprobedesk

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import java.io.File
import java.util.Properties

internal data class FileImport(val name:String,val profile:SyncProfile,val before:Properties)

internal class SyncSettingsState(private val prefs:DeskPreferences) {
    var status by mutableStateOf("")
    var busy by mutableStateOf(false)
    var fileStatus by mutableStateOf("")
        private set
    var fileImport by mutableStateOf<FileImport?>(null)
        private set
    var config by mutableStateOf(prefs.davConfig().copy(password=try { prefs.davPassword() } catch (_:Exception) {
        status="请重新输入 WebDAV 密码"; ""
    }))
    private var saved=config
    fun save() {
        if(saved!=config) { prefs.saveDav(config); saved=config }
    }
    private fun apply(desk:DeskController,profile:SyncProfile,before:Properties) {
        check(!desk.workspace.busy && !desk.snapshotBusy && !desk.mirrorBusy && desk.connecting==null) { "请在设备任务完成后重新同步" }
        prefs.applySync(profile,before)
        if(desk.session!=null) desk.disconnect()
    }

    private fun fileTask(desk:DeskController,action:suspend ()->Unit) {
        if(busy) return
        busy=true
        fileStatus=""
        desk.scope.launch {
            try { action() }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { fileStatus=e.localizedMessage ?: "文件同步失败" }
            finally { busy=false }
        }
    }

    fun exportFile(desk:DeskController) = fileTask(desk) {
        val doc=desk.host.pickSave("RProbeDesk-sync.properties") ?: return@fileTask
        val records=prefs.exportSync()
        withContext(Dispatchers.IO) {
            desk.host.cacheDir.mkdirs()
            val stage=File.createTempFile("sync-",".properties",desk.host.cacheDir)
            try {
                stage.writeBytes(encodeSyncFile(records))
                doc.writeFrom(stage)
            } finally { stage.delete() }
        }
        fileStatus="已导出到 ${doc.name}"
    }

    fun chooseImport(desk:DeskController) = fileTask(desk) {
        val before=prefs.exportSync()
        val doc=desk.host.pickOpen(".properties") ?: return@fileTask
        val profile=withContext(Dispatchers.IO) {
            doc.openInput().source().buffer().use { prefs.inspectSync(readSyncFile(it)) }
        }
        fileImport=FileImport(doc.name,profile,before)
    }

    fun cancelImport() { fileImport=null }
    fun confirmImport(desk:DeskController) {
        val pending=fileImport ?: return
        fileImport=null
        fileTask(desk) {
            apply(desk,pending.profile,pending.before)
            fileStatus="已导入设备资料和 Shell 收藏"
        }
    }

    fun run(action:String,desk:DeskController) {
        if(busy) return
        busy=true
        status="正在$action…"
        val input=config
        val before=prefs.exportSync()
        desk.scope.launch {
            try {
                save()
                val remote=withContext(Dispatchers.IO) {
                    val dav=WebDav(input)
                    when(action) {
                        "测试连接" -> { dav.test(); null }
                        "上传到云端" -> { dav.upload(before); null }
                        else -> prefs.inspectSync(dav.download())
                    }
                }
                if(remote!=null) apply(desk,remote,before)
                status=when(action) { "测试连接" -> "连接成功"; "上传到云端" -> "已上传设备资料和 Shell 收藏"; else -> "已下载并应用云端配置" }
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { status=e.localizedMessage ?: "云同步失败" }
            finally { busy=false }
        }
    }
}

@Composable
internal fun SettingsPage(desk:DeskController,cloud:SyncSettingsState) {
    var confirm by remember { mutableStateOf<String?>(null) }
    DisposableEffect(cloud) { onDispose { try { cloud.save() } catch(e:Exception) { desk.message=e.localizedMessage } } }
    DeskScrollColumn(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        DeskTitle("设置")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if(maxWidth >= 680.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale) {
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) { GeneralSettings(desk) }
                    Box(Modifier.weight(1f)) { SyncSettings(desk,cloud) { confirm=it } }
                }
            } else Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                GeneralSettings(desk)
                SyncSettings(desk,cloud) { confirm=it }
            }
        }
    }
    cloud.fileImport?.let { pending ->
        val counts="${pending.profile.deviceIds.size} 台设备、${pending.profile.commandCount} 条 Shell 收藏"
        DeskConfirm("导入同步文件","${pending.name}\n$counts\n导入会替换本机的设备资料和 Shell 收藏，并断开当前设备。",
            "确认导入",cloud::cancelImport) { cloud.confirmImport(desk) }
    }
    confirm?.let { action ->
        val detail=if(action=="上传到云端") "将本机设备资料及 Shell 收藏写入云端，替换同名配置。"
            else "使用云端设备资料及 Shell 收藏替换本地记录，并断开当前设备。"
        DeskConfirm(action,"${cloud.config.root}/${cloud.config.profile}\n$detail",action,{confirm=null}) {
            confirm=null; cloud.run(action,desk)
        }
    }
}

@Composable
private fun GeneralSettings(desk:DeskController) {
    Column(Modifier.fillMaxWidth().deskPanel().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(tr("语言"),fontWeight=FontWeight.SemiBold)
        Tools {
            for ((name,english) in listOf("中文" to false,"English" to true)) {
                DeskButton(name,tint=DeskSelection,selected=desk.prefs.english==english) {
                    try { desk.prefs.setEnglish(english) } catch(e:Exception) { desk.message=e.localizedMessage }
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth().deskPanel().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(tr("颜色模式"),fontWeight=FontWeight.SemiBold)
        Tools {
            DeskColorMode.entries.forEach { mode ->
                DeskButton(mode.label,tint=DeskSelection,selected=desk.prefs.colorMode==mode) {
                    try { desk.prefs.setColorMode(mode) } catch(e:Exception) { desk.message=e.localizedMessage }
                }
            }
        }
    }
    UpdateSettings(desk)
    if(desk.host.desktop) {
        ToolChooser(desk,"adb")
        ToolChooser(desk,"scrcpy")
        Column(Modifier.fillMaxWidth().deskPanel().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(tr("关闭主窗口时"),fontWeight=FontWeight.SemiBold)
            Tools {
                for((label,tray) in listOf("最小化到托盘" to true,"退出程序" to false)) {
                    DeskButton(label,tint=DeskSelection,selected=desk.prefs.closeToTray==tray) {
                        try { desk.prefs.setCloseToTray(tray) } catch(e:Exception) { desk.message=e.localizedMessage }
                    }
                }
            }
        }
    }
}

internal class UpdateSettingsState {
    var available by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var status by mutableStateOf("")
    var failed by mutableStateOf(false)

    fun run(desk: DeskController) {
        if (busy) return
        busy = true
        failed = false
        val host = desk.host
        val version = available
        status = if (version == null) "正在检查更新" else "正在下载安装包"
        desk.scope.launch {
            try {
                if (version == null) {
                    available = withContext(Dispatchers.IO) { checkRelease(host.appVersion) }
                    status = available?.let { "发现新版本 $it" } ?: "当前已是最新版本"
                } else {
                    val directory = if (host.desktop) File(System.getProperty("java.io.tmpdir"), "RProbeDesk-updates")
                        else File(host.cacheDir, "updates")
                    val file = withContext(Dispatchers.IO) {
                        downloadRelease(version, host.desktop, directory) { bytes, size ->
                            status = if (size != null && size > 0) "正在下载安装包 · ${bytes * 100 / size}%"
                                else "正在下载安装包 · ${byteCount(bytes)}"
                        }
                    }
                    host.installUpdate(file)
                    status = "已打开系统安装程序"
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                failed = true
                status = e.localizedMessage ?: "更新失败，请重试"
            }
            finally { busy = false }
        }
    }
}

@Composable
private fun UpdateSettings(desk: DeskController) {
    val update = desk.updates
    Column(Modifier.fillMaxWidth().deskPanel().padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(uiMessage("当前版本 ${desk.host.appVersion}"), fontWeight=FontWeight.SemiBold)
        val installing = update.available != null
        val taskActive = desk.workspace.busy || desk.snapshotBusy || desk.mirrorBusy || desk.connecting != null
        DeskButton(if (installing) "下载并安装更新" else "检查更新", !update.busy && !(installing && taskActive), tint=DeskSelection) {
            update.run(desk)
        }
        if (update.status.isNotBlank()) Text(uiMessage(update.status), color=if(update.failed) DeskError else DeskMuted)
    }
}

@Composable
private fun SyncSettings(desk:DeskController,cloud:SyncSettingsState,confirm:(String)->Unit) {
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth().deskPanel().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(tr("文件同步"),fontWeight=FontWeight.SemiBold)
            Text(tr("通过文件转移设备资料、分组方式、设备顺序和 Shell 收藏。"),color=DeskMuted)
            Tools {
                DeskButton("导出同步文件",!cloud.busy,tint=DeskSelection) { cloud.exportFile(desk) }
                DeskButton("导入同步文件",!cloud.busy,tint=DeskSelection) { cloud.chooseImport(desk) }
            }
            if(cloud.fileStatus.isNotBlank()) Text(uiMessage(cloud.fileStatus),color=DeskMuted)
        }
        WebDavSettings(desk,cloud,confirm)
    }
}

@Composable
private fun WebDavSettings(desk:DeskController,cloud:SyncSettingsState,confirm:(String)->Unit) {
    Column(Modifier.fillMaxWidth().deskPanel().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(tr("WebDAV 云同步"),fontWeight=FontWeight.SemiBold)
        Text(tr("同步设备资料、分组方式、设备顺序和 Shell 收藏。坚果云请使用第三方应用密码。"),color=DeskMuted)
        val c=cloud.config
        DeskInput(c.server,"WebDAV 服务器地址",{cloud.config=cloud.config.copy(server=it)},enabled=!cloud.busy)
        if(c.server.startsWith("http://",ignoreCase=true)) Text(tr("HTTP 会明文传输账户与密码。"),color=DeskError)
        DeskInput(c.account,"WebDAV 账户",{cloud.config=cloud.config.copy(account=it)},enabled=!cloud.busy)
        DeskInput(c.password,"WebDAV 密码",{cloud.config=cloud.config.copy(password=it)},visual=PasswordVisualTransformation(),enabled=!cloud.busy)
        DeskInput(c.root,"远程根目录",{cloud.config=cloud.config.copy(root=it)},enabled=!cloud.busy)
        DeskInput(c.profile,"同步配置名",{cloud.config=cloud.config.copy(profile=it)},enabled=!cloud.busy)
        Tools {
            DeskButton("测试连接",!cloud.busy,tint=DeskSelection) { cloud.run("测试连接",desk) }
            PrimaryButton("上传到云端",!cloud.busy) { confirm("上传到云端") }
            DeskButton("从云端下载",!cloud.busy,tint=DeskSelection) { confirm("从云端下载") }
        }
        if(cloud.status.isNotBlank()) Text(uiMessage(cloud.status),color=DeskMuted)
    }
}
