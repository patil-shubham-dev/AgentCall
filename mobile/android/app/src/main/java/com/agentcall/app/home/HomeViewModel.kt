package com.agentcall.app.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentcall.app.call.SignalingClient
import com.agentcall.app.call.VoiceBridgeEvent
import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.api.ApiService
import com.agentcall.app.data.api.AiKeyItem
import com.agentcall.app.data.database.entity.AiProfileEntity
import com.agentcall.app.data.repository.CallRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val isConnected: Boolean = false,
    val isReconnecting: Boolean = false,
    val statusText: String = "Connecting...",
    val isLoading: Boolean = true,
)

enum class AiPresence { ONLINE, BUSY, OFFLINE }

fun AiKeyItem.toPresence(): AiPresence = when {
    busy -> AiPresence.BUSY
    online -> AiPresence.ONLINE
    else -> AiPresence.OFFLINE
}

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

    /** AI availability, keyed by AI key name (matches profile.name). */
    private val _aiStatus = MutableStateFlow<Map<String, AiKeyItem>>(emptyMap())
    val aiStatus: StateFlow<Map<String, AiKeyItem>> = _aiStatus.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

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

        viewModelScope.launch {
            // Availability poll with exponential backoff on failure so a flaky
            // network never turns into a fixed 15s hammering loop.
            val backoffMs = longArrayOf(15_000, 30_000, 60_000, 120_000)
            var failureStreak = 0
            while (true) {
                if (_uiState.value.isConnected) {
                    val ok = runCatching {
                        ApiClient.ensurePhoneToken()
                        ApiClient.create<ApiService>().listAiKeys()
                    }.onSuccess { response ->
                        _aiStatus.value = response.keys.associateBy { it.name }
                    }.isSuccess
                    failureStreak = if (ok) 0 else failureStreak + 1
                    delay(backoffMs[failureStreak.coerceIn(0, backoffMs.lastIndex)])
                } else {
                    delay(15_000)
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
                    is VoiceBridgeEvent.CallEnded -> {
                        repository.saveCallEnded(event.callId, "ended")
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