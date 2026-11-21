package dev.rprobedesk

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

@Composable
internal fun DeskFrame(desk:DeskController, work:WorkspaceModel, logs:()->Unit, content:@Composable ()->Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact=CompactDesk.current
        val frameWidth=maxWidth
        Row(Modifier.fillMaxSize()) {
            if(!compact) {
                Column(Modifier.width(184.dp).fillMaxHeight().background(DeskSidebar).padding(horizontal=8.dp)) {
                    Box(Modifier.padding(start=6.dp,top=16.dp,bottom=14.dp)) { DeskBrand() }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        SideCaption("工作空间")
                        deskPages.forEach { page -> SideLink(page,page,desk.page==page,true,
                            if(page=="设备") desk.prefs.devices.size.toString() else null) { desk.page=page } }
                        Spacer(Modifier.height(12.dp))
                        Divider(color=DeskBorder)
                        SideCaption("快捷访问")
                        SideLink("镜像","打开镜像",false,desk.session!=null) { desk.mirror() }
                        SideLink("日志",if(work.logRunning) "查看日志" else "开始采集",false,desk.session!=null,click=logs)
                    }
                    Divider(color=DeskBorder)
                    Row(Modifier.padding(horizontal=8.dp,vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),
                        verticalAlignment=Alignment.CenterVertically) {
                        DeskIcon("Shell",DeskMuted,Modifier.size(14.dp))
                        Text(tr(if(desk.host.desktop) "Windows 主控" else "Android 主控"),fontSize=12.sp,color=DeskMuted)
                    }
                }
                Spacer(Modifier.width(1.dp).fillMaxHeight().background(DeskBorder))
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().heightIn(min=44.dp).background(DeskSurface).padding(horizontal=16.dp),
                    horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    if(compact) DeskBrand(showName=frameWidth >= 310.dp * LocalDensity.current.fontScale) else {
                        Text(tr("工作空间"),fontSize=13.sp,color=DeskMuted)
                        Text("›",color=DeskMuted)
                        Text(tr(desk.page),fontSize=13.sp,color=DeskInk,fontWeight=FontWeight.Medium)
                    }
                    Spacer(Modifier.weight(1f))
                    val device = desk.activeDevice
                    Spacer(Modifier.size(7.dp).background(if(device!=null) DeskGreen else DeskMuted,CircleShape))
                    Column(Modifier.widthIn(max=if(compact) frameWidth * .7f else 360.dp).padding(vertical=4.dp), horizontalAlignment=Alignment.End) {
                        Text(device?.name?.ifBlank { desk.facts["型号"].orEmpty() } ?: tr("未连接设备"),
                            maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=13.sp,color=DeskInk)
                        if(device!=null) Text(device.address,Modifier.horizontalScroll(rememberScrollState()),maxLines=1,fontFamily=DeskMono,fontSize=12.sp,color=DeskMuted)
                    }
                }
                Divider(color=DeskBorder)
                if(compact) MobileNavigation(desk.page) { desk.page=it }
                Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal=if(compact) 12.dp else 16.dp,vertical=12.dp)) { content() }
            }
        }
    }
}

@Composable
private fun SideCaption(label:String) {
    Text(tr(label),Modifier.padding(start=10.dp,top=12.dp,bottom=6.dp),fontSize=12.sp,color=DeskMuted)
}

@Composable
private fun SideLink(icon:String,label:String,selected:Boolean,enabled:Boolean,count:String?=null,click:()->Unit) {
    val isTab = label in deskPages
    Box(Modifier.fillMaxWidth().padding(vertical=1.dp).clip(DeskShape).background(if(selected) DeskSelection else Color.Transparent)
        .semantics { if(isTab) this.selected=selected }
        .clickable(enabled=enabled,role=if(isTab) Role.Tab else Role.Button,onClick=click)) {
        if(selected) Spacer(Modifier.align(Alignment.CenterStart).width(2.dp).height(28.dp).background(DeskAccent))
        Row(Modifier.fillMaxWidth().heightIn(min=36.dp).padding(horizontal=10.dp,vertical=5.dp),
            horizontalArrangement=Arrangement.spacedBy(9.dp),verticalAlignment=Alignment.CenterVertically) {
            val color=if(!enabled) DeskMuted.copy(alpha=.58f) else if(selected) DeskAccent else DeskMuted
            DeskIcon(icon,color)
            Text(tr(label),color=if(selected) DeskInk else color,fontSize=14.sp,fontWeight=if(selected) FontWeight.Medium else FontWeight.Normal)
            if(count!=null) {
                Spacer(Modifier.weight(1f))
                Text(count,Modifier.background(DeskSurface,DeskShape).padding(horizontal=5.dp),fontSize=12.sp,color=DeskMuted)
            }
        }
    }
}

@Composable
private fun MobileNavigation(current:String,select:(String)->Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().background(DeskSurface).padding(horizontal=10.dp)) {
        val rows=if(maxWidth < (480*LocalDensity.current.fontScale).dp) deskPages.chunked(4) else listOf(deskPages)
        Column {
            rows.forEach { row -> Row(Modifier.fillMaxWidth()) {
                row.forEach { name ->
                    val active=current==name
                    Box(Modifier.weight(1f).heightIn(min=38.dp).semantics { selected=active }.clickable(role=Role.Tab) { select(name) },
                        contentAlignment=Alignment.Center) {
                        Text(tr(name),fontSize=13.sp,color=if(active) DeskAccent else DeskMuted)
                        if(active) Spacer(Modifier.align(Alignment.BottomCenter).width(24.dp).height(2.dp).background(DeskAccent))
                    }
                }
                if(rows.size>1) repeat(4-row.size) { Spacer(Modifier.weight(1f)) }
            } }
        }
    }
    Divider(color=DeskBorder)
}
