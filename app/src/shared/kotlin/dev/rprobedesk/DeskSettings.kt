package dev.rprobedesk

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

internal class CloudSettings(private val prefs:DeskPreferences) {
    var status by mutableStateOf("")
    var busy by mutableStateOf(false)
    var config by mutableStateOf(prefs.davConfig().copy(password=try { prefs.davPassword() } catch (_:Exception) {
        status="请重新输入 WebDAV 密码"; ""
    }))
    private var saved=config
    fun save() {
        if(saved!=config) { prefs.saveDav(config); saved=config }
    }
    fun run(action:String,desk:DeskController) {
        if(busy) return
        busy=true
        status="正在$action…"
        val input=config
        val before=prefs.exportCloud()
        desk.scope.launch {
            try {
                save()
                val remote=withContext(Dispatchers.IO) {
                    val dav=WebDav(input)
                    when(action) {
                        "测试连接" -> { dav.test(); null }
                        "上传到云端" -> { dav.upload(before); null }
                        else -> dav.download()
                    }
                }
                if(remote!=null) {
                    check(!desk.workspace.busy && !desk.snapshotBusy && desk.connecting==null) { "请在设备任务完成后重新下载" }
                    prefs.importCloud(remote,before)
                    if(desk.session!=null) desk.disconnect()
                }
                status=when(action) { "测试连接" -> "连接成功"; "上传到云端" -> "已上传设备资料和 Shell 收藏"; else -> "已下载并应用云端配置" }
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { status=e.localizedMessage ?: "云同步失败" }
            finally { busy=false }
        }
    }
}

@Composable
internal fun SettingsPage(desk:DeskController,cloud:CloudSettings) {
    var confirm by remember { mutableStateOf<String?>(null) }
    DisposableEffect(cloud) { onDispose { try { cloud.save() } catch(e:Exception) { desk.message=e.localizedMessage } } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
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

@Composable
private fun SyncSettings(desk:DeskController,cloud:CloudSettings,confirm:(String)->Unit) {
    Column(Modifier.fillMaxWidth().deskPanel().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(tr("WebDAV 云同步"),fontWeight=FontWeight.SemiBold)
        Text(tr("在设备间同步设备资料和 Shell 收藏。坚果云请使用第三方应用密码。"),color=DeskMuted)
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
