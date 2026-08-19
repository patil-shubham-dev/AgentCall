package com.agentcall.app.call.state

import com.agentcall.app.call.CallPhase

/**
 * Pure call-phase state machine (backlog item 12) — no Android dependencies,
 * so the highest-risk transitions (stuck RECONNECTING) are unit-testable.
 *
 * The machine owns the phase and one derived flag:
 * - [CallMachineState.timerRunning]: the in-call timer only ticks while a
 *   live conversation is active; reconnect pauses it, terminal states stop it.
 *
 * ENDED is terminal: every later event is ignored. This is what guarantees a
 * finished call can never resurrect into ACTIVE.
 */
enum class CallMachineEvent {
    START_INCOMING,
    CALL_ANSWERED,
    FIRST_AI_MESSAGE,
    SOCKET_RECONNECTING,
    SOCKET_CONNECTED,
    CALL_ENDED,
    CALL_CANCELLED,
    CALL_EXPIRED,
    HARD_DISCONNECT,
}

data class CallMachineState(
    val phase: CallPhase = CallPhase.CONNECTING,
    val timerRunning: Boolean = false,
)

class CallStateMachine(initial: CallMachineState = CallMachineState()) {

    var state: CallMachineState = initial
        private set

    /** Returns the new state. Terminal states ignore every event. */
    fun onEvent(event: CallMachineEvent): CallMachineState {
        val s = state
        if (s.phase == CallPhase.ENDED) return s
        state = when (event) {
            CallMachineEvent.START_INCOMING -> s.copy(
                phase = CallPhase.CONNECTING,
                timerRunning = false,
            )
            CallMachineEvent.CALL_ANSWERED, CallMachineEvent.FIRST_AI_MESSAGE -> when (s.phase) {
                // The first backend confirmation (call_answered or an ai_message)
                // promotes the call to active.
                CallPhase.CONNECTING, CallPhase.RECONNECTING -> s.copy(
                    phase = CallPhase.ACTIVE,
                    timerRunning = true,
                )
                else -> s
            }
            CallMachineEvent.SOCKET_RECONNECTING -> if (s.phase == CallPhase.ACTIVE) {
                s.copy(phase = CallPhase.RECONNECTING, timerRunning = false)
            } else {
                s
            }
            CallMachineEvent.SOCKET_CONNECTED -> if (s.phase == CallPhase.RECONNECTING) {
                s.copy(phase = CallPhase.ACTIVE, timerRunning = true)
            } else {
                s
            }
            CallMachineEvent.CALL_ENDED,
            CallMachineEvent.CALL_CANCELLED,
            CallMachineEvent.CALL_EXPIRED,
            CallMachineEvent.HARD_DISCONNECT,
            -> s.copy(
                phase = CallPhase.ENDED,
                timerRunning = false,
            )
        }
        return state
    }
}
