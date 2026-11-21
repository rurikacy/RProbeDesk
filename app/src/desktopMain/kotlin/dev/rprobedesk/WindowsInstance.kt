package dev.rprobedesk

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.Win32Exception
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinError
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.EventQueue
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private interface WindowActivation : StdCallLibrary {
    fun SetPropW(window:HWND, name:WString, value:Pointer):Boolean
    fun GetPropW(window:HWND, name:WString):Pointer?
    fun RemovePropW(window:HWND, name:WString):Pointer?
    fun AllowSetForegroundWindow(pid:Int):Boolean
}

internal class WindowsInstance private constructor(private val event:HANDLE, private val marker:WString) : AutoCloseable {
    companion object {
        private val kernel=Kernel32.INSTANCE
        private val user=User32.INSTANCE
        private val activation=Native.load("user32",WindowActivation::class.java)

        fun acquire(profile:File):WindowsInstance? {
            val id=UUID.nameUUIDFromBytes(profile.canonicalPath.lowercase(Locale.ROOT).toByteArray())
            val name="Local\\RProbeDesk.Activate.$id"
            val marker=WString(name)
            val event=kernel.CreateEvent(null,false,false,name) ?: throw Win32Exception(Native.getLastError())
            if(Native.getLastError()!=WinError.ERROR_ALREADY_EXISTS) return WindowsInstance(event,marker)

            try {
                // The launcher receives foreground permission from Explorer. Pass it to the existing window's process.
                user.EnumWindows({ window,_ ->
                    if(activation.GetPropW(window,marker)==null) true else {
                        val pid=IntByReference()
                        user.GetWindowThreadProcessId(window,pid)
                        activation.AllowSetForegroundWindow(pid.value)
                        false
                    }
                },null)
                if(!kernel.SetEvent(event)) throw Win32Exception(Native.getLastError())
            } finally { kernel.CloseHandle(event) }
            return null
        }
    }

    fun listen(window:HWND,restore:()->Unit):AutoCloseable {
        if(!activation.SetPropW(window,marker,Pointer.createConstant(1))) throw Win32Exception(Native.getLastError())
        val active=AtomicBoolean(true)
        val waiter=thread(isDaemon=true,name="RProbeDesk window activation") {
            while(kernel.WaitForSingleObject(event,WinBase.INFINITE)==WinBase.WAIT_OBJECT_0 && active.get()) {
                EventQueue.invokeLater { if(active.get()) restore() }
            }
        }
        return AutoCloseable {
            active.set(false)
            kernel.SetEvent(event)
            waiter.join()
            activation.RemovePropW(window,marker)
        }
    }

    override fun close() { kernel.CloseHandle(event) }
}
