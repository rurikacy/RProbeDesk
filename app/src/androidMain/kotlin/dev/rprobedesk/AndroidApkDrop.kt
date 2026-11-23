package dev.rprobedesk

import android.app.Activity
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.*
import androidx.compose.ui.platform.LocalView

@Composable
internal fun Modifier.apkDropTarget(model:WorkspaceModel,hover:(Boolean)->Unit):Modifier {
    val activity=LocalView.current.context as? Activity ?: return this
    val host=model.host as? AndroidHost ?: return this
    val target=remember(model,activity) { object:ApkDropTarget(hover) {
        override fun onDrop(event:DragAndDropEvent):Boolean {
            hover(false)
            val drag=event.toAndroidDragEvent()
            val clip=drag.clipData ?: return false
            if(model.busy || clip.itemCount!=1) return false
            val uri=clip.getItemAt(0).uri?.takeIf { it.scheme=="content" } ?: return false
            val permission=activity.requestDragAndDropPermissions(drag) ?: return false
            val doc=runCatching { host.inputDocument(uri) }.getOrNull()
            if(doc==null || !doc.name.endsWith(".apk",true)) {
                permission.release()
                return false
            }
            model.installApk(dropped=doc,release=permission::release)
            return true
        }
    } }
    return dragAndDropTarget({ event ->
        val types=event.mimeTypes()
        "application/vnd.android.package-archive" in types || "application/octet-stream" in types
    },target)
}
