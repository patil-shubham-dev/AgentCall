package com.agentcall.app.call

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.api.ApiService
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

/**
 * Explicit call-phase machine (doc REAL_CALL_IMPROVEMENTS §6.2):
 * - OUTGOING: user dialed; waiting for the AI to pick up (ringback plays).
 * - RINGING: AI called the phone; waiting for the user to answer (not used
 *   by CallActivity — IncomingCallActivity owns the pre-answer surface —
 *   kept for parity with the enum).
 * - ACTIVE: answered; transcript + timer live.
 * - RECONNECTING: the signaling socket dropped mid-call; the call is still
 *   alive server-side and resumes on reconnect.
 * - ENDED: terminal; UI shows the final state.
 */
enum class CallPhase { CONNECTING, OUTGOING, RINGING, ACTIVE, RECONNECTING, ENDED }

data class ActiveCallUiState(
    val phase: CallPhase = CallPhase.CONNECTING,
    val isConnected: Boolean = false,
    val isOutgoing: Boolean = false,
    val agentName: String = "AI Agent",
    val isRecording: Boolean = false,
    val isProcessing: Boolean = false,
    val isAiSpeaking: Boolean = false,
    val isPaused: Boolean = false,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val elapsedSeconds: Int = 0,
    val callContext: CallContextInfo = CallContextInfo(),
    val messages: List<ChatBubble> = emptyList(),
    val statusText: String = "Connecting...",
    val waveformLevels: List<Float> = List(40) { 0.08f },
    val peakWaveformLevel: Float = 0f,
    val callId: String = "",
    val aiResponding: Boolean? = null,
    val aiRespondingUntilMs: Long? = null,
    val agentOnline: Boolean = true,
)

@HiltViewModel
class CallViewModel @Inject constructor(
    private val repository: CallRepository,
    private val signalingClient: SignalingClient,
    private val audioManager: CallAudioManager,
) : ViewModel() {

    private var messageCounter = 0
    private var callStartTime = 0L
    @Volatile private var isSending = false
    private var eventCollectionJob: kotlinx.coroutines.Job? = null
    private var connectionStateJob: kotlinx.coroutines.Job? = null

    private val _uiState = MutableStateFlow(ActiveCallUiState())
    val uiState: StateFlow<ActiveCallUiState> = _uiState.asStateFlow()

    fun connect(
        callId: String,
        contextSummary: String? = null,
        agentName: String = "AI Agent",
        isOutgoing: Boolean = false,
    ) {
        callStartTime = System.currentTimeMillis()
        _uiState.value = _uiState.value.copy(
            callId = callId,
            agentName = agentName,
            isOutgoing = isOutgoing,
            phase = if (isOutgoing) CallPhase.OUTGOING else CallPhase.CONNECTING,
            statusText = if (isOutgoing) "Calling..." else "Connecting...",
        )

        viewModelScope.launch {
            try {
                val api: ApiService = ApiClient.create()
                val call = api.getCall(callId)
                val summary = contextSummary?.takeIf { it.isNotBlank() }
                    ?: call.context?.summary?.takeIf { it.isNotBlank() }
                    ?: call.result?.userResponse
                    ?: call.result?.transcriptSummary
                    ?: "AI needs your input."
                val terminal = call.status == "ended" || call.status == "cancelled" || call.status == "expired"
                _uiState.value = _uiState.value.copy(
                    isConnected = !terminal,
                    statusText = when {
                        terminal -> "Call ended"
                        isOutgoing -> "Calling..."
                        else -> "Connected"
                    },
                    callContext = CallContextInfo(
                        summary = summary,
                        reason = call.context?.reason ?: "",
                        options = call.context?.options ?: emptyList(),
                    ),
                    aiResponding = call.aiWait?.active,
                    aiRespondingUntilMs = call.aiWait?.activeUntil?.toEpochMsOrNull(),
                    phase = when {
                        terminal -> CallPhase.ENDED
                        isOutgoing -> CallPhase.OUTGOING
                        else -> CallPhase.ACTIVE
                    },
                )
                if (isOutgoing && !terminal) {
                    audioManager.requestFocus()
                }
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(
                    isConnected = true,
                    statusText = if (isOutgoing) "Calling..." else "Connected",
                    callContext = CallContextInfo(summary = contextSummary ?: "AI needs your input."),
                )
            }
        }

        eventCollectionJob?.cancel()
        eventCollectionJob = viewModelScope.launch {
            CallEventBus.events.collect { event ->
                when (event) {
                    is CallEvent.AiMessage -> {
                        promoteToActive()
                        addAiMessage(event.text)
                    }
                    is CallEvent.UserMessage -> addUserTranscript(event.messageId, event.text)
                    is CallEvent.UserTextSent -> markUserTextSent(event.messageId)
                    is CallEvent.UserTextFailed -> markUserTextFailed(event.messageId)
                    is CallEvent.CallAnswered -> {
                        promoteToActive()
                        _uiState.value = _uiState.value.copy(
                            isConnected = true,
                            statusText = "Connected",
                        )
                    }
                    is CallEvent.CallEnded -> {
                        _uiState.value = _uiState.value.copy(
                            phase = CallPhase.ENDED,
                            isConnected = false,
                            statusText = "Call ended",
                        )
                    }
                    is CallEvent.AiSpeakingStarted -> setAiSpeaking(true)
                    is CallEvent.AiSpeakingFinished -> setAiSpeaking(false)
                    is CallEvent.AiWaitStatusChanged ->
                        setAiResponding(event.active, event.activeUntilMs, event.agentOnline)
                }
            }
        }

        // Phase-2 reconnect state: a mid-call socket drop must surface as
        // "Reconnecting..." (never as a hung call), and a reconnect re-syncs
        // the call so a call that ended while offline lands in ENDED.
        connectionStateJob?.cancel()
        connectionStateJob = viewModelScope.launch {
            signalingClient.connectionState.collect { state ->
                val current = _uiState.value
                when (state) {
                    SignalingClient.ConnectionState.RECONNECTING -> {
                        if (current.phase == CallPhase.ACTIVE) {
                            _uiState.value = current.copy(
                                phase = CallPhase.RECONNECTING,
                                statusText = "Reconnecting — call stays live",
                            )
                        }
                    }
                    SignalingClient.ConnectionState.CONNECTED -> {
                        if (current.phase == CallPhase.RECONNECTING) {
                            reSyncCall(current.callId)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    /** Outgoing dial completes on the first backend confirmation. */
    private fun promoteToActive() {
        val current = _uiState.value
        if (current.phase != CallPhase.OUTGOING) return
        callStartTime = System.currentTimeMillis()
        _uiState.value = current.copy(
            phase = CallPhase.ACTIVE,
            isConnected = true,
            statusText = "Connected",
        )
    }

    private fun reSyncCall(callId: String) {
        if (callId.isBlank()) return
        viewModelScope.launch {
            val terminal = try {
                val status = ApiClient.create<ApiService>().getCall(callId).status
                status == "ended" || status == "cancelled" || status == "expired"
            } catch (_: Exception) {
                false
            }
            _uiState.value = _uiState.value.copy(
                phase = if (terminal) CallPhase.ENDED else CallPhase.ACTIVE,
                isConnected = !terminal,
                statusText = if (terminal) "Call ended" else "Connected",
            )
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

    fun setAiResponding(active: Boolean, activeUntilMs: Long?, agentOnline: Boolean = true) {
        _uiState.value = _uiState.value.copy(
            aiResponding = active,
            aiRespondingUntilMs = if (active) activeUntilMs else null,
            agentOnline = agentOnline,
        )
    }

    fun setMuted(context: Context, muted: Boolean) {
        _uiState.value = _uiState.value.copy(isMuted = muted)
        context.startService(Intent(context, CallService::class.java).apply {
            action = CallService.ACTION_SET_MUTED
            putExtra(CallService.EXTRA_MUTED, muted)
        })
    }

    fun toggleSpeaker(): Boolean {
        val next = audioManager.toggleSpeaker()
        _uiState.value = _uiState.value.copy(isSpeakerOn = next)
        return next
    }

    fun tick() {
        var current = _uiState.value
        // Passive lease expiry — mirrors the backend (no lease survives its
        // activeUntil), so a missed/delayed WS push can't leave the banner stale.
        val untilMs = current.aiRespondingUntilMs
        if (current.aiResponding == true && untilMs != null && System.currentTimeMillis() > untilMs) {
            current = current.copy(aiResponding = false, aiRespondingUntilMs = null)
            _uiState.value = current
        }
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
            phase = CallPhase.ENDED,
            isConnected = false,
            statusText = "Disconnected",
            isAiSpeaking = false,
            isRecording = false,
            isProcessing = false,
            aiResponding = null,
            aiRespondingUntilMs = null,
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

private fun String.toEpochMsOrNull(): Long? =
    try {
        if (isBlank()) null else java.time.Instant.parse(this).toEpochMilli()
    } catch (_: Exception) {
        null
    }