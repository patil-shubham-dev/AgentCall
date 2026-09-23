package com.agentcall.app.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RingTimeoutPolicyTest {

    @Test
    fun `fires only while the server still reports pending`() {
        assertTrue(RingTimeoutPolicy.shouldFireTimeoutCancel("pending"))
    }

    @Test
    fun `skips answered, finished and unknown states`() {
        assertFalse(RingTimeoutPolicy.shouldFireTimeoutCancel("active"))
        assertFalse(RingTimeoutPolicy.shouldFireTimeoutCancel("paused"))
        assertFalse(RingTimeoutPolicy.shouldFireTimeoutCancel("completed"))
        assertFalse(RingTimeoutPolicy.shouldFireTimeoutCancel("cancelled"))
        assertFalse(RingTimeoutPolicy.shouldFireTimeoutCancel("aborted"))
        assertFalse(RingTimeoutPolicy.shouldFireTimeoutCancel(null))
        assertFalse(RingTimeoutPolicy.shouldFireTimeoutCancel(""))
    }

    @Test
    fun `trigger is exactly the ring window after posting`() {
        assertEquals(160_000L, RingTimeoutPolicy.triggerAtMs(100_000L))
        assertEquals(RingTimeoutPolicy.TIMEOUT_MS, 60_000L)
    }

    @Test
    fun `request codes are stable per call and differ across calls`() {
        val a1 = RingTimeoutPolicy.alarmRequestCode("call-abc")
        val a2 = RingTimeoutPolicy.alarmRequestCode("call-abc")
        val b = RingTimeoutPolicy.alarmRequestCode("call-xyz")
        assertEquals(a1, a2)
        assertTrue(a1 != b)
    }
}
