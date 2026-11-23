package dev.rprobedesk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class ReleaseAndPanelTest {
    @Test fun translatesPanelValuesByFieldAndPreservesDeviceIdentifiers() {
        assertEquals("运行中",panelValue("Android 系统属性","init.svc.adbd","running",false))
        assertEquals("认证完成",panelValue("Wi-Fi 链路","Supplicant state","COMPLETED",false))
        assertEquals("充电中",panelValue("电池原始读数","status","2",false))
        assertEquals("良好",panelValue("电池原始读数","health","2",false))
        assertEquals("自动",panelValue("Settings · system","screen_brightness_mode","1",false))
        assertEquals("running",panelValue("Wi-Fi 链路","SSID","running",false))
        assertEquals("COMPLETED",panelValue("Wi-Fi 链路","Supplicant state","COMPLETED",true))
    }

    @Test fun checksStableReleaseAndExactAsset() {
        val tag = "https://github.com/rurikacy/RProbeDesk/releases/tag/v"
        assertEquals("1.10.0", newerRelease("1.9.9", tag + "1.10.0"))
        assertNull(newerRelease("1.1.1", tag + "1.1.0"))
        assertNull(newerRelease("1.1.1", tag + "1.1.1"))
        assertFails { newerRelease("1.1.1", tag + "1.2.0-beta") }
        assertFails { newerRelease("1.1.1", "1.9.0") }
        val hash = "ab".repeat(32)
        val manifest = "$hash  app.msi\n${"cd".repeat(32)} *app.apk\n"
        assertEquals(hash, releaseChecksum(manifest, "app.msi"))
        assertFails { releaseChecksum(manifest, "other.msi") }
        assertFails { releaseChecksum(manifest + manifest, "app.msi") }
    }

    @Test fun readsOnlyCurrentNetworkAndKeepsUnknownPoliciesExplicit() {
        val output = """
            @wifi
            WifiInfo: SSID: Example, MAC: 9a:11:22:33:44:55, Net ID: 0
            @profiles
            0 macRandomizationSetting: 3
            0 mRandomizedMacAddress: 9a:11:22:33:44:55
            1 macRandomizationSetting: 0
            @props
            [ro.debuggable]: [0]
            @system
            setting=value=with=equals
        """.trimIndent()
        val details = parsePanelDetails(output)
        assertEquals("已开启（自动，当前使用随机 MAC）", details.overview["是否开启 MAC 随机化"])
        assertEquals("普通 ADB 安装仅允许显式请求降级已安装的可调试应用", details.overview["是否允许降级更新应用"])
        assertEquals("value=with=equals", details.sections["Settings · system"]?.get("setting"))
        assertEquals("已关闭（设备 MAC）", parsePanelDetails(output.replace("Net ID: 0","Net ID: 1")).overview["是否开启 MAC 随机化"])
        assertEquals("未知（当前网络未提供策略）", parsePanelDetails("").overview["是否开启 MAC 随机化"])
    }
}
