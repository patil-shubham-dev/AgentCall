package com.agentcall.app.call

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

sealed class VoiceBridgeEvent {
    data class AiMessage(val callId: String, val messageId: String, val content: String, val enrichedJson: String = "") : VoiceBridgeEvent()
    data class BargeInDetected(val callId: String, val action: String, val callbackMinutes: Int = 10) : VoiceBridgeEvent()
    data class CallbackScheduled(val callId: String, val delayMinutes: Int) : VoiceBridgeEvent()
    data class CallIncoming(val callId: String, val reason: String, val summary: String) : VoiceBridgeEvent()
    data class CallEnded(val callId: String) : VoiceBridgeEvent()
    data class CallCancelled(val callId: String) : VoiceBridgeEvent()
    data class Connected(val userId: String) : VoiceBridgeEvent()
    data class Error(val code: String, val message: String) : VoiceBridgeEvent()
    object Disconnected : VoiceBridgeEvent()
}

@Singleton
class SignalingClient @Inject constructor() {
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private var connectionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentUserId: String = "solo-user"

    private val _events = MutableSharedFlow<VoiceBridgeEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<VoiceBridgeEvent> = _events

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING
    }

    fun connect(userId: String = "solo-user") {
        currentUserId = userId
        connectionJob?.cancel()
        connectionJob = scope.launch {
            _connectionState.value = ConnectionState.CONNECTING
            connectInternal()
        }
    }

    private suspend fun connectInternal() {
        val host = com.agentcall.app.data.api.ApiClient.serverHost
        val url = "ws://$host:4001/phone?user_id=$currentUserId"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = ConnectionState.CONNECTED
                scope.launch { _events.emit(VoiceBridgeEvent.Connected(currentUserId)) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { handleMessage(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch {
                    _connectionState.value = ConnectionState.RECONNECTING
                    delay(3000)
                    connectInternal()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = ConnectionState.DISCONNECTED
                scope.launch { _events.emit(VoiceBridgeEvent.Disconnected) }
            }
        })
    }

    private suspend fun handleMessage(text: String) {
        try {
            val obj = JSONObject(text)
            val type = obj.getString("type")
            val payload = obj.optJSONObject("payload")

            when (type) {
                "connected" -> {
                    val userId = payload?.getString("user_id") ?: currentUserId
                    _events.emit(VoiceBridgeEvent.Connected(userId))
                }
                "call_incoming" -> {
                    val callId = payload?.getString("callId") ?: return
                    val reason = payload.optString("reason", "input_required")
                    val summary = payload.optString("summary", "")
                    _events.emit(VoiceBridgeEvent.CallIncoming(callId, reason, summary))
                }
                "ai_message" -> {
                    val callId = payload?.getString("callId") ?: return
                    val messageObj = payload.optJSONObject("message")
                    val content = messageObj?.getString("content") ?: return
                    val messageId = messageObj.getString("id")
                    val enriched = payload.optJSONObject("enriched")?.toString() ?: ""
                    _events.emit(VoiceBridgeEvent.AiMessage(callId, messageId, content, enriched))
                }
                "barge_in_detected" -> {
                    val callId = payload?.getString("callId") ?: return
                    val action = payload.optString("action", "none")
                    val callbackMinutes = payload.optInt("callbackMinutes", 10)
                    _events.emit(VoiceBridgeEvent.BargeInDetected(callId, action, callbackMinutes))
                }
                "callback_scheduled" -> {
                    val callId = payload?.getString("callId") ?: return
                    val delay = payload.optInt("delayMinutes", 10)
                    _events.emit(VoiceBridgeEvent.CallbackScheduled(callId, delay))
                }
                "call_ended" -> {
                    val callId = payload?.getString("callId") ?: return
                    _events.emit(VoiceBridgeEvent.CallEnded(callId))
                }
                "call_cancelled" -> {
                    val callId = payload?.getString("callId") ?: return
                    _events.emit(VoiceBridgeEvent.CallCancelled(callId))
                }
                "error" -> {
                    val code = payload?.getString("code") ?: "UNKNOWN"
                    val message = payload?.getString("message") ?: "Unknown error"
                    _events.emit(VoiceBridgeEvent.Error(code, message))
                }
            }
        } catch (e: Exception) {
            _events.emit(VoiceBridgeEvent.Error("PARSE", "Failed to parse message"))
        }
    }

    fun disconnect() {
        connectionJob?.cancel()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }
}
