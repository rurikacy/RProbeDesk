package dev.rprobedesk

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.LocalDensity
import java.util.Locale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class DeviceStat(val icon:String,val label:String,val value:Int)

@Composable
internal fun DevicePage(desk:DeskController, work:WorkspaceModel, logs:()->Unit, edit:(DeskDevice?)->Unit,pair:()->Unit,
                        connect:(DeskDevice)->Unit, disconnect:()->Unit, remove:(DeskDevice)->Unit) {
    val all=desk.prefs.devices
    val live=desk.activeDevice?.id
    val connected=if(desk.session==null) 0 else 1
    val compact=CompactDesk.current
    var filter by remember { mutableStateOf("全部") }
    var query by remember { mutableStateOf("") }
    var wifi by remember { mutableStateOf("") }
    var site by remember { mutableStateOf("") }
    val matches=all.filter { device ->
        (wifi.isEmpty() || device.wifi==wifi) && (site.isEmpty() || device.site==site) &&
            (filter=="全部" || (filter=="在线")== (device.id==live)) &&
            (device.label.contains(query.trim(),true) || device.address.contains(query.trim(),true))
    }
    val stats=listOf(DeviceStat("设备","已保存",all.size),DeviceStat("在线","在线设备",connected),
        DeviceStat("离线","离线设备",all.size-connected),DeviceStat("收藏","Shell 收藏",desk.prefs.commands.size))
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                DeskTitle("设备")
                Text("${all.size}",fontSize=13.sp,color=DeskMuted)
                Spacer(Modifier.weight(1f))
                DeskButton("配对",click=pair)
                PrimaryButton("添加") { edit(null) }
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().deskPanel()) {
                stats.chunked(if(compact) 2 else 4).forEachIndexed { index,group ->
                    if(index>0) Divider(color=DeskBorder)
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) { group.forEachIndexed { i,stat ->
                        if(i>0) Spacer(Modifier.width(1.dp).fillMaxHeight().background(DeskBorder))
                        Row(Modifier.weight(1f).padding(horizontal=12.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically,
                            horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            DeskIcon(stat.icon,DeskMuted,Modifier.size(16.dp))
                            Text(stat.label,Modifier.weight(1f),fontSize=12.sp,color=DeskMuted)
                            Text(stat.value.toString(),fontSize=15.sp,fontWeight=FontWeight.SemiBold)
                        }
                    } }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        item {
            Column(Modifier.fillMaxWidth().deskPanel()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically) {
                    for(name in listOf("全部","在线","离线")) {
                        val active=filter==name
                        Column(Modifier.semantics { selected=active }.clickable(role=Role.Tab) { filter=name }
                            .padding(horizontal=9.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                            Text(name,Modifier.padding(vertical=8.dp),fontSize=13.sp,color=if(active) DeskAccent else DeskMuted)
                            Spacer(Modifier.size(28.dp,2.dp).background(if(active) DeskAccent else Color.Transparent))
                        }
                    }
                }
                Divider(color=DeskBorder)
                val networks=all.map { it.wifi }.filter(String::isNotEmpty).distinct().sorted()
                val sites=all.map { it.site }.filter(String::isNotEmpty).distinct().sorted()
                if(networks.isNotEmpty() || sites.isNotEmpty()) Column(Modifier.padding(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    if(networks.isNotEmpty()) Tools {
                        Text("WiFi",Modifier.padding(6.dp))
                        networks.forEach { DeskButton(if(it==wifi) "✓ $it" else it,tint=DeskSelection) { wifi=if(it==wifi) "" else it } }
                    }
                    if(sites.isNotEmpty()) Tools {
                        Text("场地",Modifier.padding(6.dp))
                        sites.forEach { DeskButton(if(it==site) "✓ $it" else it,tint=DeskPaleGreen) { site=if(it==site) "" else it } }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(10.dp)) {
                    BasicTextField(query,{query=it},Modifier.fillMaxWidth().semantics { contentDescription="搜索设备" },singleLine=true,
                        textStyle=MaterialTheme.typography.body2.copy(color=DeskInk),cursorBrush=SolidColor(DeskAccent),
                        decorationBox={ input ->
                            Row(Modifier.fillMaxWidth().heightIn(min=34.dp).border(1.dp,DeskBorder,DeskShape).padding(horizontal=9.dp,vertical=6.dp),
                                horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.CenterVertically) {
                                DeskIcon("搜索",DeskMuted,Modifier.size(15.dp))
                                Box(Modifier.weight(1f)) { if(query.isEmpty()) Text("搜索备注、IP 或端口",fontSize=13.sp,color=DeskMuted); input() }
                            }
                        })
                }
                if(!compact) Row(Modifier.fillMaxWidth().background(DeskRaised).padding(horizontal=14.dp,vertical=7.dp)) {
                    Text("设备备注",Modifier.weight(1f),fontSize=12.sp,color=DeskMuted)
                    Text("连接地址",Modifier.weight(1f),fontSize=12.sp,color=DeskMuted)
                    Text("连接状态",Modifier.width(100.dp),fontSize=12.sp,color=DeskMuted)
                    Text("操作",Modifier.width(112.dp),fontSize=12.sp,color=DeskMuted)
                }
            }
        }
        if(matches.isEmpty()) item {
            Column(Modifier.fillMaxWidth().background(DeskSurface).border(1.dp,DeskBorder,DeskShape).padding(vertical=26.dp),
                horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                DeskIcon(if(all.isEmpty()) "设备" else "搜索",DeskMuted,Modifier.size(25.dp))
                Text(if(all.isEmpty()) "尚未保存设备" else "没有匹配的设备",fontSize=14.sp,color=DeskMuted)
                if(all.isNotEmpty()) DeskButton("清除筛选") { query="";filter="全部";wifi="";site="" }
            }
        }
        items(matches,key={it.id}) { device ->
            var expanded by remember { mutableStateOf(false) }
            val active=device.id==live
            Row(Modifier.fillMaxWidth().background(DeskSurface).padding(horizontal=if(compact) 9.dp else 14.dp,vertical=7.dp),
                verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end=6.dp)) {
                    Tools {
                        Text(device.label,fontWeight=FontWeight.Medium,fontSize=14.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                        if(device.wifi.isNotEmpty()) Text(device.wifi,Modifier.background(DeskSelection,DeskShape).padding(horizontal=5.dp),fontSize=12.sp)
                        if(device.site.isNotEmpty()) Text(device.site,Modifier.background(DeskPaleGreen,DeskShape).padding(horizontal=5.dp),fontSize=12.sp)
                    }
                    if(compact) Text(device.address,fontFamily=DeskMono,fontSize=12.sp,color=DeskMuted)
                }
                if(!compact) {
                    Text(device.address,Modifier.weight(1f).padding(end=8.dp),fontFamily=DeskMono,fontSize=12.sp,color=DeskMuted)
                    Row(Modifier.width(100.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
                        Spacer(Modifier.size(7.dp).background(if(active) DeskGreen else DeskMuted,CircleShape))
                        Text(if(active) "在线" else "离线",fontSize=12.sp,color=DeskMuted)
                    }
                }
                Row(if(compact) Modifier else Modifier.width(112.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    DeskButton(if(active) "断开" else "连接",desk.connecting==null) { if(active) disconnect() else connect(device) }
                    Box {
                        DeskButton("⋯",modifier=Modifier.semantics { contentDescription="编辑 ${device.label}" }) { expanded=true }
                        DropdownMenu(expanded,{expanded=false}) {
                            DropdownMenuItem(onClick={expanded=false;edit(device)}) { Text("编辑") }
                            DropdownMenuItem(onClick={expanded=false;remove(device)}) { Text("移除本地记录") }
                        }
                    }
                }
            }
            Divider(color=DeskBorder)
        }
        item {
            if(desk.connecting!=null) Row(Modifier.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("正在连接 ${desk.connecting?.address}",Modifier.weight(1f),fontSize=13.sp)
                DeskButton("取消") { desk.disconnect() }
            }
            if(compact) Row(Modifier.padding(top=10.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                DeskButton("打开镜像",connected==1) { desk.mirror() }
                DeskButton(if(work.logRunning) "查看日志" else "开始采集",connected==1,click=logs)
            }
            if(desk.host.desktop) ToolChooser(desk,"adb")
        }
    }
}

@Composable
internal fun ToolChooser(desk:DeskController, tool:String) {
    var path by remember(tool) { mutableStateOf(desk.host.toolPath(tool)) }
    var busy by remember { mutableStateOf(false) }
    var entered by remember(path) { mutableStateOf(path.orEmpty()) }
    fun update(manual:Boolean) {
        busy=true
        desk.scope.launch {
            try {
                if(manual) desk.host.setToolPath(tool,entered) else desk.host.selectTool(tool)
                path=desk.host.toolPath(tool)
                entered=path.orEmpty()
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { desk.message=e.message }
            finally { busy=false }
        }
    }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(tool=="scrcpy") DeskInput(entered,"scrcpy.exe 路径",{entered=it},enabled=!busy)
        Tools {
            DeskButton(if(path.isNullOrBlank()) "选择 $tool.exe" else "$tool.exe · 更改",!busy,tint=DeskSelection) { update(false) }
            if(tool=="scrcpy") DeskButton("保存路径",!busy && entered.isNotBlank() && entered!=path,tint=DeskSelection) { update(true) }
        }
    }
}

@Composable
internal fun MirrorPage(desk:DeskController,active:Boolean) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        DeskTitle("镜像")
        Column(Modifier.fillMaxWidth().deskPanel().padding(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(desk.activeDevice?.let { "${it.label} · ${it.address}" } ?: "未连接设备",fontWeight=FontWeight.Medium)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                PrimaryButton(if(desk.mirrorBusy) "正在打开" else if(active) "返回镜像" else "打开",desk.session!=null && !desk.mirrorBusy) { desk.mirror() }
                if(active) DeskButton("停止") { desk.host.stopMirror() }
            }
            if(desk.host.desktop) ToolChooser(desk,"scrcpy")
        }
    }
}

private data class Gauge(val label:String, val percent:Double?, val detail:String)

@Composable
private fun MetricGrid(sample:DeskMetrics?) {
    val loadPercent = sample?.load?.let { load -> sample.cores?.let { 100 * load / it } }
    val gauges = listOf(
        Gauge("整机 CPU", sample?.cpu, sample?.cpuSource.orEmpty()),
        Gauge("系统负载", loadPercent, sample?.load?.let { "1 分钟均值 $it" + (sample.cores?.let { cores -> " · $cores 核" } ?: "") }.orEmpty()),
        Gauge("GPU 占用", sample?.gpu, if(sample?.gpu==null) "不可用" else "图形处理器"),
        Gauge("内存占用", sample?.memoryPercent, sample?.memory.orEmpty()),
        Gauge("磁盘占用", sample?.disk, if(sample?.disk==null) "不可用" else "/data 分区")
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = ((maxWidth + 8.dp) / (140.dp * LocalDensity.current.fontScale + 8.dp)).toInt().coerceIn(1,gauges.size)
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            gauges.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    row.forEach { gauge ->
                        Column(Modifier.weight(1f).fillMaxHeight().deskPanel().padding(12.dp),
                            horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(gauge.label,style=MaterialTheme.typography.body1,fontWeight=FontWeight.Medium)
                            Box(Modifier.size(76.dp * LocalDensity.current.fontScale),contentAlignment=Alignment.Center) {
                                CircularProgressIndicator(progress=1f, modifier=Modifier.fillMaxSize().clearAndSetSemantics {},
                                    color=DeskRaised, strokeWidth=5.dp)
                                gauge.percent?.let {
                                    CircularProgressIndicator(progress=(it / 100).toFloat().coerceIn(0f,1f),
                                        modifier=Modifier.fillMaxSize().clearAndSetSemantics {},color=DeskAccent,strokeWidth=5.dp)
                                }
                                Text(gauge.percent?.let { String.format(Locale.ROOT,"%.0f%%",it) } ?: "—",
                                    fontFamily=DeskMono,fontWeight=FontWeight.SemiBold)
                            }
                            Text(if(sample==null) "采样中" else gauge.detail.ifBlank { "不可用" },fontSize=12.sp,color=DeskMuted)
                        }
                    }
                    repeat(columns-row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
internal fun PanelPage(desk:DeskController) {
    val sample=desk.metrics
    val facts = desk.facts + mapOf("电池" to (sample?.battery ?: "—"), "电池温度" to (sample?.temperature ?: "—"))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        DeskTitle("面板")
        UnconfirmedTasks(desk.workspace)
        Column(Modifier.fillMaxWidth().deskPanel().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(desk.activeDevice?.let { "${it.name.ifBlank { desk.facts["型号"].orEmpty() }} · ${it.address}" }
                ?: desk.lastSampleDevice?.let { "已断开 · ${it.label}" } ?: "未连接设备",fontWeight=FontWeight.SemiBold)
            if(sample!=null) Text("${if(desk.session==null || desk.samplingError!=null) "已过期" else "采样时间"} · ${deskTime(sample.captured)}",
                style=MaterialTheme.typography.body2,color=DeskMuted)
            desk.samplingError?.let { Text(it,color=DeskError) }
            Tools {
                DeskButton(if(desk.snapshotBusy) "保存中" else "截图",desk.session!=null && !desk.snapshotBusy) { desk.screenshot() }
                DeskButton("复制设备信息",desk.session!=null && desk.facts.isNotEmpty()) {
                    val info=mapOf("设备" to desk.activeDevice?.label.orEmpty(),"地址" to desk.session!!.serial)+facts
                    desk.host.copy(info.entries.joinToString("\n") { "${it.key}  ${it.value}" })
                    desk.message="已复制设备信息"
                }
            }
        }
        if(sample!=null || desk.session!=null) MetricGrid(sample)
        if(desk.facts.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().deskPanel().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("设备信息", fontSize=16.sp,fontWeight=FontWeight.SemiBold)
                val columns = if(CompactDesk.current) 1 else 2
                facts.entries.chunked(columns).forEach { row ->
                    Divider(color=DeskBorder)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        row.forEach { (label,value) ->
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Text(label,fontSize=12.sp,color=DeskMuted)
                                Text(value,style=MaterialTheme.typography.body1)
                            }
                        }
                        if(row.size<columns) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
