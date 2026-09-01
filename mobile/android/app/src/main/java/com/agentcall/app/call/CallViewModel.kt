package com.agentcall.app.call

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentcall.app.call.state.CallMachineEvent
import com.agentcall.app.call.state.CallStateMachine
import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.api.ApiService
import com.agentcall.app.data.repository.CallRepository
import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    /** MCP client that requested the call — caller badge (null = unknown). */
    val clientInfoName: String? = null,
)

private const val TAG = "CallViewModel"

/**
 * Explicit call-phase machine (doc REAL_CALL_IMPROVEMENTS §6.2):
 * - RINGING: AI called the phone; waiting for the user to answer (not used
 *   by CallActivity — IncomingCallActivity owns the pre-answer surface —
 *   kept for parity with the enum).
 * - ACTIVE: answered; transcript + timer live.
 * - RECONNECTING: the signaling socket dropped mid-call; the call is still
 *   alive server-side and resumes on reconnect.
 * - ENDED: terminal; UI shows the final state.
 */
enum class CallPhase { CONNECTING, RINGING, ACTIVE, RECONNECTING, ENDED }

data class ActiveCallUiState(
    val phase: CallPhase = CallPhase.CONNECTING,
    val isConnected: Boolean = false,
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
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private var messageCounter = 0
    private var callStartTime = 0L
    @Volatile private var isSending = false
    private var eventCollectionJob: kotlinx.coroutines.Job? = null
    private var connectionStateJob: kotlinx.coroutines.Job? = null

    // Pure FSM (backlog item 12): phase decisions live here so they are
    // unit-testable; this ViewModel only applies side effects.
    private var machine = CallStateMachine()

    private val _uiState = MutableStateFlow(ActiveCallUiState())
    val uiState: StateFlow<ActiveCallUiState> = _uiState.asStateFlow()

    fun connect(
        callId: String,
        contextSummary: String? = null,
        agentName: String = "AI Agent",
    ) {
        callStartTime = System.currentTimeMillis()
        machine = CallStateMachine()
        machine.onEvent(CallMachineEvent.START_INCOMING)
        _uiState.value = _uiState.value.copy(
            callId = callId,
            agentName = agentName,
            phase = machine.state.phase,
            statusText = "Connecting...",
        )
        // The backend already confirmed the answer over REST before this
        // screen opened; on deployments whose push socket never delivers,
        // call_answered will never arrive — confirm ACTIVE locally so the
        // timer, transcript fallback and controls engage. The backend can
        // flip pending→active a beat after the answer POST lands, so retry
        // briefly instead of reading status once and giving up (a single
        // read can race the answer and leave the call stuck in CONNECTING,
        // which also disables the transcript poll).
        viewModelScope.launch {
            var attempt = 0
            while (attempt < 5 && machine.state.phase != CallPhase.ACTIVE) {
                attempt++
                val st = repository.getCallStatus(callId)
                android.util.Log.w("AgentCall", "ACTIVE-CONFIRM attempt=$attempt status=$st phase=${machine.state.phase}")
                if (st == "active") {
                    applyMachineEvent(CallMachineEvent.CALL_ANSWERED)
                    android.util.Log.w("AgentCall", "ACTIVE-CONFIRM after=${machine.state.phase}")
                    _uiState.value = _uiState.value.copy(statusText = "Connected")
                    break
                }
                delay(2_000)
            }
        }

        viewModelScope.launch {
            try {
                val call = ApiClient.create<ApiService>().getCall(callId)
                val summary = contextSummary?.takeIf { it.isNotBlank() }
                    ?: call.context?.summary?.takeIf { it.isNotBlank() }
                    ?: call.result?.userResponse
                    ?: call.result?.transcriptSummary
                    ?: "AI needs your input."
                val terminal = call.status == "ended" || call.status == "cancelled" || call.status == "expired" || call.status == "aborted"
                if (terminal) machine.onEvent(CallMachineEvent.CALL_ENDED)
                _uiState.value = _uiState.value.copy(
                    isConnected = !terminal,
                    statusText = if (terminal) "Call ended" else "Connected",
                    callContext = CallContextInfo(
                        summary = summary,
                        reason = call.context?.reason ?: "",
                        options = call.context?.options ?: emptyList(),
                        clientInfoName = call.clientInfo?.name,
                    ),
                    aiResponding = call.aiWait?.active,
                    aiRespondingUntilMs = call.aiWait?.activeUntil?.toEpochMsOrNull(),
                    phase = machine.state.phase,
                )
                // 3a — the opening summary must exist as a persistent transcript bubble, not only as the transient banner.
                if (!terminal && summary.isNotBlank()) {
                    val alreadyHasAi = _uiState.value.messages.any { it.role == "ai" && it.text == summary }
                    if (!alreadyHasAi) addAiMessage(summary)
                }
            } catch (e: Exception) {
                Log.w(TAG, "[connect] setup failed callId=$callId", e)
                val fallback = contextSummary?.takeIf { it.isNotBlank() } ?: "AI needs your input."
                _uiState.value = _uiState.value.copy(
                    isConnected = true,
                    statusText = "Connected",
                    callContext = CallContextInfo(summary = fallback),
                )
                if (fallback.isNotBlank()) {
                    val alreadyHasAi = _uiState.value.messages.any { it.role == "ai" && it.text == fallback }
                    if (!alreadyHasAi) addAiMessage(fallback)
                }
            }
        }

        eventCollectionJob?.cancel()
        eventCollectionJob = viewModelScope.launch {
            CallEventBus.events.collect { event ->
                when (event) {
                    is CallEvent.AiMessage -> {
                        applyMachineEvent(CallMachineEvent.FIRST_AI_MESSAGE)
                        addAiMessage(event.text)
                    }
                    is CallEvent.UserMessage -> addUserTranscript(event.messageId, event.text)
                    is CallEvent.UserTextSent -> markUserTextSent(event.messageId)
                    is CallEvent.UserTextFailed -> markUserTextFailed(event.messageId)
                    is CallEvent.CallAnswered -> {
                        applyMachineEvent(CallMachineEvent.CALL_ANSWERED)
                        _uiState.value = _uiState.value.copy(
                            isConnected = true,
                            statusText = "Connected",
                        )
                    }
                    is CallEvent.CallEnded -> {
                        applyMachineEvent(CallMachineEvent.CALL_ENDED)
                        _uiState.value = _uiState.value.copy(
                            isConnected = false,
                            statusText = "Call ended",
                        )
                    }
                    is CallEvent.CallAborted -> {
                        // The agent's process disconnected mid-call — distinct
                        // copy from a normal end/cancel so the user knows the
                        // AI didn't hang up on them.
                        applyMachineEvent(CallMachineEvent.CALL_ENDED)
                        _uiState.value = _uiState.value.copy(
                            isConnected = false,
                            statusText = "AI disconnected — the call ended",
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
                        applyMachineEvent(CallMachineEvent.SOCKET_RECONNECTING)
                        if (_uiState.value.phase == CallPhase.RECONNECTING) {
                            _uiState.value = _uiState.value.copy(
                                statusText = "Reconnecting — call stays live",
                            )
                        }
                    }
                    SignalingClient.ConnectionState.CONNECTED -> {
                        if (current.phase == CallPhase.RECONNECTING) {
                            applyMachineEvent(CallMachineEvent.SOCKET_CONNECTED)
                            reSyncCall(current.callId)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * Feed an event into the pure FSM and apply the resulting phase/connect
     * state. Side-effect strings (statusText) stay in the callers.
     */
    private fun applyMachineEvent(event: CallMachineEvent) {
        val before = machine.state
        val after = machine.onEvent(event)
        if (after == before) return
        // The first ACTIVE entry (answer or reconnect resume) restarts the
        // in-call timer.
        if (after.phase == CallPhase.ACTIVE && before.phase != CallPhase.ACTIVE) {
            callStartTime = System.currentTimeMillis()
        }
        _uiState.value = _uiState.value.copy(
            phase = after.phase,
            isConnected = after.phase != CallPhase.ENDED,
        )
    }

    private fun reSyncCall(callId: String) {
        if (callId.isBlank()) return
        viewModelScope.launch {
            val terminal = try {
                val status = ApiClient.create<ApiService>().getCall(callId).status
                status == "ended" || status == "cancelled" || status == "expired" || status == "aborted"
            } catch (_: Exception) {
                false
            }
            // Keep the FSM as the phase source of truth.
            machine.onEvent(if (terminal) CallMachineEvent.CALL_ENDED else CallMachineEvent.SOCKET_CONNECTED)
            _uiState.value = _uiState.value.copy(
                phase = machine.state.phase,
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
        maybePollTranscript()
    }

    private var lastTranscriptPollMs = 0L
    private var lastTerminalCheckMs = 0L
    private val seenTranscriptKeys = mutableSetOf<String>()

    // WS-down fallback: while the signaling socket is not connected, AI
    // messages pushed over it never arrive. Poll the transcript REST endpoint
    // and render the missing AI bubbles (deduped by role+content+createdAt).
    // When the WS is up this is a no-op — the push path stays the single
    // message source.
    private fun maybePollTranscript() {
        val now = System.currentTimeMillis()
        if (now - lastTranscriptPollMs < 4_000L) return
        lastTranscriptPollMs = now
        if (signalingClient.connectionState.value == SignalingClient.ConnectionState.CONNECTED) return
        val current = _uiState.value
        if (current.callId.isBlank()) return
        if (current.phase != CallPhase.ACTIVE && current.phase != CallPhase.RECONNECTING) return
        viewModelScope.launch {
            // WS-down fallback terminal check (throttled to 20s): with the
            // socket parked the backend can complete a call with no push ever
            // arriving, leaving the UI on "AI speaking" with the screen and
            // foreground service running indefinitely. Poll the status the
            // same way the transcript is polled and end the session locally.
            if (now - lastTerminalCheckMs >= 20_000L) {
                lastTerminalCheckMs = now
                val terminal = runCatching {
                    val status = repository.getCallStatus(current.callId)
                    status == "ended" || status == "cancelled" || status == "expired" || status == "aborted"
                }.getOrDefault(false)
                if (terminal) {
                    applyMachineEvent(CallMachineEvent.CALL_ENDED)
                    _uiState.value = _uiState.value.copy(
                        isConnected = false,
                        statusText = "Call ended",
                        isAiSpeaking = false,
                    )
                    appContext.startService(Intent(appContext, CallService::class.java).apply {
                        action = CallService.ACTION_END_CALL
                        putExtra(CallService.EXTRA_CALL_ID, current.callId)
                    })
                    return@launch
                }
            }
            val msgs = repository.fetchTranscriptRemote(current.callId)
            msgs.forEach { m ->
                if (m.role != "ai" || m.content.isBlank()) return@forEach
                val key = "ai|${m.content}|${m.createdAt}"
                if (seenTranscriptKeys.add(key)) {
                    addAiMessage(m.content)
                    // The WS-down fallback path never pushes ai_message, so the
                    // voice session would stay silent and "Repeat last" would
                    // have nothing recorded. Forward the polled message to
                    // CallService so it is spoken and remembered (idempotent:
                    // CallService only speaks, dedupe lives in this set).
                    appContext.startService(Intent(appContext, CallService::class.java).apply {
                        action = CallService.ACTION_SPEAK
                        putExtra(CallService.EXTRA_CALL_ID, current.callId)
                        putExtra(CallService.EXTRA_TEXT, m.content)
                    })
                }
            }
        }
    }

    fun disconnect() {
        applyMachineEvent(CallMachineEvent.HARD_DISCONNECT)
        _uiState.value = _uiState.value.copy(
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