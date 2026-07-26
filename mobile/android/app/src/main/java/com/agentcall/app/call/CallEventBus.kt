package com.agentcall.app.call

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Shared event bus that connects CallService (foreground service)
 * to CallViewModel (UI state). This avoids needing to bind the service
 * to the activity — both sides just emit/collect from this bus.
 */
sealed class CallEvent {
    data class AiMessage(val text: String) : CallEvent()
    data class UserMessage(val text: String) : CallEvent()
    data object CallEnded : CallEvent()
}

object CallEventBus {
    private val _events = MutableSharedFlow<CallEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<CallEvent> = _events.asSharedFlow()

    fun emit(event: CallEvent) {
        _events.tryEmit(event)
    }
}
