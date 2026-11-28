package dev.rprobedesk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.collect

internal data class WorkQuestion(val title: String, val detail: String, val action: String)
internal data class SessionReminders(val checks: List<String> = emptyList(), val retry: String? = null, val logEnd: Long? = null)

internal class WorkspaceModel(
    val host: HostBridge, val session: DeviceSession?, val prefs: DeskPreferences, val target: String = session?.serial.orEmpty(),
    private val profile: String = prefs.selectedId ?: session?.serial.orEmpty(),
    private val onLogInterrupted: (() -> Unit)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var recoveryNote by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var statusPage by mutableStateOf("")
        private set
    var transferProgress by mutableStateOf<Float?>(null)
        private set
    var installingApk by mutableStateOf(false)
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
    var recentCommands by mutableStateOf(emptyList<String>())
        private set
    var shellOutput by mutableStateOf("")
        private set
    var filter by mutableStateOf(LogFilter())
    var logs by mutableStateOf(emptyList<DeviceLog>())
        private set
    var dropped by mutableStateOf(0L)
        private set
    var logRunning by mutableStateOf(false)
        private set
    var logRequested = false
        private set
    var logBuffers by mutableStateOf(emptyList<String>())
        private set
    var logBuffer by mutableStateOf("")
    var paused by mutableStateOf<List<DeviceLog>?>(null)
        private set
    val visibleLogs get() = paused ?: logs
    private var logJob: Job? = null
    private val buffer = ArrayDeque<DeviceLog>()
    private var capture = 0L
    private var logCursor = 0L
    var unreadLost = 0L
        private set
    private var priorDrops = 0L
    var displayLogs = true
    @Volatile private var logToken = 0L
    private var dirty = false
    private var lost = 0L

    init {
        scope.launch { host.foreground.collect { if (!it && !host.desktop) stopLogs("主控进入后台，采集中断") } }
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

    fun close() {
        synchronized(buffer) { logToken++; releaseCapture() }
        task?.cancel(); logJob?.cancel(); scope.cancel()
    }
    private fun releaseCapture() {
        if (capture != 0L) { NativeCore.closeLog(capture); capture = 0L }
        logCursor = 0L
    }
    private fun captureHandle(): Long {
        if (capture == 0L) { capture = NativeCore.openLog(); priorDrops = lost }
        return capture
    }
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
        if (logs.isNotEmpty() || capture != 0L) reminders = reminders.copy(logEnd = System.currentTimeMillis())
        val offline = WorkspaceModel(host, null, prefs, target, profile)
        synchronized(buffer) {
            offline.restoreReminders(reminders)
            offline.logs = logs
            offline.buffer.addAll(logs)
            offline.lost = dropped
            offline.dropped = dropped
            offline.capture = capture
            offline.priorDrops = priorDrops
            offline.logCursor = logCursor
            offline.unreadLost = unreadLost
            offline.dirty = capture != 0L
            capture = 0L
        }
        offline.paused = paused
        offline.filter = filter
        offline.logBuffer = logBuffer
        offline.logBuffers = logBuffers
        offline.shellOutput = shellOutput
        offline.recentCommands = recentCommands
        offline.status = "已断开 · $target"
        close()
        offline.flush()
        return offline
    }
    fun commandError(e:Exception) {
        statusPage="Shell"
        problem=true
        status=e.localizedMessage ?: "无法保存收藏"
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

    private fun job(page:String, label: String, release:()->Unit={}, action: suspend () -> Unit) {
        if (busy) { release(); return }
        statusPage = page
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
            } finally { installingApk=false; transferProgress=null; submitted = null; transferBeforeCommit = null; busy = false; task = null }
        }.also { it.invokeOnCompletion { release() } }
    }

    private val appQuery=Mutex()
    private var catalogJob:Job?=null
    private var catalogStarted=false
    private val attemptedVersions=mutableMapOf<String,String>()

    fun prefetchApps() {
        scope.launch {
            try { queryApps(onlyIfEmpty=true) }
            catch(e:CancellationException) { throw e }
            catch(_:Exception) { /* The Apps page can retry a failed background read. */ }
        }
    }

    private suspend fun queryApps(onlyIfEmpty:Boolean=false, retryMissing:Boolean=false) = appQuery.withLock {
        if(onlyIfEmpty && appUser!=null) return@withLock
        val device=requireNotNull(session)
        val user=device.currentUser()
        val list=device.apps(user)
        if(user!=appUser) {
            catalogJob?.cancel()
            catalogJob=null
            catalogStarted=false
            attemptedVersions.clear()
            appList=emptyList()
        }
        val previous=appList.associateBy { it.name }
        appUser=user
        appList=list.map { app ->
            val cached=previous[app.name]
            if(cached!=null && cached.version==app.version) app.copy(label=cached.label,icon=cached.icon) else app
        }
        attemptedVersions.keys.retainAll(list.mapTo(HashSet()) { it.name })
        if(retryMissing && catalogJob?.isActive!=true)
            appList.filter { it.label.isEmpty() && it.icon==null }.forEach { attemptedVersions.remove(it.name) }
        if(catalogJob?.isActive!=true) catalogJob=scope.launch {
            while(isActive) {
                val missing=appList.filter { attemptedVersions[it.name]!=it.version }
                if(missing.isEmpty()) break
                val full=!catalogStarted
                catalogStarted=true
                val batch=if(full) missing else missing.take(64)
                val versions=batch.associate { it.name to it.version }
                attemptedVersions.putAll(versions)
                try {
                    val resources=device.appResources(user,if(full) emptyList() else versions.keys.toList())
                    ensureActive()
                    appList=appList.map { app ->
                        val resource=resources[app.name]
                        if(resource!=null && versions[app.name]==app.version && attemptedVersions[app.name]==app.version)
                            app.copy(label=resource.first,icon=resource.second) else app
                    }
                } catch(e:CancellationException) { throw e }
                catch(_:Exception) { /* Keep package IDs usable on restricted devices. */ }
            }
        }
    }

    fun refreshApps(onlyIfEmpty:Boolean=false) = job("应用","读取应用…") {
        queryApps(onlyIfEmpty,retryMissing=!onlyIfEmpty)
        status = "Android 用户 $appUser · ${appList.size} 个应用"
    }

    private suspend fun userForChange(): Int {
        val current = requireNotNull(session).currentUser()
        if (appUser != null && appUser != current) {
            queryApps()
            error("Android 用户已切换，已刷新列表，请重新选择")
        }
        return current
    }

    fun appAction(app: TargetApp, action: String) = job("应用","$action · ${app.name}") {
        val device = requireNotNull(session)
        val user = userForChange()
        val pkg = remoteArg(app.name)
        val detail = "$target · Android 用户 $user\n${app.name}"
        ask("$action ${app.name}", detail + when(action) {
            "卸载" -> "\n将移除此用户的应用及数据。"
            "冻结" -> "\n冻结后无法启动，应用数据保留。"
            else -> ""
        }, action)
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
                transferProgress = size?.takeIf { it>0 }?.let { (n.toFloat()/it).coerceIn(0f,1f) }
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

    fun installApk(url: String? = null, dropped:LocalDoc?=null, release:()->Unit={}) = job("应用","准备 APK…", release) {
        val device = requireNotNull(session)
        val doc = dropped ?: if (url == null) host.pickOpen(".apk") ?: throw CancellationException() else null
        installingApk = true
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
            transferProgress=null
            status = "检查 APK…"
            val apk = withContext(Dispatchers.IO) { readApk(file) }
            val user = userForChange()
            val installed = device.apps(user).find { it.name == apk.packageName }
            val label = if (installed == null) "安装" else "更新"
            val detail = "$target · Android 用户 $user\n${apk.packageName}\n版本 ${apk.versionName.orEmpty()} (${apk.versionCode})\n$source · ${byteCount(file.length())}" +
                (installed?.let { "\n当前版本 ${it.version}，更新将替换设备上的包代码。" } ?: "")
            ask("$label APK", detail, label)
            check(device.currentUser() == user) { "Android 用户已切换，请重新选择 APK" }
            status = "传输 APK…"
            transferProgress=0f
            val output = device.install(file, user, progress("传输 APK",file.length())) {
                transferProgress=null
                submitted = "$label · ${apk.packageName} (${apk.versionCode}) · Android 用户 $user"
                status = "安装中 · ${apk.packageName}"
            }
            check(!output.contains("Failure", true) && !output.contains("Error:", true)) { output }
            status="核验安装结果…"
            attemptedVersions.remove(apk.packageName)
            queryApps()
            check(appList.any { it.name == apk.packageName && it.version == apk.versionCode.toString() }) { "未能核实安装版本，请刷新" }
            status = "已安装 · ${apk.packageName} (${apk.versionCode})"
        }
    }

    private val directoryCache = LinkedHashMap<String,List<TargetFile>>(16,.75f,true)
    fun recentDirectories():List<String> = directoryCache.keys.reversed()

    private suspend fun openPath(path: String, cached:Boolean=false) {
        val entries = (if(cached) directoryCache[path] else null) ?: requireNotNull(session).files(path)
        directoryCache[path]=entries
        if(directoryCache.size>32) directoryCache.remove(directoryCache.keys.first())
        directory = path
        fileList = entries
        filesLoaded = true
    }
    fun browse(path: String, refresh:Boolean=false) = job("文件","读取目录…") {
        require(path.startsWith('/') && '\u0000' !in path) { "请输入设备绝对路径" }
        openPath(path.trimEnd('/').ifBlank { "/" },cached=!refresh)
        status = "${fileList.size} 项"
    }

    fun upload() = job("文件","选择文件…") {
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

    fun download(file: TargetFile) = job("文件","选择保存位置…") {
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

    fun move(file: TargetFile, newDirectory: String) = job("文件","移动…") {
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
        directoryCache.clear()
        openPath(directory)
        status = "已移动 · $destination"
    }

    fun runShell(text: String) = job("Shell","运行中 · $target") {
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
        recentCommands = (listOf(text) + recentCommands.filterNot { it==text }).take(15)
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
            NativeCore.noteLog(captureHandle(), if (text.length <= 4096) text else text.take(4097))
            dirty = true
        }
    }
    private var drain: Job? = null

    private data class Page(val end: Long, val evicted: Long, val newest: Long, val rows: List<DeviceLog>)
    private fun readPage(handle: Long, cursor: Long, previous: DeviceLog?): Page? {
        val packet = java.nio.ByteBuffer.wrap(NativeCore.readLogPage(handle, cursor))
        if (!packet.hasRemaining()) return null
        val end = packet.long
        val evicted = packet.long
        val newest = packet.long
        val fields = IntArray(9)
        val rows = ArrayList<DeviceLog>()
        var parent = previous.takeIf { cursor >= evicted }
        while (packet.hasRemaining()) {
            val size = packet.int
            for (i in fields.indices) fields[i] = packet.int
            val line = String(packet.array(), packet.position(), size, Charsets.UTF_8)
            packet.position(packet.position() + size)
            val row = logRow(line, fields, 0, parent, maxOf(cursor,evicted)+rows.size)
            rows.add(row)
            parent = row
        }
        return Page(end, evicted, newest, rows)
    }

    // Main-thread publisher. Native serialization and string decoding run on a worker.
    private fun flush() {
        if (drain?.isActive == true) return
        drain = scope.launch {
            while (isActive) {
                val state = synchronized(buffer) {
                    if (!dirty || capture == 0L) {
                        if (session == null) releaseCapture()
                        return@launch
                    }
                    dirty = false
                    Triple(capture, logCursor, buffer.peekLast())
                }
                val page = try { withContext(Dispatchers.Default) { readPage(state.first, state.second, state.third) } }
                catch (e: java.io.IOException) {
                    if (synchronized(buffer) { state.first == capture }) throw e
                    null // A clear or close retired this exact capture.
                }
                if (page != null) synchronized(buffer) {
                    if (capture == state.first && logCursor == state.second) {
                        unreadLost += (page.evicted - logCursor).coerceAtLeast(0L)
                        val keep = (state.second - page.evicted).coerceAtLeast(0L)
                        while (buffer.size > keep) buffer.removeFirst()
                        buffer.addAll(page.rows)
                        logCursor = page.end
                        lost = priorDrops + page.evicted
                        logs = buffer.toList()
                        dropped = lost
                        dirty = dirty || page.end < page.newest
                    }
                }
                // Give rendering/cancellation a turn between bounded pages.
                if (page != null) delay(16) else yield()
            }
        }
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
    private fun prepareCapture(): Boolean = try {
        synchronized(buffer) { captureHandle() }
        true
    } catch (e: java.io.IOException) {
        statusPage = "日志"
        status = "日志采集暂时不可用 · " + e.localizedMessage
        problem = true
        logRequested = false
        false
    }

    fun startLogs() {
        val device = session ?: return
        if (logRunning || !host.foreground.value || !prepareCapture()) return
        logRequested = true
        logRunning = true
        val token = synchronized(buffer) { ++logToken }
        reminders.logEnd?.let { ended ->
            addLog(token, "〔${deskTime(ended)} ${tr("至")} ${deskTime(System.currentTimeMillis())} · ${tr("设备离线期间未采集")} · $target〕")
            reminders = reminders.copy(logEnd = null)
        }
        addLog(token, "〔${deskTime(System.currentTimeMillis())} · ${tr("开始采集")} · $target〕")
        flush()
        logJob = scope.launch {
            coroutineScope {
                var recheck = true
                val repaint = launch { while (isActive) { delay(if (displayLogs && paused == null) 160L else 750L); flush() } }
                try {
                    if (logBuffer.isNotEmpty()) readLogBuffers()
                    val selected = logBuffer.takeIf { it in logBuffers }?.let { " -b $it" }.orEmpty()
                    // Start from the target clock so resuming does not replay the ring buffer.
                    val command = "logcat -v threadtime$selected -T \"\$(date +%s.%N)\""
                    val reply = if (device is LogSource) device.collectLog(command) { bytes, count ->
                        synchronized(buffer) {
                            if (token == logToken) { NativeCore.appendLog(captureHandle(), bytes, count); dirty = true }
                        }
                    } else device.shell(command) { line, _ -> addLog(token, line) }
                    if (reply.exitCode != null && token == logToken) logRequested = false
                    if (reply.exitCode != null && reply.exitCode != 0) {
                        val reason = reply.stderr.trim().ifBlank { reply.stdout.trim() }.takeLast(400)
                        addLog(token, "〔${tr("日志读取失败")} · ${tr("退出码")} ${reply.exitCode} · ${uiMessage(reason)}〕")
                    }
                }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    val action = recoveryAction(e)
                    recheck = action != RecoveryAction.WaitForResources && action != RecoveryAction.StopOperation
                    addLog(token, "〔${e.localizedMessage ?: "读取失败"}〕")
                }
                finally {
                    repaint.cancel()
                    if (token == logToken) {
                        synchronized(buffer) { if (capture != 0L) NativeCore.finishLog(capture) }
                        addLog(token, "〔${deskTime(System.currentTimeMillis())} · ${tr("采集流已结束")}〕")
                        flush()
                        logRunning = false
                    }
                }
                if (recheck && logRequested && onLogInterrupted != null) {
                    // Allow adbd to restart and bound retries if the log stream keeps failing.
                    delay(1000)
                    if (token == logToken && logRequested && !logRunning) onLogInterrupted()
                }
            }
        }
    }
    fun stopLogs(reason: String = "已停止采集") {
        logRequested = false
        if (!logRunning) return
        val token = synchronized(buffer) {
            if (capture != 0L) NativeCore.finishLog(capture)
            ++logToken
        }
        logJob?.cancel()
        addLog(token, "〔${deskTime(System.currentTimeMillis())} · ${uiMessage(reason)}〕")
        flush()
        logRunning = false
    }
    fun pauseDisplay() { paused = if (paused == null) logs else null }
    fun clearLogs() {
        synchronized(buffer) {
            releaseCapture()
            buffer.clear()
            lost = 0; dropped = 0; unreadLost = 0; logs = emptyList(); dirty = false
            if (logRunning && !prepareCapture()) {
                logToken++
                logJob?.cancel()
                logRunning = false
            }
        }
        paused = null
        flush()
    }
    fun exportLogs() {
        val rows = visibleLogs.filter(filter::accepts)
        job("日志","选择保存位置…") {
            val dest = host.pickSave("logcat.txt") ?: throw CancellationException()
            staged(".txt") { file ->
                withContext(Dispatchers.IO) { file.bufferedWriter().use { out -> rows.forEach { out.write(it.text); out.newLine() } } }
                dest.writeFrom(file)
            }
            status = "已导出 ${rows.size} 行 · ${dest.name}"
        }
    }
}

