package com.agentcall.app.call

/**
 * Decides what IncomingCallActivity shows for a launch when the in-memory
 * [CallStateHolder] may be empty or stale.
 *
 * IDLE is not stale evidence: after the FCM process is reclaimed, the
 * notification (or its full-screen intent) is the only surviving record of a
 * live ring. Finishing on IDLE made the incoming-call page close itself on
 * launch. Only positive evidence may dismiss the launch — this call already
 * ended, or another call currently owns the single ring/answer slot.
 */
object IncomingLaunchPolicy {

    enum class Action { SHOW_RING, SHOW_ANSWERED, FINISH_STALE }

    fun actionFor(
        callId: String,
        launchedFromHistory: Boolean,
        state: CallState,
    ): Action = when {
        state.callId == null -> Action.SHOW_RING
        state.callId != callId -> when (state.status) {
            CallStatus.RINGING, CallStatus.ANSWERED -> Action.FINISH_STALE
            else -> Action.SHOW_RING
        }
        state.status == CallStatus.ANSWERED ->
            if (launchedFromHistory) Action.FINISH_STALE else Action.SHOW_ANSWERED
        state.status == CallStatus.ENDED -> Action.FINISH_STALE
        else -> Action.SHOW_RING
    }
}
