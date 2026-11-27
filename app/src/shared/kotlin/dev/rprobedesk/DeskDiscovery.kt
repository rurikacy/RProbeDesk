package dev.rprobedesk

import androidx.compose.foundation.layout.*
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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
internal fun DiscoverDevices(host:HostBridge,dismiss:()->Unit,manualAdd:()->Unit,choose:(DiscoveredDevice)->Unit) {
    var attempt by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var devices by remember { mutableStateOf(emptyList<DiscoveredDevice>()) }
    var error by remember { mutableStateOf<String?>(null) }
    val foreground by host.foreground.collectAsState()
    LaunchedEffect(host, attempt, foreground) {
        if (!foreground) return@LaunchedEffect
        loading=true; error=null
        fun show(items: List<DiscoveredDevice>) {
            devices = items.sortedWith(compareBy({ ':' in it.ip }, { it.ip }, { it.port }))
        }
        val updates = Channel<List<DiscoveredDevice>>(Channel.CONFLATED)
        val display = launch { for (items in updates) { show(items); if (items.isNotEmpty()) loading=false } }
        try {
            if (host is LiveDiscovery) {
                while (isActive) {
                    val result = host.discover { updates.trySend(it) }
                    show(result)
                    loading=false
                    delay(if (result.isEmpty()) 2000 else 250)
                }
            } else show(host.discoverDevices())
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) { error=e.localizedMessage ?: "设备发现失败" }
        finally { updates.close(); display.cancel(); loading=false }
    }
    val hasResults=!loading && error==null && devices.isNotEmpty()
    DeskDialog(dismiss,title={Text(tr("发现设备"),style=MaterialTheme.typography.h6)},text={
        DeskScrollColumn(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(loading) Text(tr("正在查找局域网中的 ADB 设备…"))
            else if(hasResults) {
                val hasConnectionPort=devices.any { !it.pairing }
                Column(Modifier.fillMaxWidth().deskPanel()) {
                    devices.forEachIndexed { index,d ->
                        if(index>0) Divider(color=DeskBorder)
                        val service=tr(when(d.service) {
                            "_adb-tls-pairing._tcp" -> "配对端口"
                            "_adb-tls-connect._tcp" -> "无线调试"
                            "_adb._tcp" -> "TCP 调试"
                            else -> "已有 ADB 连接"
                        })
                        Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(d.name.ifBlank { service },fontWeight=FontWeight.Medium,color=DeskInk)
                            Text(d.address,fontFamily=DeskMono,style=MaterialTheme.typography.body2,color=DeskInk)
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                if(d.name.isNotBlank()) Text(service,Modifier.weight(1f),style=MaterialTheme.typography.body2,color=DeskMuted)
                                else Spacer(Modifier.weight(1f))
                                DeskButton(if(d.pairing) "配对" else "连接",
                                    tint=if(d.pairing && hasConnectionPort) DeskSelection else DeskAccent) { choose(d) }
                            }
                        }
                    }
                }
            } else {
                if(error!=null) Text(uiMessage(error.orEmpty()),color=DeskError)
                else Text(tr("未发现设备。请确认两端在同一局域网，或手动添加 IP 和端口。"))
                DeskButton("手动添加",click=manualAdd)
            }
        }
    },dismissButton={DeskButton("关闭",click=dismiss)},confirmButton={
        DeskButton("重新搜索",!loading,tint=if(hasResults) DeskSurface else DeskAccent) { attempt++ }
    })
}
