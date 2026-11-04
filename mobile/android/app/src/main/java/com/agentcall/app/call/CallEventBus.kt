package com.agentcall.app.call

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.merge

sealed class CallEvent {
    data class AiMessage(val text: String, val messageId: String? = null) : CallEvent()
    data class UserMessage(val messageId: String, val text: String) : CallEvent()
    /** Ephemeral recognizer hypothesis; never persisted or sent to the agent. */
    data class UserSpeechPartial(val text: String) : CallEvent()
    /**
     * Speech recognition failed for good (after retries, or the recognizer
     * can't be created at all). The UI must leave its optimistic Listening
     * state — the mic button silently failing otherwise shows "Listening..."
     * for a dead session.
     */
    data class SttFailed(val reason: String? = null) : CallEvent()
    data class UserTextSent(val messageId: String) : CallEvent()
    data class UserTextFailed(val messageId: String, val text: String) : CallEvent()
    data object CallAnswered : CallEvent()
    data object CallEnded : CallEvent()
    /** The agent's process disconnected mid-call — show "AI disconnected", not a generic end. */
    data class CallAborted(val reason: String) : CallEvent()
    data object AiSpeakingStarted : CallEvent()
    data object AiSpeakingFinished : CallEvent()
    data class AiWaitStatusChanged(
        val active: Boolean,
        val activeUntilMs: Long?,
        val lastActiveAtMs: Long?,
        val agentOnline: Boolean = true,
    ) : CallEvent()

    /** Barge-in: user speech detected while TTS was playing; audio cut and STT handoff started. */
    data class BargeInDetected(val atMs: Long = System.currentTimeMillis()) : CallEvent()

    /** Real playback amplitude (0..1) from the last played TTS chunk. */
    data class TtsAmplitude(val level: Float) : CallEvent()

    /** Microphone level in recognizer RMS dB (device-dependent); [AudioLevels] normalizes it. */
    data class MicAmplitude(val level: Float) : CallEvent()
}

object CallEventBus {
    // Keep lifecycle/state events separate from the high-rate amplitude stream so
    // a busy collector cannot evict CallEnded, UserMessage, or mute acknowledgement.
    private val lifecycle = MutableSharedFlow<CallEvent>(extraBufferCapacity = 64)
    private val amplitudes = MutableSharedFlow<CallEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: Flow<CallEvent> = merge(lifecycle.asSharedFlow(), amplitudes.asSharedFlow())

    fun emit(event: CallEvent) {
        when (event) {
            is CallEvent.TtsAmplitude, is CallEvent.MicAmplitude -> amplitudes.tryEmit(event)
            else -> lifecycle.tryEmit(event)
        }
    }
}
