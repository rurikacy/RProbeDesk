package dev.rprobedesk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.awt.Dialog
import java.awt.GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSPARENT
import java.awt.MouseInfo
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.RoundRectangle2D

private const val menuRadius=12
private val menuShape=RoundedCornerShape(menuRadius.dp)
private val itemShape=RoundedCornerShape(6.dp)

@Composable
private fun TrayItem(label:String,icon:String,modifier:Modifier,focus:FocusRequester,other:FocusRequester,
                     keyboardNavigation:Boolean,click:()->Unit) {
    val interactions=remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val pressed by interactions.collectIsPressedAsState()
    val keyboardFocus=focused && keyboardNavigation
    val fill=when {
        pressed -> DeskSelection
        (hovered && !keyboardNavigation) || keyboardFocus -> DeskRaised
        else -> DeskSurface
    }
    Row(modifier.fillMaxWidth().clip(itemShape).background(fill)
        .then(if(keyboardFocus) Modifier.border(1.dp,DeskAccent,itemShape) else Modifier)
        .focusRequester(focus).onPreviewKeyEvent {
            if(it.type==KeyEventType.KeyDown && it.key in listOf(Key.DirectionUp,Key.DirectionDown,Key.Tab)) {
                other.requestFocus()
                true
            } else false
        }.clickable(interactionSource=interactions,indication=null,role=Role.Button,onClick=click)
        .padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        DeskIcon(icon,if(icon=="面板") DeskAccent else DeskMuted,Modifier.size(18.dp))
        Text(tr(label),color=DeskInk,style=MaterialTheme.typography.body1)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
internal fun showTrayMenu(reducedMotion:Boolean,dark:Boolean,restore:()->Unit,exit:()->Unit):ComposeDialog {
    val pointer=MouseInfo.getPointerInfo()
    val monitor=pointer.device.defaultConfiguration
    val bounds=monitor.bounds
    val insets=Toolkit.getDefaultToolkit().getScreenInsets(monitor)
    return ComposeDialog(null as Window?,Dialog.ModalityType.MODELESS,monitor).apply {
        isUndecorated=true
        isResizable=false
        type=Window.Type.POPUP
        isAlwaysOnTop=true
        title="RProbeDesk"
        background=java.awt.Color((if(dark) DarkDeskPalette else LightDeskPalette).surface.toArgb(),true)
        setContent(onPreviewKeyEvent={
            if(it.key==Key.Escape && it.type==KeyEventType.KeyDown) { dispose(); true } else false
        }) {
            DeskTheme(reducedMotion,dark) {
                val openFocus=remember { FocusRequester() }
                val exitFocus=remember { FocusRequester() }
                var keyboardNavigation by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { openFocus.requestFocus() }
                Column(Modifier.fillMaxSize().background(DeskSurface,menuShape).border(1.dp,DeskBorder,menuShape)
                    .onPreviewKeyEvent { if(it.type==KeyEventType.KeyDown) keyboardNavigation=true; false }
                    .onPointerEvent(PointerEventType.Move) { keyboardNavigation=false }
                    .onPointerEvent(PointerEventType.Press,PointerEventPass.Initial) { keyboardNavigation=false }
                    .padding(6.dp).deskReveal()) {
                    TrayItem("打开 RProbeDesk","面板",Modifier.weight(1f),openFocus,exitFocus,keyboardNavigation) { dispose(); restore() }
                    Divider(Modifier.padding(horizontal=8.dp,vertical=4.dp),color=DeskBorder)
                    TrayItem("退出程序","退出程序",Modifier.weight(1f),exitFocus,openFocus,keyboardNavigation) { dispose(); exit() }
                }
            }
        }
        setSize(184,92)
        if(pointer.device.isWindowTranslucencySupported(PERPIXEL_TRANSPARENT)) {
            val arc=menuRadius*2.0
            shape=RoundRectangle2D.Double(0.0,0.0,width.toDouble(),height.toDouble(),arc,arc)
        }
        val left=bounds.x+insets.left
        val top=bounds.y+insets.top
        setLocation(pointer.location.x.coerceIn(left,maxOf(left,bounds.x+bounds.width-insets.right-width)),
            (pointer.location.y-height).coerceIn(top,maxOf(top,bounds.y+bounds.height-insets.bottom-height)))
        addWindowFocusListener(object:WindowAdapter() {
            override fun windowLostFocus(e:WindowEvent) { dispose() }
        })
        isVisible=true
        toFront()
        requestFocus()
    }
}
