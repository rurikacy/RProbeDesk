@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.rprobedesk

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.Image
import org.jetbrains.compose.resources.decodeToImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.draganddrop.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal abstract class ApkDropTarget(private val hover:(Boolean)->Unit): DragAndDropTarget {
    override fun onEntered(event:DragAndDropEvent) = hover(true)
    override fun onExited(event:DragAndDropEvent) = hover(false)
    override fun onEnded(event:DragAndDropEvent) = hover(false)
}

@Composable
internal fun Tools(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun EditValue(title: String, label: String, initial: String, action: String, cancel: () -> Unit, submit: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    DeskDialog(cancel, title = { Text(uiMessage(title)) },
        text = { DeskInput(value, label, { value = it }, Modifier.fillMaxWidth().initialKeyboardFocus()) },
        confirmButton = { DeskButton(action, value.isNotBlank(),tint=DeskAccent) { submit(value) } },
        dismissButton = { DeskButton("取消", click = cancel) })
}

@Composable
internal fun WorkspacePrompts(model: WorkspaceModel) {
    model.question?.let { q ->
        DeskDialog({ model.respond(false) }, title = { Text(uiMessage(q.title)) }, text = { Text(if(q.action=="覆盖" || q.action=="移动") q.detail else uiMessage(q.detail)) },
            confirmButton = { DeskButton(q.action,tint=actionTint(q.action)) { model.respond(true) } }, dismissButton = {
                Box(Modifier.initialKeyboardFocus()) { DeskButton("取消") { model.respond(false) } }
            })
    }
}

@Composable
internal fun UnconfirmedTasks(model: WorkspaceModel) {
    if (model.reminders.checks.isEmpty() && model.reminders.retry == null) return
    Column(Modifier.fillMaxWidth().deskPanel().padding(10.dp)) {
        if (model.reminders.checks.isNotEmpty()) {
            Text(uiMessage("结果待核实 · ${model.target}"), fontSize = 13.sp)
            model.reminders.checks.forEach { Text(uiMessage(it), fontSize = 12.sp) }
        }
        model.reminders.retry?.let { Text(uiMessage("传输已中断，可重试 · $it"), fontSize = 13.sp) }
        if (model.session != null) DeskButton("清除提示") { model.acknowledgeChecks() }
    }
}

@Composable
internal fun OfflineDevicePrompt(message:String,openDevices:()->Unit) {
    Column(Modifier.fillMaxWidth().deskPanel().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(tr(message),style=MaterialTheme.typography.body1,color=DeskMuted)
        PrimaryButton("前往设备",click=openDevices)
    }
}

@Composable
internal fun Workspaces(page: String, model: WorkspaceModel, openDevices: () -> Unit) = key(model) {
    val offlineHint = when {
        model.session != null -> null
        page == "应用" && model.appList.isEmpty() && model.appUser == null -> "连接设备后可查看和安装应用"
        page == "文件" && !model.filesLoaded -> "连接设备后可浏览和传输文件"
        else -> null
    }
    var hovering by remember(page) { mutableStateOf(false) }
    var showStatus by remember(page) { mutableStateOf(false) }
    val canDrop=page=="应用" && model.session!=null && !model.busy
    LaunchedEffect(canDrop) { if(!canDrop) hovering=false }
    val drop=if(canDrop) Modifier.apkDropTarget(model) { hovering=it } else Modifier
    Box(Modifier.fillMaxSize().then(drop)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val status=when {
                model.statusPage==page || model.statusPage.isEmpty() -> model.status
                else -> ""
            }.ifBlank {
                if(page=="应用" && model.appUser!=null) "Android 用户 ${model.appUser} · ${model.appList.size} 个应用" else ""
            }
            Box(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().heightIn(min=if(CompactDesk.current) 48.dp else 36.dp),
                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    DeskTitle(page)
                    if (model.session == null && offlineHint == null) PrimaryButton("前往设备",click=openDevices)
                    if(status.isNotEmpty()) Text(uiMessage(status),Modifier.weight(1f).clickable { showStatus=true },
                        maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=13.sp,color=if(model.problem && model.statusPage==page) DeskError else DeskMuted)
                    if(model.busy && model.statusPage==page) DeskButton("取消") { model.cancelTask() }
                }
                if(page=="应用" && model.installingApk) {
                    val bar=Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp)
                    val progress=model.installProgress
                    if(progress==null) LinearProgressIndicator(modifier=bar) else LinearProgressIndicator(progress,bar)
                }
            }
            if(showStatus) DeskDialog({ showStatus=false },title={ Text(tr(page)) },text={ SelectionContainer { Text(uiMessage(status)) } },
                confirmButton={ DeskButton("关闭") { showStatus=false } },dismissButton={})
            UnconfirmedTasks(model)
            if (model.recoveryNote.isNotBlank()) Text(uiMessage(model.recoveryNote), fontSize = 12.sp)
            if (offlineHint != null) {
                OfflineDevicePrompt(offlineHint,openDevices)
                return@Column
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).deskPanel().padding(12.dp)) { when (page) {
                "应用" -> AppWorkspace(model, if (maxWidth >= 560.dp * LocalDensity.current.fontScale + 8.dp) 2 else 1)
                "文件" -> FileWorkspace(model)
                "Shell" -> ShellWorkspace(model)
                "日志" -> LogWorkspace(model)
            } }
        }
        if(canDrop && hovering) {
            Box(Modifier.fillMaxSize().deskReveal().background(DeskSelection.copy(alpha=.90f),DeskShape)
                .border(2.dp,DeskAccent,DeskShape),contentAlignment=Alignment.Center) {
                Text(tr("松开以安装 APK"),color=DeskAccent,style=MaterialTheme.typography.h6)
            }
        }
    }
}

@Composable
private fun AppWorkspace(m: WorkspaceModel, columns: Int) {
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("全部") }
    var frozen by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf<String?>(null) }
    var attempted by remember { mutableStateOf(false) }
    val ready = m.session != null && !m.busy
    LaunchedEffect(ready) { if (ready && !attempted && m.appUser == null) { attempted = true; m.refreshApps(onlyIfEmpty=true) } }
    val apps = remember(m.appList, query, frozen, group) { m.appList.filter {
        (group=="全部" || it.system==(group=="系统应用")) && (!frozen || it.frozen) &&
            (it.name.contains(query,true) || it.label.contains(query,true))
    } }
    DeskLazyGrid(columns=GridCells.Fixed(columns),modifier=Modifier.fillMaxSize(), horizontalArrangement=Arrangement.spacedBy(8.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item(span={ GridItemSpan(maxLineSpan) }) {
            Tools {
                PrimaryButton("安装 APK", ready) { m.installApk() }
                DeskButton("从链接安装", ready) { link = true }
                DeskButton("刷新", ready) { m.refreshApps() }
            }
        }
        item(span={ GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Tools { listOf("全部","系统应用","用户应用").forEach { name ->
                    DeskButton(name,tint=DeskSelection,selected=group==name) { group=name }
                } }
                DeskInput(query, "搜索名称或包名", { query = it }, Modifier.fillMaxWidth())
                DeskButton("仅已冻结",tint=DeskSelection,selected=frozen) { frozen=!frozen }
                if (apps.isEmpty() && m.appUser != null) Text(tr("没有匹配的应用"))
            }
        }
        items(apps, key = { it.name }) { app ->
            Column(Modifier.fillMaxWidth().deskPanel().padding(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    val image=remember(app.icon) { app.icon?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() } }
                    if(image!=null) Image(image,null,Modifier.size(32.dp)) else DeskIcon("应用",DeskMuted,Modifier.size(32.dp))
                    Column {
                        if(app.label.isNotEmpty()) Text(app.label,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                        SelectionContainer { Text(app.name, fontFamily = DeskMono, fontSize = 13.sp,maxLines=2,overflow=TextOverflow.Ellipsis) }
                    }
                }
                Text(listOf(tr(if (app.system) "系统应用" else "用户应用"), uiMessage("版本 ${app.version}"),
                    tr(if (app.frozen) "已冻结" else "已启用")).joinToString("  ·  "), fontSize = 12.sp)
                Tools {
                    DeskButton("启动", ready && !app.frozen && app.launchable != false,tint=actionTint("启动")) { m.appAction(app, "启动") }
                    DeskButton("停止", ready,tint=actionTint("停止")) { m.appAction(app, "停止") }
                    DeskButton(if (more == app.name) "收起" else "更多") { more = if (more == app.name) null else app.name }
                    if (more == app.name) {
                        DeskButton(if (app.frozen) "解冻" else "冻结", ready,tint=if(app.frozen) DeskPaleGreen else DeskSelection) { m.appAction(app, if (app.frozen) "解冻" else "冻结") }
                        DeskButton("卸载", ready,tint=actionTint("卸载")) { m.appAction(app, "卸载") }
                    }
                }
            }
        }
    }
    if (link) EditValue("从链接安装", "APK 直链", "", "下载", { link = false }) { link = false; m.installApk(it) }
}

@Composable
private fun FileWorkspace(m: WorkspaceModel) {
    val ready = m.session != null && !m.busy
    var attempted by remember { mutableStateOf(false) }
    var goto by remember { mutableStateOf(false) }
    var move by remember { mutableStateOf<TargetFile?>(null) }
    LaunchedEffect(ready) { if (ready && !attempted && !m.filesLoaded) { attempted = true; m.browse(m.directory) } }
    var fileActions by remember(m.directory) { mutableStateOf<String?>(null) }
    val list = rememberLazyGridState()
    LaunchedEffect(m.directory) { list.scrollToItem(0) }
    DeskLazyGrid(columns=GridCells.Fixed(if(CompactDesk.current) 1 else 3),state=list,modifier=Modifier.fillMaxSize(),
        horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item(span={GridItemSpan(maxLineSpan)}) {
            val pathScroll = rememberScrollState()
            LaunchedEffect(m.directory, pathScroll.maxValue) { pathScroll.scrollTo(pathScroll.maxValue) }
            Row(Modifier.fillMaxWidth().background(DeskRaised,DeskShape).horizontalScroll(pathScroll).padding(8.dp),
                horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically) {
                val paths = generateSequence(m.directory) { path -> if (path == "/") null else parentPath(path) }.toList().asReversed()
                paths.forEachIndexed { index, path ->
                    if(index>0) Text(tr("›"), color=DeskMuted)
                    val current = path==m.directory
                    Text(if(path=="/") tr("根目录") else path.substringAfterLast('/'),
                        Modifier.clickable(enabled=ready && !current,role=Role.Button) { m.browse(path) }.padding(6.dp),
                        fontWeight=if(current) FontWeight.SemiBold else FontWeight.Normal,color=if(current) DeskInk else DeskAccent)
                }
            }
        }
        item(span={GridItemSpan(maxLineSpan)}) {
            Tools {
                DeskButton("上一级", ready && m.directory != "/") { m.browse(parentPath(m.directory)) }
                DeskButton("转到路径", ready) { goto = true }
                PrimaryButton("上传", ready) { m.upload() }
                DeskButton("刷新", ready) { m.browse(m.directory,refresh=true) }
            }
        }
        if (m.filesLoaded && m.fileList.isEmpty()) item(span={GridItemSpan(maxLineSpan)}) { Text(tr("空目录"),Modifier.padding(12.dp),color=DeskMuted) }
        items(m.fileList, key = { it.name }) { f ->
            val enter = { m.browse(remotePath(m.directory,f.name)); Unit }
            Column(Modifier.fillMaxWidth().deskPanel()) {
                Row(Modifier.fillMaxWidth()
                    .then(if(f.directory) Modifier.clickable(enabled=ready,role=Role.Button,onClick=enter) else Modifier)
                    .padding(8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).background(if(f.directory) DeskSelection else DeskRaised,DeskShape),
                        contentAlignment=Alignment.Center) { DeskIcon(if(f.directory) "文件" else "日志",if(f.directory) DeskAccent else DeskMuted) }
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(f.name,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(tr(when { f.link -> "符号链接"; f.directory -> "目录"; f.regular -> byteCount(f.bytes); else -> "特殊文件" }) +
                            "  ·  " + deskTime(f.modified * 1000L),fontSize=12.sp,color=DeskMuted)
                    }
                    if(f.directory) Text("›",fontSize=20.sp,color=DeskAccent)
                    else if(f.regular) DeskButton(if(fileActions==f.name) "收起" else "⋯",ready,Modifier.semantics { contentDescription=uiMessage("文件操作 ${f.name}") }) {
                        fileActions=if(fileActions==f.name) null else f.name
                    }
                }
                if(fileActions==f.name && f.regular) Box(Modifier.align(Alignment.End).padding(start=8.dp,end=8.dp,bottom=8.dp)) {
                    Tools {
                        DeskButton("下载",ready,tint=DeskSelection) { fileActions=null; m.download(f) }
                        DeskButton("移动",ready) { fileActions=null; move=f }
                    }
                }
            }
        }
    }
    if (goto) EditValue("转到设备目录", "绝对路径", m.directory, "打开", { goto = false }) { goto = false; m.browse(it) }
    move?.let { f -> EditValue("移动 ${f.name}", "目标目录", m.directory, "下一步", { move = null }) { move = null; m.move(f, it) } }
}

@Composable
private fun ShellWorkspace(m: WorkspaceModel) {
    var editing by remember { mutableStateOf(false) }
    var edited by remember { mutableStateOf<SavedCommand?>(null) }
    var deleting by remember { mutableStateOf<SavedCommand?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    val ready = m.session != null && !m.busy
    val commands=m.prefs.commands
    val grouped=m.prefs.groupCommandsByTag
    val groupOf:(SavedCommand)->String={ if(grouped) it.tag else "" }
    val sections=commands.groupBy(groupOf).toSortedMap(compareBy<String> { it.isEmpty() }.thenBy { it })
    val reorder=rememberDeskReorder(sections.values.flatten(),desktop=m.host.desktop,key={ it.id },groupOf=groupOf,dragRow=true) { ids ->
        try { m.prefs.reorderCommands(ids,if(grouped) commands.first { it.id==ids.first() }.tag else null) } catch(e:Exception) { m.commandError(e) }
    }
    DeskLazyColumn(Modifier.fillMaxSize().then(reorder.gestures),state=reorder.list,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            DeskInput(m.command, "设备 Shell 命令", { m.command = it }, Modifier.fillMaxWidth(), single = false, maxLines = 6)
        }
        item {
            Tools {
                DeskButton("运行", ready && m.command.isNotBlank(),tint=DeskAccent) { m.runShell(m.command) }
                DeskButton("收藏命令", m.command.isNotBlank()) { edited = null; editing = true }
                DeskButton("复制输出", m.shellOutput.isNotEmpty()) { m.host.copy(m.shellOutput) }
            }
        }
        if (m.shellOutput.isNotEmpty()) item {
            SelectionContainer(Modifier.fillMaxWidth().background(DeskRaised,DeskShape).padding(12.dp)) { Text(m.shellOutput, fontFamily = DeskMono, fontSize = 12.sp, lineHeight = 17.sp) }
        }
        if (commands.isNotEmpty()) item {
            FlowRow(itemVerticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(tr("收藏"),fontWeight=FontWeight.SemiBold,fontSize=16.sp)
                for(mode in listOf(false,true)) DeskButton(if(mode) "按标签" else "不分组",tint=DeskSelection,selected=mode==grouped) {
                    try { m.prefs.setGroupCommandsByTag(mode) } catch(e:Exception) { m.commandError(e) }
                }
                if(!m.host.desktop && reorder.sortable) Text(tr("长按排序"),color=DeskMuted,style=MaterialTheme.typography.body2)
            }
        }
        for((tag,members) in reorder.items.groupBy(groupOf)) {
            if(grouped) item(key="tag:$tag") { GroupHeader(tag.ifEmpty { tr("未设置标签") },members.size.toString(),DeskAccent) }
            items(members,key={it.id}) { item ->
                val open=expanded==item.id
                DeskReorderRow(reorder,item.id,item.name,Modifier.deskPanel()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val actions:@Composable ()->Unit = {
                                Tools {
                                    if(open) {
                                        DeskButton("编辑") { edited=item; editing=true }
                                        DeskButton("删除收藏",tint=DeskPaleRed) { deleting=item }
                                    }
                                    DeskButton("运行",ready,tint=DeskAccent) { m.runShell(item.command) }
                                    DeskButton(if(open) "收起" else "详情",modifier=Modifier.semantics {
                                        stateDescription=tr(if(open) "已展开" else "已收起")
                                    }) { expanded=if(open) null else item.id }
                                }
                            }
                            val required=if(open) { if(UiLanguage.english) 420.dp else 288.dp } else { if(UiLanguage.english) 210.dp else 168.dp }
                            val stacked=maxWidth<required*LocalDensity.current.fontScale
                            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                                    DeskIcon("收藏",DeskAccent)
                                    Column(Modifier.weight(1f)) {
                                        Text(item.name,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                                        if(!grouped && item.tag.isNotEmpty()) Text(item.tag,color=DeskMuted,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                                    }
                                    if(!stacked) actions()
                                }
                                if(stacked) actions()
                            }
                        }
                        val preview:@Composable ()->Unit = {
                            Text(item.command,Modifier.fillMaxWidth().background(DeskRaised,DeskShape).padding(8.dp),
                                fontFamily=DeskMono,fontSize=13.sp,maxLines=if(open) Int.MAX_VALUE else 1,overflow=TextOverflow.Ellipsis)
                        }
                        if(open) SelectionContainer(reorder.excludeFromDrag) { preview() } else preview()
                    }
                }
            }
        }
    }
    deleting?.let { item -> DeskConfirm("删除收藏",item.name,"删除收藏",{deleting=null}) {
        deleting=null
        try { m.prefs.removeCommand(item.id) } catch(e:Exception) { m.commandError(e) }
    } }
    if (editing) {
        var name by remember { mutableStateOf(edited?.name.orEmpty()) }
        var tag by remember { mutableStateOf(edited?.tag.orEmpty()) }
        var text by remember { mutableStateOf(edited?.command ?: m.command) }
        DeskDialog({ editing = false }, title = { Text(tr("收藏命令")) }, text = {
            DeskScrollColumn(Modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DeskInput(name, "名称", { name = it }, Modifier.initialKeyboardFocus())
                DeskInput(tag,"标签",{ tag=it })
                DeskInput(text, "命令", { text = it }, single = false, maxLines = 6)
            }
        }, confirmButton = { DeskButton("保存", name.isNotBlank() && text.isNotBlank(),tint=DeskAccent) {
            try { m.prefs.saveCommand(edited?.id, name, text, tag); editing = false }
            catch (e: Exception) { m.commandError(e); editing = false }
        } }, dismissButton = { DeskButton("取消") { editing = false } })
    }
}

@Composable
private fun LogWorkspace(m: WorkspaceModel) {
    var filters by remember { mutableStateOf(false) }
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(m) { m.readLogBuffers() }
    val list = rememberLazyListState()
    val dragged by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) following = false }
    val rows = remember(m.visibleLogs, m.filter) { m.visibleLogs.filter(m.filter::accepts) }
    LaunchedEffect(rows.lastOrNull(), following, m.paused == null) {
        if (following && m.paused == null && rows.isNotEmpty()) list.scrollToItem(rows.lastIndex)
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Tools {
            DeskButton(if (m.logRunning) "停止采集" else "开始采集", m.session != null,tint=DeskAccent,selected=m.logRunning) { if (m.logRunning) m.stopLogs() else m.startLogs() }
            DeskButton(if (m.paused == null) "暂停显示" else "恢复显示", m.visibleLogs.isNotEmpty(),tint=DeskSelection,selected=m.paused!=null) { m.pauseDisplay() }
            DeskButton(if (filters) "收起筛选" else "筛选",tint=DeskSelection,selected=filters || m.filter!=LogFilter()) { filters = !filters }
            DeskButton("跟随最新",rows.isNotEmpty(),tint=DeskSelection,selected=following && m.paused==null) {
                val resume=m.paused!=null || !following
                if(m.paused!=null) m.pauseDisplay()
                following=resume
            }
            DeskButton("清屏", m.logs.isNotEmpty(),tint=actionTint("清屏")) { m.clearLogs() }
            DeskButton("导出筛选结果", !m.busy && rows.isNotEmpty()) { m.exportLogs() }
        }
        if (filters) {
            var draft by remember { mutableStateOf(m.filter) }
            var selectedBuffer by remember { mutableStateOf(m.logBuffer) }
            DeskDialog({ filters = false }, title = { Text(tr("筛选日志")) }, text = {
                DeskScrollColumn(Modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (m.logBuffers.isNotEmpty()) {
                        Text(tr("采集缓冲区"), fontSize = 13.sp)
                        Tools { (listOf("") + m.logBuffers).forEach { name ->
                            val label = name.ifEmpty { "默认" }
                            DeskButton(label,!m.logRunning,tint=DeskSelection,selected=name==selectedBuffer) { selectedBuffer = name }
                        } }
                    }
                    Tools { levels.forEach { level -> DeskButton(level.toString(),tint=DeskSelection,selected=draft.level==level) { draft = draft.copy(level = level) } } }
                    DeskInput(draft.tag, "Tag", { draft = draft.copy(tag = it) }, Modifier.initialKeyboardFocus())
                    DeskInput(draft.pid, "PID", { draft = draft.copy(pid = it.filter(Char::isDigit)) })
                    DeskInput(draft.text, "关键词", { draft = draft.copy(text = it) })
                    DeskButton("重置") { draft = LogFilter() }
                }
            }, confirmButton = { PrimaryButton("应用筛选") {
                m.filter = draft
                if (!m.logRunning) m.logBuffer = selectedBuffer
                filters = false
            } }, dismissButton = { DeskButton("取消") { filters = false } })
        }
        Text(tr(if(m.logRunning) "采集中" else "已停止")+"  ·  "+uiMessage("${rows.size} 行") + if(m.dropped>0) "  ·  "+uiMessage("已淘汰 ${m.dropped} 行") else "",
            fontFamily = DeskMono, fontSize = 12.sp)
        if (rows.isEmpty() && m.logs.isNotEmpty()) Text(tr("没有匹配的日志"), fontSize = 13.sp)
        val userScroll=Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while(true) {
                    val event=awaitPointerEvent(PointerEventPass.Initial)
                    if(event.type==PointerEventType.Scroll && event.changes.any { it.scrollDelta.y!=0f }) following=false
                }
            }
        }.onPreviewKeyEvent {
            if(it.type==KeyEventType.KeyDown && it.key in listOf(Key.DirectionUp,Key.DirectionDown,Key.PageUp,Key.PageDown,Key.MoveHome,Key.MoveEnd)) following=false
            false
        }
        DeskLazyColumn(Modifier.fillMaxSize().then(userScroll).background(DeskRaised,DeskShape), state = list, onBarScroll={ following=false }, contentPadding=PaddingValues(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            items(rows) { row ->
                val stripe = when (row.level) { 'E', 'F' -> DeskError; 'I' -> DeskAccent; 'W' -> DeskWarning; 'D' -> DeskBlue; else -> DeskMuted }
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(androidx.compose.ui.graphics.lerp(DeskSurface,stripe,.16f),DeskShape),verticalAlignment = Alignment.Top) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(stripe))
                    Spacer(Modifier.width(8.dp))
                    val match = m.filter.text
                    val display = remember(row.text, match) { buildAnnotatedString {
                        var pos = 0
                        if (match.isNotBlank()) while (pos < row.text.length) {
                            val start = row.text.indexOf(match, pos, true)
                            if (start < 0) break
                            append(row.text.substring(pos, start))
                            withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(row.text.substring(start, start + match.length)) }
                            pos = start + match.length
                        }
                        append(row.text.substring(pos))
                    } }
                    SelectionContainer { Text(display, Modifier.padding(top=4.dp,bottom=4.dp,end=6.dp),fontFamily = DeskMono, fontSize = 12.sp, lineHeight = 17.sp) }
                }
            }
        }
    }
}

