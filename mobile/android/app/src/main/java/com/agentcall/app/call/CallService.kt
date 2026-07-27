package com.agentcall.app.call

import android.app.*
import android.content.Context
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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class CallService : Service() {

    @Inject lateinit var signalingClient: SignalingClient

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    var callId: String? = null
    var textToSpeech: TextToSpeech? = null
    private var ttsInitialized = false
    var isRecording = false
    var isAiSpeaking = false
    var isPaused = false

    // Last AI message text, for the Repeat button
    private var lastAiMessage: String = ""

    private var speechRecognizer: SpeechRecognizer? = null

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
                        Log.d(TAG, "[TTS] start utteranceId=$utteranceId")
                    }

                    override fun onDone(utteranceId: String?) {
                        isAiSpeaking = false
                        Log.d(TAG, "[TTS] done utteranceId=$utteranceId")
                        dispatchToListeners("ai_finished_speaking", null)
                    }

                    override fun onError(utteranceId: String?) {
                        isAiSpeaking = false
                        Log.e(TAG, "[TTS] error utteranceId=$utteranceId")
                    }
                })
                Log.d(TAG, "[TTS] engine initialized")
            } else {
                Log.e(TAG, "[TTS] engine initialization failed status=$status")
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
                callId = id
                startForeground(NOTIFICATION_ID_ONGOING, createOngoingCallNotification("AI Call"))
                acquireWakeLock()
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
                    speakText(lastAiMessage)
                }
            }
            ACTION_END_CALL -> {
                endCall()
                stopSelf()
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

    fun speakText(text: String) {
        val tts = textToSpeech ?: run { initTts(); return }
        if (isPaused) return

        val utteranceId = UUID.randomUUID().toString()
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    private suspend fun speakTextOnMain(text: String) = withContext(Dispatchers.Main) {
        speakText(text)
    }

    private fun startVoiceSession(callId: String) {
        scope.launch {
            try {
                Log.d(TAG, "[HTTP] GET /calls/$callId")
                val call = api.getCall(callId)
                val summary = call.result?.userResponse ?: call.result?.transcriptSummary ?: call.messageCount?.let {
                    "Continuing our conversation."
                } ?: "AI needs your input."
                Log.d(TAG, "[WS] starting voice session callId=$callId")
                speakTextOnMain(summary)

                launch {
                    signalingClient.events.collect { event ->
                        Log.d(TAG, "[WS] event: ${event::class.simpleName}")
                        when (event) {
                            is VoiceBridgeEvent.AiMessage -> {
                                Log.d(TAG, "[WS] ai_message callId=${event.callId} text=${event.content.take(100)}")
                                speakTextOnMain(event.content)
                                dispatchToListeners("ai_message", Bundle().apply {
                                    putString("text", event.content)
                                })
                                lastAiMessage = event.content
                                CallEventBus.emit(CallEvent.AiMessage(event.content))
                            }
                            is VoiceBridgeEvent.CallEnded -> {
                                Log.d(TAG, "[WS] call_ended callId=${event.callId}")
                                CallEventBus.emit(CallEvent.CallEnded)
                                speakTextOnMain("Call ended.")
                                delay(1500); stopSelf()
                            }
                            is VoiceBridgeEvent.CallCancelled -> {
                                Log.d(TAG, "[WS] call_cancelled callId=${event.callId}")
                                CallEventBus.emit(CallEvent.CallEnded)
                                speakTextOnMain("Call was cancelled.")
                                delay(1500); stopSelf()
                            }
                            is VoiceBridgeEvent.Disconnected -> {
                                Log.w(TAG, "[WS] disconnected from server")
                                CallEventBus.emit(CallEvent.CallEnded)
                                stopSelf()
                            }
                            is VoiceBridgeEvent.Error -> {
                                Log.e(TAG, "[WS] error code=${event.code} message=${event.message}")
                            }
                            else -> {}
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[WS] voice session failed to start", e)
                stopSelf()
            }
        }
    }

    private fun startRecording() {
        if (isRecording || speechRecognizer != null) { Log.w(TAG, "[STT] already recording"); return }
        try {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            if (recognizer == null) { Log.w(TAG, "[STT] SpeechRecognizer not available"); return }
            speechRecognizer = recognizer
            isRecording = true
            Log.d(TAG, "[STT] recording started")
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
                    Log.d(TAG, "[STT] recording result: ${text?.take(100)}")
                    val currentCallId = callId
                    if (text != null && text.isNotBlank() && currentCallId != null) {
                        scope.launch { processUserText(text, currentCallId) }
                    } else {
                        Log.w(TAG, "[STT] no text recognized")
                        updateNotification("Paused")
                    }
                }

                override fun onError(error: Int) {
                    isRecording = false
                    recognizer.destroy()
                    if (speechRecognizer == recognizer) speechRecognizer = null
                    Log.e(TAG, "[STT] recognition error code=$error")
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
        if (!isRecording) { Log.w(TAG, "[STT] stopRecording called but not recording"); return }
        isRecording = false
        Log.d(TAG, "[STT] recording stopped, waiting for transcription")
        updateNotification("Processing...")
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.e(TAG, "[STT] error stopping recording", e)
        }
    }

    private fun processUserText(text: String, callId: String) {
        sendUserTextToBackend(callId, text)
        dispatchToListeners("user_transcript", Bundle().apply { putString("text", text) })
        CallEventBus.emit(CallEvent.UserMessage(text))
    }

    private fun sendUserTextToBackend(callId: String, text: String) {
        scope.launch {
            try {
                Log.d(TAG, "[HTTP] POST /calls/$callId/user-text text=${text.take(100)}")
                api.sendUserText(callId, mapOf("text" to text))
            } catch (e: Exception) {
                Log.e(TAG, "[HTTP] POST /calls/$callId/user-text failed", e)
            }
        }
    }

    private fun endCall() {
        Log.d(TAG, "[VOICE] ending call callId=$callId")
        signalingClient.disconnect()
        textToSpeech?.stop()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceBridge:CallLock")
            .apply { acquire(60_000) }
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
                .setContentText(summary.ifBlank { "AI Agent is calling..." })
                .setSmallIcon(R.drawable.ic_agent)
                .setColor(android.graphics.Color.parseColor("#6366F1"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .setOngoing(false)
                .build()

            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(NOTIFICATION_ID_INCOMING, notification)
        }

        fun cancelIncomingNotification(context: Context) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.cancel(NOTIFICATION_ID_INCOMING)
        }
    }
}
