package com.agentcall.app.call

import androidx.lifecycle.ViewModel
import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.api.ApiService
import com.agentcall.app.data.api.TokenManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

data class CallUiState(
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val elapsedSeconds: Int = 0,
    val status: CallConnectionStatus = CallConnectionStatus.CONNECTING,
    val connectionQuality: Float = 0.85f,
    val waveformLevels: List<Float> = List(40) { 0.08f },
)

enum class CallConnectionStatus {
    CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, FAILED
}

@HiltViewModel
class CallViewModel @Inject constructor(
    private val tokenManager: TokenManager,
) : ViewModel() {

    private val api: ApiService = ApiClient.create()

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    fun toggleMute() {
        _uiState.value = _uiState.value.copy(isMuted = !_uiState.value.isMuted)
    }

    fun toggleSpeaker() {
        _uiState.value = _uiState.value.copy(isSpeakerOn = !_uiState.value.isSpeakerOn)
    }

    fun tick() {
        val current = _uiState.value
        val t = current.elapsedSeconds.toFloat()
        _uiState.value = current.copy(
            elapsedSeconds = current.elapsedSeconds + 1,
            waveformLevels = generateWaveform(t, current.isMuted),
        )
    }

    private fun generateWaveform(t: Float, muted: Boolean): List<Float> {
        val base = if (muted) 0.04f else 0.08f
        return List(40) { i ->
            if (muted) {
                base + 0.02f * abs(sin(t * 0.3f + i * 0.5f))
            } else {
                val freq = 2f + 3f * abs(sin(i * 0.1f))
                val envelope = 1f - (i - 20f) * (i - 20f) / 400f
                val signal = abs(sin(t * freq * 0.5f + i * 0.3f) * 0.7f + sin(t * 1.3f + i * 0.7f) * 0.3f)
                (base + signal * envelope * 0.5f).coerceIn(0.02f, 0.95f)
            }
        }
    }

    fun setStatus(status: CallConnectionStatus) {
        _uiState.value = _uiState.value.copy(status = status)
    }

    fun setConnectionQuality(quality: Float) {
        _uiState.value = _uiState.value.copy(connectionQuality = quality.coerceIn(0f, 1f))
    }
}
