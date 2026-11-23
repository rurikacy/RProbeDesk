@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package dev.rprobedesk

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.*
import java.awt.datatransfer.DataFlavor
import java.io.File

@Composable
internal fun Modifier.apkDropTarget(model:WorkspaceModel,hover:(Boolean)->Unit):Modifier {
    fun apk(event:DragAndDropEvent):File? {
        val files=event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
        return (files?.singleOrNull() as? File)?.takeIf { it.extension.equals("apk",true) && it.isFile }
    }
    val target=remember(model) { object:ApkDropTarget(hover) {
        override fun onDrop(event:DragAndDropEvent):Boolean {
            hover(false)
            val file=runCatching { apk(event) }.getOrNull() ?: return false
            if(model.busy) return false
            model.installApk(dropped=WindowsDoc(file))
            return true
        }
    } }
    return dragAndDropTarget({ event ->
        event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) &&
            runCatching { apk(event)!=null }.getOrDefault(true)
    },target)
}
