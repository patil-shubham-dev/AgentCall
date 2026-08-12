package com.agentcall.app.call.state

import com.agentcall.app.call.CallPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backlog item 12 — the phase bugs that are worst on device (ringback never
 * stopping, stuck RECONNECTING, resurrected ENDED calls) are pinned here as
 * pure-state assertions.
 */
class CallStateMachineTest {

    private fun outgoing(): CallStateMachine =
        CallStateMachine().apply { onEvent(CallMachineEvent.START_OUTGOING) }

    private fun incoming(): CallStateMachine =
        CallStateMachine().apply { onEvent(CallMachineEvent.START_INCOMING) }

    @Test
    fun `outgoing becomes active on call_answered and stops the ringback`() {
        val m = outgoing()
        assertEquals(CallPhase.OUTGOING, m.state.phase)
        m.onEvent(CallMachineEvent.CALL_ANSWERED)
        assertEquals(CallPhase.ACTIVE, m.state.phase)
        assertTrue("ringback must stop on the OUTGOING->ACTIVE edge", m.state.ringbackShouldStop)
        assertTrue(m.state.timerRunning)
    }

    @Test
    fun `outgoing stays outgoing without any backend confirmation`() {
        val m = outgoing()
        // A socket hiccup while the AI has not picked up must NOT flip the
        // ring into RECONNECTING — the ringback keeps playing.
        m.onEvent(CallMachineEvent.SOCKET_RECONNECTING)
        assertEquals(CallPhase.OUTGOING, m.state.phase)
        assertFalse(m.state.ringbackShouldStop)
        assertFalse(m.state.timerRunning)
    }

    @Test
    fun `first ai_message promotes an outgoing call when call_answered never arrives`() {
        val m = outgoing()
        m.onEvent(CallMachineEvent.FIRST_AI_MESSAGE)
        assertEquals(CallPhase.ACTIVE, m.state.phase)
        assertTrue(m.state.ringbackShouldStop)
        assertTrue(m.state.timerRunning)
    }

    @Test
    fun `active call pauses the timer while reconnecting and resumes on reconnect`() {
        val m = outgoing()
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
        val m = outgoing()
        m.onEvent(CallMachineEvent.CALL_ANSWERED)
        m.onEvent(CallMachineEvent.SOCKET_RECONNECTING)
        assertEquals(CallPhase.RECONNECTING, m.state.phase)

        m.onEvent(CallMachineEvent.CALL_ENDED)
        assertEquals(CallPhase.ENDED, m.state.phase)
        assertFalse(m.state.timerRunning)
    }

    @Test
    fun `cancel from outgoing transitions cleanly`() {
        val m = outgoing()
        m.onEvent(CallMachineEvent.CALL_CANCELLED)
        assertEquals(CallPhase.ENDED, m.state.phase)
        assertTrue(m.state.ringbackShouldStop)
        assertFalse(m.state.timerRunning)
    }

    @Test
    fun `terminal ENDED ignores every later event`() {
        val m = outgoing()
        m.onEvent(CallMachineEvent.CALL_ENDED)
        assertEquals(CallPhase.ENDED, m.state.phase)

        m.onEvent(CallMachineEvent.CALL_ANSWERED)
        m.onEvent(CallMachineEvent.FIRST_AI_MESSAGE)
        m.onEvent(CallMachineEvent.SOCKET_CONNECTED)
        assertEquals("an ended call can never resurrect", CallPhase.ENDED, m.state.phase)
        assertFalse(m.state.timerRunning)
    }

    @Test
    fun `incoming connecting becomes active on answer without the ringback flag`() {
        val m = incoming()
        assertEquals(CallPhase.CONNECTING, m.state.phase)

        m.onEvent(CallMachineEvent.CALL_ANSWERED)
        assertEquals(CallPhase.ACTIVE, m.state.phase)
        // Incoming calls never play ringback — the flag must stay false.
        assertFalse(m.state.ringbackShouldStop)
        assertTrue(m.state.timerRunning)
    }

    @Test
    fun `hard disconnect from active ends the call`() {
        val m = outgoing()
        m.onEvent(CallMachineEvent.CALL_ANSWERED)
        m.onEvent(CallMachineEvent.HARD_DISCONNECT)
        assertEquals(CallPhase.ENDED, m.state.phase)
    }
}
