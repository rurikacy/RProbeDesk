package dev.rprobedesk

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.platform.win32.WinDef.HWND
import java.awt.EventQueue
import java.awt.Frame
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WindowsInstanceTest {
    @get:Rule val temp=TemporaryFolder()

    @Test fun repeatedLaunchWakesTheOwnerEvenBeforeItsWindowIsReady() {
        assumeTrue(Platform.isWindows())
        val profile=temp.newFolder()
        requireNotNull(WindowsInstance.acquire(profile)).use { owner ->
            assertNull(WindowsInstance.acquire(profile))
            val frame=Frame()
            EventQueue.invokeAndWait { frame.addNotify() }
            try {
                val wakes=LinkedBlockingQueue<Boolean>()
                owner.listen(HWND(Native.getWindowPointer(frame))) { wakes.offer(EventQueue.isDispatchThread()) }.use {
                    assertEquals(true,wakes.poll(5,TimeUnit.SECONDS))
                    assertNull(WindowsInstance.acquire(profile))
                    assertEquals(true,wakes.poll(5,TimeUnit.SECONDS))
                }
            } finally { EventQueue.invokeAndWait { frame.dispose() } }
        }
        requireNotNull(WindowsInstance.acquire(profile)).close()
    }
}
