@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.rprobedesk

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.LocalDensity
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun DevicePage(desk:DeskController, work:WorkspaceModel, logs:()->Unit, edit:(DeskDevice?)->Unit,pair:()->Unit,discover:()->Unit,
                        connect:(DeskDevice)->Unit, disconnect:()->Unit, remove:(DeskDevice)->Unit,
                        movedDeviceId:String?,onMoveHandled:()->Unit,isConnected:(String)->Boolean = { false }) {
    val all=desk.prefs.devices
    val live=desk.activeDevice?.id
    val connected=desk.session!=null
    val compact=CompactDesk.current
    if(all.isEmpty()) {
        DeskScrollColumn(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            DeskTitle("设备")
            Column(Modifier.fillMaxWidth().deskPanel().padding(if(compact) 16.dp else 24.dp),
                verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Column(Modifier.widthIn(max=520.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(tr("连接第一台设备"),fontSize=24.sp,lineHeight=32.sp,fontWeight=FontWeight.Medium,color=DeskInk)
                    Text(tr("在同一局域网内发现已开启无线调试的设备，也可按 IP 和端口添加。"),
                        style=MaterialTheme.typography.body1,color=DeskMuted)
                }
                Tools {
                    PrimaryButton("发现设备",click=discover)
                    DeskButton("配对",click=pair)
                    DeskButton("添加") { edit(null) }
                }
            }
        }
        return
    }
    var query by remember { mutableStateOf("") }
    val networks=all.map { it.wifi }.filter(String::isNotEmpty).distinct().sorted()
    val sites=all.map { it.site }.filter(String::isNotEmpty).distinct().sorted()
    val hasTags=networks.isNotEmpty() || sites.isNotEmpty()
    val grouping=desk.prefs.deviceGrouping
    val grouped=grouping!=DeviceGrouping.NONE
    val showWifi=grouping!=DeviceGrouping.WIFI && networks.isNotEmpty()
    val showSite=grouping!=DeviceGrouping.SITE && sites.isNotEmpty()
    var filtersOpen by remember { mutableStateOf(false) }
    var wifi by remember { mutableStateOf("") }
    var site by remember { mutableStateOf("") }
    LaunchedEffect(networks,sites) {
        if(wifi !in networks) wifi=""
        if(site !in sites) site=""
    }
    var expanded by remember { mutableStateOf<String?>(null) }
    val matches=all.filter { device ->
        (wifi.isEmpty() || device.wifi==wifi) && (site.isEmpty() || device.site==site) &&
            (device.label.contains(query.trim(),true) || device.address.contains(query.trim(),true))
    }
    val sections=grouping.sections(matches)
    val reorder=rememberDeskReorder(sections.values.flatten(),desktop=desk.host.desktop,key={ it.id },groupOf=grouping::groupKey) { ids ->
        val name=matches.firstOrNull { it.id==ids.firstOrNull() }?.let(grouping::groupKey) ?: return@rememberDeskReorder
        try { desk.prefs.reorderDevices(ids,grouping,name) }
        catch(e:Exception) { desk.message=e.localizedMessage ?: "无法保存本地配置" }
    }
    val groupIndices=remember(grouping,sites,networks) {
        val tags=if(grouping==DeviceGrouping.SITE) sites else networks
        tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
            .withIndex().associate { (index,name) -> name to index }
    }
    val light=MaterialTheme.colors.isLight
    val neutral=DeskMuted
    LaunchedEffect(movedDeviceId) {
        val id=movedDeviceId ?: return@LaunchedEffect
        // The page title and search toolbar precede the first group header.
        var headerIndex=2
        for(devices in sections.values) {
            val offset=devices.indexOfFirst { it.id==id }
            if(offset>=0) {
                val target=headerIndex+1+offset
                if(reorder.list.layoutInfo.visibleItemsInfo.none { it.index==target && it.key==id })
                    reorder.list.scrollToItem(if(offset==0) headerIndex else target)
                break
            }
            headerIndex+=1+devices.size
        }
        onMoveHandled()
    }
    val scale=LocalDensity.current.fontScale
    val actionWidth=(if(UiLanguage.english) 148.dp else 112.dp)*scale
    val actions: @Composable ()->Unit = {
        Tools {
            DeskButton("发现设备",click=discover)
            DeskButton("配对",click=pair)
            DeskButton("添加") { edit(null) }
        }
    }
    DeskLazyColumn(Modifier.fillMaxSize().then(reorder.gestures),state=reorder.list,contentPadding=PaddingValues(bottom=12.dp)) {
        item {
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    DeskTitle("设备")
                    val count="${tr("已保存")} ${all.size}"
                    val summary=if(!desk.host.desktop && reorder.sortable) "$count · ${tr("长按排序")}" else count
                    Text(summary,style=MaterialTheme.typography.body2,color=DeskMuted)
                    if(!compact) {
                        Spacer(Modifier.weight(1f))
                        actions()
                    }
                }
                if(compact) actions()
            }
            Spacer(Modifier.height(12.dp))
        }
        item {
            Column(Modifier.fillMaxWidth().deskPanel()) {
                BoxWithConstraints(Modifier.fillMaxWidth().padding(8.dp)) {
                    val separateRow=maxWidth<600.dp*scale
                    val groupingChoices:@Composable ()->Unit = {
                        DeviceGroupingChoices(grouping,desk.host.desktop) { mode ->
                            try { desk.prefs.setDeviceGrouping(mode) }
                            catch(e:Exception) { desk.message=e.localizedMessage ?: "无法保存本地配置" }
                        }
                    }
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                            SearchField(query,{ query=it },Modifier.weight(1f),"搜索设备","搜索备注、IP 或端口")
                            if(hasTags) {
                                val count=listOf(wifi,site).count { it.isNotEmpty() }
                                val label=tr(if(filtersOpen) "收起筛选" else "筛选")
                                DeskButton(if(count==0) label else "$label ($count)",localize=false,tint=DeskSelection,selected=filtersOpen || count>0,
                                    modifier=Modifier.semantics { stateDescription=tr(if(filtersOpen) "已展开" else "已收起") }) { filtersOpen=!filtersOpen }
                            }
                            if(!separateRow) groupingChoices()
                        }
                        if(separateRow) groupingChoices()
                    }
                }
                if(filtersOpen && hasTags) BoxWithConstraints(Modifier.fillMaxWidth().padding(start=12.dp,end=12.dp,bottom=8.dp)) {
                    val columns=if(networks.isNotEmpty() && sites.isNotEmpty() && maxWidth>=400.dp*scale) 2 else 1
                    val width=if(columns==2) (maxWidth-12.dp)/2 else maxWidth
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        if(networks.isNotEmpty()) DeviceFilterGroup("WiFi",networks,wifi,DeskPaleBlue,Modifier.width(width)) { wifi=it }
                        if(sites.isNotEmpty()) DeviceFilterGroup("场地",sites,site,DeskSelection,Modifier.width(width)) { site=it }
                    }
                }
                if(!compact) Row(Modifier.fillMaxWidth().background(DeskRaised)
                    .padding(start=12.dp + (if(desk.host.desktop && reorder.sortable) DeskReorderHandleWidth else 0.dp),
                        end=12.dp,top=4.dp,bottom=4.dp),
                    horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(tr("设备"),Modifier.weight(1f),fontSize=12.sp,color=DeskMuted)
                    if(showWifi || showSite) Text(listOfNotNull("WiFi".takeIf { showWifi },tr("场地").takeIf { showSite }).joinToString(" · "),
                        Modifier.weight(1f),fontSize=12.sp,color=DeskMuted)
                    Text(tr("操作"),Modifier.width(actionWidth),fontSize=12.sp,color=DeskMuted)
                }
            }
        }
        if(matches.isEmpty()) item {
            Column(Modifier.fillMaxWidth().background(DeskSurface).padding(16.dp),
                verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(tr("没有匹配的设备"),style=MaterialTheme.typography.body1,color=DeskInk)
                DeskButton("清除筛选") { query="";wifi="";site="" }
            }
        }
        for((name,devices) in reorder.items.groupBy(grouping::groupKey)) {
            val index=groupIndices[name] ?: -1
            val tone=if(index<0) neutral else deskGroupTone(index,light)
            if(grouped) item(key=-(index+2)) {
                val title=name.ifEmpty { tr(if(grouping==DeviceGrouping.SITE) "未设置场地" else "未设置 WiFi") }
                GroupHeader(title,uiMessage("${devices.size} 台设备"),tone)
            }
            items(devices,key={it.id}) { device ->
                val active=device.id==live
                val rowActions: @Composable ()->Unit = {
                    Tools {
                        DeskButton(if(active) "断开" else if(isConnected(device.id)) "切换" else "连接",desk.connecting==null,tint=if(active) DeskSurface else DeskSelection) {
                            if(active) disconnect() else connect(device)
                        }
                        DeskButton(if(expanded==device.id) "收起" else "⋯",modifier=Modifier.semantics {
                            contentDescription=uiMessage("设备操作 ${device.label}")
                            stateDescription=tr(if(expanded==device.id) "已展开" else "已收起")
                        }) {
                            expanded=if(expanded==device.id) null else device.id
                        }
                    }
                }
                val surface=Modifier.background(DeskSurface)
                val rowSurface=if(grouped) surface.deskGroupMarker(tone) else surface
                DeskReorderRow(reorder,device.id,device.label,rowSurface) {
                    val row=Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp)
                    if(compact) Column(row,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        if(scale>1.3f) {
                            DeviceIdentity(device,active)
                            DeviceTags(device,showWifi,showSite)
                            rowActions()
                        } else {
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                                DeviceIdentity(device,active,Modifier.weight(1f))
                                rowActions()
                            }
                            DeviceTags(device,showWifi,showSite)
                        }
                    } else Row(row,horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                        DeviceIdentity(device,active,Modifier.weight(1f))
                        if(showWifi || showSite) Box(Modifier.weight(1f)) { DeviceTags(device,showWifi,showSite) }
                        Box(Modifier.width(actionWidth)) { rowActions() }
                    }
                    if(expanded==device.id) Box(Modifier.fillMaxWidth().deskReveal().background(DeskSurface).padding(start=8.dp,end=8.dp,bottom=8.dp),contentAlignment=Alignment.CenterEnd) {
                        Tools {
                            DeskButton("编辑",tint=DeskSelection) { expanded=null; edit(device) }
                            DeskButton("移除本地记录",tint=DeskPaleRed) { expanded=null; remove(device) }
                        }
                    }
                }
                Divider(color=DeskBorder)
            }
        }
        item {
            if(compact) Box(Modifier.padding(top=12.dp)) {
                Tools {
                    DeskButton("打开镜像",connected) { desk.mirror() }
                    DeskButton(if(work.logRunning) "查看日志" else "开始采集",connected,click=logs)
                }
            }
        }
    }
}

@Composable
private fun SearchField(query:String,change:(String)->Unit,modifier:Modifier,label:String,hint:String=label) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(query,change,modifier.onFocusChanged { focused=it.isFocused }
        .semantics { contentDescription=tr(label) },singleLine=true,
        textStyle=MaterialTheme.typography.body2.copy(color=DeskInk),cursorBrush=SolidColor(DeskAccent),
        decorationBox={ input ->
            Row(Modifier.fillMaxWidth().heightIn(min=if(CompactDesk.current) 44.dp else 36.dp)
                .border(if(focused) 2.dp else 1.dp,if(focused) DeskAccent else DeskBorder,DeskShape)
                .padding(horizontal=8.dp,vertical=4.dp),
                horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                DeskIcon("搜索",DeskMuted,Modifier.size(16.dp))
                Box(Modifier.weight(1f)) {
                    if(query.isEmpty()) Text(tr(hint),fontSize=13.sp,color=DeskMuted,maxLines=1,overflow=TextOverflow.Ellipsis)
                    input()
                }
            }
        })
}

@Composable
private fun DeviceGroupingChoices(grouping:DeviceGrouping,desktop:Boolean,select:(DeviceGrouping)->Unit) {
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(tr("分组"),style=MaterialTheme.typography.body2,color=DeskMuted)
        FlowRow(Modifier.selectableGroup(),horizontalArrangement=Arrangement.spacedBy(8.dp),
            verticalArrangement=Arrangement.spacedBy(4.dp)) {
            DeviceGrouping.entries.forEach { mode ->
                DeskButton(mode.label,tint=DeskSelection,selected=mode==grouping,
                    modifier=Modifier.sizeIn(minWidth=64.dp,minHeight=if(desktop) 32.dp else 48.dp)) {
                    if(mode!=grouping) select(mode)
                }
            }
        }
    }
}

internal fun deskGroupTone(index:Int,light:Boolean):Color =
    Color.hsl((262f+index*137.508f)%360f,if(light) .38f else .42f,if(light) .43f else .69f)

internal fun Modifier.deskGroupMarker(tone:Color,bothEdges:Boolean=false):Modifier = drawWithContent {
    drawContent()
    val marker=Size(2.dp.toPx(),size.height)
    val color=tone.copy(alpha=.65f)
    drawRect(color,size=marker)
    if(bothEdges) drawRect(color,topLeft=Offset(size.width-marker.width,0f),size=marker)
}

@Composable
internal fun GroupHeader(title:String,detail:String,tone:Color,modifier:Modifier=Modifier,titleColor:Color=DeskInk) {
    BoxWithConstraints(modifier.fillMaxWidth().semantics(mergeDescendants=true) { heading() }.padding(top=12.dp,bottom=6.dp)) {
        val titleWidth=maxWidth*.62f
        Row(Modifier.fillMaxWidth(),
            horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            DeskIcon("收藏",tone,Modifier.size(16.dp))
            Text(title,Modifier.widthIn(max=titleWidth),style=MaterialTheme.typography.body2,fontWeight=FontWeight.SemiBold,
                color=titleColor,maxLines=2,overflow=TextOverflow.Ellipsis)
            Divider(Modifier.weight(1f),color=tone.copy(alpha=.42f))
            if(detail.isNotEmpty()) Text(detail,fontSize=12.sp,color=DeskMuted)
        }
    }
}

@Composable
private fun DeviceFilterGroup(label:String,values:List<String>,selection:String,tint:Color,modifier:Modifier,select:(String)->Unit) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(tr(label),style=MaterialTheme.typography.body2,color=DeskMuted)
        Tools { values.forEach { tag -> DeskButton(tag,localize=false,tint=tint,selected=selection==tag) { select(if(selection==tag) "" else tag) } } }
    }
}

@Composable
private fun DeviceIdentity(device:DeskDevice,active:Boolean,modifier:Modifier=Modifier) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(2.dp)) {
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(device.label,Modifier.align(Alignment.CenterVertically),fontFamily=if(device.name.isBlank()) DeskMono else null,
                fontSize=15.sp,lineHeight=20.sp,fontWeight=FontWeight.Medium,color=DeskInk,
                maxLines=if(device.name.isBlank()) Int.MAX_VALUE else 2,overflow=TextOverflow.Ellipsis)
            if(active) Row(Modifier.align(Alignment.CenterVertically),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                Spacer(Modifier.size(6.dp).background(DeskGreen,CircleShape))
                Text(tr("当前连接"),fontSize=12.sp,lineHeight=16.sp,color=DeskGreen)
            }
        }
        if(device.name.isNotBlank()) Text(device.address,fontFamily=DeskMono,fontSize=12.sp,lineHeight=16.sp,color=DeskMuted)
    }
}

@Composable
private fun DeviceTags(device:DeskDevice,showWifi:Boolean,showSite:Boolean) {
    if((!showWifi || device.wifi.isEmpty()) && (!showSite || device.site.isEmpty())) return
    FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        if(showWifi && device.wifi.isNotEmpty()) Text("WiFi ${device.wifi}",Modifier.background(DeskPaleBlue,DeskShape).padding(horizontal=6.dp,vertical=2.dp),
            fontSize=12.sp,lineHeight=16.sp,color=DeskBlue,maxLines=1,overflow=TextOverflow.Ellipsis)
        if(showSite && device.site.isNotEmpty()) Text("${tr("场地")} ${device.site}",Modifier.background(DeskSelection,DeskShape).padding(horizontal=6.dp,vertical=2.dp),
            fontSize=12.sp,lineHeight=16.sp,color=DeskAccent,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ToolChooser(desk:DeskController, tool:String) {
    var path by remember(tool) { mutableStateOf(desk.host.toolPath(tool)) }
    var busy by remember { mutableStateOf(false) }
    var entered by remember(path) { mutableStateOf(path.orEmpty()) }
    val field=remember { FocusRequester() }
    LaunchedEffect(desk.toolToConfigure) {
        if(desk.toolToConfigure==tool) {
            field.requestFocus()
            desk.toolToConfigure=null
        }
    }
    fun update(manual:Boolean) {
        busy=true
        desk.scope.launch {
            try {
                if(manual) desk.host.setToolPath(tool,entered) else desk.host.selectTool(tool)
                path=desk.host.toolPath(tool)
                if(desk.host.hasSavedToolPath(tool)) desk.missingTools.remove(tool)
                entered=path.orEmpty()
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { desk.message=e.message }
            finally { busy=false }
        }
    }
    Column(Modifier.fillMaxWidth().deskPanel().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        DeskInput(entered,"$tool.exe 路径",{entered=it},Modifier.focusRequester(field),enabled=!busy)
        if(tool in desk.missingTools && path != null && entered == path)
            Text(tr("已检测到可用路径，点击「保存路径」确认"),color=DeskMuted,fontSize=12.sp)
        Tools {
            PrimaryButton("更改 $tool.exe 路径",!busy) { update(false) }
            DeskButton("保存路径",!busy && entered.isNotBlank(),Modifier.semantics { contentDescription=tr("保存 $tool.exe 路径") },tint=DeskAccent) { update(true) }
        }
    }
}

private data class Gauge(val label:String, val percent:Double?, val detail:String, val hasReading:Boolean=percent!=null)

@Composable
internal fun MetricGrid(sample:DeskMetrics?) {
    val loadPercent = sample?.load?.let { load -> sample.cores?.let { 100 * load / it } }
    fun capacityLines(detail:String)=uiMessage(detail).replace(" · ","\n")
    val gauges = listOf(
        Gauge("整机 CPU", sample?.cpu, sample?.cpuSource.orEmpty()),
        Gauge("系统负载", loadPercent, sample?.load?.let { "1 分钟均值 $it" + (sample.cores?.let { cores -> " · $cores 核" } ?: "") }.orEmpty(),
            hasReading=sample?.load!=null),
        Gauge("GPU 占用", sample?.gpu, if(sample?.gpu==null) "不可用" else "图形处理器"),
        Gauge("内存占用", sample?.memoryPercent, capacityLines(sample?.memory.orEmpty())),
        Gauge("磁盘占用", sample?.disk, "${capacityLines(sample?.diskCapacity ?: "容量不可用")}\n/data 分区",
            hasReading=sample?.disk!=null || sample?.diskCapacity!=null)
    )
    val (readings,missing)=gauges.partition { it.hasReading }
    val scale=LocalDensity.current.fontScale
    Layout(modifier=Modifier.fillMaxWidth(),content={
        readings.forEach { gauge ->
            Row(Modifier.deskPanel().semantics(mergeDescendants=true) {}.padding(12.dp),
                horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                if(gauge.percent!=null) {
                    Box(Modifier.size(60.dp*scale),contentAlignment=Alignment.Center) {
                        CircularProgressIndicator(progress=1f,modifier=Modifier.fillMaxSize().clearAndSetSemantics {},
                            color=DeskRaised,strokeWidth=4.dp)
                        CircularProgressIndicator(progress=(gauge.percent/100).toFloat().coerceIn(0f,1f),
                            modifier=Modifier.fillMaxSize().clearAndSetSemantics {},color=DeskAccent,strokeWidth=4.dp)
                        Text(String.format(Locale.ROOT,"%.0f%%",gauge.percent),fontFamily=DeskMono,
                            fontSize=16.sp,fontWeight=FontWeight.Medium)
                    }
                }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(tr(gauge.label),style=MaterialTheme.typography.body1,fontWeight=FontWeight.Medium)
                    Text(uiMessage(gauge.detail),style=MaterialTheme.typography.body2,
                        color=if(gauge.percent==null) DeskInk else DeskMuted)
                }
            }
        }
        if(missing.isNotEmpty()) MissingMetrics(missing,sample==null,Modifier.semantics(mergeDescendants=true) {})
    }) { cards,constraints ->
        val width=constraints.maxWidth
        val gap=8.dp.roundToPx()
        val columns=((width+gap)/((184.dp*scale).roundToPx()+gap)).coerceIn(1,gauges.size)
        fun columnStart(column:Int)=(column*(width+gap).toDouble()/columns).roundToInt()
        val widths=cards.indices.map { index ->
            val column=index%columns
            val end=if(index==readings.size) columns else column+1
            columnStart(end)-gap-columnStart(column)
        }
        val height=cards.indices.maxOf { cards[it].maxIntrinsicHeight(widths[it]) }
        val cells=cards.mapIndexed { index,card -> card.measure(Constraints.fixed(widths[index],height)) }
        val rows=(cells.size+columns-1)/columns
        layout(width,rows*height+(rows-1)*gap) {
            cells.forEachIndexed { index,cell ->
                cell.placeRelative(columnStart(index%columns),(index/columns)*(height+gap))
            }
        }
    }
}

@Composable
private fun MissingMetrics(gauges:List<Gauge>,sampling:Boolean,modifier:Modifier) {
    Column(modifier.deskPanel().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp,Alignment.CenterVertically)) {
        gauges.forEach { gauge ->
            Tools {
                Text(tr(gauge.label),style=MaterialTheme.typography.body2,color=DeskInk)
                val detail=if(sampling) "采样中" else gauge.detail.takeUnless { it.isBlank() || it=="—" } ?: "不可用"
                Text(uiMessage(detail),style=MaterialTheme.typography.body2,color=DeskMuted)
            }
        }
    }
}

@Composable
private fun ParameterRow(fields:List<Pair<String,String>>,columns:Int) {
    val rule=androidx.compose.ui.graphics.lerp(DeskSurface,DeskBorder,.55f)
    val halfLine=with(LocalDensity.current) { 9.sp.toDp() }
    Row(Modifier.fillMaxWidth().background(DeskSurface).drawWithContent {
        drawContent()
        // Use one physical pixel for the rules at every Windows display scale.
        drawLine(rule,Offset(0f,size.height-.5f),Offset(size.width,size.height-.5f))
        for(i in 1 until fields.size) {
            val x=kotlin.math.floor(size.width*i/columns)+.5f
            drawLine(rule,Offset(x,0f),Offset(x,size.height))
        }
    }) {
        fields.forEach { (label,value) ->
            Column(Modifier.weight(1f).padding(horizontal=8.dp,vertical=halfLine)) {
                Text(label,fontSize=12.sp,lineHeight=15.sp,color=DeskMuted)
                Text(value,fontSize=14.sp,lineHeight=18.sp,color=DeskInk)
            }
        }
        if(fields.size<columns) Spacer(Modifier.weight((columns-fields.size).toFloat()))
    }
}

@Composable
internal fun PanelPage(desk:DeskController) {
    val mirrorOn by desk.host.mirrorActive.collectAsState()
    val sample=desk.metrics
    val target=desk.session
    val foreground by desk.host.foreground.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var detailStatus by remember(target) { mutableStateOf("") }
    var query by remember(target) { mutableStateOf("") }
    LaunchedEffect(target,foreground,refresh) {
        if(target==null || !foreground) return@LaunchedEffect
        detailStatus="正在读取参数"
        try {
            val details=kotlinx.coroutines.withTimeoutOrNull(20000) {
                parsePanelDetails(target.checked(panelCommand))
            } ?: error("参数读取超时")
            if(desk.session===target) desk.panelDetails=details
            detailStatus=""
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) { detailStatus=e.localizedMessage ?: "参数读取失败" }
    }
    val details=desk.panelDetails
    val facts = desk.facts + details?.overview.orEmpty() + mapOf("电池" to (sample?.battery ?: "—"), "电池温度" to (sample?.temperature ?: "—"))
    val english=UiLanguage.english
    val sections=remember(facts,details,query,english) {
        val summaries=facts.entries.groupBy { (key,_) -> when(key) {
            "是否开启 MAC 随机化","是否允许降级更新应用","IP 地址","MAC","系统调试标志","启动验证状态","加密状态" -> "网络与安装策略"
            "Android","Android 用户","SDK","安全补丁","构建类型","构建指纹","构建编号" -> "Android 与构建"
            "电池","电池温度" -> "电源"
            else -> "硬件与显示"
        } }.mapValues { (_,rows) -> rows.associate { it.key to it.value } }
        val search=query.trim()
        (summaries + details?.sections.orEmpty()).mapValues { (group, values) ->
            val groupMatch=search.isEmpty() || panelGroup(group,english).contains(search,true) || group.contains(search,true)
            values.mapNotNull { (key,value) ->
                val label=panelLabel(group,key,english)
                val reading=panelValue(group,key,value,english)
                if(groupMatch || listOf(key,value,label,reading).any { it.contains(search,true) }) label to reading else null
            }
        }
    }
    val colors=listOf(DeskAccent,DeskBlue,DeskGreen,DeskWarning)
    val columns=if(desk.host.desktop) 3 else if(CompactDesk.current) 1 else 2
    DeskLazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(bottom=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                DeskTitle("面板")
                UnconfirmedTasks(desk.workspace)
            }
        }
        item {
            if(desk.session==null && desk.lastSampleDevice==null && desk.samplingError==null) {
                OfflineDevicePrompt("未连接设备") { desk.page="设备" }
            } else Column(Modifier.fillMaxWidth().deskPanel().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(desk.activeDevice?.let { "${it.name.ifBlank { desk.facts["型号"].orEmpty() }} · ${it.address}" }
                    ?: desk.lastSampleDevice?.let { uiMessage("已断开 · ${it.label}") } ?: tr("未连接设备"),fontWeight=FontWeight.SemiBold)
                if(sample!=null) Text("${tr(if(desk.session==null || desk.samplingError!=null) "已过期" else "采样时间")} · ${deskTime(sample.captured)}",
                    style=MaterialTheme.typography.body2,color=DeskMuted)
                desk.samplingError?.let { Text(uiMessage(it),color=DeskError) }
                if(desk.session==null) PrimaryButton("前往设备") { desk.page="设备" }
                else Tools {
                    DeskButton(if(desk.snapshotBusy) "保存中" else "截图",!desk.snapshotBusy) { desk.screenshot() }
                    DeskButton("复制设备信息",desk.facts.isNotEmpty()) {
                        val info=mapOf("设备" to desk.activeDevice?.label.orEmpty(),"地址" to desk.session!!.serial)+facts
                        desk.host.copy(info.entries.joinToString("\n") { "${tr(it.key)}  ${uiMessage(it.value)}" })
                        desk.message="已复制设备信息"
                    }
                    PrimaryButton(if(desk.mirrorBusy) "正在打开" else if(mirrorOn) "返回镜像" else "打开系统镜像",!desk.mirrorBusy) { desk.mirror() }
                    if(mirrorOn) DeskButton("停止") { desk.host.stopMirror() }
                }
            }
        }
        if(sample!=null || desk.session!=null) item {
            Column {
                GroupHeader(tr("实时监测"),"",DeskBlue)
                MetricGrid(sample)
            }
        }
        if(desk.facts.isNotEmpty()) {
            item {
                val reading=detailStatus=="正在读取参数"
                Column(Modifier.fillMaxWidth().padding(top=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),
                        verticalArrangement=Arrangement.spacedBy(8.dp),itemVerticalAlignment=Alignment.CenterVertically) {
                        SearchField(query,{query=it},Modifier.weight(1f).widthIn(min=144.dp*LocalDensity.current.fontScale),"搜索参数")
                        DeskButton(if(reading) "正在读取参数" else "刷新参数",target!=null && !reading,
                            modifier=Modifier.heightIn(min=36.dp)) { refresh++ }
                    }
                    if(detailStatus.isNotEmpty() && !reading) Text(uiMessage(detailStatus),color=DeskError)
                }
            }
            if(sections.values.all { it.isEmpty() }) item {
                Text(tr("没有匹配的参数"),Modifier.padding(top=12.dp),color=DeskMuted)
            }
            sections.entries.forEachIndexed { index,(group,entries) ->
                if(entries.isEmpty()) return@forEachIndexed
                val searching=query.isNotBlank()
                val open=searching || desk.prefs.isPanelGroupExpanded(group,defaultExpanded=group !in details?.sections.orEmpty())
                item(key=group) {
                    val modifier=if(searching) Modifier else Modifier.clickable(role=Role.Button) {
                        try { desk.prefs.setPanelGroupExpanded(group,!open) }
                        catch(e:Exception) { desk.message=e.localizedMessage ?: "无法保存本地配置" }
                    }.semantics { stateDescription=tr(if(open) "已展开" else "已收起") }
                    val arrow=when { searching -> ""; open -> "▾ "; else -> "▸ " }
                    GroupHeader(panelGroup(group,english),arrow+uiMessage("${entries.size} 项"),colors[index % colors.size],modifier)
                }
                val rowColumns=if(searching && entries.size==1) 1 else columns
                if(open) items(entries.chunked(rowColumns)) { row -> ParameterRow(row,rowColumns) }
            }
        }
    }
}
