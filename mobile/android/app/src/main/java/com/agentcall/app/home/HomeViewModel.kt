package com.agentcall.app.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentcall.app.call.SignalingClient
import com.agentcall.app.call.VoiceBridgeEvent
import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.api.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val isConnected: Boolean = false,
    val incomingCallId: String? = null,
    val incomingSummary: String? = null,
    val activeCallId: String? = null,
    val statusText: String = "Connecting...",
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val signalingClient: SignalingClient,
) : ViewModel() {

    private val api: ApiService = ApiClient.create()

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        connect()
    }

    fun connect() {
        signalingClient.connect()

        viewModelScope.launch {
            signalingClient.events.collect { event ->
                when (event) {
                    is VoiceBridgeEvent.Connected -> {
                        _uiState.value = _uiState.value.copy(
                            isConnected = true,
                            statusText = "Ready",
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
                        _uiState.value = _uiState.value.copy(
                            incomingCallId = null,
                            activeCallId = null,
                            statusText = "Ready",
                        )
                    }
                    is VoiceBridgeEvent.Disconnected -> {
                        _uiState.value = _uiState.value.copy(
                            isConnected = false,
                            statusText = "Disconnected",
                        )
                    }
                    else -> {}
                }
            }
        }
    }

    private fun checkActiveCall() {
        viewModelScope.launch {
            try {
                val response = api.getActiveCall("solo-user")
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
