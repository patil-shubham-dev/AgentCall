package com.agentcall.app.call

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AgentCall"

sealed class VoiceBridgeEvent {
    data class AiMessage(val callId: String, val messageId: String, val content: String) : VoiceBridgeEvent()
    data class CallbackScheduled(val callId: String, val delayMinutes: Int) : VoiceBridgeEvent()
    data class CallIncoming(val callId: String, val reason: String, val summary: String) : VoiceBridgeEvent()
    data class CallEnded(val callId: String) : VoiceBridgeEvent()
    data class CallCancelled(val callId: String) : VoiceBridgeEvent()
    data class Connected(val userId: String) : VoiceBridgeEvent()
    data class Error(val code: String, val message: String) : VoiceBridgeEvent()
    object Disconnected : VoiceBridgeEvent()
}

@Singleton
class SignalingClient @Inject constructor(
    private val app: Application,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null
    private var connectionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentUserId: String = "solo-user"

    private var reconnectAttempt = 0
    private var networkCallback: ConnectivityNetworkCallback? = null

    private val _events = MutableSharedFlow<VoiceBridgeEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<VoiceBridgeEvent> = _events

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING
    }

    fun connect(userId: String = "solo-user") {
        Log.d(TAG, "[WS] connect userId=$userId")
        currentUserId = userId
        reconnectAttempt = 0
        connectionJob?.cancel()
        registerNetworkCallback()
        SignalingForegroundService.start(app)
        connectionJob = scope.launch {
            _connectionState.value = ConnectionState.CONNECTING
            connectInternal()
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = app.getSystemService(Application.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = ConnectivityNetworkCallback(this)
        networkCallback = callback
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        val cm = app.getSystemService(Application.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.unregisterNetworkCallback(cb)
    }

    private class ConnectivityNetworkCallback(
        private val client: SignalingClient,
    ) : NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "[NET] network available $network")
            client.onNetworkAvailable()
        }
    }

    private fun onNetworkAvailable() {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Log.d(TAG, "[WS] network available — triggering reconnect")
            connectionJob?.cancel()
            reconnectAttempt = 0
            connectionJob = scope.launch {
                _connectionState.value = ConnectionState.CONNECTING
                connectInternal()
            }
        }
    }

    private suspend fun connectInternal() {
        com.agentcall.app.data.api.ApiClient.ensurePhoneToken(currentUserId)
        val url = com.agentcall.app.data.api.ApiClient.getWsUrl(currentUserId)
        Log.d(TAG, "[WS] connecting to $url")
        val request = Request.Builder().url(url).build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "[WS] opened userId=$currentUserId")
                reconnectAttempt = 0
                _connectionState.value = ConnectionState.CONNECTED
                scope.launch { _events.emit(VoiceBridgeEvent.Connected(currentUserId)) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "[WS] <- message size=${text.length}")
                scope.launch { handleMessage(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "[WS] connection failure", t)
                scope.launch {
                    reconnectAttempt++
                    _connectionState.value = ConnectionState.RECONNECTING
                    val delayMs = calculateBackoff(reconnectAttempt)
                    Log.d(TAG, "[WS] reconnect attempt $reconnectAttempt in ${delayMs}ms")
                    delay(delayMs)
                    connectInternal()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "[WS] closed code=$code reason=$reason")
                scope.launch {
                    _connectionState.value = ConnectionState.RECONNECTING
                    reconnectAttempt++
                    val delayMs = calculateBackoff(reconnectAttempt)
                    delay(delayMs)
                    connectInternal()
                }
            }
        }

        webSocket?.close(1000, "Reconnecting")
        webSocket = client.newWebSocket(request, listener)
    }

    private fun calculateBackoff(attempt: Int): Long {
        val baseMs = 1000L
        val maxMs = 30000L
        val shift = (attempt - 1).coerceIn(0, 5)
        val exponential = baseMs * (1L shl shift)
        val jitter = (0..500).random()
        return (exponential + jitter).coerceAtMost(maxMs)
    }

    private suspend fun handleMessage(text: String) {
        try {
            val obj = JSONObject(text)
            val type = obj.getString("type")
            val payload = obj.optJSONObject("payload")
            Log.d(TAG, "[WS] message type=$type")

            when (type) {
                "connected" -> {
                    val userId = payload?.getString("user_id") ?: currentUserId
                    Log.d(TAG, "[WS] connected userId=$userId")
                    _events.emit(VoiceBridgeEvent.Connected(userId))
                }
                "call_incoming" -> {
                    val callId = payload?.getString("callId") ?: return
                    val reason = payload.optString("reason", "input_required")
                    val summary = payload.optString("summary", "")
                    Log.d(TAG, "[WS] call_incoming callId=$callId reason=$reason")
                    _events.emit(VoiceBridgeEvent.CallIncoming(callId, reason, summary))
                }
                "ai_message" -> {
                    val callId = payload?.getString("callId") ?: return
                    val messageObj = payload.optJSONObject("message")
                    val content = messageObj?.getString("content") ?: return
                    val messageId = messageObj.getString("id")
                    Log.d(TAG, "[WS] ai_message callId=$callId text=${content.take(100)}")
                    _events.emit(VoiceBridgeEvent.AiMessage(callId, messageId, content))
                }
                "callback_scheduled" -> {
                    val callId = payload?.getString("callId") ?: return
                    val delay = payload.optInt("delayMinutes", 10)
                    Log.d(TAG, "[WS] callback_scheduled callId=$callId delay=$delay")
                    _events.emit(VoiceBridgeEvent.CallbackScheduled(callId, delay))
                }
                "call_ended" -> {
                    val callId = payload?.getString("callId") ?: return
                    Log.d(TAG, "[WS] call_ended callId=$callId")
                    _events.emit(VoiceBridgeEvent.CallEnded(callId))
                }
                "call_cancelled" -> {
                    val callId = payload?.getString("callId") ?: return
                    Log.d(TAG, "[WS] call_cancelled callId=$callId")
                    _events.emit(VoiceBridgeEvent.CallCancelled(callId))
                }
                "error" -> {
                    val code = payload?.getString("code") ?: "UNKNOWN"
                    val message = payload?.getString("message") ?: "Unknown error"
                    Log.e(TAG, "[WS] error code=$code message=$message")
                    _events.emit(VoiceBridgeEvent.Error(code, message))
                }
                else -> Log.w(TAG, "[WS] unknown message type=$type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[WS] failed to parse message", e)
            _events.emit(VoiceBridgeEvent.Error("PARSE", "Failed to parse message"))
        }
    }

    fun disconnect() {
        Log.d(TAG, "[WS] disconnect")
        unregisterNetworkCallback()
        connectionJob?.cancel()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        SignalingForegroundService.stop(app)
    }
}
