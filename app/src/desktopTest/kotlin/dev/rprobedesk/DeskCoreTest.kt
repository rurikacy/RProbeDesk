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
        assertNull(first.cpu)
        val sample = parseMetrics("cpu 30 0 25 160 15 0 0 0 9999\nMemTotal: 8388608 kB\nMemAvailable: 3145728 kB\n  temperature: 318", first.ticks,20)
        assertEquals(35.0, sample.cpu!!, 0.001)
        assertEquals("已用 5.4 GB · 总计 8.6 GB",sample.memory)
        val mixed=parseMetrics("MemTotal: 976563 kB\nMemAvailable: 1 kB",null,0)
        assertEquals("已用 1000.0 MB · 总计 1.0 GB",mixed.memory)
        assertEquals(62.5, sample.memoryPercent)
        assertEquals("31.8 °C",sample.temperature)
        val reset = parseMetrics("cpu 5 0 100 300 15 0 0 0\n",sample.ticks,30)
        assertNull(reset.cpu)
        assertEquals("—",reset.memory)
        val fallback = parseMetrics("  8.7% TOTAL: 4.7% user + 4% kernel",null,40)
        assertEquals(8.7,fallback.cpu)
        assertEquals("—",fallback.temperature)
        val board = parseMetrics("gpu=17@300000000Hz\nload=10.12\ncores=8\ndisk=32%\ndiskTotalKiB=10485760\ndiskUsedKiB=3355443\nlevel: 8\nscale: 10\nstatus: 2",null,50)
        assertEquals(17.0,board.gpu)
        assertEquals(10.12,board.load)
        assertEquals(8,board.cores)
        assertEquals(32.0,board.disk)
        assertEquals("已用 3.2 GiB · 总计 10.0 GiB",board.diskCapacity)
        assertNull(parseMetrics("disk=32%",null,0).diskCapacity)
        assertEquals("80% · 充电中",board.battery)
        assertNull(parseMetrics("gpu=unavailable\ndisk=101%",null,60).disk)
    }

    @Test fun recordsAndFavoritesSurviveRestartAndSeparateEditors() {
        val dir = temp.newFolder()
        val otherWindow = DeskPreferences(dir)
        val first = DeskPreferences(dir)
        val id = first.saveDevice(null,"测试机","192.168.1.1",5555,"Lab WiFi","一楼")
        otherWindow.saveCommand(null,"多行","echo hello\necho 安卓")
        assertEquals(id, first.saveDevice(null,"重复","192.168.1.1",5555))
        first.markConnected(id)
        // Version 1.0 stored a theme preference in this same file.
        File(dir,"desk.properties").appendText("\ndark=true\n")
        val read = DeskPreferences(dir)
        assertEquals(id,read.selectedId)
        assertEquals("测试机",read.devices.single().name)
        assertEquals("Lab WiFi",read.devices.single().wifi)
        assertEquals("一楼",read.devices.single().site)
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

    @Test fun filteredDeviceOrderSurvivesConcurrentEditsAndReconnect() {
        val dir = temp.newFolder()
        val prefs = DeskPreferences(dir)
        val a = prefs.saveDevice(null, "A", "10.0.0.1", 5555)
        val b = prefs.saveDevice(null, "B", "10.0.0.2", 5555)
        val c = prefs.saveDevice(null, "C", "10.0.0.3", 5555)
        val d = prefs.saveDevice(null, "D", "10.0.0.4", 5555)
        val editor = DeskPreferences(dir)
        editor.removeDevice(d)
        val extra = editor.saveDevice(null, "E", "10.0.0.5", 5555)

        prefs.reorderDevices(listOf(c, d, a))
        assertEquals(listOf(c, b, a, extra), prefs.devices.map { it.id })
        assertEquals(extra, prefs.selectedId)
        prefs.markConnected(a)
        prefs.saveDevice(b, "Z", "10.0.0.2", 5555)
        val new1 = prefs.saveDevice(null, "Y", "10.0.0.6", 5555)
        val new2 = prefs.saveDevice(null, "X", "10.0.0.7", 5555)
        assertEquals(listOf(c, b, a, extra, new1, new2), DeskPreferences(dir).devices.map { it.id })
    }

    @Test fun favoriteOrderStaysLocalAcrossSyncRenameAndRestart() {
        val dir = temp.newFolder()
        val prefs = DeskPreferences(dir)
        prefs.saveCommand("b", "B", "second")
        prefs.saveCommand("a", "A", "first")
        assertEquals(listOf("a", "b"), prefs.commands.map { it.id })
        val portable = prefs.exportSync()
        prefs.reorderCommands(listOf("b", "a"))
        assertEquals(portable, prefs.exportSync())

        val remote = prefs.exportSync().apply {
            setProperty("command.z.name", "Z")
            setProperty("command.z.text", "new from sync")
        }
        prefs.applySync(prefs.inspectSync(remote), portable)
        prefs.saveCommand("b", "renamed", "edited")
        prefs.saveCommand("c", "C", "new locally")
        assertEquals(listOf("b", "a", "z", "c"), DeskPreferences(dir).commands.map { it.id })
    }

    @Test fun deviceGroupingUsesCurrentTagsAndOnlyMovesVisibleMembersOfTheGroup() {
        val dir=temp.newFolder()
        val prefs=DeskPreferences(dir)
        val a=prefs.saveDevice(null,"A","10.0.0.1",5555,"Guest","North")
        val b=prefs.saveDevice(null,"B","10.0.0.2",5555,"Guest","East")
        val c=prefs.saveDevice(null,"C","10.0.0.3",5555,"Lab","North")
        val d=prefs.saveDevice(null,"D","10.0.0.4",5555,"","North")
        val e=prefs.saveDevice(null,"E","10.0.0.5",5555,"Lab","")
        fun sections()=prefs.deviceGrouping.sections(prefs.devices).mapValues { (_,rows) -> rows.map { it.id } }

        assertEquals(DeviceGrouping.NONE,prefs.deviceGrouping)
        assertEquals(mapOf("" to listOf(a,b,c,d,e)),sections())
        prefs.setDeviceGrouping(DeviceGrouping.SITE)
        assertEquals(listOf("East","North",""),sections().keys.toList())
        // B belongs to East and C is hidden by the current search.
        prefs.reorderDevices(listOf(d,b,a),DeviceGrouping.SITE,"North")
        assertEquals(listOf(d,b,c,a,e),prefs.devices.map { it.id })
        val copy=DeskPreferences(temp.newFolder())
        copy.applySync(copy.inspectSync(prefs.exportSync()),copy.exportSync())
        assertEquals(DeviceGrouping.SITE,copy.deviceGrouping)
        assertEquals(listOf(d,b,c,a,e),copy.devices.map { it.id })
        assertEquals(DeviceGrouping.SITE,DeskPreferences(dir).deviceGrouping)

        DeskPreferences(dir).saveDevice(a,"A","10.0.0.1",5555,"Lab","East")
        prefs.reorderDevices(listOf(a,d),DeviceGrouping.SITE,"North")
        assertEquals(listOf(d,b,c,a,e),prefs.devices.map { it.id })
        assertEquals(mapOf("East" to listOf(b,a),"North" to listOf(d,c),"" to listOf(e)),sections())
        prefs.setDeviceGrouping(DeviceGrouping.WIFI)
        assertEquals(mapOf("Guest" to listOf(b),"Lab" to listOf(c,a,e),"" to listOf(d)),sections())
        prefs.reorderDevices(listOf(e,c),DeviceGrouping.WIFI,"Lab")
        assertEquals(listOf(d,b,e,a,c),prefs.devices.map { it.id })
        prefs.setDeviceGrouping(DeviceGrouping.NONE)
        assertEquals(listOf(d,b,e,a,c),DeskPreferences(dir).devices.map { it.id })
    }

    @Test fun syncValidatesLayoutAndPreservesItWhenReadingLegacyProfiles() {
        val prefs=DeskPreferences(temp.newFolder())
        val a=prefs.saveDevice(null,"A","192.0.2.1",5555)
        val b=prefs.saveDevice(null,"B","192.0.2.2",5555)
        val initial=prefs.exportSync()
        prefs.markConnected(b)
        val recent=prefs.exportSync()
        assertEquals("0",recent.getProperty("device.$b.order"))
        assertEquals("none",recent.getProperty("deviceGrouping"))
        assertFails { prefs.applySync(prefs.inspectSync(initial),initial) }
        prefs.reorderDevices(listOf(a,b))
        val manual=prefs.exportSync()
        prefs.setDeviceGrouping(DeviceGrouping.WIFI)
        assertFails { prefs.applySync(prefs.inspectSync(recent),manual) }
        for((key,value) in listOf("deviceGrouping" to "unknown","device.$b.order" to "0","device.$a.order" to "-1")) {
            val invalid=prefs.exportSync().apply { setProperty(key,value) }
            assertFails { prefs.inspectSync(invalid) }
        }
        assertFails { prefs.inspectSync(prefs.exportSync().apply { remove("device.$a.order") }) }
        val legacy=prefs.exportSync().apply {
            setProperty("format","RProbeDesk/1")
            remove("deviceGrouping")
            remove("device.$a.order")
            remove("device.$b.order")
            setProperty("device.$a.name","Z")
        }
        prefs.applySync(prefs.inspectSync(legacy),prefs.exportSync())
        assertEquals(DeviceGrouping.WIFI,prefs.deviceGrouping)
        assertEquals(listOf(a,b),prefs.devices.map { it.id })
        assertEquals("Z",prefs.devices.first().name)
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
