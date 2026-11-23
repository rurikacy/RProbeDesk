package dev.rprobedesk

import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import androidx.compose.ui.awt.ComposeWindow
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.nfd.NativeFileDialog.*
import org.lwjgl.util.nfd.NFDFilterItem
import org.lwjgl.util.nfd.NFDSaveDialogArgs
import org.lwjgl.util.nfd.NFDOpenDialogArgs
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import org.jetbrains.skiko.SystemTheme
import org.jetbrains.skiko.currentSystemTheme

internal fun windowsSaveName(suggested: String): String {
    val name = suggested.map { c -> if (c < ' ' || c in "<>:\"/\\|?*") '_' else c }
        .joinToString("").trimEnd('.', ' ').ifEmpty { "download" }
    val reserved = Regex("(?i)CON|PRN|AUX|NUL|CONIN\\$|CONOUT\\$|(?:COM|LPT)[1-9¹²³]")
    return if (reserved.matches(name.substringBefore('.').trimEnd())) "_$name" else name
}

internal fun checkWindowsTool(name: String, help: String, pairing: Boolean = false) {
    val versionPattern = if (name == "adb") "Android Debug Bridge version" else "scrcpy"
    val version = Regex("(?m)^" + versionPattern + """\s+(\d+(?:\.\d+)+)""")
        .find(help)?.groupValues?.get(1)
        ?: error("无法识别 $name.exe 的版本，请重新选择官方工具")
    if (name == "adb") {
        check(Regex("""(?m)^\s*-t\s+""").containsMatchIn(help)) { "ADB $version 不支持按连接区分设备，请更新 Platform-Tools" }
        if (pairing) check(Regex("""(?m)^\s*pair\s+""").containsMatchIn(help)) {
            "ADB $version 不支持无线配对，请更新 Platform-Tools"
        }
    } else {
        check(listOf("--no-audio", "--no-clipboard-autosync", "--serial", "--window-title").all {
            Regex("(?m)^\\s*(?:-s,\\s*)?" + it + "(?=[=\\s]|$)").containsMatchIn(help)
        }) { "scrcpy $version 缺少镜像所需的选项，请选择较新版本" }
    }
}

internal suspend fun verifyWindowsTool(name: String, path: String, pairing: Boolean = false): String {
    val group = ProcessGroup()
    try {
        val reply = withTimeoutOrNull(5000) {
            group.text(listOf(path, if (name == "adb") "help" else "--help"))
        } ?: error("$name.exe 无响应，请重新选择工具")
        check(reply.exitCode == 0) { "$name.exe 未能报告版本，请重新选择官方工具" }
        checkWindowsTool(name, reply.stdout + "\n" + reply.stderr, pairing)
        return path
    } catch (e: java.io.IOException) {
        throw IllegalStateException("无法启动 $name.exe，请重新选择工具", e)
    } finally { group.close() }
}

internal class WindowsDoc private constructor(
    private val file: File, private val original: BasicFileAttributes?, private val originalHash: ByteArray?
) : LocalDoc {
    constructor(input: File) : this(input, null, null)
    override val name: String get() = file.name
    override val size: Long? get() = file.takeIf { it.isFile }?.length()
    companion object {
        private fun attributes(file: File): BasicFileAttributes? =
            if (Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) null
            else Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)

        private suspend fun checksum(file: File): ByteArray {
            val task = currentCoroutineContext()
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    task.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest()
        }

        suspend fun forSave(file: File): WindowsDoc = withContext(Dispatchers.IO) {
            val original = attributes(file)
            require(original == null || original.isRegularFile) { "请选择普通文件或新文件名" }
            WindowsDoc(file, original, if (original == null) null else checksum(file))
        }
    }
    override fun openInput(): InputStream = file.inputStream()
    override suspend fun writeFrom(file: File) = withContext(Dispatchers.IO) {
        val dest = this@WindowsDoc.file.toPath()
        val tmp = Files.createTempFile(dest.parent, ".rprobe-", ".partial")
        try {
            val task = currentCoroutineContext()
            file.inputStream().use { input ->
                Files.newOutputStream(tmp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        task.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                    }
                }
            }
            task.ensureActive()
            val current = attributes(this@WindowsDoc.file)
            if (original == null) {
                Files.move(tmp, dest)
            } else {
                check(original.isRegularFile && current?.isRegularFile == true && original.fileKey() == current.fileKey() &&
                    original.size() == current.size() && original.lastModifiedTime() == current.lastModifiedTime() &&
                    original.creationTime() == current.creationTime() && checksum(this@WindowsDoc.file).contentEquals(originalHash)) {
                    "目标文件已发生变化，请重新选择保存位置"
                }
                Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(tmp) }
        Unit
    }
}

private val desktopProfile=File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "RProbeDesk")

private interface WindowsAnimationSettings : StdCallLibrary {
    fun SystemParametersInfoW(action:Int,param:Int,result:IntByReference,flags:Int):Boolean
}

private interface WindowsCaption : StdCallLibrary {
    fun DwmSetWindowAttribute(hwnd:HWND,attribute:Int,value:IntByReference,size:Int):Int
}

internal class WindowsHost @JvmOverloads constructor(private val frame: Frame, private val exitForUpdate: () -> Unit = { frame.dispose() }) : HostBridge {
    override val appVersion: String get() = System.getProperty("jpackage.app-version") ?: System.getProperty("rprobedesk.version")
    override fun installUpdate(file: File) {
        val installer = File(System.getenv("SystemRoot"), "System32/msiexec.exe")
        ProcessBuilder(installer.absolutePath, "/i", file.absolutePath, "/qb", "/norestart").start()
        exitForUpdate()
    }
    override val desktop = true
    override val settingsDir = desktopProfile.apply { mkdirs() }
    override val cacheDir = File(settingsDir, "cache").apply { mkdirs() }
    override val foreground = MutableStateFlow(true)
    private val animationSettings=Native.load("user32",WindowsAnimationSettings::class.java)
    override val reducedMotion = MutableStateFlow(true)
    override val mirrorActive = MutableStateFlow(false)
    private val prefs = Preferences.userRoot().node("dev/rprobedesk/tools")
    @Volatile private var mirror: Process? = null
    @Volatile private var mirrorGeneration = 0L

    private suspend fun choose(suffix:String="", saveName:String?=null):File? {
        val owner=(frame as? ComposeWindow)?.windowHandle ?: 0L
        return withContext(Dispatchers.IO) {
            check(NFD_Init()==NFD_OKAY) { NFD_GetError() ?: "文件选择器初始化失败" }
            try {
                MemoryStack.stackPush().use { stack ->
                    val ext=suffix.trimStart('.')
                    val filter=if(ext.isEmpty()) null else NFDFilterItem.calloc(1,stack).also {
                        it[0].name(stack.UTF8(ext.uppercase())).spec(stack.UTF8(ext))
                    }
                    val selected=stack.mallocPointer(1)
                    val result=if(saveName==null) {
                        val args=NFDOpenDialogArgs.calloc(stack)
                        if(filter!=null) args.filterList(filter)
                        args.parentWindow().type(NFD_WINDOW_HANDLE_TYPE_WINDOWS.toLong()).handle(owner)
                        NFD_OpenDialog_With(selected,args)
                    } else {
                        val args=NFDSaveDialogArgs.calloc(stack).defaultName(stack.UTF8(saveName))
                        args.parentWindow().type(NFD_WINDOW_HANDLE_TYPE_WINDOWS.toLong()).handle(owner)
                        NFD_SaveDialog_With(selected,args)
                    }
                    when(result) {
                        NFD_OKAY -> try { File(MemoryUtil.memUTF8(selected[0])) }
                                    finally { NFD_FreePath(selected[0]) }
                        NFD_CANCEL -> null
                        else -> error(NFD_GetError() ?: "打开文件选择器失败")
                    }
                }
            } finally { NFD_Quit() }
        }
    }
    override suspend fun pickOpen(suffix:String) = choose(suffix)?.let(::WindowsDoc)
    override suspend fun pickSave(name: String) = choose(saveName=windowsSaveName(name))?.let { WindowsDoc.forSave(it) }

    override fun hasSavedToolPath(tool: String): Boolean =
        prefs.get(tool, null)?.let { File(it).isFile } == true

    override fun toolPath(tool: String): String? {
        val name = tool.removeSuffix(".exe")
        require(name == "adb" || name == "scrcpy")
        val saved = prefs.get(name, null)
        if (saved != null) return saved.takeIf { File(it).isFile }
        val sdkRoots = listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"),
            System.getenv("LOCALAPPDATA")?.let { "$it/Android/Sdk" })
        val folders = System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { File(it).isAbsolute }
        val candidates = if (name == "adb") sdkRoots.map { "$it/platform-tools" } + folders else folders
        return candidates.map { File(it.trim('"'), "$name.exe") }.firstOrNull(File::isFile)?.absolutePath
    }

    override suspend fun selectTool(tool: String) {
        val name = tool.removeSuffix(".exe")
        require(name == "adb" || name == "scrcpy")
        val selected = choose("exe") ?: return
        setToolPath(name,selected.absolutePath)
    }

    override suspend fun setToolPath(tool:String,path:String) {
        val name=tool.removeSuffix(".exe")
        require(name in listOf("adb","scrcpy"))
        val selected=File(path.trim().removeSurrounding("\""))
        require(selected.isAbsolute && selected.name.equals("$name.exe", true) && selected.isFile) { "请选择 $name.exe" }
        verifyWindowsTool(name, selected.absolutePath)
        prefs.put(name, selected.absolutePath)
        prefs.flush()
    }

    private suspend fun requireAdb(pairing: Boolean = false) =
        verifyWindowsTool("adb", toolPath("adb") ?: error("请先选择 Android Platform-Tools 中的 adb.exe"), pairing)
    override suspend fun discoverDevices():List<DiscoveredDevice> {
        val adb=requireAdb()
        val group=ProcessGroup()
        try {
            return withTimeoutOrNull(12_000) {
                var services=group.text(listOf(adb,"mdns","services"))
                if(services.exitCode==0 && "_tcp" !in services.stdout) {
                    kotlinx.coroutines.delay(2000)
                    services=group.text(listOf(adb,"mdns","services"))
                }
                val connected=group.text(listOf(adb,"devices"))
                check(services.exitCode==0 || connected.exitCode==0) { "设备发现失败" }
                windowsDiscoveredDevices(services.stdout,connected.stdout)
            } ?: error("设备发现超时，请重试")
        } finally { group.close() }
    }

    override suspend fun pair(address: String, port: Int, code: String) {
        require(code.matches(Regex("[0-9]{6}"))) { "请输入六位配对码" }
        val group = ProcessGroup()
        try {
            val result = withTimeout(45000) {
                group.text(listOf(requireAdb(pairing = true), "pair", targetAddress(address, port)), "$code\n")
            }
            check(result.exitCode == 0 && "Successfully paired" in result.stdout) { "配对失败，请核对配对端口和有效期" }
        } finally { group.close() }
    }
    override suspend fun connect(address: String, port: Int) = connectWindows(requireAdb(), address, port)

    override suspend fun startMirror(session: DeviceSession) {
        val generation = mirrorGeneration
        withContext(Dispatchers.IO) { openMirror(session as WindowsSession, generation) }
    }

    private suspend fun openMirror(device: WindowsSession, generation: Long) {
        val existing = mirror
        if (existing != null && existing.isAlive) {
            val systemDir = System.getenv("SystemRoot") ?: "C:/Windows"
            val powershell = File(systemDir, "System32/WindowsPowerShell/v1.0/powershell.exe")
            ProcessBuilder(powershell.absolutePath, "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                "-Command", "(New-Object -ComObject WScript.Shell).AppActivate(${existing.pid()})").start()
            return
        }
        val exe = verifyWindowsTool("scrcpy", toolPath("scrcpy") ?: error("请在设置页选择 scrcpy.exe"))
        device.shell("true").checked()
        val builder = localAdbProcess(listOf(exe, "--serial=${device.serial}", "--window-title=RProbeDesk · ${device.serial}",
            "--no-clipboard-autosync", "--no-audio")).apply {
            environment()["ADB"] = device.adb
            directory(File(exe).parentFile)
            redirectErrorStream(true)
        }
        currentCoroutineContext().ensureActive()
        val process = synchronized(this) {
            check(generation == mirrorGeneration) { "镜像请求已取消" }
            builder.start().also { mirror = it }
        }
        val stream = processIo.submit<String> { process.inputStream.bufferedReader().use { reader ->
            var last = ""
            reader.forEachLine { if (it.isNotBlank()) last = it.takeLast(512) }
            last
        } }
        if (process.waitFor(600, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            synchronized(this) { if (mirror === process) mirror = null }
            error(stream.get().ifEmpty { "镜像进程未能启动" })
        }
        synchronized(this) { if (mirror === process) mirrorActive.value = true }
        process.onExit().thenRun {
            synchronized(this) {
                if (mirror === process) { mirror = null; mirrorActive.value = false }
            }
        }
    }
    @Synchronized
    override fun stopMirror() {
        mirrorGeneration++
        val p = mirror
        mirror = null
        mirrorActive.value = false
        p?.destroy()
    }
    override fun copy(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
    fun updateMotion() {
        val toolkit = Toolkit.getDefaultToolkit()
        val clientAnimations=IntByReference()
        val known=animationSettings.SystemParametersInfoW(0x1042,0,clientAnimations,0) // SPI_GETCLIENTAREAANIMATION
        reducedMotion.value = (known && clientAnimations.value==0) || toolkit.getDesktopProperty("win.menu.animation") == false ||
            toolkit.getDesktopProperty("win.combobox.animation") == false
    }
}

fun main() {
    WindowsInstance.acquire(desktopProfile)?.use { runDesktop(it) }
}

private fun runDesktop(instance:WindowsInstance) = application {
    var visible by remember { mutableStateOf(true) }
    var hideOnClose by remember { mutableStateOf<(() -> Boolean)?>(null) }
    Window(onCloseRequest = { if(hideOnClose?.invoke()!=true) exitApplication() }, visible=visible, title = "RProbeDesk",
        state = rememberWindowState(width = 1280.dp, height = 850.dp)) {
        val host = remember { WindowsHost(window, ::exitApplication) }
        val preferences=remember(host) { DeskPreferences(host.settingsDir) }
        val mode=preferences.colorMode
        var systemDark by remember { mutableStateOf(currentSystemTheme==SystemTheme.DARK) }
        // Compose Desktop 1.8 reads the OS appearance once. Refresh the native value while following it.
        LaunchedEffect(mode,visible) {
            if(mode==DeskColorMode.SYSTEM && visible) while(isActive) {
                systemDark=currentSystemTheme==SystemTheme.DARK
                delay(1500)
            }
        }
        val dark=mode.isDark(systemDark)
        LaunchedEffect(window,dark) {
            val hwnd=HWND(Native.getWindowPointer(window))
            val caption=Native.load("dwmapi",WindowsCaption::class.java)
            caption.DwmSetWindowAttribute(hwnd,20,IntByReference(if(dark) 1 else 0),4) // DWMWA_USE_IMMERSIVE_DARK_MODE
        }
        DisposableEffect(window) {
            WindowsHost::class.java.getResource("/window-rprobe.png")?.let { resource ->
                val source = ImageIO.read(resource)
                window.iconImages = listOf(16, 20, 24, 32, 40, 48, 64, 96, 128).map { size ->
                    source.getScaledInstance(size, size, java.awt.Image.SCALE_AREA_AVERAGING)
                } + source
            }
            val hwnd=HWND(Native.getWindowPointer(window))
            var trayMenu:androidx.compose.ui.awt.ComposeDialog?=null
            var restoreOnOpen=false
            fun restore() {
                if(visible && !window.isShowing) {
                    restoreOnOpen=true
                    return
                }
                trayMenu?.dispose()
                visible=true
                window.extendedState=window.extendedState and Frame.ICONIFIED.inv()
                window.isVisible=true
                window.toFront()
                User32.INSTANCE.SetForegroundWindow(hwnd)
                window.requestFocus()
                host.foreground.value=true
            }
            val activations=instance.listen(hwnd,::restore)
            val tray=if(java.awt.SystemTray.isSupported()) java.awt.SystemTray.getSystemTray() else null
            val icon=tray?.let {
                val item=java.awt.TrayIcon(window.iconImages.last(),"RProbeDesk").apply {
                    isImageAutoSize=true
                    addMouseListener(object:java.awt.event.MouseAdapter() {
                        override fun mouseClicked(event:java.awt.event.MouseEvent) {
                            if(event.button==java.awt.event.MouseEvent.BUTTON1 && event.clickCount==1) {
                                restore()
                            }
                        }
                        override fun mouseReleased(event:java.awt.event.MouseEvent) {
                            if(event.button==java.awt.event.MouseEvent.BUTTON3) {
                                trayMenu?.dispose()
                                host.updateMotion()
                                val trayDark=preferences.colorMode.isDark(currentSystemTheme==SystemTheme.DARK)
                                trayMenu=showTrayMenu(host.reducedMotion.value,trayDark,::restore,::exitApplication)
                            }
                        }
                    })
                }
                try { it.add(item); item } catch (_:java.awt.AWTException) { null }
            }
            hideOnClose={
                val hide=icon!=null && preferences.closeToTray
                if(hide) { visible=false; host.foreground.value=false }
                hide
            }
            val listener = object : WindowAdapter() {
                override fun windowOpened(e:WindowEvent?) {
                    if(restoreOnOpen) { restoreOnOpen=false; restore() }
                }
                override fun windowIconified(e: WindowEvent?) { host.foreground.value = false }
                override fun windowDeiconified(e: WindowEvent?) { host.foreground.value = true }
                override fun windowActivated(e: WindowEvent?) { host.updateMotion() }
            }
            host.updateMotion()
            window.addWindowListener(listener)
            onDispose { activations.close(); trayMenu?.dispose(); hideOnClose=null; if(icon!=null) tray.remove(icon); window.removeWindowListener(listener); host.stopMirror() }
        }
        DeskApp(host,preferences,dark)
    }
}
