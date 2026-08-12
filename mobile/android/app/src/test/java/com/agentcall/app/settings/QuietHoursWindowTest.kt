package com.agentcall.app.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backlog item 6 — the window math (including overnight wrap-around) is the
 * part that silently breaks, so it is pinned as pure-state assertions.
 */
class QuietHoursWindowTest {

    @Test
    fun `same-day window contains in-range minutes only`() {
        // 09:00 -> 17:00
        assertTrue(QuietHoursManager.isMinutesInRange(9 * 60, 9 * 60, 17 * 60))
        assertTrue(QuietHoursManager.isMinutesInRange(12 * 60, 9 * 60, 17 * 60))
        assertFalse("start is not quiet", QuietHoursManager.isMinutesInRange(8 * 60 + 59, 9 * 60, 17 * 60))
        assertFalse("end is not quiet", QuietHoursManager.isMinutesInRange(17 * 60, 9 * 60, 17 * 60))
    }

    @Test
    fun `overnight window wraps past midnight`() {
        // 22:00 -> 07:00
        assertTrue(QuietHoursManager.isMinutesInRange(23 * 60, 22 * 60, 7 * 60))
        assertTrue(QuietHoursManager.isMinutesInRange(3 * 60, 22 * 60, 7 * 60))
        assertTrue("exactly at start", QuietHoursManager.isMinutesInRange(22 * 60, 22 * 60, 7 * 60))
        assertFalse("14:00 is outside", QuietHoursManager.isMinutesInRange(14 * 60, 22 * 60, 7 * 60))
    }

    @Test
    fun `zero-length window is disabled`() {
        assertFalse(QuietHoursManager.isMinutesInRange(12 * 60, 12 * 60, 12 * 60))
    }

    @Test
    fun `labels format as HHMM`() {
        assertTrue(QuietHoursManager.minutesToLabel(22 * 60) == "22:00")
        assertTrue(QuietHoursManager.minutesToLabel(7 * 60 + 5) == "07:05")
    }
}
