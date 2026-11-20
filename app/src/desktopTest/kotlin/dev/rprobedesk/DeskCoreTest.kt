package dev.rprobedesk

import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class DeskCoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun manualEndpoints() {
        assertEquals("192.168.1.2",normalizedIp("192.168.001.2"))
        assertEquals(normalizedIp("::1"),normalizedIp("[::1]"))
        assertEquals("fe80:0:0:0:0:0:0:1%wlan0",normalizedIp("fe80::1%wlan0"))
        for (value in listOf("example.com","256.0.0.1","127.0.0.1:5555","a:::b","::1%")) {
            assertFailsWith<IllegalArgumentException> { normalizedIp(value) }
        }
        assertEquals(65535,parsePort("65535"))
        for (value in listOf("","0","+1","65536")) assertFailsWith<IllegalArgumentException> { parsePort(value) }
    }

    @Test fun independentMetricSources() {
        val first = parseMetrics("cpu 10 0 10 100 10 0 0 0 900\n",null,10)
        assertEquals("—",first.cpu)
        val sample = parseMetrics("cpu 30 0 25 160 15 0 0 0 9999\nMemTotal: 8388608 kB\nMemAvailable: 3145728 kB\n  temperature: 318", first.ticks,20)
        assertEquals("35.0 %",sample.cpu)
        assertEquals("5120 / 8192 MiB",sample.memory)
        assertTrue(sample.memoryRatio.startsWith("62.5%"))
        assertEquals("31.8 °C",sample.temperature)
        val reset = parseMetrics("cpu 5 0 100 300 15 0 0 0\n",sample.ticks,30)
        assertEquals("—",reset.cpu)
        assertEquals("—",reset.memory)
        val fallback = parseMetrics("  8.7% TOTAL: 4.7% user + 4% kernel",null,40)
        assertEquals("8.7 %",fallback.cpu)
        assertEquals("—",fallback.temperature)
    }

    @Test fun recordsAndFavoritesSurviveRestartAndSeparateEditors() {
        val dir = temp.newFolder()
        val otherWindow = DeskPreferences(dir)
        val first = DeskPreferences(dir)
        val id = first.saveDevice(null,"测试机","192.168.1.1",5555)
        otherWindow.saveCommand(null,"多行","echo hello\necho 安卓")
        assertEquals(id, first.saveDevice(null,"重复","192.168.1.1",5555))
        first.markConnected(id)
        // Version 1.0 stored a theme preference in this same file.
        File(dir,"desk.properties").appendText("\ndark=true\n")
        val read = DeskPreferences(dir)
        assertEquals(id,read.selectedId)
        assertEquals("测试机",read.devices.single().name)
        assertTrue(read.devices.single().lastUsed > 0)
        assertEquals("echo hello\necho 安卓",read.commands.single().command)
        read.saveDevice(id,"改名","192.168.1.1",45000)
        assertEquals(45000,read.devices.single().port)
        read.removeDevice(id)
        read.removeCommand(read.commands.single().id)
        val empty=DeskPreferences(dir)
        assertTrue(empty.devices.isEmpty() && empty.commands.isEmpty())
    }

    @Test fun incompatibleSettingsPreserved() {
        val dir = temp.newFolder()
        val file = File(dir,"desk.properties")
        file.writeText("version=99\nfuture=original\n")
        val before=file.readBytes()
        val state=DeskPreferences(dir)
        assertNotNull(state.error)
        assertFails { state.saveCommand(null,"不可写入","echo test") }
        assertContentEquals(before,file.readBytes())
    }

    @Test fun verifyPeerBeforeEnablingControls() {
        val basic="model=Tablet\nrelease=14\nuser=0\nsdk=34\nabi=arm64-v8a\n"
        val first=parseProbe(basic+"serial=device-A\nandroidId=12345678")
        val old=DeskDevice("local","测试平板","10.0.0.2",5555,1,
            first.digest,first.model,"10.0.0.2:5555")
        assertEquals(64, first.digest!!.length)
        assertNull(first.review(old,true))
        assertNotNull(parseProbe(basic+"serial=device-B\nandroidId=abcdef12").review(old,true))
        assertNotNull(first.review(old.copy(port=40001),true))
        assertNotNull(parseProbe(basic).review(old,true))
        assertFails { parseProbe(basic.replace("user=0","user=Permission denied")) }
        assertFalse(first.facts.toString().contains("12345678"))
        assertFalse(probeCommand(false).contains("android_id"))
    }
}
