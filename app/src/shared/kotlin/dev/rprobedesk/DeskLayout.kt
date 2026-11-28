package dev.rprobedesk

import androidx.compose.animation.core.*
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
                    DeskScrollColumn(Modifier.weight(1f)) {
                        SideCaption("工作空间")
                        deskPages.filterNot { it=="设置" }.forEach { page ->
                            if(page=="应用") SideCaption("调试工具")
                            SideLink(page,page,desk.page==page,true,
                            if(page=="设备") desk.prefs.devices.size.toString() else null) { desk.page=page } }
                        Spacer(Modifier.height(12.dp))
                        Divider(color=DeskBorder)
                        SideCaption("快捷访问")
                        SideLink("镜像","打开镜像",false,desk.session!=null) { desk.mirror() }
                        SideLink("日志",if(work.logRunning) "查看日志" else "开始采集",false,desk.session!=null,click=logs)
                    }
                    Divider(color=DeskBorder)
                    SideLink("设置","设置",desk.page=="设置",true) { desk.page="设置" }
                    if(desk.missingTools.isNotEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(top=4.dp)) {
                            desk.missingTools.forEach { tool -> ToolPathLink(tool) { desk.openToolSettings(tool) } }
                        }
                    }
                    Row(Modifier.padding(horizontal=8.dp,vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),
                        verticalAlignment=Alignment.CenterVertically) {
                        DeskIcon("Shell",DeskMuted,Modifier.size(14.dp))
                        Text(tr(if(desk.host.desktop) "Windows 主控" else "Android 主控"),fontSize=12.sp,color=DeskMuted)
                    }
                }
                Spacer(Modifier.width(1.dp).fillMaxHeight().background(DeskBorder))
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().heightIn(min=52.dp).background(DeskSurface).padding(horizontal=16.dp),
                    horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    if(compact) DeskBrand(showName=frameWidth >= 310.dp * LocalDensity.current.fontScale) else {
                        DeskIcon("镜像",DeskMuted,Modifier.size(16.dp))
                        Text(tr("工作空间"),fontSize=13.sp,color=DeskMuted)
                        Text("›",color=DeskMuted)
                        Text(tr(desk.page),fontSize=13.sp,color=DeskInk,fontWeight=FontWeight.Medium)
                    }
                    val device = desk.activeDevice
                    val connecting=desk.connecting
                    val pulse=if(connecting!=null && !LocalReducedMotion.current) {
                        rememberInfiniteTransition().animateFloat(.38f,1f,infiniteRepeatable(tween(850),RepeatMode.Reverse)).value
                    } else 1f
                    Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.End),
                        verticalAlignment=Alignment.CenterVertically) {
                        Spacer(Modifier.size(7.dp).background(when {
                            connecting!=null -> DeskAccent.copy(alpha=pulse)
                            device!=null -> DeskGreen
                            else -> DeskMuted
                        },CircleShape))
                        if(connecting!=null) {
                            Text(uiMessage("正在连接 ${connecting.address}"),Modifier.weight(1f,fill=false),
                                maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=13.sp,color=DeskInk)
                            DeskButton("取消") { desk.disconnect() }
                        } else
                        Column(Modifier.widthIn(max=if(compact) frameWidth * .7f else 360.dp).padding(vertical=4.dp), horizontalAlignment=Alignment.End) {
                            Text(device?.name?.ifBlank { desk.facts["型号"].orEmpty() } ?: tr("未连接设备"),
                                maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=13.sp,color=DeskInk)
                            if(device!=null) Text(device.address,Modifier.horizontalScroll(rememberScrollState()),maxLines=1,fontFamily=DeskMono,fontSize=12.sp,color=DeskMuted)
                        }
                    }
                }
                Divider(color=DeskBorder)

                Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal=if(compact) 12.dp else 20.dp,vertical=if(compact) 10.dp else 16.dp)) {
                    key(desk.page) { Box(Modifier.fillMaxSize().deskReveal()) { content() } }
                }
                if(compact) MobileNavigation(desk.page) { desk.page=it }
                if(compact && desk.missingTools.isNotEmpty()) {
                    Divider(color=DeskBorder)
                    Row(Modifier.fillMaxWidth().background(DeskSidebar).padding(horizontal=4.dp)) {
                        desk.missingTools.forEach { tool -> ToolPathLink(tool) { desk.openToolSettings(tool) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolPathLink(tool:String, openSettings:()->Unit) {
    Box(Modifier.clip(DeskShape).clickable(role=Role.Button,onClickLabel=tr("前往设置"),onClick=openSettings)
        .heightIn(min=32.dp).padding(horizontal=8.dp,vertical=4.dp),contentAlignment=Alignment.CenterStart) {
        Text(tr(if(tool=="adb") "未连接ADB" else "未连接scrcpy"),color=DeskError,fontSize=12.sp)
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
        .then(if(selected) Modifier.border(1.dp,DeskSelectionBorder,DeskShape) else Modifier)
        .semantics { if(isTab) this.selected=selected }
        .clickable(enabled=enabled,role=if(isTab) Role.Tab else Role.Button,onClick=click)) {

        Row(Modifier.fillMaxWidth().heightIn(min=40.dp).padding(horizontal=10.dp,vertical=5.dp),
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
    Divider(color=DeskBorder)
    BoxWithConstraints(Modifier.fillMaxWidth().background(DeskSidebar)) {
        val large = LocalDensity.current.fontScale > 1.4f
        val rows = if(large || maxWidth < 336.dp || (UiLanguage.english && maxWidth < 490.dp))
            deskPages.chunked(4) else listOf(deskPages)
        Column {
            rows.forEach { pages ->
                Row(Modifier.fillMaxWidth()) {
                    pages.forEach { name ->
                        val active=current==name
                        Column(Modifier.weight(1f).heightIn(min=48.dp).semantics { selected=active }
                            .clickable(role=Role.Tab) { select(name) }.padding(vertical=4.dp),
                            verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                            if(!large) Box(Modifier.width(36.dp).background(if(active) DeskSelection else Color.Transparent,DeskShape)
                                .padding(vertical=2.dp),contentAlignment=Alignment.Center) {
                                DeskIcon(name,if(active) DeskAccent else DeskMuted,Modifier.size(17.dp))
                            }
                            Text(tr(name),fontSize=11.sp,lineHeight=14.sp,color=if(active) DeskAccent else DeskMuted)
                        }
                    }
                }
            }
        }
    }
}
