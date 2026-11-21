@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.rprobedesk

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class DeskUiTest {
    @get:Rule val temp = TemporaryFolder()
    private val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Tab)

    private fun ComposeUiTest.key(key: Key) {
        onAllNodes(isRoot()).onLast().performKeyInput { pressKey(key) }
        waitForIdle()
    }

    private fun ComposeUiTest.tabTo(target: SemanticsMatcher) {
        repeat(40) {
            if (onAllNodes(target and isFocused()).fetchSemanticsNodes().isNotEmpty()) return
            key(Key.Tab)
        }
        fail("Tab 未能到达 ${target.description}，当前焦点 ${onAllNodes(isFocused()).fetchSemanticsNodes().map { it.config }}")
    }

    @Test fun compactLargeTypeKeyboardWorkflows() = runDesktopComposeUiTest(width=360,height=760) {
        val host=FixtureHost(temp.newFolder())
        DeskPreferences(host.settingsDir).apply {
            saveDevice(null,"实验室用于大字号测试的开发设备","2001:db8:abcd:ef01:2345:6789:abcd:ef01",40321)
            saveCommand(null,"运行样例","echo fixture")
        }
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,2f)) { DeskApp(host) }
        }
        deskPages.forEach { name -> onNode(tab and hasText(name)).assertIsDisplayed() }
        val button=SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Button)
        val add=button and hasText("添加")
        val before=onNode(add).captureToImage().toAwtImage()
        tabTo(add)
        val after=onNode(add).captureToImage().toAwtImage()
        assertNotEquals(before.getRGB(1,before.height/2), after.getRGB(1,after.height/2), "主要按钮聚焦时应显示清晰的边线")
        val output=File("build/reports/desk-ui").apply { mkdirs() }
        check(ImageIO.write(onRoot().captureToImage().toAwtImage(),"png",File(output,"workbench-360-200pct.png")))
        key(Key.Enter)
        onNodeWithText("添加设备").assertIsDisplayed()
        onNodeWithContentDescription("设备备注").assertIsFocused()
        tabTo(button and hasText("取消"))
        key(Key.Spacebar)
        onNodeWithText("添加设备").assertDoesNotExist()
        onNode(add).assertIsFocused()
        val connect=button and hasText("连接")
        tabTo(connect)
        onNode(connect).assertIsDisplayed()
        key(Key.Enter)
        waitUntil(timeoutMillis=6000) { host.connects==1 && onAllNodes(hasText("5120 / 8192 MiB")).fetchSemanticsNodes().isNotEmpty() }
        tabTo(tab and hasText("Shell"))
        key(Key.Spacebar)
        val command=hasContentDescription("设备 Shell 命令")
        tabTo(command)
        onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        onNode(command).assertIsNotFocused()
        tabTo(button and hasText("运行") and isEnabled())
        key(Key.Enter)
        waitUntil(timeoutMillis=6000) { host.favorites==1 }
        tabTo(tab and hasText("应用"))
        key(Key.Enter)
        waitUntil(timeoutMillis=6000) { onAllNodes(hasText("dev.rprobedesk.fixture")).fetchSemanticsNodes().isNotEmpty() }
        tabTo(button and hasText("更多"))
        key(Key.Enter)
        tabTo(button and hasText("卸载"))
        key(Key.Enter)
        waitUntil(timeoutMillis=6000) { onAllNodes(hasText("卸载 dev.rprobedesk.fixture")).fetchSemanticsNodes().isNotEmpty() }
        onNode(button and hasText("取消") and isFocused()).assertIsDisplayed()
        key(Key.Enter)
        waitUntil(timeoutMillis=6000) { onAllNodes(hasText("已取消")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0,host.uninstalls)
    }

    @Test fun boundaryKeepsSidebarAt760() = runDesktopComposeUiTest(width=760,height=500) {
        setContent { DeskApp(FixtureHost(temp.root)) }
        deskPages.forEach { name ->
            val edge=onNode(tab and hasText(name)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.right
            assertTrue(edge<=184f)
        }
    }

    @Test fun optionalPairCodeAndDialogAlignment() = runDesktopComposeUiTest(width=1120,height=800) {
        val host=FixtureHost(temp.newFolder())
        setContent { DeskApp(host) }
        onNodeWithText("未连接设备").assertHasNoClickAction()
        onNodeWithText("添加").performClick()
        val save=onNodeWithText("保存").fetchSemanticsNode().boundsInRoot
        val cancel=onNodeWithText("取消").fetchSemanticsNode().boundsInRoot
        assertEquals(save.top,cancel.top)
        assertEquals(save.height,cancel.height)
        onNodeWithText("取消").performClick()
        onNodeWithText("配对").performClick()
        onNodeWithContentDescription("IP 地址").performTextInput("10.0.0.12")
        onNodeWithContentDescription("连接端口").performTextInput("5555")
        onNodeWithContentDescription("配对码（可选）").performTextInput("123")
        onAllNodesWithText("配对").onLast().performClick()
        onNodeWithText("配对码应为六位数字").assertIsDisplayed()
        onNodeWithContentDescription("配对码（可选）").performTextClearance()
        onNodeWithText("保存并连接").performClick()
        waitUntil(timeoutMillis=6000) { host.connects==1 && onAllNodes(hasText("Fixture")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("10.0.0.12:5555").assertHasNoClickAction()
        assertEquals("10.0.0.12:5555",DeskPreferences(host.settingsDir).devices.single().address)
    }

    @Test fun lightWorkbenchAndDeviceSearch() = runDesktopComposeUiTest(width=1280,height=850) {
        val host=FixtureHost(temp.newFolder())
        DeskPreferences(host.settingsDir).apply {
            saveDevice(null,"实验室平板","10.0.0.12",37000)
            saveDevice(null,"界面回归机","10.0.0.18",40321)
            saveDevice(null,"移动开发机","10.0.0.24",41257)
            saveCommand(null,"测试样例","echo fixture")
        }
        File(host.settingsDir,"desk.properties").appendText("\ndark=true\n")
        setContent { DeskApp(host) }
        val image=onRoot().captureToImage().toAwtImage()
        assertEquals(DeskSidebar.toArgb(),image.getRGB(0,0))
        assertEquals(DeskSurface.toArgb(),image.getRGB(190,0))
        assertEquals(DeskBackground.toArgb(),image.getRGB(190,60))
        onNodeWithContentDescription("切换至暗色").assertDoesNotExist()
        onNodeWithContentDescription("切换至亮色").assertDoesNotExist()
        onNodeWithText("连接设备").assertDoesNotExist()
        listOf("设备","面板","应用","文件","Shell","镜像","日志").forEach {
            val row=onNode(tab and hasText(it)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(row.right<=184f)
        }
        onNodeWithText("界面回归机").assertIsDisplayed()
        onAllNodesWithText("工作空间").assertCountEquals(2)
        onNodeWithText("打开镜像").assertIsNotEnabled()
        val output=File("build/reports/desk-ui").apply { mkdirs() }
        check(ImageIO.write(image,"png",File(output,"workbench-desktop.png")))
        onNodeWithContentDescription("搜索设备").performTextInput("37000")
        onNodeWithText("实验室平板").assertIsDisplayed()
        onNodeWithText("界面回归机").assertDoesNotExist()
        onNode(tab and hasText("在线")).performClick()
        onNodeWithText("没有匹配的设备").assertIsDisplayed()
    }
    @Test fun narrowConnectFavoriteMirrorAndDisconnect() = runDesktopComposeUiTest(width=390,height=850) {
        val host=FixtureHost(temp.newFolder())
        DeskPreferences(host.settingsDir).apply {
            saveDevice(null,"实验平板","10.0.0.12",37000)
            saveCommand(null,"运行样例","echo fixture")
        }
        setContent { DeskApp(host) }
        for(page in listOf("设备","面板","应用","文件","Shell","镜像","日志")) onNode(tab and hasText(page)).assertIsDisplayed()
        onNodeWithText("实验平板").assertIsDisplayed()
        val output=File("build/reports/desk-ui").apply { mkdirs() }
        check(ImageIO.write(onRoot().captureToImage().toAwtImage(),"png",File(output,"workbench-compact.png")))

        onAllNodes(hasText("连接") and hasClickAction()).onLast().performClick()
        waitUntil(timeoutMillis=6000) { host.connects==1 && onAllNodes(hasText("5120 / 8192 MiB")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("5120 / 8192 MiB").assertExists()
        onNode(tab and hasText("Shell")).performClick()
        onNode(hasText("运行") and isEnabled() and hasClickAction()).performClick()
        waitUntil(timeoutMillis=6000) { host.favorites==1 }

        onNode(tab and hasText("设备")).performClick()
        onNode(hasText("打开镜像") and SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Button)).performClick()
        assertEquals(1,host.mirrors)
        host.online=false
        runOnIdle { host.foreground.value=false }
        waitForIdle()
        runOnIdle { host.foreground.value=true }
        waitUntil(timeoutMillis=6000) { host.closed }
        onNode(hasText("打开镜像") and SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Button)).assertIsNotEnabled()
    }

    private class FixtureHost(override val settingsDir:File):HostBridge {
        override val cacheDir=settingsDir
        override val desktop=true
        override val reducedMotion=MutableStateFlow(true)
        override val foreground=MutableStateFlow(true)
        override val mirrorActive=MutableStateFlow(false)
        @Volatile var connects=0
        @Volatile var favorites=0
        @Volatile var uninstalls=0
        @Volatile var mirrors=0
        @Volatile var closed=false
        @Volatile var online=true
        override suspend fun pair(address:String,port:Int,code:String)=error("unexpected pair")
        override suspend fun connect(address:String,port:Int):DeviceSession {
            connects++
            return object:DeviceSession {
                override val serial="$address:$port"
                override suspend fun shell(command:String,onLine:((String,Boolean)->Unit)?):ShellResult {
                    check(online)
                    val output=when {
                        command==probeCommand(true) -> "model=Fixture\nrelease=14\nsdk=34\nabi=x86_64\nuser=0\nserial=fixture-A"
                        command==metricCommand -> "MemTotal: 8388608 kB\nMemAvailable: 3145728 kB\ncpu 20 0 3 50 5\n temperature: 318"
                        command=="echo RProbeDesk" -> "RProbeDesk"
                        command=="echo fixture" -> { favorites++; "fixture" }
                        command.startsWith("(\nam get-current-user\n") -> "0\n__RPD_STATUS__0\n"
                        command.startsWith("(\npm list packages --show-versioncode ") -> "package:dev.rprobedesk.fixture versionCode:1\n__RPD_STATUS__0\n"
                        command.startsWith("(\npm list packages -") -> "\n__RPD_STATUS__0\n"
                        command.startsWith("cmd package query-activities ") -> "dev.rprobedesk.fixture/.Demo"
                        command.contains("pm uninstall") -> { uninstalls++; "\n__RPD_STATUS__0\n" }
                        else -> error("unexpected command: $command")
                    }
                    return ShellResult(output,"",0)
                }
                override suspend fun pull(path:String,output:OutputStream,progress:(Long)->Unit)=error("unexpected pull")
                override suspend fun push(path:String,input:InputStream,size:Long?,progress:(Long)->Unit)=error("unexpected push")
                override suspend fun install(apk:File,userId:Int)=error("unexpected install")
                override suspend fun screenshot(output:OutputStream)=error("unexpected screenshot")
                override fun close() { closed=true }
            }
        }
        override suspend fun pickOpen(suffix:String):LocalDoc?=null
        override suspend fun pickSave(name:String):LocalDoc?=null
        override suspend fun selectTool(tool:String) {}
        override fun toolPath(tool:String):String?=null
        override suspend fun startMirror(session:DeviceSession) { mirrors++; mirrorActive.value=true }
        override fun stopMirror() { mirrorActive.value=false }
        override fun copy(text:String) {}
    }
}
