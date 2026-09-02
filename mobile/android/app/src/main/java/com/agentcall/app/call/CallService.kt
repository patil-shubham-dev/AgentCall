package com.agentcall.app.call

import android.app.*
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.agentcall.app.BuildConfig
import com.agentcall.app.ForegroundTracker
import com.agentcall.app.MainActivity
import com.agentcall.app.R
import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.api.ApiService
import com.agentcall.app.data.api.CallbackRequest
import com.agentcall.app.data.api.CancelRequest
import com.agentcall.app.data.api.CompleteRequest
import com.agentcall.app.data.api.CompleteResultPayload
import com.agentcall.app.data.database.dao.TranscriptMessageDao
import com.agentcall.app.data.repository.CallRepository
import com.agentcall.app.settings.MessageTemplates
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.coroutineContext
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class CallService : Service() {

    @Inject lateinit var signalingClient: SignalingClient
    @Inject lateinit var transcriptDao: TranscriptMessageDao
    @Inject lateinit var repository: CallRepository
    @Inject lateinit var audioManager: CallAudioManager

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Survives stopSelf(): End/Done + Decline persist their intent first, then retry
    // on this scope so a service teardown can never cancel a pending delivery.
    private val retryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    // Battery audit H2: force-ends a wedged call (backend silence, UI gone)
    // at the max-call duration. Cancelled by endCall() on every terminal path.
    private var callWatchdogJob: Job? = null
    // Debug builds only: adb-injected override of the watchdog/wake-lock
    // duration so on-device verification can exercise the wedge scenario in
    // seconds instead of 30 minutes. Release builds never read it.
    @Volatile private var debugMaxCallMs = 0L
    // Backlog item 13: one-shot resource-release guard — every terminal path
    // funnels through releaseCallResources(), and double-releases are no-ops.
    private var resourcesReleased = false
    private var ttsIdleJob: kotlinx.coroutines.Job? = null

    var callId: String? = null
    var textToSpeech: TextToSpeech? = null
    private var ttsInitialized = false
    private var ttsInitJob: Job? = null
    // Backlog item 11 — bundled Piper TTS (sherpa-onnx, offline, no first-run
    // voice-install dependency). Initialized lazily on an IO coroutine: the
    // 81 MB model extraction + ONNX load blocks for seconds and must never
    // run on the main thread. Speech is paced sentence-by-sentence through a
    // single serialized worker; the system TextToSpeech engine remains as the
    // fallback when Piper is unavailable.
    private val piperEngine = PiperTtsEngine(this)
    @Volatile private var piperReady = false
    private var piperInitJob: Job? = null
    @Volatile private var piperAttempted = false
    private val speechChannel = Channel<String>(Channel.UNLIMITED)
    private var speechWorker: Job? = null
    @Volatile var isRecording = false
    @Volatile var isAiSpeaking = false
    @Volatile var isPaused = false
    /** Muted: the AI's spoken replies are silenced (transcript still flows). */
    @Volatile var isMuted = false

    private var transcriptSequence = 0
    private var lastAiMessage: String = ""
    private var incomingSummary: String? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var pendingSpeakText: String? = null

    // TTS latency telemetry: answer -> first spoken word, per service instance.
    private var callStartMs = 0L
    private var ttsInitStartMs = 0L
    private var firstWordLogged = false
    private val speakRequestedAt = mutableMapOf<String, Long>()

    private val api: ApiService = ApiClient.create()

    override fun onCreate() {
        super.onCreate()
        initTts()
    }

    private fun initTts() {
        if (ttsInitialized && textToSpeech != null) return
        if (textToSpeech == null) ttsInitStartMs = System.currentTimeMillis()
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsInitialized = true
                textToSpeech?.language = Locale.US
                // Route TTS through the voice-communication path instead of
                // the media stream: the default USAGE_MEDIA plays out of the
                // loudspeaker regardless of the speaker toggle. With
                // USAGE_VOICE_COMMUNICATION the audio follows the
                // communication device — earpiece by default, loudspeaker
                // when the Speaker button (CallAudioManager) toggles it,
                // and Bluetooth/wired headsets when connected.
                textToSpeech?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        if (utteranceId == WARMUP_UTTERANCE_ID) return
                        val now = System.currentTimeMillis()
                        val synthMs = speakRequestedAt.remove(utteranceId)?.let { now - it }
                        isAiSpeaking = true
                        if (!firstWordLogged) {
                            firstWordLogged = true
                            Log.i(
                                TAG,
                                "[TTS] first word: answer->word=${now - callStartMs}ms " +
                                    "init=${now - ttsInitStartMs}ms synth=${synthMs}ms",
                            )
                        }
                        CallEventBus.emit(CallEvent.AiSpeakingStarted)
                    }

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == WARMUP_UTTERANCE_ID) {
                            deleteWarmupFile()
                            return
                        }
                        isAiSpeaking = false
                        CallEventBus.emit(CallEvent.AiSpeakingFinished)
                    }

                    override fun onError(utteranceId: String?) {
                        if (utteranceId == WARMUP_UTTERANCE_ID) return
                        isAiSpeaking = false
                    }
                })
                val queued = pendingSpeakText
                if (queued != null) {
                    pendingSpeakText = null
                    speakText(queued)
                } else {
                    warmUpTts()
                }
            }
        }
    }

    private val listenerMap = mutableMapOf<String, (Bundle?) -> Unit>()

    private fun dispatchToListeners(type: String, data: Bundle?) {
        listenerMap[type]?.invoke(data)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Snapshot before handling the intent: pending entries flushed here must
        // be ones that predate this start, or a freshly-enqueued message would be
        // attempted twice (flush job + its own retry loop) and POSTed twice.
        val pendingUserTexts = snapshotPendingUserTexts()
        scope.launch {
            flushPending(KEY_PENDING_CANCELS) { attemptCancel(it) }
            flushPending(KEY_PENDING_COMPLETES) { attemptComplete(it) }
            flushPending(KEY_PENDING_ANSWERS) { attemptAnswer(it) }
            flushPending(KEY_PENDING_CALLBACKS) { attemptScheduleCallback(it) }
            flushPendingUserTexts(pendingUserTexts)
        }
        when (intent?.action) {
            ACTION_PREWARM_TTS -> {
                if (textToSpeech == null) initTts()
                // Piper needs no user-gated voice install and is the primary
                // engine — kick off its (slow) init at ring time so the
                // greeting is ready when the call starts. A failed attempt
                // this ring is retried on the next ring.
                piperAttempted = false
                ensurePiperInit()
            }
            ACTION_START_CALL -> {
                val id = intent.getStringExtra(EXTRA_CALL_ID) ?: return START_NOT_STICKY
                val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "AI Agent"
                // Backlog item 14 — duplicate-answer guard: the notification's
                // direct Answer action, the full-screen Answer button, and a
                // stale FSI for an already-answered call can each fire an
                // ACTION_START_CALL for the same call. Only the first may open
                // a session; a second START_CALL for the same call is a silent
                // no-op, and a START_CALL for a different call while this one
                // owns the state is ignored (single-ring design).
                val state = CallStateHolder.state.value
                if (state.callId != null && state.callId != id) {
                    Log.w(TAG, "[ANSWER] ignoring START_CALL for $id — ${state.status} for ${state.callId}")
                    return START_STICKY
                }
                if (state.status == CallStatus.ANSWERED || state.status == CallStatus.ENDED) {
                    Log.i(TAG, "[ANSWER] duplicate START_CALL for $id (${state.status}) — skipping")
                    return START_STICKY
                }
                if (BuildConfig.DEBUG) {
                    debugMaxCallMs = intent.getLongExtra(EXTRA_DEBUG_MAX_CALL_MS, 0L)
                }
                // Battery audit H2: everything from resource acquisition (wake
                // lock, audio focus) to session start is guarded — an exception
                // mid-setup must tear down exactly like a user-initiated end,
                // or the wake lock and foreground notification outlive a call
                // that never started.
                try {
                    hasActiveCall = true
                    CallStateHolder.answered(id)
                    // A fresh call owns fresh resources (the service instance may
                    // have survived an earlier call's endCall + idle TTS shutdown).
                    resourcesReleased = false
                    ttsIdleJob?.cancel()
                    callId = id
                    callStartMs = System.currentTimeMillis()
                    firstWordLogged = false
                    transcriptSequence = 0
                    incomingSummary = intent.getStringExtra(EXTRA_CONTEXT_SUMMARY)
                    // A parked websocket (idle background) must be restored before
                    // AiMessage events can reach this session.
                    signalingClient.connectIfIdle()
                    // Runs after hasActiveCall=true so the FGS sees the live call
                    // and keeps itself alive instead of parking.
                    SignalingForegroundService.notifyRingResolved(this)
                    // Fresh call, fresh Piper attempt: a previous call may have
                    // failed init (transient I/O/memory), and the ring-time prewarm
                    // may not have run on this process instance.
                    piperAttempted = false
                    ensurePiperInit()
                    startForeground(NOTIFICATION_ID_ONGOING, createOngoingCallNotification("AI Call"))
                    acquireWakeLock(effectiveMaxCallMs() + WAKELOCK_TIMEOUT_BUFFER_MS)
                    audioManager.requestFocus()
                    // Earpiece by default, like a real phone call. Explicit even
                    // though the route usually defaults there: on pre-S devices
                    // isSpeakerphoneOn persists across calls, so a previous
                    // speakerphone call would leave the next one blaring.
                    audioManager.setSpeakerphone(false)
                    // Direct Answer action from the incoming-call notification
                    // landed here — the ring UI is not open to dismiss it.
                    cancelIncomingNotification(this)
                    val agentId = callerName.lowercase().replace("\\s+".toRegex(), "-")
                    scope.launch { repository.markCallAnswered(id, agentId, callerName) }
                    startVoiceSession(id)
                    startCallWatchdog()
                    retryWithBackoff(id, KEY_PENDING_ANSWERS, "ANSWER") { attemptAnswer(it) }
                    // §4: Answer from notification/lock screen must bring the
                    // Active Call UI to the foreground, not just start the
                    // background service. IncomingCallActivity's in-app Answer
                    // already shows ActiveCallScreen via showCall=true, so it
                    // sets EXTRA_LAUNCH_UI=false to avoid a duplicate launch.
                    val shouldLaunchUi = intent.getBooleanExtra(EXTRA_LAUNCH_UI, true)
                    if (shouldLaunchUi) {
                        try {
                            val callIntent = Intent(this, CallActivity::class.java).apply {
                                putExtra(EXTRA_CALL_ID, id)
                                putExtra(EXTRA_CALLER_NAME, callerName)
                                putExtra(EXTRA_CONTEXT_SUMMARY, incomingSummary ?: "")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            }
                            startActivity(callIntent)
                        } catch (_: Exception) { }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "[CALL] session setup failed — forcing teardown for $id", t)
                    CallStateHolder.ended(id)
                    CallEventBus.emit(CallEvent.CallEnded)
                    endCall()
                    stopSelf()
                }
            }
            ACTION_START_RECORDING -> startRecording()
            ACTION_STOP_RECORDING -> stopRecording()
            ACTION_SPEAK -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return START_NOT_STICKY
                // The fallback path (WS-down) delivers AI messages via the
                // transcript poll in CallViewModel; it forwards them here so
                // they are spoken AND recorded as lastAiMessage — otherwise
                // "Repeat last" is a silent no-op on such deployments.
                lastAiMessage = text
                speakText(text)
            }
            ACTION_SET_MUTED -> {
                isMuted = intent.getBooleanExtra(EXTRA_MUTED, false)
                updateNotification(if (isMuted) "Muted" else "AI speaking enabled")
                Log.i(TAG, "[MUTE] muted=$isMuted")
            }
            ACTION_REPEAT_LAST -> {
                if (lastAiMessage.isNotBlank()) {
                    speakText(lastAiMessage, force = true)
                }
            }
            ACTION_SEND_TEXT -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return START_NOT_STICKY
                val cid = intent.getStringExtra(EXTRA_CALL_ID) ?: callId ?: return START_NOT_STICKY
                val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID) ?: UUID.randomUUID().toString()
                enqueueUserText(cid, messageId, text)
            }
            ACTION_END_CALL -> terminateCall(intent.getStringExtra(EXTRA_CALL_ID) ?: callId)
            ACTION_CANCEL_CALL -> {
                val id = intent.getStringExtra(EXTRA_CALL_ID) ?: return START_NOT_STICKY
                // Backlog item 14 — an answered call must never be cancelled:
                // the ring UI's countdown auto-decline can race the answer tap,
                // and a late duplicate cancel can trail a resolved ring.
                val state = CallStateHolder.state.value
                if (state.status == CallStatus.ANSWERED && state.callId == id) {
                    Log.i(TAG, "[CANCEL] ignoring cancel for answered call $id")
                    return START_STICKY
                }
                CallStateHolder.ended(id)
                // Direct Decline action from the incoming-call notification
                // landed here — the ring UI is not open to dismiss it.
                cancelIncomingNotification(this)
                val note = intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }
                if (note != null) {
                    savePendingNote(id, note)
                    // Local transcript: the decline note shows in
                    // history before the backend syncs it (backlog item 5).
                    // Written once per action; saveTranscriptLocally later
                    // replaces the local copy with server truth, so a retried
                    // backend POST can never duplicate the row.
                    scope.launch { repository.saveUserTextMessage(id, note) }
                }
                retryWithBackoff(id, KEY_PENDING_CANCELS, "CANCEL") { attemptCancel(it) }
                if (!intent.getBooleanExtra(EXTRA_FROM_TIMEOUT, false)) {
                    // Clear the FGS ring state: a decline via the notification's
                    // direct action (or the activity) leaves the ring armed, and
                    // the 60s timeout would then send a duplicate decline note.
                    // Timeout-originated cancels skip this — the FGS already
                    // cleared its ring and must stay alive for call_expired.
                    SignalingForegroundService.notifyRingResolved(this)
                }
                // Battery audit H3: a declined/expired ring never opened a
                // session, but the ring-time prewarm may have loaded Piper and
                // onCreate already bound system TTS. Without this cleanup the
                // sticky service stays resident holding both engines (~100 MB+)
                // until process death. Same funnel as endCall(): idle shutdown
                // schedules the engine release; stopSelf() drops the service.
                // Retry jobs live on retryScope and survive teardown by design,
                // and ttsIdleJob is cancelled by any subsequent START_CALL.
                if (!hasActiveCall) {
                    scheduleTtsIdleShutdown()
                    stopSelf()
                }
            }
            ACTION_SCHEDULE_CALLBACK -> {
                val id = intent.getStringExtra(EXTRA_CALL_ID) ?: return START_NOT_STICKY
                val delayMin = intent.getStringExtra(EXTRA_TEXT)?.toIntOrNull() ?: 10
                val note = intent.getStringExtra(EXTRA_NOTE)?.takeIf { it.isNotBlank() }
                savePendingCallback(id, delayMin, note)
                retryWithBackoff(id, KEY_PENDING_CALLBACKS, "CALLBACK") { attemptScheduleCallback(it) }
                // Battery audit H3: same ring-resolution cleanup as CANCEL —
                // "Later" also ends a ring that never opened a session, so the
                // prewarmed engines must not keep the sticky service resident.
                if (!hasActiveCall) {
                    scheduleTtsIdleShutdown()
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun retryWithBackoff(callId: String, key: String, tag: String, attempt: suspend (String) -> Boolean) {
        persistPendingId(key, callId)
        retryScope.launch {
            val backoffMs = longArrayOf(1000, 2000, 4000, 8000, 16000, 32000, 60000)
            for (delayMs in backoffMs) {
                if (attempt(callId)) {
                    removePendingId(key, callId)
                    return@launch
                }
                delay(delayMs)
            }
            if (attempt(callId)) {
                removePendingId(key, callId)
            } else {
                Log.w(TAG, "[$tag] retries exhausted for $callId; kept pending for next service start")
            }
        }
    }

    private suspend fun attemptCancel(callId: String): Boolean {
        return try {
            api.cancelCall(callId, CancelRequest(note = pendingNoteFor(callId)))
            repository.saveCallEnded(callId, "cancelled")
            Log.i(TAG, "[CANCEL] backend confirmed cancel for $callId")
            true
        } catch (e: Exception) {
            Log.w(TAG, "[CANCEL] cancel $callId failed, will retry", e)
            false
        }
    }

    private suspend fun attemptComplete(callId: String): Boolean {
        return try {
            // Backlog item 3: surface the call summary. The client-derived
            // recap (last AI message) travels with the COMPLETE request so the
            // backend result carries it even when the AI never sent one.
            api.completeCall(
                callId,
                CompleteRequest(result = CompleteResultPayload(transcriptSummary = deriveSummary())),
            )
            // Then persist the authoritative recap — the backend's computed
            // transcriptSummary when present, else what we derived.
            runCatching {
                val call = api.getCall(callId)
                (call.result?.transcriptSummary ?: deriveSummary())?.let {
                    repository.saveCallSummary(callId, it)
                }
            }
            Log.i(TAG, "[COMPLETE] backend confirmed completion for $callId")
            true
        } catch (e: Exception) {
            Log.w(TAG, "[COMPLETE] complete $callId failed, will retry", e)
            false
        }
    }

    /**
     * Backlog item 3: the recap shown in history = the last AI message,
     * truncated to a readable one-liner (140 chars, no trailing punctuation).
     */
    private fun deriveSummary(): String? {
        val text = lastAiMessage.trim()
        if (text.isBlank()) return null
        return text.take(140).trimEnd('.', '!', '?', ' ', ',', ':', ';')
    }

    private suspend fun attemptAnswer(callId: String): Boolean {
        return try {
            api.answerCall(callId)
            Log.i(TAG, "[ANSWER] backend confirmed answer for $callId")
            true
        } catch (e: Exception) {
            Log.w(TAG, "[ANSWER] answer $callId failed, will retry", e)
            false
        }
    }

    private suspend fun attemptScheduleCallback(callId: String): Boolean {
        return try {
            val request = pendingCallbackFor(callId)
            if (request == null) {
                Log.w(TAG, "[CALLBACK] no pending callback payload for $callId")
                return true
            }
            api.scheduleCallback(callId, request)
            Log.i(TAG, "[CALLBACK] backend confirmed callback for $callId")
            true
        } catch (e: Exception) {
            Log.w(TAG, "[CALLBACK] schedule $callId failed, will retry", e)
            false
        }
    }

    private fun savePendingCallback(callId: String, delayMinutes: Int, note: String?) {
        val entry = JSONObject()
            .put("delayMinutes", delayMinutes)
            .putOpt("note", note)
            .toString()
        getSharedPreferences(PREFS_CALL_STATE, MODE_PRIVATE)
            .edit().putString("callback:$callId", entry).apply()
    }

    private fun pendingCallbackFor(callId: String): CallbackRequest? {
        val raw = getSharedPreferences(PREFS_CALL_STATE, MODE_PRIVATE)
            .getString("callback:$callId", null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            CallbackRequest(
                delayMinutes = obj.optInt("delayMinutes", 10),
                note = obj.optString("note").ifBlank { null },
            )
        }.getOrNull()
    }

    private suspend fun flushPending(key: String, attempt: suspend (String) -> Boolean) {
        val pending = getSharedPreferences(PREFS_CALL_STATE, MODE_PRIVATE)
            .getStringSet(key, emptySet())
            ?.toList()
            ?: return
        for (callId in pending) {
            if (attempt(callId)) {
                removePendingId(key, callId)
            }
        }
    }

    private fun persistPendingId(key: String, callId: String) {
        val prefs = getSharedPreferences(PREFS_CALL_STATE, MODE_PRIVATE)
        val ids = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (ids.add(callId)) {
            prefs.edit().putStringSet(key, ids).apply()
        }
    }

    private fun removePendingId(key: String, callId: String) {
        val prefs = getSharedPreferences(PREFS_CALL_STATE, MODE_PRIVATE)
        val ids = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: return
        if (ids.remove(callId)) {
            prefs.edit().putStringSet(key, ids).remove("note:$callId").remove("callback:$callId").apply()
        }
    }

    private fun savePendingNote(callId: String, note: String) {
        getSharedPreferences(PREFS_CALL_STATE, MODE_PRIVATE)
            .edit().putString("note:$callId", note).apply()
    }

    private fun pendingNoteFor(callId: String): String? {
        return getSharedPreferences(PREFS_CALL_STATE, MODE_PRIVATE)
            .getString("note:$callId", null)
    }

    fun speakText(text: String, force: Boolean = false) {
        if (!force && isPaused) return
        if (isMuted && !force) {
            // Muted: silence the voice but keep the transcript truthful — the
            // last message still updates so "Repeat last" works after unmuting.
            lastAiMessage = text
            Log.d(TAG, "[MUTE] skipping speech (muted): ${text.take(60)}")
            return
        }
        if (text.isBlank()) return
        // Single serialized worker (speechLoop) paces Piper sentence-by-
        // sentence or hands the message to the system TTS engine — one AI
        // message at a time, no interleaving between the two engines.
        enqueueSpeech(text)
    }

    @Synchronized
    private fun enqueueSpeech(text: String) {
        speechChannel.trySend(text)
        if (speechWorker?.isActive != true) {
            speechWorker = scope.launch { speechLoop() }
        }
    }

    private suspend fun speechLoop() {
        for (text in speechChannel) {
            if (!coroutineContext.isActive) break
            if (isPaused) continue
            speakPaced(text)
        }
    }

    private suspend fun speakPaced(text: String) {
        // Piper-first: bundled, offline, no first-run voice install, and it
        // gives per-sentence pacing. If init is still running (cold ring, the
        // prewarm races the answer), wait up to a short cap — init blocks for
        // seconds, so an unready engine falls back to system TTS for this
        // message rather than delaying the first word.
        val initJob = ensurePiperInit()
        if (piperReady) {
            speakWithPiper(text)
            return
        }
        if (initJob != null) {
            // Battery audit M3(b): coroutine-native wait — the join() suspends
            // (zero wakeups) instead of polling piperReady at 20 Hz. The cap
            // keeps the first word fast: an engine that is still initializing
            // after PIPER_WAIT_MS hands this message to system TTS.
            withTimeoutOrNull(PIPER_WAIT_MS) { initJob.join() }
        }
        if (piperReady) {
            speakWithPiper(text)
        } else {
            speakWithSystemTts(text)
        }
    }

    /**
     * Starts Piper init once per call. Idempotent, synchronized, never touches
     * the main thread (81 MB model extraction + ONNX load). Returns the running
     * init job so callers can wait on it; null when already ready or already
     * failed (a failed attempt is retried on the next ring/call via
     * [piperAttempted] reset).
     */
    @Synchronized
    private fun ensurePiperInit(): Job? {
        if (piperReady) return null
        if (piperInitJob?.isActive == true) return piperInitJob
        if (piperAttempted) return null
        piperAttempted = true
        val job = scope.launch {
            piperReady = piperEngine.init()
            Log.i(TAG, "[PIPER] init finished ready=$piperReady")
        }
        piperInitJob = job
        return job
    }

    private suspend fun speakWithPiper(text: String) {
        val sentences = SpeechPacing.splitIntoSentences(text)
        if (sentences.isEmpty()) return
        if (!firstWordLogged) {
            firstWordLogged = true
            Log.i(TAG, "[TTS] piper first word: answer->word=${System.currentTimeMillis() - callStartMs}ms")
        }
        isAiSpeaking = true
        CallEventBus.emit(CallEvent.AiSpeakingStarted)
        try {
            sentences.forEachIndexed { index, sentence ->
                if (!coroutineContext.isActive || piperEngine.stopRequested) return@forEachIndexed
                if (index > 0) {
                    // Breathing pause before every sentence but the first.
                    // Battery audit M3(c): a single cancellable delay replaces
                    // the 20 Hz stopRequested poll — every requestStop() caller
                    // also cancels this worker job (releaseCallResources), so
                    // cancellation propagation interrupts the pause instantly;
                    // the post-delay check covers the flag-only path.
                    delay(SpeechPacing.sentenceDelayMs())
                    if (!coroutineContext.isActive || piperEngine.stopRequested) return@forEachIndexed
                }
                val speed = SpeechPacing.sentenceSpeed()
                withContext(Dispatchers.Default) {
                    piperEngine.speakSentence(sentence, speed)
                }
            }
        } finally {
            isAiSpeaking = false
            CallEventBus.emit(CallEvent.AiSpeakingFinished)
        }
    }

    private fun speakWithSystemTts(text: String) {
        val tts = textToSpeech
        if (tts == null || !ttsInitialized) {
            pendingSpeakText = text
            if (tts == null) initTts()
            return
        }
        val utteranceId = UUID.randomUUID().toString()
        speakRequestedAt[utteranceId] = System.currentTimeMillis()
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    private suspend fun speakTextOnMain(text: String) = withContext(Dispatchers.Main) {
        speakText(text)
    }

    // Warms the engine's voice data with a silent file synthesis so the first
    // real utterance never pays the lazy voice-load cost. Runs after any queued
    // greeting so it can never delay the user's first spoken word.
    private fun warmUpTts() {
        val tts = textToSpeech ?: return
        try {
            tts.synthesizeToFile(WARMUP_TEXT, null, File(cacheDir, WARMUP_FILE), WARMUP_UTTERANCE_ID)
        } catch (e: Exception) {
            Log.w(TAG, "[TTS] warm-up synthesis failed", e)
        }
    }

    private fun deleteWarmupFile() {
        try {
            File(cacheDir, WARMUP_FILE).delete()
        } catch (_: Exception) {}
    }

    private fun startVoiceSession(callId: String) {
        scope.launch {
            var summary = "AI needs your input."

            // Subscribe to signaling events FIRST — before any network call —
            // so the collector is ready before any WebSocket AiMessage can arrive.
            val eventsJob = launch {
                signalingClient.events.collect { event ->
                    when (event) {
                        is VoiceBridgeEvent.AiMessage -> {
                            if (event.callId != callId) return@collect
                            speakTextOnMain(event.content)
                            lastAiMessage = event.content
                            CallEventBus.emit(CallEvent.AiMessage(event.content))
                            repository.saveAiMessage(callId, event.content)
                        }
                        is VoiceBridgeEvent.CallAnswered -> {
                            if (event.callId != callId) return@collect
                            Log.i(TAG, "[WS] backend confirmed answer for $callId")
                            CallEventBus.emit(CallEvent.CallAnswered)
                        }
                        is VoiceBridgeEvent.CallEnded -> {
                            if (event.callId != callId) return@collect
                            CallEventBus.emit(CallEvent.CallEnded)
                            speakTextOnMain("Call ended.")
                            repository.saveCallEnded(callId, "ended")
                            deriveSummary()?.let { repository.saveCallSummary(callId, it) }
                            delay(1500); stopSelf()
                        }
                        is VoiceBridgeEvent.CallCancelled -> {
                            if (event.callId != callId) return@collect
                            CallEventBus.emit(CallEvent.CallEnded)
                            speakTextOnMain("Call was cancelled.")
                            repository.saveCallEnded(callId, "cancelled")
                            deriveSummary()?.let { repository.saveCallSummary(callId, it) }
                            delay(1500); stopSelf()
                        }
                        is VoiceBridgeEvent.CallExpired -> {
                            if (event.callId != callId) return@collect
                            // Missed call: the ring window closed before
                            // anyone answered (agent offline / no answer).
                            CallEventBus.emit(CallEvent.CallEnded)
                            repository.saveCallEnded(callId, "expired")
                            deriveSummary()?.let { repository.saveCallSummary(callId, it) }
                            // Backlog item 1: when the app is backgrounded the
                            // miss must still surface — a silent notification
                            // that deep-links into the agent's profile. (The
                            // SignalingForegroundService is the primary path
                            // for unanswered rings; this covers the edge where
                            // the service was bound to the call.)
                            if (!ForegroundTracker.isForeground) {
                                scope.launch {
                                    repository.getCallRecord(callId)?.let { record ->
                                        showMissedCallNotification(
                                            this@CallService,
                                            callId,
                                            record.callerName,
                                            record.agentId,
                                        )
                                    }
                                }
                            }
                            delay(1500); stopSelf()
                        }
                        is VoiceBridgeEvent.CallAborted -> {
                            // Distinct terminal outcome: the agent's process
                            // died mid-call, not a user cancel. Emit the
                            // dedicated event so the UI can say "AI disconnected"
                            // instead of the generic "Call ended".
                            if (event.callId != callId) return@collect
                            CallEventBus.emit(CallEvent.CallAborted(event.reason))
                            speakTextOnMain("The AI disconnected. Call ended.")
                            repository.saveCallEnded(callId, "aborted")
                            deriveSummary()?.let { repository.saveCallSummary(callId, it) }
                            delay(1500); stopSelf()
                        }
                        is VoiceBridgeEvent.AiWaitStatus -> {
                            if (event.callId != callId) return@collect
                            CallEventBus.emit(
                                CallEvent.AiWaitStatusChanged(
                                    event.active,
                                    event.activeUntilMs,
                                    event.lastActiveAtMs,
                                    event.agentOnline,
                                )
                            )
                        }
                        // Disconnected is defined but NOT emitted by SignalingClient.
                        // The client handles reconnection internally (onFailure/onClosed).
                        // If ever wired up, should show "Reconnecting..." UI, NOT tear down call.
                        else -> {}
                    }
                }
            }

            try {
                val call = api.getCall(callId)
                summary = incomingSummary?.takeIf { it.isNotBlank() }
                    ?: call.context?.summary?.takeIf { it.isNotBlank() }
                    ?: call.result?.userResponse
                    ?: call.result?.transcriptSummary
                    ?: "AI needs your input."
                Log.i(TAG, "[GREET] source=${if (incomingSummary?.isNotBlank() == true) "incoming" else if (call.context?.summary?.isNotBlank() == true) "api-context" else "fallback"} summary=\"$summary\"")
            } catch (e: Exception) {
                Log.w(TAG, "[WS] failed to fetch call data on start, continuing anyway", e)
                summary = incomingSummary?.takeIf { it.isNotBlank() } ?: "AI needs your input."
                Log.i(TAG, "[GREET] source=incoming-or-fallback summary=\"$summary\"")
            }

            // Give the user a beat to bring the phone to their ear after
            // answering — the AI's first words start ~1s after the answer
            // tap, like the ring-out silence on a real call.
            delay(1000)
            speakTextOnMain(summary)
            if (lastAiMessage.isBlank()) {
                lastAiMessage = summary
            }
        }
    }

    private fun startRecording() {
        if (isRecording || speechRecognizer != null) return
        try {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            if (recognizer == null) return
            speechRecognizer = recognizer
            isRecording = true
            updateNotification("Recording...")

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = texts?.firstOrNull()
                    isRecording = false
                    recognizer.destroy()
                    if (speechRecognizer == recognizer) speechRecognizer = null
                    val currentCallId = callId
                    if (text != null && text.isNotBlank() && currentCallId != null) {
                        val messageId = UUID.randomUUID().toString()
                        scope.launch {
                            processUserText(text, currentCallId, messageId)
                            repository.saveUserTextMessage(currentCallId, text)
                        }
                    } else {
                        updateNotification("Paused")
                    }
                }

                override fun onError(error: Int) {
                    isRecording = false
                    recognizer.destroy()
                    if (speechRecognizer == recognizer) speechRecognizer = null
                    speakText("Sorry, I didn't catch that.")
                    updateNotification("Paused")
                }

                override fun onReadyForSpeech(params: Bundle) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle) {}
                override fun onEvent(eventType: Int, params: Bundle) {}
            })

            recognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "[STT] failed to start recording", e)
            isRecording = false; speechRecognizer = null
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        updateNotification("Processing...")
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.e(TAG, "[STT] error stopping recording", e)
        }
    }

    private fun processUserText(text: String, callId: String, messageId: String) {
        CallEventBus.emit(CallEvent.UserMessage(messageId, text))
        enqueueUserText(callId, messageId, text)
    }

    // Persisted retry-with-backoff for user replies (voice or typed). Mirrors the
    // ANSWER/COMPLETE/CANCEL pattern so a flaky network can never silently drop
    // a message; the backend dedupes by client_message_id so retries can't
    // duplicate a message that was actually accepted.
    private fun enqueueUserText(callId: String, messageId: String, text: String) {
        persistPendingUserText(callId, messageId, text)
        retryScope.launch {
            val backoffMs = longArrayOf(1000, 2000, 4000, 8000, 16000, 32000, 60000)
            for (delayMs in backoffMs) {
                if (attemptUserText(callId, messageId, text)) {
                    removePendingUserText(messageId)
                    CallEventBus.emit(CallEvent.UserTextSent(messageId))
                    return@launch
                }
                delay(delayMs)
            }
            if (attemptUserText(callId, messageId, text)) {
                removePendingUserText(messageId)
                CallEventBus.emit(CallEvent.UserTextSent(messageId))
            } else {
                Log.w(TAG, "[USER-TEXT] retries exhausted for $messageId; kept pending for next service start")
                CallEventBus.emit(CallEvent.UserTextFailed(messageId, text))
            }
        }
    }

    private suspend fun attemptUserText(callId: String, messageId: String, text: String): Boolean {
        return try {
            api.sendUserText(callId, mapOf("text" to text, "client_message_id" to messageId))
            Log.i(TAG, "[USER-TEXT] backend confirmed user text $messageId")
            true
        } catch (e: Exception) {
            Log.w(TAG, "[USER-TEXT] send $messageId failed, will retry", e)
            false
        }
    }

    private fun persistPendingUserText(callId: String, messageId: String, text: String) {
        val prefs = getSharedPreferences(PREFS_CALL_STATE, MODE_PRIVATE)
        val entries = prefs.getStringSet(KEY_PENDING_USER_TEXTS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val entry = JSONObject()
            .put("callId", callId)
            .put("messageId", messageId)
            .put("text", text)
            .toString()
        if (entries.add(entry)) {
            prefs.edit().putStringSet(KEY_PENDING_USER_TEXTS, entries).apply()
        }
    }

    private fun removePendingUserText(messageId: String) {
        val prefs = getSharedPreferences(PREFS_CALL_STATE, MODE_PRIVATE)
        val entries = prefs.getStringSet(KEY_PENDING_USER_TEXTS, emptySet())?.toMutableSet() ?: return
        val matching = entries.filter {
            runCatching { JSONObject(it).getString("messageId") }.getOrNull() == messageId
        }
        if (matching.isNotEmpty()) {
            entries.removeAll(matching.toSet())
            prefs.edit().putStringSet(KEY_PENDING_USER_TEXTS, entries).apply()
        }
    }

    private fun snapshotPendingUserTexts(): List<String> {
        return getSharedPreferences(PREFS_CALL_STATE, MODE_PRIVATE)
            .getStringSet(KEY_PENDING_USER_TEXTS, emptySet())
            ?.toList()
            ?: emptyList()
    }

    private fun flushPendingUserTexts(entries: List<String>) {
        for (entry in entries) {
            val obj = try {
                JSONObject(entry)
            } catch (_: Exception) {
                continue
            }
            val messageId = obj.getString("messageId")
            retryScope.launch {
                if (attemptUserText(obj.getString("callId"), messageId, obj.getString("text"))) {
                    removePendingUserText(messageId)
                    CallEventBus.emit(CallEvent.UserTextSent(messageId))
                }
            }
        }
    }

    /**
     * Single terminal-teardown path for a live call session: persists the end
     * record and recap, schedules the backend COMPLETE with retry, then tears
     * down service resources. Shared by the user's End action (ACTION_END_CALL)
     * and the max-duration watchdog so both produce identical state.
     */
    private fun terminateCall(cid: String?) {
        if (cid != null) {
            scope.launch {
                repository.saveCallEnded(cid, "ended")
                deriveSummary()?.let { repository.saveCallSummary(cid, it) }
            }
            retryWithBackoff(cid, KEY_PENDING_COMPLETES, "COMPLETE") { attemptComplete(it) }
        }
        CallEventBus.emit(CallEvent.CallEnded)
        endCall()
        stopSelf()
    }

    /**
     * Battery audit H2: the watchdog is the actual fix for the wedge scenario
     * (backend never sends a terminal event, no UI left to press End). At the
     * max-call duration it force-terminates through the same [terminateCall]
     * path as a user-initiated end. The wake-lock timeout (acquire time +
     * buffer) is only insurance in case this job itself fails.
     */
    private fun startCallWatchdog() {
        callWatchdogJob?.cancel()
        val durationMs = effectiveMaxCallMs()
        callWatchdogJob = scope.launch {
            delay(durationMs)
            val cid = callId ?: return@launch
            Log.w(TAG, "[CALL] watchdog fired after ${durationMs / 1000}s — forcing end for $cid")
            terminateCall(cid)
        }
    }

    /** Watchdog/wake-lock ceiling: adb-overridable in debug builds for verification. */
    private fun effectiveMaxCallMs(): Long {
        // Debug-only prefs override lets adb-driven verification set the cap
        // via run-as without needing to inject service intents (this ROM
        // blocks shell starts of non-exported components entirely).
        if (BuildConfig.DEBUG) {
            val pref = getSharedPreferences(PREFS_CALL_STATE, MODE_PRIVATE)
                .getLong(KEY_DEBUG_MAX_CALL_MS, 0L)
            if (pref > 0L) return pref
        }
        return if (BuildConfig.DEBUG && debugMaxCallMs > 0L) debugMaxCallMs else MAX_CALL_DURATION_MS
    }

    private fun endCall() {
        // FCM-only idle: after call ends, close the call-scoped WS and return
        // to fully idle (no FGS, no socket, no notification). hasActiveCall
        // must be cleared before parking so maybeParkAndStop sees idle.
        hasActiveCall = false
        // Close the WS that was opened for this call (CallService opened it via
        // connectIfIdle on answer). FCM will handle the next wake.
        try { signalingClient.park() } catch (_: Exception) {}
        callWatchdogJob?.cancel()
        callWatchdogJob = null
        val endedId = callId
        if (endedId != null) CallStateHolder.ended(endedId)
        val firstEnd = !resourcesReleased
        releaseCallResources()
        scheduleTtsIdleShutdown()
        callId = null
        if (firstEnd) {
            SignalingForegroundService.notifyIdlePark(this)
        }
    }

    /**
     * Backlog item 13: every terminal path (END_CALL, CallCancelled,
     * CallExpired, disconnect, onDestroy) funnels here exactly once. The
     * guard makes double-releases harmless.
     */
    private fun releaseCallResources() {
        if (resourcesReleased) return
        resourcesReleased = true
        // Stop the speech pipeline: drop queued sentences, cancel the worker,
        // and stop any sentence mid-playback.
        while (speechChannel.tryReceive().isSuccess) {}
        speechWorker?.cancel()
        speechWorker = null
        piperEngine.requestStop()
        textToSpeech?.stop()
        speechRecognizer?.destroy()
        speechRecognizer = null
        isRecording = false
        isAiSpeaking = false
        isMuted = false
        audioManager.abandonFocus()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /**
     * Backlog item 13: the TTS engines must not stay alive app-wide after a
     * call. Shut both down after an idle window; initTts()/ensurePiperInit()
     * recreate on demand. Piper's 81 MB model + native libs are released so
     * they are not held in memory after the call.
     */
    private fun scheduleTtsIdleShutdown() {
        ttsIdleJob?.cancel()
        ttsIdleJob = retryScope.launch {
            delay(TTS_IDLE_SHUTDOWN_MS)
            if (callId == null && !isRecording && !isAiSpeaking) {
                // Battery-audit verification hook: makes the engine release
                // observable in logcat (dumpsys alone cannot separate mmap'd
                // model pages from allocator retention).
                Log.i(TAG, "[TTS] idle shutdown — releasing system TTS + Piper engines")
                runCatching { textToSpeech?.shutdown() }
                textToSpeech = null
                ttsInitialized = false
                runCatching { piperEngine.release() }
                piperReady = false
                piperAttempted = false
                piperInitJob = null
            }
        }
    }

    /**
     * Battery audit H2: the lock is always acquired WITH a timeout (the
     * documented pattern for exactly this failure mode — developer.android.com
     * "Release a wake lock": acquire(long) automatically releases after the
     * timeout, so even if every in-process release path fails the OS reclaims
     * it). The timeout is the max-call duration plus a buffer so that under
     * normal operation the watchdog's graceful terminateCall() wins and only a
     * failed watchdog ever hits the raw timeout.
     */
    private fun acquireWakeLock(timeoutMs: Long) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceBridge:CallLock")
            .apply { acquire(timeoutMs) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun createOngoingCallNotification(text: String): Notification {
        val intent = Intent(this, CallActivity::class.java).apply {
            putExtra(EXTRA_CALL_ID, callId); flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val endPi = PendingIntent.getService(this, 2,
            Intent(this, CallService::class.java)
                .setAction(ACTION_END_CALL)
                .putExtra(EXTRA_CALL_ID, callId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ONGOING_CALL)
            .setContentTitle("AI Voice Call")
            .setContentText(text)
            .setSmallIcon(R.drawable.agentcall_notification_icon)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setColorized(true)
            .setSilent(true)
            .addAction(R.drawable.ic_missed_call, "End", endPi)
            .setContentIntent(pi).build()
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIFICATION_ID_ONGOING, createOngoingCallNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); endCall(); super.onDestroy() }

    companion object {
        const val TAG = "AgentCall"
        const val ACTION_START_CALL = "com.agentcall.action.START_CALL"
        const val ACTION_START_RECORDING = "com.agentcall.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.agentcall.action.STOP_RECORDING"
        const val ACTION_SPEAK = "com.agentcall.action.SPEAK"
        const val ACTION_SET_MUTED = "com.agentcall.action.SET_MUTED"
        const val ACTION_REPEAT_LAST = "com.agentcall.action.REPEAT_LAST"
        const val ACTION_SEND_TEXT = "com.agentcall.action.SEND_TEXT"
        const val ACTION_END_CALL = "com.agentcall.action.END_CALL"
        const val ACTION_CANCEL_CALL = "com.agentcall.action.CANCEL_CALL"
        const val ACTION_SCHEDULE_CALLBACK = "com.agentcall.action.SCHEDULE_CALLBACK"
        const val ACTION_PREWARM_TTS = "com.agentcall.action.PREWARM_TTS"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_TEXT = "text"
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_NOTE = "note"
        const val EXTRA_MUTED = "muted"
        const val EXTRA_LAUNCH_UI = "launch_ui"
        // Set by the FGS ring-timeout auto-decline: lets ACTION_CANCEL_CALL
        // skip notifying the FGS to park/stop (it must stay alive to receive
        // the server's call_expired and post the missed-call notification).
        const val EXTRA_FROM_TIMEOUT = "from_timeout"
        /**
         * Whether a call session is live (CallService has accepted an answer
         * and not yet ended it). The FGS consults this before parking/stopping
         * itself — an active call must never lose its ring/wake infrastructure.
         */
        @Volatile
        var hasActiveCall = false
        const val CHANNEL_ONGOING_CALL = "ongoing_call"
        // v2: old "incoming_call" channel had no ringtone/vibration and ColorOS drops
        // channel updates/delete, so an in-place upgrade is impossible — version the ID.
        const val CHANNEL_INCOMING_CALL = "incoming_call_v2"
        // Quiet-hours variant (backlog item 6): silent ring channel so DND
        // never makes noise while the call still reaches the screen.
        const val CHANNEL_INCOMING_CALL_QUIET = "incoming_call_quiet_v1"
        // Missed-call channel (backlog item 1): silent, informational only.
        const val CHANNEL_MISSED_CALLS = "missed_calls"
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_CONTEXT_SUMMARY = "context_summary"
        const val EXTRA_PRIORITY = "priority"
        private const val PREFS_CALL_STATE = "agentcall_call_state"
        private const val KEY_PENDING_CANCELS = "pending_cancel_ids"
        private const val KEY_PENDING_COMPLETES = "pending_complete_ids"
        private const val KEY_PENDING_ANSWERS = "pending_answer_ids"
        private const val KEY_PENDING_CALLBACKS = "pending_callback_ids"
        private const val KEY_PENDING_USER_TEXTS = "pending_user_texts"
        private const val NOTIFICATION_ID_ONGOING = 1001
        private const val NOTIFICATION_ID_INCOMING = 1002
        private const val NOTIFICATION_ID_MISSED = 1005
        private const val TTS_IDLE_SHUTDOWN_MS = 60_000L
        // Battery audit H2: hard ceiling on a single call session. The longest
        // legitimate call this product supports is an AI check-in conversation;
        // 30 min leaves generous margin above that. The wake lock carries this
        // plus WAKELOCK_TIMEOUT_BUFFER_MS so the OS-level self-release only
        // fires if the in-process watchdog itself failed.
        const val MAX_CALL_DURATION_MS = 30 * 60_000L
        private const val WAKELOCK_TIMEOUT_BUFFER_MS = 5 * 60_000L
        // Debug builds only: lets adb-driven verification exercise the watchdog
        // with a short cap (am start-service --el). Release ignores it entirely.
        const val EXTRA_DEBUG_MAX_CALL_MS = "debug_max_call_ms"
        private const val KEY_DEBUG_MAX_CALL_MS = "debug_max_call_ms"
        // How long a message may wait for a Piper init that is already
        // running (cold ring: prewarm raced the answer). Init blocks for
        // seconds, so the cap keeps the first word fast — an unready engine
        // hands the message to the system TTS instead.
        private const val PIPER_WAIT_MS = 3_000L
        private const val WARMUP_UTTERANCE_ID = "tts-warmup"
        private const val WARMUP_TEXT = "ok"
        private const val WARMUP_FILE = "tts-warmup.wav"

        /**
         * [quiet] (backlog item 6): post to the silent quiet-hours channel.
         * The full-screen intent still launches the ring UI — DND silences
         * the phone, it never hides the call.
         */
        fun showIncomingCallNotification(
            context: Context,
            callId: String,
            callerName: String,
            summary: String,
            quiet: Boolean = false,
            clientInfoName: String? = null,
        ) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val canUseFullScreen = Build.VERSION.SDK_INT < 34 || mgr.canUseFullScreenIntent()

            if (!canUseFullScreen) {
                Log.w(TAG, "[NOTIF] USE_FULL_SCREEN_INTENT not granted — full-screen incoming call will not show")
                showFullScreenIntentWarning(context)
            }

            val intent = Intent(context, IncomingCallActivity::class.java).apply {
                putExtra("call_id", callId)
                putExtra("caller_name", callerName)
                putExtra("context_summary", summary)
                clientInfoName?.let { putExtra("client_info_name", it) }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(context, callId.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val channel = if (quiet) CHANNEL_INCOMING_CALL_QUIET else CHANNEL_INCOMING_CALL
            // Direct actions, mirroring the full-screen IncomingCallActivity
            // buttons: Answer starts the same ACTION_START_CALL session the
            // screen's Answer button starts; Decline sends the same
            // ACTION_CANCEL_CALL with the same decline note. Tapping these
            // never opens a second confirmation screen. Request codes are
            // derived from the call id so simultaneous calls can't collide.
            val answerPi = PendingIntent.getService(
                context, callId.hashCode() * 2,
                Intent(context, CallService::class.java).apply {
                    action = ACTION_START_CALL
                    putExtra(EXTRA_CALL_ID, callId)
                    putExtra(EXTRA_CALLER_NAME, callerName)
                    putExtra(EXTRA_CONTEXT_SUMMARY, summary)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val declinePi = PendingIntent.getService(
                context, callId.hashCode() * 2 + 1,
                Intent(context, CallService::class.java).apply {
                    action = ACTION_CANCEL_CALL
                    putExtra(EXTRA_CALL_ID, callId)
                    putExtra(EXTRA_TEXT, MessageTemplates.declineMessage(context))
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val callStyle = if (Build.VERSION.SDK_INT >= 31) {
                val person = Person.Builder().setName(callerName).build()
                NotificationCompat.CallStyle.forIncomingCall(person, declinePi, answerPi)
            } else {
                null
            }

            val builder = NotificationCompat.Builder(context, channel)
                .setContentTitle(if (quiet) "Incoming AI Call (quiet)" else "Incoming AI Call")
                .setContentText(summary.ifBlank { "$callerName is calling..." })
                .setSmallIcon(R.drawable.agentcall_notification_icon)
                .setColor(android.graphics.Color.parseColor("#6366F1"))
                .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(pi, true)
                // Without a contentIntent the notification is inert when the
                // full-screen intent is rejected (no USE_FULL_SCREEN_INTENT
                // grant): the user could never open the incoming-call UI.
                // Both intents point at the same IncomingCallActivity so body tap
                // opens the full UI exactly like the real phone app.
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOngoing(false)
                .setStyle(callStyle)
            // CallStyle.forIncomingCall already registers the two actions
            // (Answer/Decline) with the system UI; adding them again via
            // addAction creates a duplicate third tappable element on OEM
            // skins (realme/ColorOS showed a red pill + purple pill + green
            // icon). Only add fallback actions when CallStyle is unavailable
            // (API <31).
            if (callStyle == null) {
                builder.addAction(R.drawable.ic_call, "Answer", answerPi)
                builder.addAction(R.drawable.ic_missed_call, "Decline", declinePi)
            }
            val notification = builder.build()

            mgr.notify(NOTIFICATION_ID_INCOMING, notification)
        }

        private const val NOTIFICATION_ID_FSI_WARNING = 1004

        fun showFullScreenIntentWarning(context: Context) {
            val openSettingsIntent = Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", context.packageName, null),
            )
            val settingsPi = PendingIntent.getActivity(
                context, 0, openSettingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val warning = NotificationCompat.Builder(context, SignalingForegroundService.CHANNEL_SIGNALING)
                .setContentTitle("Incoming calls may not work")
                .setContentText("Tap to grant \"Full screen intent\" permission in App Settings > Notifications")
                .setSmallIcon(R.drawable.agentcall_notification_icon)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .setContentIntent(settingsPi)
                .build()

            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(NOTIFICATION_ID_FSI_WARNING, warning)
        }

        fun cancelFullScreenIntentWarning(context: Context) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.cancel(NOTIFICATION_ID_FSI_WARNING)
        }

        fun cancelIncomingNotification(context: Context) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.cancel(NOTIFICATION_ID_INCOMING)
        }

        /**
         * Silent missed-call notification (backlog item 1): only posted when
         * the app is backgrounded; tap opens the agent's profile via the
         * MainActivity extra. Shared by the foreground service (primary path)
         * and the in-call service (edge case).
         */
        fun showMissedCallNotification(context: Context, callId: String, callerName: String, agentId: String) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra("profile_id", agentId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pi = PendingIntent.getActivity(
                context, callId.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_MISSED_CALLS)
                .setContentTitle("Missed call from $callerName")
                .setContentText("The AI tried to call you but no one answered. Tap to call back.")
                .setSmallIcon(R.drawable.agentcall_notification_icon)
                .setColor(android.graphics.Color.parseColor("#F59E0B"))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            mgr.notify(NOTIFICATION_ID_MISSED, notification)
        }
    }
}
