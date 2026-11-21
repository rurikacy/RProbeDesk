package dev.rprobedesk

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import java.awt.Dialog
import java.awt.MouseInfo
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

internal fun showTrayMenu(restore:()->Unit,exit:()->Unit):ComposeDialog {
    val pointer=MouseInfo.getPointerInfo()
    val monitor=pointer.device.defaultConfiguration
    val bounds=monitor.bounds
    val insets=Toolkit.getDefaultToolkit().getScreenInsets(monitor)
    return ComposeDialog(null as Window?,Dialog.ModalityType.MODELESS,monitor).apply {
        isUndecorated=true
        type=Window.Type.POPUP
        isAlwaysOnTop=true
        title="RProbeDesk"
        setContent {
            DeskTheme {
                CompositionLocalProvider(CompactDesk provides false) {
                    Column(Modifier.fillMaxSize().deskPanel().onPreviewKeyEvent {
                        if(it.key==Key.Escape && it.type==KeyEventType.KeyDown) { dispose(); true } else false
                    }.padding(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp,Alignment.CenterVertically),
                        horizontalAlignment=Alignment.CenterHorizontally) {
                        DeskButton("打开 RProbeDesk",modifier=Modifier.fillMaxWidth(),tint=DeskSelection) { dispose(); restore() }
                        DeskButton("退出程序",modifier=Modifier.fillMaxWidth(),tint=DeskPaleRed) { dispose(); exit() }
                    }
                }
            }
        }
        setSize(164,92)
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
