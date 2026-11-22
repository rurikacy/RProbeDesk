@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.rprobedesk

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.SystemTheme
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

    private fun SemanticsNodeInteraction.fillPixel(): Int {
        val image=captureToImage().toAwtImage()
        return image.getRGB(6,image.height/2)
    }

    @Test fun reducedMotionShowsMouseAndKeyboardPressBeforeNavigation() = runDesktopComposeUiTest(width=1280,height=850) {
        setContent { DeskApp(FixtureHost(temp.root)) }
        val shell=onNode(tab and hasText("Shell"))
        val devices=onNode(tab and hasText("设备"))
        val go=onNodeWithText("前往设备")
        shell.performClick()
        go.performMouseInput { moveTo(center) }
        val hovered=go.fillPixel()
        mainClock.autoAdvance=false
        go.performMouseInput { press() }
        mainClock.advanceTimeByFrame()
        shell.assertIsSelected()
        val pressed=go.fillPixel()
        assertNotEquals(hovered,pressed)
        mainClock.advanceTimeBy(200)
        assertEquals(pressed,go.fillPixel(),"减少动态效果时，按下反馈应在首帧完成")
        go.performMouseInput { release() }
        mainClock.advanceTimeByFrame()
        devices.assertIsSelected()
        mainClock.autoAdvance=true

        shell.performClick()
        tabTo(hasText("前往设备") and hasClickAction())
        val focused=go.fillPixel()
        go.performKeyInput { keyDown(Key.Spacebar) }
        shell.assertIsSelected()
        assertNotEquals(focused,go.fillPixel())
        go.performKeyInput { keyUp(Key.Spacebar) }
        devices.assertIsSelected()
    }

    @Test fun touchPressScrollCancelAndTapConnectsOnlyOnce() = runDesktopComposeUiTest(width=390,height=850) {
        val host=FixtureHost(temp.root)
        val prefs=DeskPreferences(host.settingsDir)
        repeat(18) { index -> prefs.saveDevice(null,"测试机 ${index+1}","192.0.2.${index+1}",5555) }
        setContent { DeskApp(host) }
        val connect=onAllNodes(hasText("连接") and hasClickAction()).onFirst()
        val initial=connect.fillPixel()
        mainClock.autoAdvance=false
        connect.performTouchInput { down(center) }
        mainClock.advanceTimeByFrame()
        assertEquals(0,host.connects)
        assertNotEquals(initial,connect.fillPixel())
        connect.performTouchInput { moveBy(Offset(0f,-128f)); up() }
        mainClock.autoAdvance=true
        onNode(tab and hasText("设备")).assertIsSelected()
        assertEquals(0,host.connects)
        onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        connect.performTouchInput { click() }
        waitUntil(timeoutMillis=6000) { host.connects==1 && onAllNodes(hasText("复制设备信息")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun rapidDialogReopenRestoresKeyboardFocusAndFreshDraft() = runDesktopComposeUiTest(width=390,height=850) {
        val host=FixtureHost(temp.root).apply { reducedMotion.value=false }
        setContent { DeskApp(host) }
        val add=hasText("添加") and hasClickAction()
        tabTo(add)
        val trigger=onNode(add)
        val name=onNodeWithContentDescription("设备备注")
        mainClock.autoAdvance=false
        repeat(3) {
            key(Key.Enter)
            mainClock.advanceTimeBy(32)
            name.assertIsFocused().assertTextEquals("")
            name.performTextInput("临时备注")
            key(Key.Escape)
            mainClock.advanceTimeByFrame()
            name.assertDoesNotExist()
            trigger.assertIsFocused()
        }
        mainClock.autoAdvance=true
        assertTrue(DeskPreferences(host.settingsDir).devices.isEmpty())
    }

    @Test fun dialogFadeRespectsMotionSetting() = runDesktopComposeUiTest(width=1280,height=850) {
        val host=FixtureHost(temp.root)
        setContent { DeskApp(host) }
        mainClock.autoAdvance=false
        for (reduced in listOf(false,true)) {
            runOnIdle { host.reducedMotion.value=reduced }
            mainClock.advanceTimeBy(32)
            onNodeWithText("添加").performClick()
            mainClock.advanceTimeBy(32)
            val save=onNodeWithText("保存")
            val opening=save.fillPixel()
            mainClock.advanceTimeBy(160)
            val settled=save.fillPixel()
            if (reduced) assertEquals(settled,opening) else assertNotEquals(settled,opening)
            onNodeWithText("取消").performClick()
            mainClock.advanceTimeByFrame()
            save.assertDoesNotExist()
        }
        mainClock.autoAdvance=true
    }

    @Test fun compactLargeTypeKeyboardWorkflows() = runDesktopComposeUiTest(width=360,height=760) {
        val host=FixtureHost(temp.newFolder())
        DeskPreferences(host.settingsDir).apply {
            saveDevice(null,"实验室用于大字号测试的开发设备","2001:db8:abcd:ef01:2345:6789:abcd:ef01",40321,
                "Engineering-Lab-Integration-5GHz","实验楼二层联合调试台东侧工位")
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
        for(label in listOf("WiFi Engineering-Lab-Integration-5GHz","场地 实验楼二层联合调试台东侧工位")) {
            val box=onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(box.left>=0 && box.right<=360)
            assertTrue(box.height<=42f,"两倍字号下标签应占单行")
        }
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

    @Test fun languageSwitchPersistsAndPreservesUserLabels() = runDesktopComposeUiTest(width=1280,height=850) {
        val host=FixtureHost(temp.newFolder())
        DeskPreferences(host.settingsDir).saveDevice(null,"设置","127.0.0.1",5555,"日志")
        setContent { DeskApp(host) }
        onNode(tab and hasText("设置")).performClick()
        onNodeWithText("English").performClick()
        onNode(tab and hasText("Devices")).performClick()
        onNodeWithText("Connect").assertIsDisplayed()
        onNodeWithText("设置").assertIsDisplayed()
        onNodeWithText("WiFi 日志").assertIsDisplayed()
        onNodeWithText("Filter").performClick()
        onNode(hasText("日志") and hasClickAction()).assertIsDisplayed()
        assertTrue(DeskPreferences(host.settingsDir).english)
        onNode(tab and hasText("Settings")).performClick()
        onNodeWithText("中文").performClick()
        onNode(tab and hasText("设备")).assertIsDisplayed()
        assertFalse(DeskPreferences(host.settingsDir).english)
    }

    @Test fun manualToolPathsAreSavedFromSettings() = runDesktopComposeUiTest(width=1280,height=850) {
        val original=FixtureHost(temp.root)
        val host=object: HostBridge by original {
            override fun toolPath(tool:String) = original.toolPath(tool) ?: if(tool=="adb") "C:/SDK/adb.exe" else null
            override fun hasSavedToolPath(tool:String) = original.toolPath(tool)!=null
        }
        setContent { DeskApp(host) }
        for (tool in listOf("adb","scrcpy")) {
            val warning=if(tool=="adb") "未连接ADB" else "未连接scrcpy"
            onNodeWithText(warning).assertIsDisplayed().performClick()
            val input=onNodeWithContentDescription("$tool.exe 路径").assertIsFocused()
            val path="C:/Android Tools/$tool.exe"
            input.performTextReplacement(path)
            onNodeWithContentDescription("保存 $tool.exe 路径").performClick()
            waitUntil { host.toolPath(tool)==path }
            onNodeWithText(warning).assertDoesNotExist()
        }
    }

    @Test fun discoveryPrefillsPairingAndOpensOnlyTheSelectedEndpoint() = runDesktopComposeUiTest(width=1180,height=800) {
        val host=FixtureHost(temp.newFolder())
        setContent { DeskApp(host) }
        onNodeWithText("连接第一台设备").assertIsDisplayed()
        onNodeWithContentDescription("搜索设备").assertDoesNotExist()
        onNodeWithText("更改 adb.exe 路径").assertDoesNotExist()
        onNodeWithText("发现设备").performMouseInput { click() }
        onNodeWithText("10.0.0.12:40200").assertIsDisplayed()
        onAllNodes(hasText("配对") and hasClickAction()).onLast().performMouseInput { click() }
        onNodeWithContentDescription("IP 地址").assertTextContains("10.0.0.12")
        onNodeWithContentDescription("配对端口").assertTextContains("40200")
        assertEquals(0,host.connects)
        onNodeWithText("取消").performMouseInput { click() }
        onNodeWithText("发现设备").performMouseInput { click() }
        onNodeWithText("连接").performMouseInput { click() }
        waitUntil(timeoutMillis=6000) { host.connects==1 && onAllNodes(hasText("复制设备信息")).fetchSemanticsNodes().isNotEmpty() }
        onNode(tab and hasText("面板")).assertIsSelected()
        onNode(tab and hasText("设备")).performClick()
        onNodeWithContentDescription("设备操作 Lab").performClick()
        onNodeWithText("移除本地记录").assertIsDisplayed()
        onNodeWithText("收起").performClick()
        onNodeWithText("移除本地记录").assertDoesNotExist()
        onNode(tab and hasText("镜像")).performClick()
        onNodeWithText("更改 scrcpy.exe 路径").assertDoesNotExist()
        onNode(tab and hasText("设置")).performClick()
        val general=onNodeWithText("语言").fetchSemanticsNode().boundsInRoot
        val sync=onNodeWithText("文件同步").fetchSemanticsNode().boundsInRoot
        assertTrue(sync.left>general.right)
        assertEquals(general.top,sync.top)
    }

    @Test fun fileSyncExportsPortableRecordsAndRequiresConfirmationBeforeReplacing() = runDesktopComposeUiTest(width=1180,height=800) {
        val base=FixtureHost(temp.newFolder())
        val file=File(temp.root,"workspace.properties")
        val host=object:HostBridge by base {
            override suspend fun pickSave(name:String)=WindowsDoc.forSave(file)
            override suspend fun pickOpen(suffix:String):LocalDoc {
                assertEquals(".properties",suffix)
                return WindowsDoc(file)
            }
        }
        val prefs=DeskPreferences(host.settingsDir)
        prefs.saveDevice(null,"旧设备","192.0.2.1",5555,"Lab","广州")
        prefs.saveCommand(null,"旧收藏","echo old")
        prefs.setCloseToTray(false)
        setContent { DeskApp(host) }
        onNodeWithText("连接").performClick()
        waitUntil(timeoutMillis=6000) { onAllNodes(hasText("复制设备信息")).fetchSemanticsNodes().isNotEmpty() }
        onNode(tab and hasText("设置")).performClick()
        onNodeWithText("导出同步文件").performClick()
        waitUntil { onAllNodes(hasText("已导出到 workspace.properties")).fetchSemanticsNodes().isNotEmpty() }
        val exported=java.util.Properties().apply { file.inputStream().use { load(it) } }
        assertEquals(prefs.exportSync(),exported)
        assertFalse(exported.containsKey("selected") || exported.containsKey("closeToTray"))
        val incoming=DeskPreferences(temp.newFolder())
        val first=incoming.saveDevice(null,"A","192.0.2.2",5555,"Office","上海")
        val second=incoming.saveDevice(null,"B","192.0.2.3",5555,"Office","上海")
        incoming.setDeviceGrouping(DeviceGrouping.WIFI)
        incoming.reorderDevices(listOf(second,first),DeviceGrouping.WIFI,"Office")
        incoming.saveCommand(null,"新收藏","echo new")
        file.writeBytes(encodeSyncFile(incoming.exportSync()))
        fun openImport() {
            onNodeWithText("导入同步文件").performClick()
            waitUntil { onAllNodes(hasText("确认导入")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("2 台设备、1 条 Shell 收藏",substring=true).assertIsDisplayed()
        }
        openImport()
        onNodeWithText("取消").performClick()
        assertFalse(base.closed)
        assertEquals("旧设备",DeskPreferences(host.settingsDir).devices.single().name)
        openImport()
        onNodeWithText("确认导入").performClick()
        waitUntil { base.closed }
        fun checkImported() {
            val current=DeskPreferences(host.settingsDir)
            assertEquals(incoming.exportSync(),current.exportSync())
            assertEquals(DeviceGrouping.WIFI,current.deviceGrouping)
            assertEquals(listOf(second,first),current.devices.map { it.id })
            assertFalse(current.closeToTray)
        }
        checkImported()
        file.writeText("format=RProbeDesk/1\ndav.secret=invalid\n")
        onNodeWithText("导入同步文件").performClick()
        waitUntil { onAllNodes(hasText("同步文件格式无效")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("确认导入").assertDoesNotExist()
        checkImported()
    }

    @Test fun emptyDiscoverySavesManualDeviceWithoutConnecting() = runDesktopComposeUiTest(width=390,height=850) {
        val host=FixtureHost(temp.newFolder())
        val discovery=object:HostBridge by host {
            override suspend fun discoverDevices()=emptyList<DiscoveredDevice>()
        }
        setContent { DeskApp(discovery) }
        onNodeWithText("发现设备").performClick()
        onNodeWithText("手动添加").performClick()
        onNodeWithText("添加设备").assertIsDisplayed()
        onNodeWithContentDescription("IP 地址").performTextInput("192.0.2.8")
        onNodeWithContentDescription("连接端口").performTextInput("5555")
        onNodeWithText("保存").performClick()
        onNodeWithContentDescription("搜索设备").assertIsDisplayed()
        assertEquals("192.0.2.8:5555",DeskPreferences(host.settingsDir).devices.single().address)
        assertEquals(0,host.connects)
    }

    @Test fun offlineShellKeepsFavoritesAndLinksBackToDevices() = runDesktopComposeUiTest(width=390,height=850) {
        val host=FixtureHost(temp.newFolder())
        DeskPreferences(host.settingsDir).saveCommand(null,"本地收藏",List(50) { "echo fixture" }.joinToString("\n"))
        setContent { DeskApp(host) }
        onNode(tab and hasText("Shell")).performClick()
        val details=onNodeWithText("详情").fetchSemanticsNode().boundsInRoot
        onNodeWithText("详情").performClick()
        val less=onNodeWithText("收起")
        assertEquals(details,less.fetchSemanticsNode().boundsInRoot)
        val edit=onNodeWithText("编辑").assertIsEnabled().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val delete=onNodeWithText("删除收藏").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(edit.top,delete.top)
        assertTrue(edit.right<=delete.left)
        less.performClick()
        onNodeWithText("编辑").assertDoesNotExist()
        onNodeWithText("前往设备").performClick()
        onNode(tab and hasText("设备")).assertIsSelected()
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
            setColorMode(DeskColorMode.LIGHT)
            saveDevice(null,"实验室平板","10.0.0.12",37000)
            saveDevice(null,"界面回归机","10.0.0.18",40321)
            saveDevice(null,"移动开发机","10.0.0.24",41257)
            saveCommand(null,"测试样例","echo fixture")
        }
        File(host.settingsDir,"desk.properties").appendText("\ndark=true\n")
        setContent { DeskApp(host) }
        val image=onRoot().captureToImage().toAwtImage()
        assertEquals(LightDeskPalette.sidebar.toArgb(),image.getRGB(0,0))
        assertEquals(LightDeskPalette.surface.toArgb(),image.getRGB(190,0))
        assertEquals(LightDeskPalette.background.toArgb(),image.getRGB(190,60))
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
        onNodeWithText("在线").assertDoesNotExist()
        onNodeWithText("离线").assertDoesNotExist()
        onNodeWithContentDescription("搜索设备").performTextReplacement("不存在的设备")
        onNodeWithText("没有匹配的设备").assertIsDisplayed()
        onNodeWithText("清除筛选").performClick()
        onNodeWithText("界面回归机").assertIsDisplayed()
    }
    @Test fun editingSiteShowsTheDeviceInItsNewGroup() = runDesktopComposeUiTest(width=960,height=640) {
        val host=FixtureHost(temp.root)
        val prefs=DeskPreferences(host.settingsDir)
        for(n in 1..15) prefs.saveDevice(null,"Node %02d".format(n),"192.0.2.$n",5555,"WiFi A","Lab")
        setContent { DeskApp(host,prefs) }
        onNodeWithText("不分组").assertIsSelected()
        tabTo(hasText("按 WiFi") and hasClickAction())
        key(Key.Enter)
        onNodeWithText("按 WiFi").assertIsSelected().assertIsFocused()
        onNodeWithText("WiFi A").assertIsDisplayed()
        onNodeWithText("按场地").performMouseInput { click() }
        onNodeWithText("按场地").assertIsSelected()
        onNodeWithText("按 WiFi").assertIsNotSelected()
        onNodeWithText("Lab").assertIsDisplayed()
        onNodeWithContentDescription("设备操作 Node 01").performClick()
        onNodeWithText("编辑").performClick()
        onNodeWithContentDescription("场地").performTextReplacement("Studio")
        onNodeWithText("保存").performClick()
        onNodeWithText("Studio").assertIsDisplayed()
        onNodeWithText("Node 01").assertIsDisplayed()
        onNodeWithContentDescription("拖动排序 Node 01").assertDoesNotExist()
    }

    @Test fun compactGroupingChoicesSwitchWithOneTap() = runDesktopComposeUiTest(width=360,height=760) {
        val host=object:HostBridge by FixtureHost(temp.root) { override val desktop=false }
        val prefs=DeskPreferences(host.settingsDir)
        prefs.saveDevice(null,"Tablet","192.0.2.1",5555,"Mesh","Lab")
        setContent { DeskApp(host,prefs) }
        val none=onNodeWithText("不分组")
        val site=onNodeWithText("按场地")
        val wifi=onNodeWithText("按 WiFi")
        none.assertIsDisplayed().assertIsSelected()
        site.assertIsDisplayed().performTouchInput { click() }
        site.assertIsSelected()
        onNodeWithText("Lab").assertIsDisplayed()
        wifi.assertIsDisplayed().performTouchInput { click() }
        wifi.assertIsSelected()
        site.assertIsNotSelected()
        onNodeWithText("Mesh").assertIsDisplayed()
        none.performTouchInput { click() }
        none.assertIsSelected()
        wifi.assertIsNotSelected()
        onNodeWithText("Mesh").assertDoesNotExist()
    }

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    @Test fun appearanceFollowsSystemUnlessExplicitlySelected() = runDesktopComposeUiTest(width=1280,height=850) {
        val host=FixtureHost(temp.root)
        val system=mutableStateOf(SystemTheme.Light)
        setContent { CompositionLocalProvider(LocalSystemTheme provides system.value) { DeskApp(host) } }
        onNode(tab and hasText("设置")).performClick()
        val palette={ p:DeskPalette ->
            val image=onRoot().captureToImage().toAwtImage()
            assertEquals(p.sidebar.toArgb(),image.getRGB(0,0))
            assertEquals(p.surface.toArgb(),image.getRGB(190,0))
            assertEquals(p.background.toArgb(),image.getRGB(190,60))
        }
        onNodeWithText("跟随系统").assertIsSelected()
        palette(LightDeskPalette)
        runOnIdle { system.value=SystemTheme.Dark }
        palette(DarkDeskPalette)
        onNodeWithText("明色").performClick().assertIsSelected()
        palette(LightDeskPalette)
        onNodeWithText("暗色").performClick().assertIsSelected()
        runOnIdle { system.value=SystemTheme.Light }
        palette(DarkDeskPalette)
        assertEquals(DeskColorMode.DARK,DeskPreferences(host.settingsDir).colorMode)
        val output=File("build/reports/desk-ui").apply { mkdirs() }
        val darkImage=onRoot().captureToImage().toAwtImage()
        val heading=onNodeWithText("颜色模式").fetchSemanticsNode().boundsInRoot
        assertEquals(0xff181818.toInt(),darkImage.getRGB(heading.right.toInt()+8,heading.center.y.toInt()))
        check(ImageIO.write(darkImage,"png",File(output,"dark-settings.png")))
        onNodeWithText("跟随系统").performClick().assertIsSelected()
        palette(LightDeskPalette)
        assertEquals(DeskColorMode.SYSTEM,DeskPreferences(host.settingsDir).colorMode)
    }

    @Test fun narrowConnectFavoriteMirrorAndDisconnect() = runDesktopComposeUiTest(width=390,height=850) {
        val host=FixtureHost(temp.newFolder())
        DeskPreferences(host.settingsDir).apply {
            saveDevice(null,"实验平板","10.0.0.12",37000,"LabNet","实验室")
            saveCommand(null,"运行样例","echo fixture")
        }
        setContent { DeskApp(host) }
        for(page in listOf("设备","面板","应用","文件","Shell","镜像","日志")) onNode(tab and hasText(page)).assertIsDisplayed()
        onNodeWithText("实验平板").assertIsDisplayed()
        onNodeWithText("WiFi LabNet").assertIsDisplayed()
        onNodeWithText("场地 实验室").assertIsDisplayed()
        onNodeWithText("当前连接").assertDoesNotExist()
        val output=File("build/reports/desk-ui").apply { mkdirs() }
        check(ImageIO.write(onRoot().captureToImage().toAwtImage(),"png",File(output,"workbench-compact.png")))

        onAllNodes(hasText("连接") and hasClickAction()).onLast().performClick()
        waitUntil(timeoutMillis=6000) { host.connects==1 && onAllNodes(hasText("5120 / 8192 MiB")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("5120 / 8192 MiB").assertExists()
        onNode(tab and hasText("Shell")).performClick()
        onNode(hasText("运行") and isEnabled() and hasClickAction()).performClick()
        waitUntil(timeoutMillis=6000) { host.favorites==1 }

        onNode(tab and hasText("设备")).performClick()
        onNodeWithText("当前连接").assertIsDisplayed()
        onNode(hasText("打开镜像") and SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Button)).performClick()
        assertEquals(1,host.mirrors)
        host.online=false
        runOnIdle { host.foreground.value=false }
        waitForIdle()
        runOnIdle { host.foreground.value=true }
        waitUntil(timeoutMillis=6000) { host.closed }
        onNodeWithText("当前连接").assertDoesNotExist()
        onNode(hasText("打开镜像") and SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Button)).assertIsNotEnabled()
    }

    @Test fun filtersAndFavoriteDeletionRespectTheSelectedPage() = runDesktopComposeUiTest(width=1280,height=850) {
        val host=FixtureHost(temp.newFolder()).apply { reducedMotion.value=false }
        val prefs=DeskPreferences(host.settingsDir)
        prefs.saveDevice(null,"开发机","10.0.0.1",5555,"Lab","楼上")
        prefs.saveDevice(null,"测试机","10.0.0.2",5555,"Office","楼下")
        prefs.saveCommand(null,"测试命令","echo fixture")
        setContent { DeskApp(host) }
        val wifiTag=onNodeWithText("WiFi Lab").assertIsDisplayed()
        val siteTag=onNodeWithText("场地 楼上").assertIsDisplayed()
        assertNotEquals(wifiTag.fillPixel(),siteTag.fillPixel())
        onNodeWithText("筛选").performClick()
        val wifiFilter=onNode(hasText("Lab") and hasClickAction())
        val before=wifiFilter.captureToImage().toAwtImage()
        mainClock.autoAdvance=false
        repeat(3) { index ->
            wifiFilter.performTouchInput { click() }
            mainClock.advanceTimeByFrame()
            if (index==1) wifiFilter.assertIsNotSelected() else wifiFilter.assertIsSelected()
        }
        mainClock.advanceTimeBy(160)
        mainClock.autoAdvance=true
        val after=wifiFilter.captureToImage().toAwtImage()
        assertNotEquals(before.getRGB(4,before.height/2),after.getRGB(4,after.height/2),"选中的 WiFi 标签应有可见高亮")
        onNodeWithText("测试机").assertDoesNotExist()
        onNodeWithText("连接").performClick()
        waitUntil(timeoutMillis=6000) { onAllNodes(hasText("复制设备信息")).fetchSemanticsNodes().isNotEmpty() }
        onNode(tab and hasText("设备")).performClick()
        onAllNodesWithText("当前连接").assertCountEquals(1)
        onNodeWithText("断开").assertIsDisplayed()
        onNodeWithText("连接").assertIsDisplayed()
        onNode(tab and hasText("应用")).performClick()
        waitUntil(timeoutMillis=6000) { onAllNodes(hasText("Android 用户 0 · 1 个应用")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("系统应用").performClick()
        onNodeWithText("没有匹配的应用").assertIsDisplayed()
        onNodeWithText("用户应用").performClick()
        onNodeWithText("dev.rprobedesk.fixture").assertIsDisplayed()
        onNode(tab and hasText("Shell")).performClick()
        onNodeWithText("Android 用户 0 · 1 个应用").assertDoesNotExist()
        onNodeWithText("详情").performClick()
        onNodeWithText("删除收藏").performClick()
        onNode(hasText("取消") and hasClickAction()).performClick()
        onNodeWithText("测试命令").assertIsDisplayed()
        onNodeWithText("删除收藏").performClick()
        onAllNodes(hasText("删除收藏") and hasClickAction()).onLast().performClick()
        onNodeWithText("测试命令").assertDoesNotExist()
        onNode(tab and hasText("日志")).performClick()
        onAllNodes(hasText("开始采集") and hasClickAction()).onLast().performClick()
        waitUntil(timeoutMillis=6000) { onAllNodes(hasText("log-199")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("log-199").performMouseInput { moveTo(center); scroll(-5f) }
        onNodeWithText("跟随最新").assertIsNotSelected()
        onNodeWithText("跟随最新").performClick()
        onNodeWithText("跟随最新").assertIsSelected()
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
        override suspend fun discoverDevices()=listOf(DiscoveredDevice("Lab","10.0.0.12",5555,"_adb._tcp"),
            DiscoveredDevice("Pair","10.0.0.12",40200,"_adb-tls-pairing._tcp"))
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
                        command=="logcat -b all -g" -> "main: ring buffer"
                        command.startsWith("logcat -v threadtime") -> {
                            repeat(200) { onLine?.invoke("log-$it",false) }
                            kotlinx.coroutines.awaitCancellation()
                        }
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
                override suspend fun install(apk:File,userId:Int, transferred:(Long)->Unit, installing:()->Unit)=error("unexpected install")
                override suspend fun screenshot(output:OutputStream)=error("unexpected screenshot")
                override fun close() { closed=true }
            }
        }
        override suspend fun pickOpen(suffix:String):LocalDoc?=null
        override suspend fun pickSave(name:String):LocalDoc?=null
        override suspend fun selectTool(tool:String) {}
        private val paths=mutableMapOf<String,String>()
        override fun toolPath(tool:String)=paths[tool]
        override suspend fun setToolPath(tool:String,path:String) { paths[tool]=path }
        override suspend fun startMirror(session:DeviceSession) { mirrors++; mirrorActive.value=true }
        override fun stopMirror() { mirrorActive.value=false }
        override fun copy(text:String) {}
    }
}
