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
    /**
     * The AI's spoken replies are silenced (transcript still flows). NOT a
     * microphone mute — the name is explicit so screens can't mislabel it.
     */
    val aiVoiceMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val elapsedSeconds: Int = 0,
    val callContext: CallContextInfo = CallContextInfo(),
    val messages: List<ChatBubble> = emptyList(),
    val statusText: String = "Connecting...",
    /**
     * Transient hands-free voice hint (STT degraded / awaiting mic). Rendered
     * only inside the call-stage support line — never as a banner. Cleared by
     * the next user speech event.
     */
    val voiceHint: String? = null,
    /** Normalized microphone energy for the bottom call wave (0..1). */
    val userAudioLevel: Float = 0f,
    /** Normalized AI playback energy for the top call wave (0..1). */
    val aiAudioLevel: Float = 0f,
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

    /**
     * Real audio amplitude buffer (M4): mic RMS while listening and Piper
     * chunk RMS while speaking both feed it; the waveform shows what the
     * audio path actually carries instead of synthetic math.
     */
    private val audioLevels = AudioLevels()

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
            voiceHint = null,
            // Fresh-bound VM is already idle, but sync the route flag from
            // the real device state so consecutive calls can't disagree
            // with what the audio service actually left set.
            isSpeakerOn = audioManager.isSpeakerOn,
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
                    addAiMessage(summary, "summary-${summary.hashCode()}")
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
                    addAiMessage(fallback, "summary-${fallback.hashCode()}")
                }
            }
        }

        eventCollectionJob?.cancel()
        eventCollectionJob = viewModelScope.launch {
            CallEventBus.events.collect { event ->
                when (event) {
                    is CallEvent.AiMessage -> {
                        applyMachineEvent(CallMachineEvent.FIRST_AI_MESSAGE)
                        addAiMessage(event.text, event.messageId)
                    }
                    is CallEvent.UserMessage -> {
                        addUserTranscript(event.messageId, event.text)
                        clearSttHint()
                    }
                    is CallEvent.UserSpeechPartial -> {
                        // Display recognition progress locally only. A partial
                        // may be revised by the recognizer, so it must never
                        // become a transcript bubble or agent-visible event.
                        // Presence and the live waveform carry the state — the
                        // header never shows a competing status line.
                        clearSttHint()
                        _uiState.value = _uiState.value.copy(
                            isAiSpeaking = false,
                            isRecording = true,
                            isProcessing = false,
                        )
                    }
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
                    is CallEvent.AiSpeakingStarted -> {
                        audioLevels.reset(micStream = false)
                        setAiSpeaking(true)
                    }
                    is CallEvent.AiSpeakingFinished -> {
                        audioLevels.reset(micStream = false)
                        setAiSpeaking(false)
                    }
                    is CallEvent.AiWaitStatusChanged ->
                        setAiResponding(event.active, event.activeUntilMs, event.agentOnline)
                    is CallEvent.TtsAmplitude -> pushAmplitude(mic = false, level = event.level)
                    is CallEvent.MicAmplitude -> pushAmplitude(mic = true, level = event.level)
                    is CallEvent.SttFailed -> {
                        // The recognizer failed for good; the listening loop
                        // stands down until it recovers. Surface a quiet hint
                        // in the call stage — banner-free, single source of
                        // truth for "what is happening right now".
                        _uiState.value = _uiState.value.copy(
                            isRecording = false,
                            isProcessing = false,
                            voiceHint = "Voice isn't working right now — you can still type",
                        )
                    }
                    is CallEvent.BargeInDetected -> {
                        // TTS has been stopped before the recognizer starts.
                        // Update local UI immediately rather than waiting for
                        // Piper's non-cancellable JNI synthesis to unwind.
                        audioLevels.reset(micStream = false)
                        clearSttHint()
                        _uiState.value = _uiState.value.copy(
                            isAiSpeaking = false,
                            isRecording = true,
                            isProcessing = false,
                        )
                    }
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
                    SignalingClient.ConnectionState.DISCONNECTED -> {
                        // Mid-call socket loss that arrives as a flat DISCONNECTED
                        // (network callback before the client's own RECONNECTING
                        // emission, or the reconnect machinery giving up after
                        // MAX_RECONNECT_ATTEMPTS). Never tear the call down: the
                        // WS-down fallbacks own recovery from here — the
                        // transcript poll delivers AI messages, the terminal check
                        // ends the call if the backend resolved it, and the
                        // watchdog bounds a genuinely dead session. The FSM only
                        // leaves ACTIVE for an explicitly observed RECONNECTING;
                        // map DISCONNECTED onto it so the reconnect path re-anchors the
                        // timer without resetting it and the next CONNECTED re-syncs.
                        if (current.phase == CallPhase.ACTIVE) {
                            applyMachineEvent(CallMachineEvent.SOCKET_RECONNECTING)
                            _uiState.value = _uiState.value.copy(
                                statusText = "Reconnecting — call stays live",
                            )
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
        // Timer semantics: the FIRST entry into ACTIVE arms the clock.
        // A RECONNECTING→ACTIVE resume must never reset it — the call has
        // kept running server-side, so re-anchor the start so elapsed time
        // is preserved exactly (a phone call's duration is wall time since
        // it first went active, not time since the socket recovered).
        if (after.phase == CallPhase.ACTIVE && before.phase != CallPhase.ACTIVE) {
            callStartTime = if (before.phase == CallPhase.RECONNECTING) {
                System.currentTimeMillis() - _uiState.value.elapsedSeconds * 1000L
            } else {
                System.currentTimeMillis()
            }
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

    fun addAiMessage(text: String, stableId: String? = null) {
        val id = stableId ?: "ai_${messageCounter++}"
        // Idempotent insertion — same id never inserted twice (protects against
        // WS push + transcript poll + summary seeding racing for the same text).
        if (_uiState.value.messages.any { it.id == id }) return
        // Also guard against identical content already present under a different
        // deterministic summary id (e.g. summary hash vs server messageId with same text).
        // For server-originated messages with a real id, id check is sufficient,
        // but summaries seeded locally use content hash.
        if (stableId != null && stableId.startsWith("summary-")) {
            if (_uiState.value.messages.any { it.role == "ai" && it.text == text }) return
        }
        val msg = ChatBubble(
            id = id,
            role = "ai",
            text = text,
            timestamp = System.currentTimeMillis(),
        )
        // NOTE: isAiSpeaking is NOT set here on purpose — the real playback
        // start (AiSpeakingStarted from TTS) is the single authority for the
        // speaking state. A message bubble can be inserted while the user is
        // mid-utterance; setting speaking here produced contradictory status.
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + msg,
        )
    }

    fun addUserTranscript(messageId: String, text: String) {
        if (_uiState.value.messages.any { it.id == messageId }) return
        val msg = ChatBubble(
            id = messageId,
            role = "user",
            text = text,
        )
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + msg,
            // The user's finalized turn is the boundary — nothing can still
            // be "thinking" once a transcript bubble exists or the AI starts.
            isAiSpeaking = false,
            isProcessing = false,
        )
    }

    fun sendTextMessage(text: String, messageId: String = UUID.randomUUID().toString()) {
        if (text.isBlank() || isSending) return
        // Idempotent: typed message that already exists (retry / double tap)
        if (_uiState.value.messages.any { it.id == messageId }) return
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
        )
    }

    fun retryUserText(context: Context, messageId: String, text: String) {
        val cid = _uiState.value.callId
        if (cid.isBlank()) return
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.map {
                if (it.id == messageId) it.copy(failed = false) else it
            },
        )
        context.startService(Intent(context, CallService::class.java).apply {
            action = CallService.ACTION_SEND_TEXT
            putExtra(CallService.EXTRA_CALL_ID, cid)
            putExtra(CallService.EXTRA_TEXT, text)
            putExtra(CallService.EXTRA_MESSAGE_ID, messageId)
        })
    }

    private fun clearSttHint() {
        if (_uiState.value.voiceHint != null) {
            _uiState.value = _uiState.value.copy(voiceHint = null)
        }
    }

    fun setAiSpeaking(speaking: Boolean) {
        _uiState.value = _uiState.value.copy(
            isAiSpeaking = speaking,
            isProcessing = if (speaking) false else _uiState.value.isProcessing,
        )
    }

    fun setAiResponding(active: Boolean, activeUntilMs: Long?, agentOnline: Boolean = true) {
        _uiState.value = _uiState.value.copy(
            aiResponding = active,
            aiRespondingUntilMs = if (active) activeUntilMs else null,
            agentOnline = agentOnline,
        )
    }

    fun setAiVoiceMuted(context: Context, muted: Boolean) {
        _uiState.value = _uiState.value.copy(aiVoiceMuted = muted)
        context.startService(Intent(context, CallService::class.java).apply {
            action = CallService.ACTION_SET_MUTED
            putExtra(CallService.EXTRA_MUTED, muted)
        })
    }

    /**
     * Audio-route picker support: only routes Android actually reports;
     * selection is applied to the communication device (S+) or the legacy
     * speakerphone flag. `isSpeakerOn` is synced from the real resulting
     * state, not the request.
     */
    fun availableAudioRoutes(): List<CallAudioManager.AudioRoute> = audioManager.availableRoutes()

    fun currentAudioRouteId(): String = audioManager.currentRouteId()

    fun selectAudioRoute(route: CallAudioManager.AudioRoute): Boolean {
        val next = audioManager.selectRoute(route)
        _uiState.value = _uiState.value.copy(isSpeakerOn = next)
        return next
    }

    /**
     * New amplitude chunk → buffer + full-state snapshot at the producers'
     * cadence (~10 Hz each, already throttled upstream). The call aura is the
     * only thing recomposing on these.
     */
    private fun pushAmplitude(mic: Boolean, level: Float) {
        if (mic) audioLevels.onMic(level) else audioLevels.onTts(level)
        val state = _uiState.value
        val energy = audioLevels.energyLevels(
            listeningActive = state.isRecording,
            speakingActive = state.isAiSpeaking,
            muted = state.aiVoiceMuted,
        )
        _uiState.value = state.copy(
            userAudioLevel = energy.user,
            aiAudioLevel = energy.ai,
        )
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
        // ENDED freezes the timer so the summary panel shows the call's real
        // duration, not time-since-end.
        val elapsed = if (current.phase == CallPhase.ENDED) {
            current.elapsedSeconds
        } else if (callStartTime > 0) {
            ((System.currentTimeMillis() - callStartTime) / 1000).toInt()
        } else {
            current.elapsedSeconds
        }
        // Idle envelope falls back to the quiet baseline; amplitude events
        // overwrite this at ~10 Hz whenever a stream is actually live.
        audioLevels.decayTowardBaseline(current.isRecording, current.isAiSpeaking)
        val energy = audioLevels.energyLevels(
            listeningActive = current.isRecording,
            speakingActive = current.isAiSpeaking,
            muted = current.aiVoiceMuted,
        )
        _uiState.value = current.copy(
            elapsedSeconds = elapsed,
            userAudioLevel = energy.user,
            aiAudioLevel = energy.ai,
        )
        maybePollTranscript()
    }

    // MARK: removed dead code — setPaused() had no callers (the paused banner
    // was never reachable) and disconnect() was never invoked by any screen.

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
                if (!seenTranscriptKeys.add(key)) return@forEach
                // Dedupe against already-visible messages (summary seeding or
                // prior poll). Use the transcript key as stable id so the same
                // server message never inserts twice even after process recreation.
                val stableId = "transcript-${m.createdAt}-${m.content.hashCode()}"
                if (_uiState.value.messages.any { it.id == stableId }) return@forEach
                // Also guard identical text already shown as summary
                if (_uiState.value.messages.any { it.role == "ai" && it.text == m.content }) return@forEach
                addAiMessage(m.content, stableId)
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

private fun String.toEpochMsOrNull(): Long? =
    try {
        if (isBlank()) null else java.time.Instant.parse(this).toEpochMilli()
    } catch (_: Exception) {
        null
    }
