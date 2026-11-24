package dev.rprobedesk

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Properties
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class WebDavTest {
    @get:Rule val folder=TemporaryFolder()

    @Test fun authenticatedRoundTripKeepsHostSettingsAndTrustLocal() {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val directories=mutableSetOf("/dav/")
        val requests=mutableListOf<Pair<String,String>>()
        var payload=byteArrayOf()
        server.createContext("/dav/") { ex ->
            val path=ex.requestURI.path
            requests.add(ex.requestMethod to path)
            val code=if(ex.requestHeaders.getFirst("Authorization")!="Basic YWRtaW46c2VjcmV0") 401 else when(ex.requestMethod) {
                "PROPFIND" -> if(path in directories && ex.requestHeaders.getFirst("Depth")=="0") 207 else 404
                "MKCOL" -> if(directories.add(path)) 201 else 405
                "PUT" -> { payload=ex.requestBody.readBytes(); 201 }
                "GET" -> if(payload.isNotEmpty()) 200 else 404
                else -> 405
            }
            val bytes=if(code==200) payload else byteArrayOf()
            ex.sendResponseHeaders(code,if(bytes.isEmpty()) -1 else bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        server.start()
        try {
            val config=DavConfig("http://127.0.0.1:${server.address.port}/dav/","admin","secret","测试/组 A","现场")
            val dav=WebDav(config)
            val source=DeskPreferences(folder.newFolder())
            val id=source.saveDevice(null,"样机","10.0.0.1",5555,"Office","实验室")
            val second=source.saveDevice(null,"备用","10.0.0.2",5555,"Guest","实验室")
            source.markConnected(id,"local-proof","model","10.0.0.1:5555")
            source.setDeviceGrouping(DeviceGrouping.SITE)
            source.reorderDevices(listOf(second,id),DeviceGrouping.SITE,"实验室")
            val resolution="resolution"
            source.saveCommand(resolution,"分辨率","wm size","常用")
            val density="density"
            source.saveCommand(density,"密度","wm density","常用")
            source.reorderCommands(listOf(density,resolution),"常用")
            if(System.getProperty("os.name").startsWith("Windows")) {
                source.saveDav(config)
                assertEquals("secret",source.davPassword())
            }
            dav.test()
            assertFails { WebDav(config.copy(password="wrong")).test() }
            assertFails { dav.download() }
            val records=source.exportSync()
            dav.upload(records); dav.upload(records)
            assertTrue("secret" !in payload.decodeToString() && "local-proof" !in payload.decodeToString())
            val dest=DeskPreferences(folder.newFolder())
            dest.setCloseToTray(false)
            dest.applySync(dest.inspectSync(dav.download()),dest.exportSync())
            assertEquals(records,dest.exportSync())
            assertFalse(dest.closeToTray)
            assertEquals(DeviceGrouping.SITE,dest.deviceGrouping)
            assertEquals(listOf(second,id),dest.devices.map { it.id })
            assertNull(dest.devices.last().deviceDigest)
            assertEquals("Office",dest.devices.last().wifi)
            assertEquals(listOf(density,resolution),dest.commands.map { it.id })
            assertEquals("wm size",dest.commands.last().command)
            assertEquals("GET" to "/dav/测试/组 A/现场/workspace.properties",requests.last())
            dest.reorderCommands(listOf(resolution,density),"常用")
            assertFails { dest.applySync(dest.inspectSync(records),records) }
            val legacy=Properties().apply {
                putAll(records)
                setProperty("format","RProbeDesk/2")
                remove("command.$density.order")
                remove("command.$resolution.order")
            }
            dest.applySync(dest.inspectSync(legacy),dest.exportSync())
            assertEquals(listOf(resolution,density),dest.commands.map { it.id })
            assertFails { dest.inspectSync(Properties().apply {
                putAll(records)
                remove("command.$resolution.order")
            }) }
            val before=dest.exportSync()
            val invalid=Properties().apply { putAll(records); setProperty("device.$id.port","bad") }
            assertFails { dest.inspectSync(invalid) }
            assertEquals(before,dest.exportSync())
        } finally { server.stop(0) }
    }
}
