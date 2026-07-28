package com.agentcall.app.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentcall.app.call.SignalingClient
import com.agentcall.app.call.VoiceBridgeEvent
import com.agentcall.app.data.database.entity.AiProfileEntity
import com.agentcall.app.data.repository.CallRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val isConnected: Boolean = false,
    val isReconnecting: Boolean = false,
    val statusText: String = "Connecting...",
    val isLoading: Boolean = true,
)

data class IncomingCallEvent(
    val callId: String,
    val callerName: String,
    val summary: String,
)

object ServerConfigEvent {
    val reconnectRequests = MutableStateFlow(0)
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val signalingClient: SignalingClient,
    private val repository: CallRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val profiles: StateFlow<List<AiProfileEntity>> = repository
        .getAllProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

    private val _incomingCallEvents = Channel<IncomingCallEvent>(Channel.BUFFERED)
    val incomingCallEvents = _incomingCallEvents.receiveAsFlow()

    private var eventsJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            signalingClient.connectionState.collect { state ->
                _uiState.value = _uiState.value.copy(
                    isReconnecting = state == SignalingClient.ConnectionState.RECONNECTING,
                    statusText = when (state) {
                        SignalingClient.ConnectionState.CONNECTING -> "Connecting..."
                        SignalingClient.ConnectionState.RECONNECTING -> "Reconnecting..."
                        SignalingClient.ConnectionState.DISCONNECTED -> "Disconnected"
                        SignalingClient.ConnectionState.CONNECTED -> _uiState.value.statusText
                    },
                )
            }
        }

        connect()

        viewModelScope.launch {
            ServerConfigEvent.reconnectRequests.collect { count ->
                if (count > 0) {
                    delay(200)
                    reconnect()
                }
            }
        }
    }

    fun connect() {
        signalingClient.connect()
        eventsJob?.cancel()
        eventsJob = viewModelScope.launch {
            signalingClient.events.collect { event ->
                when (event) {
                    is VoiceBridgeEvent.Connected -> {
                        _uiState.value = _uiState.value.copy(
                            isConnected = true,
                            isReconnecting = false,
                            statusText = "Ready",
                            isLoading = false,
                        )
                    }
                    is VoiceBridgeEvent.CallIncoming -> {
                        val agentId = event.callerName.lowercase().replace("\\s+".toRegex(), "-")
                        repository.ensureProfileExists(agentId, event.callerName)
                        _incomingCallEvents.send(
                            IncomingCallEvent(event.callId, event.callerName, event.summary)
                        )
                    }
                    is VoiceBridgeEvent.CallEnded -> {
                        repository.saveCallEnded(event.callId, "ended")
                    }
                    is VoiceBridgeEvent.CallCancelled -> {
                    }
                    is VoiceBridgeEvent.Disconnected -> {
                        _uiState.value = _uiState.value.copy(
                            isConnected = false,
                            isReconnecting = false,
                            statusText = "Disconnected",
                        )
                    }
                    is VoiceBridgeEvent.Error -> {
                        _snackbarEvents.tryEmit("Error: ${event.message}")
                    }
                    else -> {}
                }
            }
        }
    }

    fun reconnect() {
        signalingClient.disconnect()
        _uiState.value = _uiState.value.copy(
            isConnected = false,
            isReconnecting = true,
            statusText = "Reconnecting...",
        )
        viewModelScope.launch {
            delay(300)
            connect()
        }
    }
}