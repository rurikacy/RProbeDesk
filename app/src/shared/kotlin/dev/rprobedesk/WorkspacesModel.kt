package dev.rprobedesk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

internal data class WorkQuestion(val title: String, val detail: String, val action: String)
internal data class SessionReminders(val checks: List<String> = emptyList(), val retry: String? = null, val logEnd: Long? = null)

internal class WorkspaceModel(
    val host: HostBridge, val session: DeviceSession?, val prefs: DeskPreferences, val target: String = session?.serial.orEmpty()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val profile = prefs.selectedId ?: session?.serial.orEmpty()
    var recoveryNote by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
    var problem by mutableStateOf(false)
    var question by mutableStateOf<WorkQuestion?>(null)
        private set
    private var answer: CompletableDeferred<Boolean>? = null
    private var task: Job? = null
    private var submitted: String? = null
    private var transferBeforeCommit: String? = null
    var reminders by mutableStateOf(SessionReminders())
        private set

    var appList by mutableStateOf(emptyList<TargetApp>())
        private set
    var appUser by mutableStateOf<Int?>(null)
        private set
    var directory by mutableStateOf("/sdcard")
        private set
    var fileList by mutableStateOf(emptyList<TargetFile>())
        private set
    var filesLoaded by mutableStateOf(false)
        private set
    var command by mutableStateOf("")
    var shellOutput by mutableStateOf("")
        private set
    var filter by mutableStateOf(LogFilter())
    var logs by mutableStateOf(emptyList<DeviceLog>())
        private set
    var dropped by mutableStateOf(0L)
        private set
    var logRunning by mutableStateOf(false)
        private set
    var logBuffers by mutableStateOf(emptyList<String>())
        private set
    var logBuffer by mutableStateOf("")
    var paused by mutableStateOf<List<DeviceLog>?>(null)
        private set
    val visibleLogs get() = paused ?: logs
    private var logJob: Job? = null
    private val buffer = ArrayDeque<DeviceLog>()
    private var logToken = 0L
    private var dirty = false
    private var lost = 0L

    init {
        scope.launch { host.foreground.collect { if (!it && !host.desktop && logRunning) stopLogs("主控进入后台，采集中断") } }
        scope.launch(Dispatchers.IO) {
            try {
                recoverLocalStages(host.cacheDir)
                if (session != null) {
                    val pending = recoverUploads(host.settingsDir, profile, session)
                    if (pending.isNotEmpty()) recoveryNote = "待核实的上传暂存目录 · " + pending.joinToString()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { recoveryNote = e.localizedMessage ?: "暂存文件清理待重试" }
        }
    }

    fun close() { logToken++; task?.cancel(); logJob?.cancel(); scope.cancel() }
    fun restoreReminders(saved: SessionReminders) { reminders = saved }
    fun acknowledgeChecks() { reminders = reminders.copy(checks = emptyList(), retry = null) }

    private fun rememberSubmitted() {
        submitted?.let { description ->
            reminders = reminders.copy(checks = (reminders.checks + description).distinct())
        }
    }

    private fun rememberInterruption() {
        if (submitted != null) rememberSubmitted()
        else transferBeforeCommit?.let { reminders = reminders.copy(retry = it) }
    }

    private suspend fun transportResponds(): Boolean {
        val device = session ?: return false
        return try {
            withTimeoutOrNull(2000) { device.shell("echo RProbeDesk").stdout.trim() == "RProbeDesk" } == true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
    }

    fun disconnectedView(): WorkspaceModel {
        rememberInterruption()
        if (logRunning) stopLogs("设备连接已结束 · $target")
        if (logs.isNotEmpty()) reminders = reminders.copy(logEnd = System.currentTimeMillis())
        close()
        return WorkspaceModel(host, null, prefs, target).also { offline ->
            offline.restoreReminders(reminders)
            offline.logs = logs
            offline.buffer.addAll(logs)
            offline.lost = dropped
            offline.dropped = dropped
            offline.paused = paused
            offline.filter = filter
            offline.shellOutput = shellOutput
            offline.status = "已断开 · $target"
        }
    }
    fun cancelTask() { task?.cancel() }
    fun respond(accept: Boolean) { answer?.complete(accept) }

    private suspend fun ask(title: String, detail: String, action: String) = withContext(Dispatchers.Main.immediate) {
        val result = CompletableDeferred<Boolean>()
        answer = result
        question = WorkQuestion(title, detail, action)
        try { if (!result.await()) throw CancellationException("已取消") }
        finally { answer = null; question = null }
    }

    private fun job(label: String, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        problem = false
        status = label
        submitted = null
        task = scope.launch {
            try { action() }
            catch (e: CancellationException) {
                rememberSubmitted()
                status = if (submitted != null) "执行已中断，结果待核实 · $target" else "已取消"
            }
            catch (e: Exception) {
                problem = true
                val message = e.localizedMessage?.take(700) ?: e.javaClass.simpleName
                if ((submitted != null || transferBeforeCommit != null) && !transportResponds()) rememberInterruption()
                status = if (submitted != null) "请核实目标状态 · $message" else message
            } finally { submitted = null; transferBeforeCommit = null; busy = false; task = null }
        }
    }

    private suspend fun queryApps() {
        val device = requireNotNull(session)
        val user = device.currentUser()
        val list = device.apps(user)
        appUser = user
        appList = list
    }

    fun refreshApps() = job("读取应用…") { queryApps(); status = "Android 用户 $appUser · ${appList.size} 个应用" }

    private suspend fun userForChange(): Int {
        val current = requireNotNull(session).currentUser()
        if (appUser != null && appUser != current) {
            queryApps()
            error("Android 用户已切换，已刷新列表，请重新选择")
        }
        return current
    }

    fun appAction(app: TargetApp, action: String) = job("$action · ${app.name}") {
        val device = requireNotNull(session)
        val user = userForChange()
        val pkg = remoteArg(app.name)
        val detail = "$target · Android 用户 $user\n${app.name}"
        if (action in listOf("冻结", "卸载")) {
            ask("$action ${app.name}", detail + if (action == "卸载") "\n将移除此用户的应用及数据。" else "\n冻结后无法启动，应用数据保留。", action)
        }
        check(device.currentUser() == user) { "Android 用户已切换，请刷新" }
        val cmd = when (action) {
            "启动" -> {
                val out = device.checked("cmd package resolve-activity --brief --user $user -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg")
                val activity = out.lineSequence().map { it.trim() }.lastOrNull { it.startsWith(app.name + "/") }
                    ?: error("应用没有可启动的入口")
                "am start --user $user -n ${remoteArg(activity)}"
            }
            "停止" -> "am force-stop --user $user $pkg"
            "冻结" -> "pm disable-user --user $user $pkg"
            "解冻" -> "pm enable --user $user $pkg"
            "卸载" -> "pm uninstall --user $user $pkg"
            else -> error("未知操作")
        }
        submitted = "$action · ${app.name} · Android 用户 $user"
        val reply = device.checked(cmd)
        check(!reply.contains("Error:", true) && !reply.contains("Failure [", true)) { reply }
        queryApps()
        val actual = appList.firstOrNull { it.name == app.name }
        when (action) {
            "冻结" -> check(actual?.frozen == true) { "目标没有返回已冻结状态" }
            "解冻" -> check(actual?.frozen == false) { "目标仍未启用该应用" }
            "卸载" -> check(actual == null) { "目标仍列出该应用" }
        }
        status = (if (action == "停止") "已强制停止" else "已$action") + " · ${app.name}"
    }

    private fun progress(label: String, size: Long?): (Long) -> Unit {
        var last = 0L
        return { n ->
            val now = System.nanoTime()
            if (now - last > 150_000_000 || n == size) {
                last = now
                status = "$label · ${byteCount(n)}" + (size?.let { " / ${byteCount(it)}" } ?: "")
            }
        }
    }

    private suspend fun staged(suffix: String, action: suspend (File) -> Unit) {
        var ticket: WorkTicket? = null
        try {
            withContext(Dispatchers.IO) {
                ticket = WorkTicket.create(host.cacheDir, "local", mapOf("suffix" to suffix.removePrefix(".")))
            }
            action(requireNotNull(ticket).localFile)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                ticket?.let { it.release(!it.localFile.exists() || it.localFile.delete()) }
            }
        }
    }

    fun installApk(url: String? = null) = job("准备 APK…") {
        val device = requireNotNull(session)
        val doc = if (url == null) host.pickOpen(".apk") ?: throw CancellationException() else null
        staged(".apk") { file ->
            val source = withContext(Dispatchers.IO) {
                if (doc != null) {
                    doc.openInput().use { stageStream(it, file, doc.size, progress("读取 APK", doc.size)) }
                    doc.name
                } else {
                    var show: ((Long) -> Unit)? = null
                    downloadApk(requireNotNull(url), file, { address ->
                        ask("允许明文 HTTP 下载？", "来源 · $address\n本次传输未经加密。", "继续下载")
                    }) { n, total ->
                        val update = show ?: progress("下载中", total).also { show = it }
                        update(n)
                    }
                }
            }
            status = "检查 APK…"
            val apk = withContext(Dispatchers.IO) { readApk(file) }
            val user = userForChange()
            val installed = device.apps(user).find { it.name == apk.packageName }
            val label = if (installed == null) "安装" else "更新"
            val detail = "$target · Android 用户 $user\n${apk.packageName}\n版本 ${apk.versionName.orEmpty()} (${apk.versionCode})\n$source · ${byteCount(file.length())}" +
                (installed?.let { "\n当前版本 ${it.version}，更新将替换设备上的包代码。" } ?: "")
            ask("$label APK", detail, label)
            check(device.currentUser() == user) { "Android 用户已切换，请重新选择 APK" }
            status = "安装中 · ${apk.packageName}"
            submitted = "$label · ${apk.packageName} (${apk.versionCode}) · Android 用户 $user"
            val output = device.install(file, user)
            check(!output.contains("Failure", true) && !output.contains("Error:", true)) { output }
            queryApps()
            check(appList.any { it.name == apk.packageName && it.version == apk.versionCode.toString() }) { "未能核实安装版本，请刷新" }
            status = "已安装 · ${apk.packageName} (${apk.versionCode})"
        }
    }

    private suspend fun openPath(path: String) {
        val entries = requireNotNull(session).files(path)
        directory = path
        fileList = entries
        filesLoaded = true
    }
    fun browse(path: String) = job("读取目录…") {
        require(path.startsWith('/') && '\u0000' !in path) { "请输入设备绝对路径" }
        openPath(path.trimEnd('/').ifBlank { "/" })
        status = "${fileList.size} 项"
    }

    fun upload() = job("选择文件…") {
        val device = requireNotNull(session)
        val doc = host.pickOpen() ?: throw CancellationException()
        val path = remotePath(directory, doc.name)
        transferBeforeCommit = "上传 · $path"
        val existing = device.fileStamp(path)
        if (existing.isNotEmpty()) {
            require(regularStamp(existing)) { "目标已有同名目录、链接或特殊文件，请选择其他位置" }
            ask("覆盖同名文件？", "$target\n$path", "覆盖")
        }
        val nonce = UUID.randomUUID().toString()
        val ticket = WorkTicket.create(host.settingsDir, "upload", mapOf("profile" to profile, "directory" to directory, "marker" to nonce))
        val folder = remoteArg(remoteStage(ticket))
        val temp = remotePath(remoteStage(ticket), "payload")
        val tmp = remoteArg(temp)
        try {
            device.checked("umask 077; mkdir -- $folder && cd $folder && printf '%s' ${remoteArg(nonce)} > owner")
            withContext(Dispatchers.IO) { doc.openInput().use { device.push(temp, it, doc.size, progress("上传", doc.size)) } }
            val remoteSize = device.checked("stat -c %s -- $tmp").trim().toLong()
            check(doc.size == null || doc.size == remoteSize) { "上传字节数不一致" }
            submitted = "上传 · $path"
            device.publishFile(temp, path, existing)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                val cleaned = withTimeoutOrNull(3500) { runCatching { device.cleanUpload(ticket, sameSession = true) }.isSuccess } == true
                ticket.release(cleaned)
                if (!cleaned) recoveryNote = "暂存目录待核实，重连原设备后重试 · ${remoteStage(ticket)}"
            }
        }
        openPath(directory)
        status = "已上传 · $path"
    }

    fun download(file: TargetFile) = job("选择保存位置…") {
        val device = requireNotNull(session)
        val path = remotePath(directory, file.name)
        require(file.regular) { "请选择普通文件" }
        val dest = host.pickSave(file.name) ?: throw CancellationException()
        transferBeforeCommit = "下载 · $path"
        staged(".part") { temp ->
            val before = device.fileStamp(path)
            require(regularStamp(before)) { "源文件已变动，请刷新后重试" }
            withContext(Dispatchers.IO) { temp.outputStream().use { device.pull(path, it, progress("下载", file.bytes)) } }
            check(temp.length() == file.bytes && before == device.fileStamp(path)) { "设备文件已变化，请刷新后重试" }
            transferBeforeCommit = null
            dest.writeFrom(temp)
        }
        status = "已保存 · ${dest.name}"
    }

    fun move(file: TargetFile, newDirectory: String) = job("移动…") {
        val device = requireNotNull(session)
        val source = remotePath(directory, file.name)
        val destination = remotePath(newDirectory, file.name)
        require(file.regular) { "请选择普通文件" }
        require(source != destination) { "文件已在该目录" }
        val original = device.fileStamp(source)
        require(regularStamp(original)) { "源文件已变化，请刷新后重试" }
        val existing = device.fileStamp(destination)
        if (existing.isEmpty()) {
            ask("移动文件", "$target\n$source\n→ $destination", "移动")
        } else {
            require(regularStamp(existing)) { "目标已有同名目录、链接或特殊文件，请选择其他位置" }
            require(existing != original) { "源和目标指向同一文件" }
            ask("移动并覆盖同名文件？", "$target\n$source\n→ $destination", "覆盖")
        }
        submitted = "移动 · $source → $destination"
        device.publishFile(source, destination, existing, original)
        openPath(directory)
        status = "已移动 · $destination"
    }

    fun runShell(text: String) = job("运行中 · $target") {
        require(text.isNotBlank())
        val device = requireNotNull(session)
        val output = StringBuilder()
        var trimmed = false
        var current = true
        fun append(value: String, err: Boolean) = synchronized(output) {
            if (current) {
                if (err) output.append("[stderr] ")
                output.append(value.takeLast(65536)).append('\n')
                if (output.length > 131072) { output.delete(0, output.length - 65536); trimmed = true }
            }
        }
        fun snapshot() = synchronized(output) { (if (trimmed) "〔前段输出已截断〕\n" else "") + output }
        command = text
        shellOutput = ""
        submitted = "Shell · ${deskTime(System.currentTimeMillis())}"
        coroutineScope {
            val display = launch { while (isActive) { delay(125); shellOutput = snapshot() } }
            try {
                val reply = device.shell(text) { line, err -> append(line, err) }
                if (synchronized(output) { output.isEmpty() }) {
                    if (reply.stdout.isNotEmpty()) append(reply.stdout, false)
                    if (reply.stderr.isNotEmpty()) append(reply.stderr, true)
                }
                status = reply.exitCode?.let { "退出码 $it · $target" } ?: "合并输出 · 退出码未知 · $target"
                problem = reply.exitCode?.let { it != 0 } ?: false
            } finally {
                display.cancel()
                synchronized(output) { current = false }
                shellOutput = snapshot()
            }
        }
    }

    private fun addLog(token: Long, text: String) = synchronized(buffer) {
        if (token == logToken) {
            while (buffer.size >= 1500) { buffer.removeFirst(); lost++ }
            val line = if (text.length > 4096) text.take(4096) + "〔行已截断〕" else text
            buffer.addLast(parseLog(line, buffer.peekLast()))
            dirty = true
        }
    }
    private fun flush() = synchronized(buffer) {
        if (dirty) { logs = buffer.toList(); dropped = lost; dirty = false }
    }
    suspend fun readLogBuffers() {
        val device = session ?: return
        if (logBuffers.isNotEmpty()) return
        try {
            val reply = device.shell("logcat -b all -g")
            val reported = reply.stdout.lineSequence().map { it.substringBefore(':').trim() }.toSet()
            logBuffers = listOf("main", "system", "crash").filter { it in reported }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { logBuffers = emptyList() }
    }
    fun startLogs() {
        val device = session ?: return
        if (logRunning || !host.foreground.value) return
        logRunning = true
        val token = synchronized(buffer) { ++logToken }
        reminders.logEnd?.let { ended ->
            addLog(token, "〔${deskTime(ended)} 至 ${deskTime(System.currentTimeMillis())} · 设备离线期间未采集 · $target〕")
            reminders = reminders.copy(logEnd = null)
        }
        addLog(token, "〔${deskTime(System.currentTimeMillis())} · 开始采集 · $target〕")
        flush()
        logJob = scope.launch {
            coroutineScope {
                val repaint = launch { while (isActive) { delay(160); flush() } }
                val selected = logBuffer.takeIf { it in logBuffers }?.let { " -b $it" }.orEmpty()
                try {
                    // Start from the target clock so resuming does not replay the ring buffer.
                    val reply = device.shell("logcat -v threadtime$selected -T \"\$(date +%s.%N)\"") { line, _ -> addLog(token, line) }
                    if (reply.exitCode != null && reply.exitCode != 0) {
                        val reason = reply.stderr.trim().ifBlank { reply.stdout.trim() }.takeLast(400)
                        addLog(token, "〔日志读取失败 · 退出码 ${reply.exitCode} · $reason〕")
                    }
                }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { addLog(token, "〔${e.localizedMessage ?: "读取失败"}〕") }
                finally {
                    repaint.cancel()
                    if (token == logToken) {
                        addLog(token, "〔${deskTime(System.currentTimeMillis())} · 采集流已结束〕")
                        flush()
                        logRunning = false
                    }
                }
            }
        }
    }
    fun stopLogs(reason: String = "已停止采集") {
        if (!logRunning) return
        val token = synchronized(buffer) { ++logToken }
        logJob?.cancel()
        addLog(token, "〔${deskTime(System.currentTimeMillis())} · $reason〕")
        flush()
        logRunning = false
    }
    fun pauseDisplay() { paused = if (paused == null) logs else null }
    fun clearLogs() {
        synchronized(buffer) { buffer.clear(); lost = 0; dirty = true }
        paused = null
        flush()
    }
    fun exportLogs() {
        val rows = visibleLogs.filter(filter::accepts)
        job("选择保存位置…") {
            val dest = host.pickSave("logcat.txt") ?: throw CancellationException()
            staged(".txt") { file ->
                withContext(Dispatchers.IO) { file.bufferedWriter().use { out -> rows.forEach { out.write(it.text); out.newLine() } } }
                dest.writeFrom(file)
            }
            status = "已导出 ${rows.size} 行 · ${dest.name}"
        }
    }
}

