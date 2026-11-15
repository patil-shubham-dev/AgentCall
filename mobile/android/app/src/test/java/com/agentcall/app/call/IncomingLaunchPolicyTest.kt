package com.agentcall.app.call

import com.agentcall.app.call.IncomingLaunchPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ring page used to finish itself whenever CallStateHolder was not
 * RINGING, including after the FCM process was reclaimed (IDLE) — the page
 * closed on every launch and the notification answer landed nowhere. These
 * cases pin the replacement: only positive evidence may dismiss a launch.
 */
class IncomingLaunchPolicyTest {

    private val callId = "call-1"

    private fun action(
        state: CallState,
        fromHistory: Boolean = false,
        id: String = callId,
    ): Action = IncomingLaunchPolicy.actionFor(id, fromHistory, state)

    @Test
    fun `empty state shows the ring instead of finishing`() {
        assertEquals(Action.SHOW_RING, action(CallState()))
    }

    @Test
    fun `ringing call shows the ring`() {
        assertEquals(Action.SHOW_RING, action(CallState(CallStatus.RINGING, callId)))
    }

    @Test
    fun `answered call shows the live call page`() {
        assertEquals(Action.SHOW_ANSWERED, action(CallState(CallStatus.ANSWERED, callId)))
    }

    @Test
    fun `answered relaunch from history is stale`() {
        assertEquals(
            Action.FINISH_STALE,
            action(CallState(CallStatus.ANSWERED, callId), fromHistory = true),
        )
    }

    @Test
    fun `ended call is stale`() {
        assertEquals(Action.FINISH_STALE, action(CallState(CallStatus.ENDED, callId)))
    }

    @Test
    fun `another ringing call supersedes this launch`() {
        assertEquals(
            Action.FINISH_STALE,
            action(CallState(CallStatus.RINGING, "call-2")),
        )
    }

    @Test
    fun `another answered call supersedes this launch`() {
        assertEquals(
            Action.FINISH_STALE,
            action(CallState(CallStatus.ANSWERED, "call-2")),
        )
    }

    @Test
    fun `another finished call never blocks a new ring`() {
        assertEquals(
            Action.SHOW_RING,
            action(CallState(CallStatus.ENDED, "call-2")),
        )
    }
}
