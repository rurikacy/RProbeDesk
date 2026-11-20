@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.rprobedesk

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun Tools(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable
private fun RecordLine(details:@Composable ()->Unit,actions:@Composable FlowRowScope.()->Unit) {
    Column {
        Divider(color=DeskBorder)
        if(CompactDesk.current) Column(Modifier.padding(vertical=6.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            details()
            Tools(actions)
        } else Row(Modifier.fillMaxWidth().padding(vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),
            verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { details() }
            Tools(actions)
        }
    }
}

@Composable
private fun EditValue(title: String, label: String, initial: String, action: String, cancel: () -> Unit, submit: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(cancel, title = { Text(title) },
        text = { DeskInput(value, label, { value = it }, Modifier.fillMaxWidth().initialKeyboardFocus()) },
        confirmButton = { DeskButton(action, value.isNotBlank()) { submit(value) } },
        dismissButton = { DeskButton("取消", click = cancel) })
}

@Composable
internal fun WorkspacePrompts(model: WorkspaceModel) {
    model.question?.let { q ->
        AlertDialog({ model.respond(false) }, title = { Text(q.title) }, text = { Text(q.detail) },
            confirmButton = { DeskButton(q.action) { model.respond(true) } }, dismissButton = {
                Box(Modifier.initialKeyboardFocus()) { DeskButton("取消") { model.respond(false) } }
            })
    }
}

@Composable
internal fun UnconfirmedTasks(model: WorkspaceModel) {
    if (model.reminders.checks.isEmpty() && model.reminders.retry == null) return
    Column(Modifier.fillMaxWidth().deskPanel().padding(10.dp)) {
        if (model.reminders.checks.isNotEmpty()) {
            Text("结果待核实 · ${model.target}", fontSize = 13.sp)
            model.reminders.checks.forEach { Text(it, fontSize = 12.sp) }
        }
        model.reminders.retry?.let { Text("传输已中断，可重试 · $it", fontSize = 13.sp) }
        if (model.session != null) DeskButton("清除提示") { model.acknowledgeChecks() }
    }
}

@Composable
internal fun Workspaces(page: String, model: WorkspaceModel) = key(model) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DeskTitle(page)
        UnconfirmedTasks(model)
        if (model.recoveryNote.isNotBlank()) Text(model.recoveryNote, fontSize = 12.sp)
        if (model.busy || model.status.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (model.problem) Box(Modifier.size(3.dp, 22.dp).background(DeskError))
                Text(model.status, Modifier.weight(1f), fontSize = 13.sp)
                if (model.busy) DeskButton("取消") { model.cancelTask() }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).deskPanel().padding(12.dp)) { when (page) {
            "应用" -> AppWorkspace(model)
            "文件" -> FileWorkspace(model)
            "Shell" -> ShellWorkspace(model)
            "日志" -> LogWorkspace(model)
        } }
    }
}

@Composable
private fun AppWorkspace(m: WorkspaceModel) {
    var query by remember { mutableStateOf("") }
    var frozen by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf<String?>(null) }
    var attempted by remember { mutableStateOf(false) }
    val ready = m.session != null && !m.busy
    LaunchedEffect(ready) { if (ready && !attempted && m.appUser == null) { attempted = true; m.refreshApps() } }
    val apps = remember(m.appList, query, frozen) { m.appList.filter { (!frozen || it.frozen) && it.name.contains(query, true) } }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Tools {
                PrimaryButton("安装 APK", ready) { m.installApk() }
                DeskButton("从链接安装", ready) { link = true }
                DeskButton("刷新", ready) { m.refreshApps() }
            }
            DeskInput(query, "搜索包名", { query = it }, Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(frozen, { frozen = it })
                Text("仅已冻结", fontSize = 13.sp)
            }
            if (apps.isEmpty() && m.appUser != null) Text("没有匹配的应用")
        }
        items(apps, key = { it.name }) { app ->
            RecordLine(details={
                SelectionContainer { Text(app.name, fontFamily = DeskMono, fontSize = 14.sp) }
                Text(listOf(if (app.system) "系统应用" else "用户应用", "版本 ${app.version}",
                    if (app.frozen) "已冻结" else "已启用").joinToString("  ·  "), fontSize = 12.sp)
            }) {
                    DeskButton("启动", ready && !app.frozen && app.launchable != false) { m.appAction(app, "启动") }
                    DeskButton(if (app.frozen) "解冻" else "冻结", ready) { m.appAction(app, if (app.frozen) "解冻" else "冻结") }
                    DeskButton(if (more == app.name) "收起" else "更多") { more = if (more == app.name) null else app.name }
                    if (more == app.name) {
                        DeskButton("停止", ready) { m.appAction(app, "停止") }
                        DeskButton("卸载", ready) { m.appAction(app, "卸载") }
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
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SelectionContainer { Text(m.directory, fontFamily = DeskMono, fontSize = 14.sp) }
            Tools {
                DeskButton("上一级", ready && m.directory != "/") { m.browse(parentPath(m.directory)) }
                DeskButton("转到路径", ready) { goto = true }
                PrimaryButton("上传", ready) { m.upload() }
                DeskButton("刷新", ready) { m.browse(m.directory) }
            }
            if (m.filesLoaded && m.fileList.isEmpty()) Text("空目录")
        }
        items(m.fileList, key = { it.name }) { f ->
            RecordLine(details={
                SelectionContainer { Text(f.name, fontSize = 15.sp) }
                Text(when { f.link -> "符号链接"; f.directory -> "目录"; f.regular -> byteCount(f.bytes); else -> "特殊文件" } +
                    "  ·  " + deskTime(f.modified * 1000L), fontFamily = DeskMono, fontSize = 12.sp)
            }) {
                    if (f.directory) DeskButton("打开", ready) { m.browse(remotePath(m.directory, f.name)) }
                    else DeskButton("下载", ready && f.regular) { m.download(f) }
                    if (f.regular) DeskButton("移动", ready) { move = f }
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
    var expanded by remember { mutableStateOf<String?>(null) }
    val ready = m.session != null && !m.busy
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            DeskInput(m.command, "设备 Shell 命令", { m.command = it }, Modifier.fillMaxWidth(), single = false, maxLines = 6)
            Tools {
                DeskButton("运行", ready && m.command.isNotBlank()) { m.runShell(m.command) }
                DeskButton("收藏命令", m.command.isNotBlank()) { edited = null; editing = true }
                DeskButton("复制输出", m.shellOutput.isNotEmpty()) { m.host.copy(m.shellOutput) }
            }
        }
        if (m.shellOutput.isNotEmpty()) item {
            SelectionContainer { Text(m.shellOutput, fontFamily = DeskMono, fontSize = 12.sp, lineHeight = 17.sp) }
        }
        if (m.prefs.commands.isNotEmpty()) item { Text("收藏",fontWeight=FontWeight.SemiBold,fontSize=16.sp) }
        items(m.prefs.commands, key = { it.id }) { item ->
            Column {
                Divider()
                Text(item.name)
                Tools {
                    DeskButton("运行", ready) { m.runShell(item.command) }
                    DeskButton(if (expanded == item.id) "收起" else "详情") { expanded = if (expanded == item.id) null else item.id }
                }
                if (expanded == item.id) {
                    SelectionContainer { Text(item.command, fontFamily = DeskMono, fontSize = 13.sp) }
                    Tools {
                        DeskButton("编辑") { edited = item; editing = true }
                        DeskButton("删除收藏") {
                            try { m.prefs.removeCommand(item.id) } catch (e: Exception) { m.problem = true; m.status = e.localizedMessage.orEmpty() }
                        }
                    }
                }
            }
        }
    }
    if (editing) {
        var name by remember { mutableStateOf(edited?.name.orEmpty()) }
        var text by remember { mutableStateOf(edited?.command ?: m.command) }
        AlertDialog({ editing = false }, title = { Text("收藏命令") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DeskInput(name, "名称", { name = it }, Modifier.initialKeyboardFocus())
                DeskInput(text, "命令", { text = it }, single = false, maxLines = 6)
            }
        }, confirmButton = { DeskButton("保存", name.isNotBlank() && text.isNotBlank()) {
            try { m.prefs.saveCommand(edited?.id, name, text); editing = false }
            catch (e: Exception) { m.problem = true; m.status = e.localizedMessage.orEmpty(); editing = false }
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
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Tools {
            DeskButton(if (m.logRunning) "停止采集" else "开始采集", m.session != null) { if (m.logRunning) m.stopLogs() else m.startLogs() }
            DeskButton(if (m.paused == null) "暂停显示" else "恢复显示", m.visibleLogs.isNotEmpty()) { m.pauseDisplay() }
            DeskButton(if (filters) "收起筛选" else "筛选") { filters = !filters }
            DeskButton("跟随最新", rows.isNotEmpty()) { if (m.paused != null) m.pauseDisplay(); following = true }
            DeskButton("清屏", m.logs.isNotEmpty()) { m.clearLogs() }
            DeskButton("导出筛选结果", !m.busy && rows.isNotEmpty()) { m.exportLogs() }
        }
        if (filters) {
            var draft by remember { mutableStateOf(m.filter) }
            var selectedBuffer by remember { mutableStateOf(m.logBuffer) }
            AlertDialog({ filters = false }, title = { Text("筛选日志") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (m.logBuffers.isNotEmpty()) {
                        Text("采集缓冲区", fontSize = 13.sp)
                        Tools { (listOf("") + m.logBuffers).forEach { name ->
                            val label = name.ifEmpty { "默认" }
                            DeskButton(if (name == selectedBuffer) "✓ $label" else label, !m.logRunning) { selectedBuffer = name }
                        } }
                    }
                    Tools { levels.forEach { level -> DeskButton(if (draft.level == level) "✓ $level" else level.toString()) { draft = draft.copy(level = level) } } }
                    DeskInput(draft.tag, "Tag", { draft = draft.copy(tag = it) }, Modifier.initialKeyboardFocus())
                    DeskInput(draft.pid, "PID", { draft = draft.copy(pid = it.filter(Char::isDigit)) })
                    DeskInput(draft.text, "关键词", { draft = draft.copy(text = it) })
                    DeskButton("重置") { draft = LogFilter() }
                }
            }, confirmButton = { DeskButton("应用筛选") {
                m.filter = draft
                if (!m.logRunning) m.logBuffer = selectedBuffer
                filters = false
            } }, dismissButton = { DeskButton("取消") { filters = false } })
        }
        Text("${if (m.logRunning) "采集中" else "已停止"}  ·  ${rows.size} 行" + if (m.dropped > 0) "  ·  已淘汰 ${m.dropped} 行" else "",
            fontFamily = DeskMono, fontSize = 12.sp)
        if (rows.isEmpty() && m.logs.isNotEmpty()) Text("没有匹配的日志", fontSize = 13.sp)
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            items(rows) { row ->
                val stripe = when (row.level) { 'E', 'F' -> DeskError; 'I' -> DeskAccent; 'W' -> Color(0xff95610d); else -> Color.Transparent }
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 3.dp).size(2.dp, 12.dp).background(stripe))
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
                    SelectionContainer { Text(display, fontFamily = DeskMono, fontSize = 12.sp, lineHeight = 17.sp) }
                }
            }
        }
    }
}

