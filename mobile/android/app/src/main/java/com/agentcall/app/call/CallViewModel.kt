package com.agentcall.app.call

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.api.ApiService
import com.agentcall.app.data.database.dao.TranscriptMessageDao
import com.agentcall.app.data.repository.CallRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.sin

data class ChatBubble(
    val id: String,
    val role: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val failed: Boolean = false,
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
    val isPaused: Boolean = false,
    val elapsedSeconds: Int = 0,
    val callContext: CallContextInfo = CallContextInfo(),
    val messages: List<ChatBubble> = emptyList(),
    val statusText: String = "Connecting...",
    val waveformLevels: List<Float> = List(40) { 0.08f },
    val peakWaveformLevel: Float = 0f,
    val callId: String = "",
)

@HiltViewModel
class CallViewModel @Inject constructor(
    private val repository: CallRepository,
) : ViewModel() {

    private var messageCounter = 0
    private var callStartTime = 0L
    @Volatile private var isSending = false
    private var eventCollectionJob: kotlinx.coroutines.Job? = null

    private val _uiState = MutableStateFlow(ActiveCallUiState())
    val uiState: StateFlow<ActiveCallUiState> = _uiState.asStateFlow()

    fun connect(callId: String, contextSummary: String? = null) {
        callStartTime = System.currentTimeMillis()
        _uiState.value = _uiState.value.copy(callId = callId)

        viewModelScope.launch {
            try {
                val api: ApiService = ApiClient.create()
                val call = api.getCall(callId)
                val summary = contextSummary?.takeIf { it.isNotBlank() }
                    ?: call.context?.summary?.takeIf { it.isNotBlank() }
                    ?: call.result?.userResponse
                    ?: call.result?.transcriptSummary
                    ?: "AI needs your input."
                _uiState.value = _uiState.value.copy(
                    isConnected = true,
                    statusText = "Connected",
                    callContext = CallContextInfo(
                        summary = summary,
                        reason = call.context?.reason ?: "",
                        options = call.context?.options ?: emptyList(),
                    ),
                )
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(
                    isConnected = true,
                    statusText = "Connected",
                    callContext = CallContextInfo(summary = contextSummary ?: "AI needs your input."),
                )
            }
        }

        eventCollectionJob?.cancel()
        eventCollectionJob = viewModelScope.launch {
            CallEventBus.events.collect { event ->
                when (event) {
                    is CallEvent.AiMessage -> addAiMessage(event.text)
                    is CallEvent.UserMessage -> addUserTranscript(event.messageId, event.text)
                    is CallEvent.UserTextSent -> markUserTextSent(event.messageId)
                    is CallEvent.UserTextFailed -> markUserTextFailed(event.messageId)
                    is CallEvent.CallAnswered -> {
                        _uiState.value = _uiState.value.copy(
                            isConnected = true,
                            statusText = "Connected",
                        )
                    }
                    is CallEvent.CallEnded -> disconnect()
                    is CallEvent.AiSpeakingStarted -> setAiSpeaking(true)
                    is CallEvent.AiSpeakingFinished -> setAiSpeaking(false)
                }
            }
        }
    }

    fun addAiMessage(text: String) {
        val msg = ChatBubble(
            id = "ai_${messageCounter++}",
            role = "ai",
            text = text,
            timestamp = System.currentTimeMillis(),
        )
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + msg,
            statusText = "AI speaking",
            isAiSpeaking = true,
        )
        val cid = _uiState.value.callId
        if (cid.isNotBlank()) {
            viewModelScope.launch { repository.saveAiMessage(cid, text) }
        }
    }

    fun addUserTranscript(messageId: String, text: String) {
        val msg = ChatBubble(
            id = messageId,
            role = "user",
            text = text,
        )
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + msg,
            isAiSpeaking = false,
            statusText = "You said: ${text.take(50)}",
        )
        val cid = _uiState.value.callId
        if (cid.isNotBlank()) {
            viewModelScope.launch { repository.saveUserTextMessage(cid, text) }
        }
    }

    fun sendTextMessage(text: String, messageId: String = UUID.randomUUID().toString()) {
        if (text.isBlank() || isSending) return
        isSending = true
        val cid = _uiState.value.callId
        if (cid.isBlank()) { isSending = false; return }

        val msg = ChatBubble(
            id = messageId,
            role = "user",
            text = text,
        )
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + msg,
            statusText = "You typed: ${text.take(50)}",
        )

        viewModelScope.launch {
            repository.saveUserTextMessage(cid, text)
            isSending = false
        }
    }

    fun markUserTextSent(messageId: String) {
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.map {
                if (it.id == messageId) it.copy(failed = false) else it
            },
        )
    }

    fun markUserTextFailed(messageId: String) {
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.map {
                if (it.id == messageId && it.role == "user") it.copy(failed = true) else it
            },
            statusText = "Message not sent — tap to retry",
        )
    }

    fun retryUserText(context: Context, messageId: String, text: String) {
        val cid = _uiState.value.callId
        if (cid.isBlank()) return
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.map {
                if (it.id == messageId) it.copy(failed = false) else it
            },
            statusText = "Resending...",
        )
        context.startService(Intent(context, CallService::class.java).apply {
            action = CallService.ACTION_SEND_TEXT
            putExtra(CallService.EXTRA_CALL_ID, cid)
            putExtra(CallService.EXTRA_TEXT, text)
            putExtra(CallService.EXTRA_MESSAGE_ID, messageId)
        })
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
        val elapsed = if (callStartTime > 0) ((System.currentTimeMillis() - callStartTime) / 1000).toInt() else current.elapsedSeconds
        val t = elapsed.toFloat()
        val levels = generateWaveform(t, !current.isRecording && !current.isAiSpeaking)
        _uiState.value = current.copy(
            elapsedSeconds = elapsed,
            waveformLevels = levels,
            peakWaveformLevel = levels.maxOrNull() ?: 0.08f,
        )
    }

    fun disconnect() {
        _uiState.value = _uiState.value.copy(
            isConnected = false,
            statusText = "Disconnected",
            isAiSpeaking = false,
            isRecording = false,
            isProcessing = false,
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