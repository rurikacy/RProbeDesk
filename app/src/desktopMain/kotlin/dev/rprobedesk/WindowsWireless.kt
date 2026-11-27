package dev.rprobedesk

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.prefs.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.awt.Frame
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

internal suspend fun confirmWindowsDevice(owner: Frame, endpoint: String): String? =
    withTimeout(90_000) {
        suspendCancellableCoroutine { pending ->
            SwingUtilities.invokeLater {
                if (!pending.isActive) return@invokeLater
                val pane = JOptionPane(
                    "$endpoint\n" + tr("请输入目标设备通知中的八位确认码"),
                    JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION
                ).apply { wantsInput = true }
                val dialog = pane.createDialog(owner, tr("核对目标设备"))
                pending.invokeOnCancellation { SwingUtilities.invokeLater { dialog.dispose() } }
                try {
                    if (pending.isActive) dialog.isVisible = true
                    val value = if (pane.value == JOptionPane.OK_OPTION) pane.inputValue as? String else null
                    if (pending.isActive) pending.resumeWith(Result.success(value?.trim()))
                } finally { dialog.dispose() }
            }
        }
    }

internal class WindowsWireless(
    private val confirm: suspend (String) -> String?,
    private val pins: Preferences = Preferences.userRoot().node("dev/rprobedesk/verified-peers"),
) {
    private val paired = mutableMapOf<String, Long>()
    @Synchronized fun paired(address: String) { paired[address] = System.nanoTime() }
    @Synchronized private fun recentPair(address: String): Boolean =
        paired.remove(address)?.let { System.nanoTime() - it in 0..300_000_000_000L } == true

    suspend fun verify(session: WindowsSession, address: String, expectedPeer: String?) {
        val previous = pins.get(session.serial, null)
        val expected = expectedPeer?.takeIf { it.startsWith("tls:") }
        val newPair = expectedPeer == null && recentPair(address)
        val pinned = expected ?: previous?.takeUnless { newPair }
        withContext(Dispatchers.IO) {
            val dir = System.getenv("ANDROID_USER_HOME")?.let(::File)
                ?: File(System.getenv("ANDROID_SDK_HOME") ?: System.getProperty("user.home"), ".android")
            // Rust's SocketAddr accepts a numeric IPv6 scope ID. The saved address may use its interface name.
            val zone = address.removeSurrounding("[","]").substringAfter('%',"")
            val endpoint = if (zone.isEmpty() || zone.toIntOrNull() != null) session.serial else {
                val nic = NetworkInterface.getByName(zone)
                    ?: NetworkInterface.getNetworkInterfaces().asSequence().firstOrNull { it.displayName == zone }
                    ?: throw java.io.IOException("未找到 IPv6 网络接口 $zone")
                session.serial.replace("%$zone]", "%${nic.index}]")
            }
            val handle = NativeCore.directConnect(endpoint, dir.absolutePath, pinned.orEmpty())
            if (handle != 0L) session.attachWireless(handle)
            currentCoroutineContext().ensureActive()
        }
        val peer = session.verifiedPeerKey
        val confirmPeer = needsDeviceConfirmation(expectedPeer,peer,previous,false,newPair,requirePair = false)
        if (peer == null || previous == peer) return
        if (confirmPeer) {
            val nonce = SecureRandom().nextInt(100_000_000).toString().padStart(8, '0')
            session.checked("cmd notification post -t RProbeDesk RProbeDesk-confirm $nonce")
            if (confirm(session.serial) != nonce) throw PeerRejected("设备确认未通过，请重新核对目标通知")
        }
        pins.put(session.serial, peer)
        pins.flush()
    }
}

/** The native reader dispatches all channels on the one authenticated TLS connection. */
internal suspend fun <T> wirelessService(handle: Long, name: String, block: (InputStream, OutputStream) -> T): T =
    suspendCancellableCoroutine { pending ->
        val active = AtomicLong()
        fun close() { active.getAndSet(0).takeIf { it != 0L }?.let { NativeCore.directCloseStream(handle, it) } }
        pending.invokeOnCancellation { close() }
        processIo.execute {
            val result = runCatching {
                if (!pending.isActive) throw java.util.concurrent.CancellationException()
                val channel = NativeCore.directOpen(handle, name)
                active.set(channel)
                try {
                    if (!pending.isActive) throw java.util.concurrent.CancellationException()
                    val input = object : InputStream() {
                        private val one = ByteArray(1)
                        override fun read() = if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
                        override fun read(b:ByteArray, off:Int, len:Int) = if (len == 0) 0 else
                            NativeCore.directRead(handle,channel,b,off,minOf(len,65536))
                    }
                    val output = object : OutputStream() {
                        override fun write(b:Int) = write(byteArrayOf(b.toByte()),0,1)
                        override fun write(b:ByteArray, off:Int, len:Int) {
                            require(off >= 0 && len >= 0 && off <= b.size-len)
                            var pos = off
                            while (pos < off + len) {
                                val count = minOf(65536,off + len - pos)
                                NativeCore.directWrite(handle,channel,b,pos,count)
                                pos += count
                            }
                        }
                    }
                    block(input, output)
                } finally { close() }
            }
            if (pending.isActive) pending.resumeWith(result)
        }
    }
