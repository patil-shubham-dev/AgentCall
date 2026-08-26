package com.agentcall.app.call

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CallStatus { IDLE, RINGING, ANSWERED, ENDED }

data class CallState(
    val status: CallStatus = CallStatus.IDLE,
    val callId: String? = null,
)

/**
 * Process-wide ring/answer truth (backlog item 14 — duplicate answer screen).
 *
 * The notification's Answer action (ACTION_START_CALL from a PendingIntent)
 * and the full-screen IncomingCallActivity's Answer button race each other:
 * before this holder existed each path was unaware of the other, so both could
 * start a session for the same call and the FSI could stay up after an answer.
 *
 * [CallStateHolder] is the single writer of the ring lifecycle:
 * - [ringing] — the FGS just posted the ring (before the notification, so the
 *   Answer PendingIntent can never fire before the state says RINGING).
 * - [answered] — ACTION_START_CALL accepted the answer (CallService).
 * - [ended] — cancel/timeout/terminal WS event (CallService / FGS).
 *
 * Writes are guarded by the callId: a stale event for a previous call can
 * never clobber a live ring for a newer one (the FGS only rings one call at a
 * time, but a late call_cancelled for call A must not erase RINGING for B).
 *
 * IncomingCallActivity validates against this before showing the ring UI and
 * before sending a decline, so a stale full-screen intent (answered or ended
 * while the FSI was still queued) is a silent no-op instead of a second
 * screen or a cancel racing a live call.
 */
object CallStateHolder {

    private val _state = MutableStateFlow(CallState())
    val state: StateFlow<CallState> = _state.asStateFlow()

    fun ringing(callId: String) {
        _state.value = CallState(CallStatus.RINGING, callId)
    }

    fun answered(callId: String) {
        val s = _state.value
        if (s.status != CallStatus.IDLE && s.callId != callId) return
        _state.value = CallState(CallStatus.ANSWERED, callId)
    }

    fun ended(callId: String) {
        val s = _state.value
        if (s.callId != callId) return
        _state.value = CallState(CallStatus.ENDED, callId)
    }
}