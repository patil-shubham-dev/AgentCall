package com.agentcall.app.call.state

import com.agentcall.app.call.CallPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backlog item 12 — the phase bugs that are worst on device (stuck
 * RECONNECTING, resurrected ENDED calls) are pinned here as pure-state
 * assertions. Calls are AI-originated only (the user-facing Call button was
 * removed), so the machine starts from START_INCOMING in every test.
 */
class CallStateMachineTest {

    private fun incoming(): CallStateMachine =
        CallStateMachine().apply { onEvent(CallMachineEvent.START_INCOMING) }

    @Test
    fun `incoming connecting becomes active on call_answered`() {
        val m = incoming()
        assertEquals(CallPhase.CONNECTING, m.state.phase)
        m.onEvent(CallMachineEvent.CALL_ANSWERED)
        assertEquals(CallPhase.ACTIVE, m.state.phase)
        assertTrue(m.state.timerRunning)
    }

    @Test
    fun `connecting stays connecting without any backend confirmation`() {
        val m = incoming()
        // A socket hiccup while the AI has not picked up must NOT flip the
        // call into RECONNECTING — the call has not gone live yet.
        m.onEvent(CallMachineEvent.SOCKET_RECONNECTING)
        assertEquals(CallPhase.CONNECTING, m.state.phase)
        assertFalse(m.state.timerRunning)
    }

    @Test
    fun `first ai_message promotes the call when call_answered never arrives`() {
        val m = incoming()
        m.onEvent(CallMachineEvent.FIRST_AI_MESSAGE)
        assertEquals(CallPhase.ACTIVE, m.state.phase)
        assertTrue(m.state.timerRunning)
    }

    @Test
    fun `active call pauses the timer while reconnecting and resumes on reconnect`() {
        val m = incoming()
        m.onEvent(CallMachineEvent.CALL_ANSWERED)
        assertTrue(m.state.timerRunning)

        m.onEvent(CallMachineEvent.SOCKET_RECONNECTING)
        assertEquals(CallPhase.RECONNECTING, m.state.phase)
        assertFalse("timer paused while reconnecting", m.state.timerRunning)

        m.onEvent(CallMachineEvent.SOCKET_CONNECTED)
        assertEquals(CallPhase.ACTIVE, m.state.phase)
        assertTrue("timer resumed after reconnect", m.state.timerRunning)
    }

    @Test
    fun `reconnecting ends when the call dies while disconnected`() {
        val m = incoming()
        m.onEvent(CallMachineEvent.CALL_ANSWERED)
        m.onEvent(CallMachineEvent.SOCKET_RECONNECTING)
        assertEquals(CallPhase.RECONNECTING, m.state.phase)

        m.onEvent(CallMachineEvent.CALL_ENDED)
        assertEquals(CallPhase.ENDED, m.state.phase)
        assertFalse(m.state.timerRunning)
    }

    @Test
    fun `cancel from connecting transitions cleanly`() {
        val m = incoming()
        m.onEvent(CallMachineEvent.CALL_CANCELLED)
        assertEquals(CallPhase.ENDED, m.state.phase)
        assertFalse(m.state.timerRunning)
    }

    @Test
    fun `terminal ENDED ignores every later event`() {
        val m = incoming()
        m.onEvent(CallMachineEvent.CALL_ENDED)
        assertEquals(CallPhase.ENDED, m.state.phase)

        m.onEvent(CallMachineEvent.CALL_ANSWERED)
        m.onEvent(CallMachineEvent.FIRST_AI_MESSAGE)
        m.onEvent(CallMachineEvent.SOCKET_CONNECTED)
        assertEquals("an ended call can never resurrect", CallPhase.ENDED, m.state.phase)
        assertFalse(m.state.timerRunning)
    }

    @Test
    fun `hard disconnect from active ends the call`() {
        val m = incoming()
        m.onEvent(CallMachineEvent.CALL_ANSWERED)
        m.onEvent(CallMachineEvent.HARD_DISCONNECT)
        assertEquals(CallPhase.ENDED, m.state.phase)
    }
}