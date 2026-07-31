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
    private var wakeLock: PowerManager.WakeLock? = null

    var callId: String? = null
    var textToSpeech: TextToSpeech? = null
    private var ttsInitialized = false
    @Volatile var isRecording = false
    @Volatile var isAiSpeaking = false
    @Volatile var isPaused = false

    private var transcriptSequence = 0
    private var lastAiMessage: String = ""

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
        when (intent?.action) {
            ACTION_START_CALL -> {
                val id = intent.getStringExtra(EXTRA_CALL_ID) ?: return START_NOT_STICKY
                val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "AI Agent"
                callId = id
                transcriptSequence = 0
                startForeground(NOTIFICATION_ID_ONGOING, createOngoingCallNotification("AI Call"))
                acquireWakeLock()
                val agentId = callerName.lowercase().replace("\\s+".toRegex(), "-")
                scope.launch { repository.markCallAnswered(id, agentId, callerName) }
                startVoiceSession(id)
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
                val cid = callId
                if (cid != null) {
                    scope.launch { repository.saveCallEnded(cid, "ended") }
                }
                CallEventBus.emit(CallEvent.CallEnded)
                endCall()
                stopSelf()
            }
            "com.agentcall.action.CANCEL_CALL" -> {
                val id = intent.getStringExtra(EXTRA_CALL_ID) ?: return START_NOT_STICKY
                scope.launch {
                    try {
                        api.completeCall(id, mapOf("status" to "cancelled"))
                    } catch (_: Exception) {}
                }
            }
            "com.agentcall.action.SCHEDULE_CALLBACK" -> {
                val id = intent.getStringExtra(EXTRA_CALL_ID) ?: return START_NOT_STICKY
                val delayMin = intent.getStringExtra(EXTRA_TEXT)?.toIntOrNull() ?: 10
                scope.launch {
                    try {
                        api.scheduleCallback(id, mapOf("delay_minutes" to delayMin))
                    } catch (_: Exception) {}
                }
            }
        }
        return START_STICKY
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
                summary = call.result?.userResponse ?: call.result?.transcriptSummary ?: "AI needs your input."
            } catch (e: Exception) {
                Log.w(TAG, "[WS] failed to fetch call data on start, continuing anyway", e)
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
            Intent(this, CallService::class.java).setAction(ACTION_END_CALL),
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
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_TEXT = "text"
        const val CHANNEL_ONGOING_CALL = "ongoing_call"
        const val CHANNEL_INCOMING_CALL = "incoming_call"
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_CONTEXT_SUMMARY = "context_summary"
        const val EXTRA_PRIORITY = "priority"
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