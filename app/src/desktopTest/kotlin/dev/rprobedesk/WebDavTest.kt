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
            source.markConnected(id,"local-proof","model","10.0.0.1:5555")
            source.saveCommand(null,"分辨率","wm size")
            if(System.getProperty("os.name").startsWith("Windows")) {
                source.saveDav(config)
                assertEquals("secret",source.davPassword())
            }
            dav.test()
            assertFails { WebDav(config.copy(password="wrong")).test() }
            assertFails { dav.download() }
            val records=source.exportCloud()
            dav.upload(records); dav.upload(records)
            assertTrue("secret" !in payload.decodeToString() && "local-proof" !in payload.decodeToString())
            val dest=DeskPreferences(folder.newFolder())
            dest.setCloseToTray(false)
            dest.importCloud(dav.download(),dest.exportCloud())
            assertEquals(records,dest.exportCloud())
            assertFalse(dest.closeToTray)
            assertNull(dest.devices.single().deviceDigest)
            assertEquals("Office",dest.devices.single().wifi)
            assertEquals("wm size",dest.commands.single().command)
            assertEquals("GET" to "/dav/测试/组 A/现场/workspace.properties",requests.last())
            dest.saveCommand(null,"本地","id")
            assertFails { dest.importCloud(records,records) }
            val before=dest.exportCloud()
            val invalid=Properties().apply { putAll(records); setProperty("device.$id.port","bad") }
            assertFails { dest.importCloud(invalid,before) }
            assertEquals(before,dest.exportCloud())
        } finally { server.stop(0) }
    }
}
