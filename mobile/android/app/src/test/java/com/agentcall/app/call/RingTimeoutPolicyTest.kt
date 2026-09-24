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

    @Test
    fun `rings only for pending server status`() {
        assertTrue(RingTimeoutPolicy.shouldRingForServerStatus("pending"))
    }

    @Test
    fun `never rings for answered calls (active or paused)`() {
        // The 2026-09-24 hardening: a replayed/queued call_incoming for an
        // already-answered call must not re-ring — it would clobber
        // CallStateHolder mid-session and arm a 60s timeout on a live call.
        assertFalse(RingTimeoutPolicy.shouldRingForServerStatus("active"))
        assertFalse(RingTimeoutPolicy.shouldRingForServerStatus("paused"))
    }

    @Test
    fun `never rings for terminal or unknown server status`() {
        assertFalse(RingTimeoutPolicy.shouldRingForServerStatus("ended"))
        assertFalse(RingTimeoutPolicy.shouldRingForServerStatus("cancelled"))
        assertFalse(RingTimeoutPolicy.shouldRingForServerStatus("expired"))
        assertFalse(RingTimeoutPolicy.shouldRingForServerStatus("aborted"))
        assertFalse(RingTimeoutPolicy.shouldRingForServerStatus("completed"))
        assertFalse(RingTimeoutPolicy.shouldRingForServerStatus(null))
        assertFalse(RingTimeoutPolicy.shouldRingForServerStatus(""))
        assertFalse(RingTimeoutPolicy.shouldRingForServerStatus("PENDING"))
    }
}
