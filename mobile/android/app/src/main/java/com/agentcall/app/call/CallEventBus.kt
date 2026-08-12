package com.agentcall.app.call

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class CallEvent {
    data class AiMessage(val text: String) : CallEvent()
    data class UserMessage(val messageId: String, val text: String) : CallEvent()
    data class UserTextSent(val messageId: String) : CallEvent()
    data class UserTextFailed(val messageId: String, val text: String) : CallEvent()
    data object CallAnswered : CallEvent()
    data object CallEnded : CallEvent()
    data object AiSpeakingStarted : CallEvent()
    data object AiSpeakingFinished : CallEvent()
    data class AiWaitStatusChanged(
        val active: Boolean,
        val activeUntilMs: Long?,
        val lastActiveAtMs: Long?,
        val agentOnline: Boolean = true,
    ) : CallEvent()
}

object CallEventBus {
    private val _events = MutableSharedFlow<CallEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<CallEvent> = _events.asSharedFlow()

    fun emit(event: CallEvent) {
        _events.tryEmit(event)
    }
}