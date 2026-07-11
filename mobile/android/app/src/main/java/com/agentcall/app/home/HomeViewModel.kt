package com.agentcall.app.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.api.ApiService
import com.agentcall.app.data.api.TokenManager
import com.agentcall.app.data.model.CallResponse
import com.agentcall.app.data.model.PresenceResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val calls: List<CallResponse> = emptyList(),
    val presence: PresenceResponse? = null,
    val activeCall: String? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val tokenManager: TokenManager
) : ViewModel() {

    private val api: ApiService = ApiClient.create()

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun loadCallHistory() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val response = api.getCallHistory()
                _uiState.value = _uiState.value.copy(
                    calls = response.calls.filter { it.status != "connected" },
                    activeCall = response.calls.find { it.status == "connected" }?.callId,
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load calls",
                )
            }
        }
    }

    fun refreshPresence() {
        viewModelScope.launch {
            try {
                val userId = tokenManager.userId ?: return@launch
                val presence = api.getUserPresence(userId)
                _uiState.value = _uiState.value.copy(presence = presence)
            } catch (_: Exception) { }
        }
    }
}
