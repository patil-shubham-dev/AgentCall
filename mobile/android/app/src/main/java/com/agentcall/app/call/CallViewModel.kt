package com.agentcall.app.call

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.api.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.sin

data class ChatBubble(
    val id: String,
    val role: String,
    val text: String,
    val emotion: String = "neutral",
    val timestamp: Long = System.currentTimeMillis(),
)

data class CallContextInfo(
    val summary: String = "",
    val reason: String = "",
    val options: List<String> = emptyList(),
)

data class ActiveCallUiState(
    val isConnected: Boolean = false,
    val isRecording: Boolean = false,
    val isProcessing: Boolean = false,
    val isAiSpeaking: Boolean = false,
    val isBargeIn: Boolean = false,
    val isPaused: Boolean = false,
    val isAITyping: Boolean = false,
    val elapsedSeconds: Int = 0,
    val callContext: CallContextInfo = CallContextInfo(),
    val messages: List<ChatBubble> = emptyList(),
    val statusText: String = "Connecting...",
    val waveformLevels: List<Float> = List(40) { 0.08f },
    val peakWaveformLevel: Float = 0f,
)

@HiltViewModel
class CallViewModel @Inject constructor() : ViewModel() {

    private val api: ApiService = ApiClient.create()
    private var messageCounter = 0

    private val _uiState = MutableStateFlow(ActiveCallUiState())
    val uiState: StateFlow<ActiveCallUiState> = _uiState.asStateFlow()

    fun connect(callId: String) {
        viewModelScope.launch {
            try {
                val call = api.getCall(callId)
                val summary = call.result?.transcriptSummary ?: call.result?.userResponse ?: "AI needs your input."
                _uiState.value = _uiState.value.copy(
                    isConnected = true,
                    statusText = "Connected",
                    callContext = CallContextInfo(summary = summary),
                )
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(
                    isConnected = true,
                    statusText = "Connected",
                )
            }
        }
    }

    fun addAiMessage(text: String, emotion: String = "neutral") {
        val msg = ChatBubble(
            id = "ai_${messageCounter++}",
            role = "ai",
            text = text,
            emotion = emotion,
            timestamp = System.currentTimeMillis(),
        )
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + msg,
            statusText = when (emotion) {
                "urgent" -> "AI is urgent"
                "excited" -> "AI is excited"
                "thoughtful" -> "AI is thinking"
                else -> "AI speaking"
            },
            isAiSpeaking = true,
            isAITyping = false,
        )
    }

    fun showAITyping() {
        _uiState.value = _uiState.value.copy(isAITyping = true, isAiSpeaking = false)
    }

    fun addUserTranscript(text: String) {
        val msg = ChatBubble(
            id = "user_${messageCounter++}",
            role = "user",
            text = text,
        )
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + msg,
            isAiSpeaking = false,
            statusText = "You said: ${text.take(50)}",
        )
    }

    fun setBargeIn(bargeIn: Boolean) {
        _uiState.value = _uiState.value.copy(
            isBargeIn = bargeIn,
            statusText = if (bargeIn) "You interrupted — processing..." else "",
        )
    }

    fun setPaused(paused: Boolean) {
        _uiState.value = _uiState.value.copy(
            isPaused = paused,
            statusText = if (paused) "Call paused — press Record when ready" else "Resumed",
        )
    }

    fun setRecording(recording: Boolean) {
        _uiState.value = _uiState.value.copy(
            isRecording = recording,
            statusText = if (recording) "Listening..." else "Processing...",
            isProcessing = !recording,
        )
    }

    fun setAiSpeaking(speaking: Boolean) {
        _uiState.value = _uiState.value.copy(isAiSpeaking = speaking)
    }

    fun tick() {
        val current = _uiState.value
        val t = current.elapsedSeconds.toFloat()
        val levels = generateWaveform(t, !current.isRecording && !current.isAiSpeaking)
        _uiState.value = current.copy(
            elapsedSeconds = current.elapsedSeconds + 1,
            waveformLevels = levels,
            peakWaveformLevel = levels.maxOrNull() ?: 0.08f,
        )
    }

    fun disconnect() {
        _uiState.value = _uiState.value.copy(
            isConnected = false,
            statusText = "Disconnected",
            isAiSpeaking = false,
        )
    }

    private fun generateWaveform(t: Float, silent: Boolean): List<Float> {
        val base = if (silent) 0.04f else 0.08f
        return List(40) { i ->
            if (silent) {
                base + 0.02f * abs(sin(t * 0.3f + i * 0.5f))
            } else {
                val freq = 2f + 3f * abs(sin(i * 0.1f))
                val envelope = 1f - (i - 20f) * (i - 20f) / 400f
                val signal = abs(sin(t * freq * 0.5f + i * 0.3f) * 0.7f + sin(t * 1.3f + i * 0.7f) * 0.3f)
                (base + signal * envelope * 0.5f).coerceIn(0.02f, 0.95f)
            }
        }
    }
}
