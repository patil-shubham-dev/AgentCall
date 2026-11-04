package com.agentcall.app.call

/**
 * What the user's counterpart is doing right now — the single label the
 * active-call screen renders. Derived purely from observable state so it is
 * unit-testable and consistent between the header, the call stage, the
 * waveform color, and screen-reader text.
 *
 * Hands-free semantics: the microphone is always captured for the whole
 * call, so LISTENING is the normal connected state — [isRecording] no longer
 * gates it (that flag only tracks whether the STT engine session is open at
 * this instant).
 */
enum class CallPresence {
    CONNECTING,
    LISTENING,
    THINKING,
    SPEAKING,
    RECONNECTING,
    MUTED,
    ERROR,
    ENDED,
}

val CallPresence.label: String
    get() = when (this) {
        CallPresence.CONNECTING -> "Connecting…"
        CallPresence.LISTENING -> "Connected · Listening"
        CallPresence.THINKING -> "Thinking…"
        CallPresence.SPEAKING -> "AI speaking"
        CallPresence.RECONNECTING -> "Reconnecting…"
        CallPresence.MUTED -> "AI muted"
        CallPresence.ERROR -> "Agent offline"
        CallPresence.ENDED -> "Call ended"
    }

fun callPresence(
    phase: CallPhase,
    isRecording: Boolean,
    isProcessing: Boolean,
    isAiSpeaking: Boolean,
    aiResponding: Boolean?,
    aiVoiceMuted: Boolean,
    agentOnline: Boolean,
): CallPresence = when {
    phase == CallPhase.ENDED -> CallPresence.ENDED
    phase == CallPhase.RECONNECTING -> CallPresence.RECONNECTING
    aiVoiceMuted -> CallPresence.MUTED
    isAiSpeaking -> CallPresence.SPEAKING
    isProcessing || aiResponding == true -> CallPresence.THINKING
    phase == CallPhase.CONNECTING || phase == CallPhase.RINGING -> CallPresence.CONNECTING
    !agentOnline -> CallPresence.ERROR
    else -> CallPresence.LISTENING
}
