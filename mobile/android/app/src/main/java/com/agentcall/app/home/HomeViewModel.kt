package com.agentcall.app.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentcall.app.call.SignalingClient
import com.agentcall.app.call.VoiceBridgeEvent
import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.api.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class HomeUiState(
    val isConnected: Boolean = false,
    val isReconnecting: Boolean = false,
    val connectionQuality: ConnectionQuality = ConnectionQuality.UNKNOWN,
    val incomingCallId: String? = null,
    val incomingSummary: String? = null,
    val activeCallId: String? = null,
    val statusText: String = "Connecting...",
    val recentCalls: List<RecentCallEntry> = emptyList(),
    val isLoading: Boolean = true,
)

enum class ConnectionQuality {
    UNKNOWN, POOR, FAIR, GOOD, EXCELLENT
}

data class RecentCallEntry(
    val callId: String,
    val summary: String,
    val reason: String,
    val status: String,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Shared counter that increments whenever the settings screen changes the server host.
 * HomeViewModel watches this and reconnects automatically.
 */
object ServerConfigEvent {
    val reconnectRequests = MutableStateFlow(0)
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val signalingClient: SignalingClient,
) : ViewModel() {

    private val api: ApiService = ApiClient.create()

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

    private var eventsJob: kotlinx.coroutines.Job? = null
    private var connectionCheckJob: kotlinx.coroutines.Job? = null

    init {
        // Track signaling client connection state for reconnection UI
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
        // Watch for server IP changes from settings and reconnect
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
        startConnectionQualityCheck()

        eventsJob?.cancel()
        eventsJob = viewModelScope.launch {
            signalingClient.events.collect { event ->
                when (event) {
                    is VoiceBridgeEvent.Connected -> {
                        _uiState.value = _uiState.value.copy(
                            isConnected = true,
                            isReconnecting = false,
                            connectionQuality = ConnectionQuality.GOOD,
                            statusText = "Ready",
                            isLoading = false,
                        )
                        checkActiveCall()
                    }
                    is VoiceBridgeEvent.CallIncoming -> {
                        _uiState.value = _uiState.value.copy(
                            incomingCallId = event.callId,
                            incomingSummary = event.summary,
                            statusText = "Incoming call",
                        )
                    }
                    is VoiceBridgeEvent.CallEnded,
                    is VoiceBridgeEvent.CallCancelled -> {
                        val current = _uiState.value
                        if (current.activeCallId != null) {
                            val entry = RecentCallEntry(
                                callId = current.activeCallId,
                                summary = current.statusText,
                                reason = "completed",
                                status = if (event is VoiceBridgeEvent.CallCancelled) "cancelled" else "ended",
                            )
                            _uiState.value = current.copy(
                                incomingCallId = null,
                                activeCallId = null,
                                statusText = "Ready",
                                recentCalls = listOf(entry) + current.recentCalls.take(9),
                            )
                        } else {
                            _uiState.value = current.copy(
                                incomingCallId = null,
                                activeCallId = null,
                                statusText = "Ready",
                            )
                        }
                    }
                    is VoiceBridgeEvent.Disconnected -> {
                        _uiState.value = _uiState.value.copy(
                            isConnected = false,
                            isReconnecting = false,
                            connectionQuality = ConnectionQuality.UNKNOWN,
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
        connectionCheckJob?.cancel()
        _uiState.value = _uiState.value.copy(
            isConnected = false,
            isReconnecting = true,
            connectionQuality = ConnectionQuality.UNKNOWN,
            statusText = "Reconnecting...",
        )
        viewModelScope.launch {
            delay(300)
            connect()
        }
    }

    private fun startConnectionQualityCheck() {
        connectionCheckJob?.cancel()
        connectionCheckJob = viewModelScope.launch {
            var goodPings = 0
            var missedPings = 0
            while (true) {
                delay(5000)
                val current = _uiState.value
                if (!current.isConnected) continue
                val result = withContext(Dispatchers.IO) {
                    try {
                        api.getActiveCall("solo-user")
                        Pair(goodPings + 1, 0)
                    } catch (_: Exception) {
                        Pair(0, missedPings + 1)
                    }
                }
                goodPings = result.first
                missedPings = result.second
                val quality = when {
                    goodPings > 3 -> ConnectionQuality.EXCELLENT
                    goodPings > 1 -> ConnectionQuality.GOOD
                    goodPings > 0 -> ConnectionQuality.FAIR
                    missedPings > 2 -> ConnectionQuality.POOR
                    else -> ConnectionQuality.FAIR
                }
                if (current.connectionQuality != quality || (missedPings > 3 && current.statusText != "Connection lost")) {
                    _uiState.value = _uiState.value.copy(
                        connectionQuality = quality,
                        statusText = if (missedPings > 3) "Connection lost" else current.statusText,
                    )
                }
            }
        }
    }

    private fun checkActiveCall() {
        viewModelScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    api.getActiveCall("solo-user")
                }
                if (response.activeCall != null) {
                    _uiState.value = _uiState.value.copy(
                        activeCallId = response.activeCall.callId,
                    )
                }
            } catch (_: Exception) {}
        }
    }

    fun clearIncoming() {
        _uiState.value = _uiState.value.copy(incomingCallId = null, incomingSummary = null)
    }

    fun clearActiveCall() {
        _uiState.value = _uiState.value.copy(activeCallId = null)
    }
}
