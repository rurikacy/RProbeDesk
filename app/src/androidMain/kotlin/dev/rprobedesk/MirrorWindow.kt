package dev.rprobedesk

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import io.github.muntashirakon.adb.AdbStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

internal class MirrorWindow(
    private val activity: ComponentActivity,
    private val context: Context,
    private val session: AndroidSession,
    private val onClose: () -> Unit,
) {
    private val dialog = Dialog(context, R.style.RProbeDialog)
    private val root = LinearLayout(context)
    private val area = FrameLayout(context)
    private val video = SurfaceView(context)
    private val status = TextView(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ready = CompletableDeferred<Unit>()
    private val controls = Channel<ByteArray>(64)
    private val channels = CopyOnWriteArrayList<AdbStream>()
    private val closed = AtomicBoolean()
    private var width = 0
    private var height = 0
    private var lastTouch: Pair<Int, Int>? = null
    private val heldKeys = mutableSetOf<Int>()
    private var controlEnabled = true

    suspend fun start() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        root.orientation = LinearLayout.VERTICAL
        val foreground = context.getColor(R.color.rpd_purple)
        root.setBackgroundColor(context.getColor(R.color.rpd_surface))
        area.setBackgroundColor(Color.BLACK)
        val bar = HorizontalScrollView(context)
        val items = LinearLayout(context).apply { setPadding(dp(12),0,dp(12),0) }
        bar.addView(items)
        fun action(name: String, run: () -> Unit) {
            items.addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = tr(name)
                textSize = 14f
                setTextColor(foreground)
                isAllCaps = false
                setPadding(dp(16),0,dp(16),0)
                minimumWidth = dp(48)
                minimumHeight = dp(40)
                layoutParams = LinearLayout.LayoutParams(-2,dp(40)).apply { marginEnd = dp(6); topMargin = dp(2); bottomMargin = dp(2) }
                fun outline(stroke: Int, color: Int) = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(4).toFloat()
                    setStroke(dp(stroke), color)
                }
                background = StateListDrawable().apply {
                    val focus = context.getColor(R.color.rpd_purple)
                    addState(intArrayOf(android.R.attr.state_focused), outline(2, focus))
                    addState(intArrayOf(android.R.attr.state_pressed), outline(1, focus))
                    addState(intArrayOf(), outline(1, foreground))
                }
                setOnClickListener { run() }
            })
        }
        action("关闭") { close() }
        action("返回") { tapKey(KeyEvent.KEYCODE_BACK) }
        action("主页") { tapKey(KeyEvent.KEYCODE_HOME) }
        action("输入文字") { textDialog() }
        root.addView(bar)
        root.addView(View(context).apply { setBackgroundColor(context.getColor(R.color.rpd_border)) }, -1, dp(1))
        status.setTextColor(context.getColor(R.color.rpd_secondary))
        status.textSize = 12f
        status.typeface = Typeface.MONOSPACE
        status.setPadding(dp(12),dp(4),dp(12),dp(4))
        status.text = "${session.serial}  ·  ${tr("正在打开镜像")}"
        root.addView(status)
        root.addView(area, LinearLayout.LayoutParams(-1, 0, 1f))
        video.contentDescription = tr("目标设备屏幕")
        video.isFocusableInTouchMode = true
        area.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        area.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> resize() }
        video.setOnTouchListener { _, event -> touch(event) }
        video.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_VOLUME_DOWN) false
            else if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
                if (event.action == KeyEvent.ACTION_DOWN) heldKeys.add(key) else heldKeys.remove(key)
                keyEvent(event.action, key, event.repeatCount, event.metaState)
                true
            } else false
        }
        video.setOnFocusChangeListener { _, focused -> if (!focused) releaseInputs() }
        video.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { stream(holder.surface) }
                    } catch (failure: Exception) {
                        if (!closed.get()) {
                            ready.completeExceptionally(failure)
                            if (failure !is CancellationException)
                                Toast.makeText(activity, uiMessage(failure.message ?: "镜像连接中断"), Toast.LENGTH_LONG).show()
                            close()
                        }
                    }
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) { close() }
        })
        dialog.setContentView(root)
        dialog.setOnDismissListener { close() }
        dialog.show()
        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawableResource(R.color.rpd_canvas)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        video.requestFocus()
        withTimeout(25_000) { ready.await() }
    }

    fun focus() { if (dialog.isShowing) video.requestFocus() }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        ready.completeExceptionally(CancellationException("镜像已关闭"))
        controls.close()
        scope.cancel()
        if (dialog.isShowing) dialog.dismiss()
        onClose()
        Thread({
            channels.forEach { runCatching { it.close() } }
        }, "RProbeDesk-mirror-close").apply { isDaemon = true }.start()
    }

    private fun tracked(service: String): AdbStream {
        val stream = session.open(service)
        channels.add(stream)
        if (closed.get()) { stream.close(); throw CancellationException() }
        return stream
    }

    private suspend fun stream(surface: Surface) = coroutineScope {
        val id = java.security.SecureRandom().nextInt(Int.MAX_VALUE)
        val socket = String.format(Locale.ROOT, "localabstract:scrcpy_%08x", id)
        val remote = "/data/local/tmp/rprobedesk-${UUID.randomUUID()}.jar"
        try {
            activity.assets.open("scrcpy-server-v3.3.4").use { session.push(remote, it, null) }
            val options = "scid=${id.toString(16)} tunnel_forward=true audio=false max_size=1280 max_fps=30" +
                " video_bit_rate=3000000 send_device_meta=false send_dummy_byte=false clipboard_autosync=false"
            val server = tracked("shell:CLASSPATH=$remote app_process / com.genymobile.scrcpy.Server 3.3.4 $options")
            val diagnostics = launch {
                runInterruptible {
                    server.openInputStream().bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (line.contains("inject", true) && (line.contains("denied", true) ||
                                    line.contains("permission", true) || line.contains("Could not", true)))
                                activity.runOnUiThread {
                                    controlEnabled = false
                                    status.text = "${session.serial}  ·  ${tr("仅查看，目标限制输入")}"
                                }
                        }
                    }
                }
            }
            var videoChannel: AdbStream? = null
            for (attempt in 0 until 40) {
                ensureActive()
                if (server.isClosed) throw IOException("目标未能启动镜像服务")
                try { videoChannel = tracked(socket); break } catch (_: java.net.ConnectException) { delay(100) }
            }
            val frames = DataInputStream((videoChannel ?: error("目标视频通道未就绪")).openInputStream().buffered())
            val controller = tracked(socket)
            val inputWriter = launch {
                val output = controller.openOutputStream()
                for (message in controls) { output.write(message); output.flush() }
            }
            try {
                decode(frames, surface)
            } finally {
                diagnostics.cancel()
                inputWriter.cancel()
            }
        } finally {
            channels.forEach { runCatching { it.close() } }
            withContext(NonCancellable) { withTimeoutOrNull(2500) { runCatching { session.shell("rm -f $remote") } } }
        }
    }

    private suspend fun decode(input: DataInputStream, surface: Surface) = coroutineScope {
        check(input.readInt() == 0x68323634) { "目标 H.264 编码器不可用" }
        val firstW = input.readInt()
        val firstH = input.readInt()
        require(firstW in 1..8192 && firstH in 1..8192) { "视频尺寸无效" }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, firstW, firstH)
        format.setInteger(MediaFormat.KEY_MAX_WIDTH, maxOf(firstW, firstH))
        format.setInteger(MediaFormat.KEY_MAX_HEIGHT, maxOf(firstW, firstH))
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 shl 20)
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            codec.configure(format, surface, null, 0)
            codec.start()
            val output = launch {
                val info = MediaCodec.BufferInfo()
                var w = firstW
                var h = firstH
                var newSize = true
                while (isActive) {
                    val idx = codec.dequeueOutputBuffer(info, 20_000)
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        w = if (f.containsKey("crop-right")) f.getInteger("crop-right") - f.getInteger("crop-left") + 1 else f.getInteger(MediaFormat.KEY_WIDTH)
                        h = if (f.containsKey("crop-bottom")) f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1 else f.getInteger(MediaFormat.KEY_HEIGHT)
                        newSize = true
                    } else if (idx >= 0) {
                        codec.releaseOutputBuffer(idx, true)
                        if (newSize) {
                            withContext(Dispatchers.Main.immediate) {
                                releaseInputs()
                                width = w
                                height = h
                                resize()
                                if (controlEnabled) status.text = "${session.serial}  ·  ${w} × $h"
                                ready.complete(Unit)
                            }
                            newSize = false
                        }
                    }
                }
            }
            try {
                runInterruptible {
                    while (!Thread.interrupted()) {
                        val timestamp = input.readLong()
                        val length = input.readInt()
                        require(length in 1..(8 shl 20)) { "目标返回了无效的视频帧" }
                        val packet = ByteArray(length)
                        input.readFully(packet)
                        var idx: Int
                        do { if (Thread.interrupted()) throw InterruptedException(); idx = codec.dequeueInputBuffer(20_000) } while (idx < 0)
                        val buffer = checkNotNull(codec.getInputBuffer(idx))
                        buffer.clear()
                        require(length <= buffer.remaining()) { "视频帧超过主控解码缓冲区" }
                        buffer.put(packet)
                        val config = timestamp < 0
                        val flags = if (config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                        codec.queueInputBuffer(idx, 0, length, timestamp and 0x3fff_ffff_ffff_ffffL, flags)
                    }
                }
            } finally {
                withContext(NonCancellable) { output.cancelAndJoin() }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private fun resize() {
        if (width <= 0 || height <= 0 || area.width <= 0 || area.height <= 0) return
        val scale = minOf(area.width.toFloat() / width, area.height.toFloat() / height)
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        val params = video.layoutParams as FrameLayout.LayoutParams
        if (params.width != w || params.height != h) { params.width = w; params.height = h; video.layoutParams = params }
    }

    private fun submit(bytes: ByteArray) {
        if (!closed.get() && controlEnabled && width > 0 && !controls.trySend(bytes).isSuccess) {
            Toast.makeText(activity, tr("输入通道拥堵，请重新打开镜像"), Toast.LENGTH_SHORT).show()
            close()
        }
    }
    private fun keyEvent(action: Int, key: Int, repeat: Int = 0, meta: Int = 0) {
        submit(ByteBuffer.allocate(14).put(0).put(action.toByte()).putInt(key).putInt(repeat).putInt(meta).array())
    }
    private fun tapKey(key: Int) { keyEvent(0, key); keyEvent(1, key) }
    private fun touch(event: MotionEvent): Boolean {
        if (width < 1 || height < 1 || !controlEnabled) return false
        val action = event.actionMasked
        if (action !in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) return true
        if (action != MotionEvent.ACTION_DOWN && lastTouch == null) return true
        if (action == MotionEvent.ACTION_DOWN) video.requestFocus()
        val x = (event.x * width / video.width).toInt().coerceIn(0, width - 1)
        val y = (event.y * height / video.height).toInt().coerceIn(0, height - 1)
        sendTouch(action, x, y)
        lastTouch = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) null else x to y
        if (action == MotionEvent.ACTION_UP) video.performClick()
        return true
    }
    private fun sendTouch(action: Int, x: Int, y: Int) {
        val pressure = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) 0 else -1
        submit(ByteBuffer.allocate(32).put(2).put(action.toByte()).putLong(-2L).putInt(x).putInt(y)
            .putShort(width.toShort()).putShort(height.toShort()).putShort(pressure.toShort()).putInt(0).putInt(0).array())
    }
    private fun releaseInputs() {
        lastTouch?.let { sendTouch(MotionEvent.ACTION_UP, it.first, it.second) }
        lastTouch = null
        heldKeys.forEach { keyEvent(1, it) }
        heldKeys.clear()
    }
    private fun textDialog() {
        if (!controlEnabled || width == 0) return
        val field = EditText(context, null, 0, R.style.RProbeInput).apply { contentDescription = tr("发送到目标的文字") }
        AlertDialog.Builder(context, R.style.RProbeDialog).setTitle(uiMessage("粘贴到目标设备")).setView(field)
            .setPositiveButton(uiMessage("发送")) { _, _ ->
                val bytes = field.text.toString().toByteArray()
                if (bytes.size > 65500) Toast.makeText(activity, tr("文字过长"), Toast.LENGTH_SHORT).show()
                else submit(ByteBuffer.allocate(14 + bytes.size).put(9).putLong(0).put(1).putInt(bytes.size).put(bytes).array())
            }.setNegativeButton(uiMessage("取消"), null).show().styleActions()
    }
}
