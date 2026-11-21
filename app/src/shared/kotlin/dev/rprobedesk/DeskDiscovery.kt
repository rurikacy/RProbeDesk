package dev.rprobedesk

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

data class DiscoveredDevice(val name:String,val ip:String,val port:Int,val service:String) {
    val pairing get() = service=="_adb-tls-pairing._tcp"
    val address get() = if(':' in ip) "[$ip]:$port" else "$ip:$port"
}

internal val adbServiceTypes=listOf("_adb._tcp","_adb-tls-connect._tcp","_adb-tls-pairing._tcp")

internal fun discoveredDevice(name:String,ip:String,port:Int,service:String):DiscoveredDevice? {
    if((service.isNotEmpty() && service !in adbServiceTypes) || port !in 1..65535) return null
    val address=try { normalizedIp(ip) } catch(_:IllegalArgumentException) { return null }
    return DiscoveredDevice(name,address,port,service)
}

@Composable
internal fun DiscoverDevices(host:HostBridge,dismiss:()->Unit,choose:(DiscoveredDevice)->Unit) {
    var attempt by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var devices by remember { mutableStateOf(emptyList<DiscoveredDevice>()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(attempt) {
        loading=true; error=null
        try { devices=host.discoverDevices().distinctBy { it.address to it.pairing }.sortedWith(compareBy({it.ip},{it.port})) }
        catch(e:CancellationException) { throw e }
        catch(e:Exception) { error=e.localizedMessage ?: "设备发现失败" }
        finally { loading=false }
    }
    DeskDialog(dismiss,title={Text(tr("发现设备"),style=MaterialTheme.typography.h6)},text={
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(loading) Text(tr("正在查找局域网中的 ADB 设备…"))
            else if(error!=null) Text(uiMessage(error.orEmpty()),color=DeskError)
            else if(devices.isEmpty()) Text(tr("未发现设备。请确认两端在同一局域网，或手动添加 IP 和端口。"))
            if(!loading && error==null) devices.forEach { d ->
                Column(Modifier.fillMaxWidth().deskPanel().padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    if(d.name.isNotBlank()) Text(d.name,style=MaterialTheme.typography.body1)
                    Text(d.address,fontFamily=DeskMono)
                    Tools {
                        Text(tr(when(d.service) { "_adb-tls-pairing._tcp" -> "配对端口"; "_adb-tls-connect._tcp" -> "无线调试"; "_adb._tcp" -> "TCP 调试"; else -> "已有 ADB 连接" }),color=DeskMuted)
                        DeskButton(if(d.pairing) "配对" else "连接",tint=DeskSelection) { choose(d) }
                    }
                }
            }
        }
    },dismissButton={DeskButton("关闭",click=dismiss)},confirmButton={PrimaryButton("重新搜索",!loading) { attempt++ }})
}
