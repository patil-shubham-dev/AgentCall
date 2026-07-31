package com.agentcall.app.call

import android.app.*
import android.content.Context
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
import com.agentcall.app.R
import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.api.ApiService
import com.agentcall.app.data.api.CallbackRequest
import com.agentcall.app.data.api.CancelRequest
import com.agentcall.app.data.database.dao.TranscriptMessageDao
import com.agentcall.app.data.repository.CallRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class CallService : Service() {

    @Inject lateinit var signalingClient: SignalingClient
    @Inject lateinit var transcriptDao: TranscriptMessageDao
    @Inject lateinit var repository: CallRepository

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Survives stopSelf(): End/Done + Decline persist their intent first, then retry
    // on this scope so a service teardown can never cancel a pending delivery.
    private val retryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    var callId: String? = null
    var textToSpeech: TextToSpeech? = null
    private var ttsInitialized = false
    @Volatile var isRecording = false
    @Volatile var isAiSpeaking = false
    @Volatile var isPaused = false

    private var transcriptSequence = 0
    private var lastAiMessage: String = ""
    private var incomingSummary: String? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var pendingSpeakText: String? = null

    private val api: ApiService = ApiClient.create()

    override fun onCreate() {
        super.onCreate()
        initTts()
    }

    private fun initTts() {
        if (ttsInitialized && textToSpeech != null) return
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsInitialized = true
                textToSpeech?.language = Locale.US
                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isAiSpeaking = true
                        CallEventBus.emit(CallEvent.AiSpeakingStarted)
                    }

                    override fun onDone(utteranceId: String?) {
                        isAiSpeaking = false
                        CallEventBus.emit(CallEvent.AiSpeakingFinished)
                    }

                    override fun onError(utteranceId: String?) {
                        isAiSpeaking = false
                    }
                })
                pendingSpeakText?.let {
                    pendingSpeakText = null
                    speakText(it)
                }
            }
        }
    }

    private val listenerMap = mutableMapOf<String, (Bundle?) -> Unit>()

    private fun dispatchToListeners(type: String, data: Bundle?) {
        listenerMap[type]?.invoke(data)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            flushPending(KEY_PENDING_CANCELS) { attemptCancel(it) }
            flushPending(KEY_PENDING_COMPLETES) { attemptComplete(it) }
            flushPending(KEY_PENDING_ANSWERS) { attemptAnswer(it) }
        }
        when (intent?.action) {
            ACTION_START_CALL -> {
                val id = intent.getStringExtra(EXTRA_CALL_ID) ?: return START_NOT_STICKY
                val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "AI Agent"
                callId = id
                transcriptSequence = 0
                incomingSummary = intent.getStringExtra(EXTRA_CONTEXT_SUMMARY)
                SignalingForegroundService.notifyRingResolved(this)
                startForeground(NOTIFICATION_ID_ONGOING, createOngoingCallNotification("AI Call"))
                acquireWakeLock()
                val agentId = callerName.lowercase().replace("\\s+".toRegex(), "-")
                scope.launch { repository.markCallAnswered(id, agentId, callerName) }
                startVoiceSession(id)
                retryWithBackoff(id, KEY_PENDING_ANSWERS, "ANSWER") { attemptAnswer(it) }
            }
            ACTION_START_RECORDING -> startRecording()
            ACTION_STOP_RECORDING -> stopRecording()
            ACTION_SPEAK -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return START_NOT_STICKY
                speakText(text)
            }
            ACTION_REPEAT_LAST -> {
                if (lastAiMessage.isNotBlank()) {
                    speakText(lastAiMessage, force = true)
                }
            }
            ACTION_SEND_TEXT -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return START_NOT_STICKY
                val cid = intent.getStringExtra(EXTRA_CALL_ID) ?: callId ?: return START_NOT_STICKY
                scope.launch {
                    try {
                        api.sendUserText(cid, mapOf("text" to text))
                        repository.saveUserTextMessage(cid, text)
                    } catch (_: Exception) {}
                }
            }
            ACTION_END_CALL -> {
                val cid = intent.getStringExtra(EXTRA_CALL_ID) ?: callId
                if (cid != null) {
                    scope.launch { repository.saveCallEnded(cid, "ended") }
                    retryWithBackoff(cid, KEY_PENDING_COMPLETES, "COMPLETE") { attemptComplete(it) }
                }
                CallEventBus.emit(CallEvent.CallEnded)
                endCall()
                stopSelf()
            }
            ACTION_CANCEL_CALL -> {
                val id = intent.getStringExtra(EXTRA_CALL_ID) ?: return START_NOT_STICKY
                val note = intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }
                if (note != null) savePendingNote(id, note)
                retryWithBackoff(id, KEY_PENDING_CANCELS, "CANCEL") { attemptCancel(it) }
            }
            ACTION_SCHEDULE_CALLBACK -> {
                val id = intent.getStringExtra(EXTRA_CALL_ID) ?: return START_NOT_STICKY
                val delayMin = intent.getStringExtra(EXTRA_TEXT)?.toIntOrNull() ?: 10
                val note = intent.getStringExtra(EXTRA_NOTE)?.takeIf { it.isNotBlank() }
                scope.launch {
                    try {
                        api.scheduleCallback(id, CallbackRequest(delayMinutes = delayMin, note = note))
                    } catch (_: Exception) {}
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
            api.completeCall(callId)
            Log.i(TAG, "[COMPLETE] backend confirmed completion for $callId")
            true
        } catch (e: Exception) {
            Log.w(TAG, "[COMPLETE] complete $callId failed, will retry", e)
            false
        }
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
            prefs.edit().putStringSet(key, ids).remove("note:$callId").apply()
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
        val tts = textToSpeech
        if (tts == null || !ttsInitialized) {
            pendingSpeakText = text
            if (tts == null) initTts()
            return
        }

        val utteranceId = UUID.randomUUID().toString()
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    private suspend fun speakTextOnMain(text: String) = withContext(Dispatchers.Main) {
        speakText(text)
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
                        is VoiceBridgeEvent.CallEnded -> {
                            if (event.callId != callId) return@collect
                            CallEventBus.emit(CallEvent.CallEnded)
                            speakTextOnMain("Call ended.")
                            repository.saveCallEnded(callId, "ended")
                            delay(1500); stopSelf()
                        }
                        is VoiceBridgeEvent.CallCancelled -> {
                            if (event.callId != callId) return@collect
                            CallEventBus.emit(CallEvent.CallEnded)
                            speakTextOnMain("Call was cancelled.")
                            repository.saveCallEnded(callId, "cancelled")
                            delay(1500); stopSelf()
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
            } catch (e: Exception) {
                Log.w(TAG, "[WS] failed to fetch call data on start, continuing anyway", e)
                summary = incomingSummary?.takeIf { it.isNotBlank() } ?: "AI needs your input."
            }

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
                        scope.launch {
                            processUserText(text, currentCallId)
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

    private fun processUserText(text: String, callId: String) {
        sendUserTextToBackend(callId, text)
        CallEventBus.emit(CallEvent.UserMessage(text))
    }

    private fun sendUserTextToBackend(callId: String, text: String) {
        scope.launch {
            try {
                api.sendUserText(callId, mapOf("text" to text))
            } catch (e: Exception) {
                Log.e(TAG, "[HTTP] POST /calls/$callId/user-text failed", e)
            }
        }
    }

    private fun endCall() {
        textToSpeech?.stop()
        speechRecognizer?.destroy()
        speechRecognizer = null
        isRecording = false
        isAiSpeaking = false
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceBridge:CallLock")
            .apply { acquire() }
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
            .setSmallIcon(R.drawable.ic_agent)
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
        const val ACTION_REPEAT_LAST = "com.agentcall.action.REPEAT_LAST"
        const val ACTION_SEND_TEXT = "com.agentcall.action.SEND_TEXT"
        const val ACTION_END_CALL = "com.agentcall.action.END_CALL"
        const val ACTION_CANCEL_CALL = "com.agentcall.action.CANCEL_CALL"
        const val ACTION_SCHEDULE_CALLBACK = "com.agentcall.action.SCHEDULE_CALLBACK"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_TEXT = "text"
        const val EXTRA_NOTE = "note"
        const val CHANNEL_ONGOING_CALL = "ongoing_call"
        // v2: old "incoming_call" channel had no ringtone/vibration and ColorOS drops
        // channel updates/delete, so an in-place upgrade is impossible — version the ID.
        const val CHANNEL_INCOMING_CALL = "incoming_call_v2"
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_CONTEXT_SUMMARY = "context_summary"
        const val EXTRA_PRIORITY = "priority"
        private const val PREFS_CALL_STATE = "agentcall_call_state"
        private const val KEY_PENDING_CANCELS = "pending_cancel_ids"
        private const val KEY_PENDING_COMPLETES = "pending_complete_ids"
        private const val KEY_PENDING_ANSWERS = "pending_answer_ids"
        private const val NOTIFICATION_ID_ONGOING = 1001
        private const val NOTIFICATION_ID_INCOMING = 1002

        fun showIncomingCallNotification(context: Context, callId: String, callerName: String, summary: String) {
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
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(context, callId.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val notification = NotificationCompat.Builder(context, CHANNEL_INCOMING_CALL)
                .setContentTitle("Incoming AI Call")
                .setContentText(summary.ifBlank { "$callerName is calling..." })
                .setSmallIcon(R.drawable.ic_agent)
                .setColor(android.graphics.Color.parseColor("#6366F1"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .setOngoing(false)
                .build()

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
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
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
    }
}