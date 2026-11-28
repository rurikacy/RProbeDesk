package dev.rprobedesk

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.ContextThemeWrapper
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    private lateinit var host: AndroidHost
    private val connectivity by lazy { getSystemService(android.net.ConnectivityManager::class.java) }
    private val networkEvents = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) = NativeCore.networkChanged()
        override fun onLinkPropertiesChanged(network: android.net.Network, props: android.net.LinkProperties) = NativeCore.networkChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = AndroidHost(this)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
        setContent {
            val prefs=remember { DeskPreferences(host.settingsDir) }
            val systemDark=isSystemInDarkTheme()
            val dark=prefs.colorMode.isDark(systemDark)
            val tappable=WindowInsets.tappableElement
            val density=LocalDensity.current
            val direction=LocalLayoutDirection.current
            // System navigation buttons can retain the launcher's icon colors, including on a side edge.
            val buttonNavigation=tappable.getBottom(density)>0 ||
                tappable.getLeft(density,direction)>0 || tappable.getRight(density,direction)>0
            val navigationDark=if(buttonNavigation) systemDark else dark
            val bars=remember { WindowCompat.getInsetsController(window,window.decorView) }
            SideEffect {
                host.dark=dark
                bars.isAppearanceLightStatusBars=!dark
                bars.isAppearanceLightNavigationBars=!navigationDark
            }
            val palette=if(dark) DarkDeskPalette else LightDeskPalette
            val navigationBackground=when {
                !buttonNavigation -> palette.background
                systemDark -> DarkDeskPalette.surface
                else -> LightDeskPalette.surface
            }
            Box(Modifier.fillMaxSize().background(navigationBackground).navigationBarsPadding()
                .background(palette.surface).safeDrawingPadding()) {
                DeskApp(host,prefs,dark)
            }
        }
    }
    override fun onStart() {
        super.onStart()
        connectivity.registerDefaultNetworkCallback(networkEvents)
        host.foreground.value = true
    }
    override fun onStop() {
        host.foreground.value = false
        connectivity.unregisterNetworkCallback(networkEvents)
        host.discovery.stop()
        NativeCore.stopDiscovery()
        host.stopMirror()
        super.onStop()
    }
    override fun onDestroy() {
        host.dispose()
        super.onDestroy()
    }
}

internal class AndroidHost(private val activity: ComponentActivity) : HostBridge, LiveDiscovery {
    var dark=false
    private fun dialogContext():Context = ContextThemeWrapper(activity,R.style.RProbeDialog).apply {
        applyOverrideConfiguration(Configuration().apply {
            val night=if(dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            uiMode=(activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        })
    }
    private val resolver = activity.contentResolver
    override val settingsDir: File = activity.filesDir
    override val cacheDir: File = activity.cacheDir
    override val desktop = false
    override val appVersion: String get() = requireNotNull(activity.packageManager.getPackageInfo(activity.packageName, 0).versionName)
    override fun installUpdate(file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file)
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
    override val foreground = MutableStateFlow(false)
    override val reducedMotion = MutableStateFlow(motionOff())
    override val mirrorActive = MutableStateFlow(false)
    private val adb = AndroidAdb(activity, ::readCode)
    private var mirror: MirrorWindow? = null
    private var pending: CancellableContinuation<Uri?>? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val observeMotion = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) { reducedMotion.value = motionOff() }
    }
    private val openPicker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument(), ::picked)
    private val folderPicker = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree(), ::picked)

    init {
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observeMotion)
    }
    private fun motionOff() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    override suspend fun discoverDevices()=discover {}
    internal val discovery = androidDiscovery(activity)
    override suspend fun discover(onUpdate: (List<DiscoveredDevice>) -> Unit) = discovery.discover(onUpdate)
    override suspend fun pair(address: String, port: Int, code: String) = adb.pair(address, port, code)
    override suspend fun connect(address: String, port: Int) = adb.connect(address, port)

    override suspend fun reconnect(address: String, port: Int, verifiedPeer: String) = adb.connect(address, port, verifiedPeer)

    private fun picked(uri: Uri?) {
        val request = pending
        pending = null
        if (request?.isActive == true) request.resume(uri)
    }
    private suspend fun pick(launch: () -> Unit): Uri? = withContext(Dispatchers.Main.immediate) {
        check(pending == null) { "文件选择器已经打开" }
        val keepTransfer = Intent(activity, TransferPickerService::class.java)
        activity.startForegroundService(keepTransfer)
        try {
            suspendCancellableCoroutine { result ->
                pending = result
                try { launch() } catch (error: Throwable) { pending = null; throw error }
            }
        } finally {
            activity.stopService(keepTransfer)
        }
    }
    override suspend fun pickOpen(suffix: String): LocalDoc? {
        val uri = pick { openPicker.launch(arrayOf("*/*")) } ?: return null
        return withContext(Dispatchers.IO) { inputDocument(uri) }
    }
    internal fun inputDocument(uri:Uri):LocalDoc {
        var display = uri.lastPathSegment ?: "文件"
        var count: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                if (!it.isNull(0)) display = it.getString(0)
                if (!it.isNull(1)) count = it.getLong(1)
            }
        }
        return object : LocalDoc {
            override val name = display
            override val size = count
            override fun openInput(): InputStream = resolver.openInputStream(uri) ?: throw IOException("无法读取所选文档")
            override suspend fun writeFrom(file: File): Unit = error("所选文档只用于读取")
        }
    }
    override suspend fun pickSave(name: String): LocalDoc? {
        require(name.isNotBlank() && name !in setOf(".", "..") && name.none { it == '/' || it == '\\' || it == '\u0000' })
        val tree = pick { folderPicker.launch(null) } ?: return null
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        var displayName=name
        return object : LocalDoc {
            override val name get()=displayName
            override val size: Long? = null
            override fun openInput(): InputStream = error("目标文档只用于保存")
            override suspend fun writeFrom(file: File) = withContext(Dispatchers.IO) {
                val mime = when (name.substringAfterLast('.').lowercase()) {
                    "png" -> "image/png"
                    "txt", "log" -> "text/plain"
                    else -> "application/octet-stream"
                }
                val dest = DocumentsContract.createDocument(resolver, parent, mime, name)
                    ?: throw IOException("文档提供方无法创建文件")
                try {
                    val job = currentCoroutineContext()
                    resolver.openOutputStream(dest, "w")?.use { output ->
                        file.inputStream().use { input ->
                            val bytes = ByteArray(65536)
                            while (true) {
                                job.ensureActive()
                                val n = input.read(bytes)
                                if (n < 0) break
                                output.write(bytes, 0, n)
                            }
                        }
                    } ?: throw IOException("文档无法写入")
                } catch (error: Throwable) {
                    runCatching { DocumentsContract.deleteDocument(resolver, dest) }
                    throw error
                }
                runCatching {
                    resolver.query(dest,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor ->
                        if(cursor.moveToFirst() && !cursor.isNull(0)) displayName=cursor.getString(0)
                    }
                }
                Unit
            }
        }
    }

    override suspend fun selectTool(tool: String): Unit = error("Android 使用内置设备传输")
    override fun toolPath(tool: String): String? = null

    override suspend fun startMirror(session: DeviceSession) = withContext(Dispatchers.Main.immediate) {
        check(session is AndroidSession) { "设备连接不匹配" }
        val live = mirror
        if (live != null) { live.focus(); return@withContext }
        lateinit var window: MirrorWindow
        window = MirrorWindow(activity, dialogContext(), session) {
            if (mirror === window) { mirror = null; mirrorActive.value = false }
        }
        mirror = window
        try {
            window.start()
            mirrorActive.value = true
        } catch (error: Throwable) {
            window.close()
            throw error
        }
    }
    override fun stopMirror() { activity.runOnUiThread { mirror?.close() } }
    override fun copy(text: String) {
        activity.runOnUiThread {
            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("RProbeDesk", text))
        }
    }
    private suspend fun readCode(endpoint: String): String? = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { result ->
            val context=dialogContext()
            val input = EditText(context, null, 0, R.style.RProbeInput).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(InputFilter.LengthFilter(8))
                isSingleLine = true
                contentDescription = tr("目标通知中的八位确认码")
            }
            val dialog = AlertDialog.Builder(context, R.style.RProbeDialog)
                .setTitle(uiMessage("核对目标设备"))
                .setMessage(uiMessage("请从 $endpoint 这台设备的 RProbeDesk 通知中输入八位数字。"))
                .setView(input)
                .setPositiveButton(uiMessage("确认")) { _, _ -> if (result.isActive) result.resume(input.text.toString()) }
                .setNegativeButton(uiMessage("取消")) { _, _ -> if (result.isActive) result.resume(null) }
                .setOnCancelListener { if (result.isActive) result.resume(null) }.show()
            dialog.styleActions()
            result.invokeOnCancellation { mainHandler.post { dialog.dismiss() } }
        }
    }
    fun dispose() {
        stopMirror()
        picked(null)
        resolver.unregisterContentObserver(observeMotion)
    }
}

internal fun AlertDialog.styleActions() {
    val confirm=getButton(AlertDialog.BUTTON_POSITIVE)
    val density=context.resources.displayMetrics.density
    val accent=context.getColor(R.color.rpd_purple)
    val onAccent=context.getColor(R.color.rpd_on_accent)
    val surface=context.getColor(R.color.rpd_surface)
    for (button in listOf(getButton(AlertDialog.BUTTON_NEGATIVE),confirm)) {
        val primary=button==confirm
        button.backgroundTintList=null
        button.background=android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(((if(primary) onAccent else accent) and 0xffffff) or 0x24000000),
            android.graphics.drawable.GradientDrawable().apply {
                setColor(if(primary) accent else surface)
                setStroke(density.toInt().coerceAtLeast(1),accent)
                cornerRadius=4*density
            },null)
        button.setTextColor(if(primary) onAccent else accent)
    }
    (confirm.parent as? android.view.ViewGroup)?.let { row ->
        row.setPadding(row.paddingLeft,row.paddingTop,row.paddingRight,(12*density).toInt())
    }
}

internal val deskDialogProperties = androidx.compose.ui.window.DialogProperties()
