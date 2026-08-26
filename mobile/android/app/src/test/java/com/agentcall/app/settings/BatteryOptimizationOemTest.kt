package com.agentcall.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Battery-optimization onboarding (ColorOS Doze finding) — the OEM mapping
 * and log-entry format are the parts that silently break, pinned as pure
 * assertions. Android APIs (PowerManager, SharedPreferences) stay out of
 * these tests by design.
 */
class BatteryOptimizationOemTest {

    @Test
    fun `realme and oppo map to the ColorOS guidance`() {
        for (m in listOf("realme", "Realme", "OPPO", "oppo")) {
            val info = OemMatcher.match(m)
            assertEquals("oppo", info.slug)
            assertEquals("https://dontkillmyapp.com/oppo", info.supportUrl)
            assertTrue("ColorOS needs deep-link candidates", info.deepLinkCandidates.isNotEmpty())
            assertTrue(info.deepLinkCandidates[0].startsWith("com.coloros.safecenter/"))
        }
    }

    @Test
    fun `oneplus keeps the slug but gets its own instructions`() {
        val info = OemMatcher.match("OnePlus")
        assertEquals("oppo", info.slug)
        assertEquals("OnePlus (OxygenOS)", info.displayName)
        assertFalse("OnePlus instructions must differ from generic ColorOS",
            info.instructions.contains("Background Freeze"))
    }

    @Test
    fun `xiaomi redmi and poco share the MIUI guidance`() {
        for (m in listOf("Xiaomi", "Redmi", "POCO")) {
            val info = OemMatcher.match(m)
            assertEquals("xiaomi", info.slug)
            assertEquals("https://dontkillmyapp.com/xiaomi", info.supportUrl)
            assertEquals("com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
                info.deepLinkCandidates.single())
        }
    }

    @Test
    fun `vivo samsung and huawei map with non-empty instructions`() {
        assertEquals("vivo", OemMatcher.match("vivo").slug)
        assertEquals("vivo", OemMatcher.match("iQOO").slug)
        assertEquals("samsung", OemMatcher.match("samsung").slug)
        assertEquals("huawei", OemMatcher.match("HUAWEI").slug)
        assertEquals("huawei", OemMatcher.match("Honor").slug)
        assertTrue("samsung has no deep link", OemMatcher.match("samsung").deepLinkCandidates.isEmpty())
        assertTrue("huawei has a deep link", OemMatcher.match("huawei").deepLinkCandidates.isNotEmpty())
    }

    @Test
    fun `unknown manufacturer falls back to the generic guide`() {
        val info = OemMatcher.match("SomePhoneInc")
        assertEquals("", info.slug)
        assertEquals("https://dontkillmyapp.com", info.supportUrl)
        assertTrue(info.deepLinkCandidates.isEmpty())
        assertTrue(info.instructions.isNotBlank())
    }

    @Test
    fun `log entries render as sorted timestamp pipe granted pipe source`() {
        val t1 = Instant.parse("2026-08-20T14:05:00Z")
        val t2 = Instant.parse("2026-08-20T14:06:30Z")
        assertEquals("2026-08-20T14:05:00Z|granted=true|exemption_request",
            BatteryOptimizationManager.LogEntry(t1, true, "exemption_request").render())
        assertEquals("2026-08-20T14:06:30Z|granted=false|exemption_request",
            BatteryOptimizationManager.LogEntry(t2, false, "exemption_request").render())
        assertEquals("2026-08-20T14:05:00Z|granted=n/a|opened_app_details_fallback",
            BatteryOptimizationManager.LogEntry(t1, null, "opened_app_details_fallback").render())
        // Newer entries sort first (most-recent-first log).
        assertTrue(
            BatteryOptimizationManager.LogEntry(t2, true, "exemption_request").render() >
                BatteryOptimizationManager.LogEntry(t1, true, "exemption_request").render()
        )
    }
}