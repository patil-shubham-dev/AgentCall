package com.agentcall.app.call

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.agentcall.app.data.api.ApiClient
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
    data class CallIncoming(
        val callId: String,
        val reason: String,
        val summary: String,
        val callerName: String,
        val createdAtMs: Long? = null,
        val expiresAtMs: Long? = null,
    ) : VoiceBridgeEvent()
    data class CallAnswered(val callId: String) : VoiceBridgeEvent()
    data class CallEnded(val callId: String) : VoiceBridgeEvent()
    data class CallCancelled(val callId: String) : VoiceBridgeEvent()
    data class CallExpired(val callId: String, val reason: String) : VoiceBridgeEvent()
    data class Connected(val userId: String) : VoiceBridgeEvent()
    data class Error(val code: String, val message: String) : VoiceBridgeEvent()
    data class AiWaitStatus(
        val callId: String,
        val active: Boolean,
        val activeUntilMs: Long?,
        val lastActiveAtMs: Long?,
        val agentOnline: Boolean = true,
    ) : VoiceBridgeEvent()
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
    var currentUserId: String = "solo-user"
        private set

    @Volatile
    private var reconnectAttempt = 0
    private var networkCallback: ConnectivityNetworkCallback? = null
    private var wasEverConnected = false

    @Volatile
    private var userDisconnected = false

    @Volatile
    private var connectGeneration = 0

    companion object {
        private const val MAX_RECONNECT_ATTEMPTS = 20
        private const val MIN_RECONNECT_DELAY_MS = 2000L
    }

    private val _events = MutableSharedFlow<VoiceBridgeEvent>(replay = 1, extraBufferCapacity = 64)
    val events: SharedFlow<VoiceBridgeEvent> = _events

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING
    }

    fun connect(userId: String = "solo-user") {
        if (_connectionState.value == ConnectionState.CONNECTED && userId == currentUserId) {
            return
        }
        currentUserId = userId
        reconnectAttempt = 0
        userDisconnected = false
        _connectionState.value = ConnectionState.CONNECTING
        connectionJob?.cancel()
        registerNetworkCallback()
        SignalingForegroundService.start(app)
        connectGeneration++
        connectionJob = scope.launch {
            connectInternal("CALLER=connect()")
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
    ) : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "[NET] network available $network")
            client.onNetworkAvailable()
        }
    }

    private fun onNetworkAvailable() {
        if (userDisconnected) return
        val state = _connectionState.value
        Log.d(TAG, "[TRACE] onNetworkAvailable() fired state=$state")
        if (state == ConnectionState.DISCONNECTED) {
            Log.d(TAG, "[WS] network became available — reconnecting")
            reconnectAttempt = 0
            connectGeneration++
            connectionJob = scope.launch {
                _connectionState.value = ConnectionState.CONNECTING
                connectInternal("CALLER=onNetworkAvailable")
            }
        }
    }

    private suspend fun connectInternal(caller: String = "unknown") {
        val gen = connectGeneration
        Log.d(TAG, "[TRACE] connectInternal() called from=$caller state=${_connectionState.value} attempt=$reconnectAttempt")
        if (userDisconnected) return
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "[WS] max reconnect attempts reached ($MAX_RECONNECT_ATTEMPTS)")
            _connectionState.value = ConnectionState.DISCONNECTED
            scope.launch { _events.emit(VoiceBridgeEvent.Error("MAX_RECONNECT", "Server unreachable after $MAX_RECONNECT_ATTEMPTS attempts")) }
            return
        }
        try {
            ApiClient.ensurePhoneToken(currentUserId)
        } catch (e: Exception) {
            Log.e(TAG, "[WS] failed to get phone token", e)
            _connectionState.value = ConnectionState.RECONNECTING
            reconnectAttempt++
            val delayMs = calculateBackoff(reconnectAttempt)
            delay(delayMs)
            connectInternal("CALLER=token_retry")
            return
        }
        val url = ApiClient.getWsUrl(currentUserId)
        Log.d(TAG, "[WS] connecting to ${url.substringBefore('?')}")
        val requestBuilder = Request.Builder().url(url)
        ApiClient.phoneToken?.let { requestBuilder.addHeader("Authorization", "Bearer $it") }
        val request = requestBuilder.build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (connectGeneration != gen || userDisconnected) {
                    webSocket.cancel()
                    return
                }
                Log.d(TAG, "[WS] opened userId=$currentUserId")
                reconnectAttempt = 0
                wasEverConnected = true
                _connectionState.value = ConnectionState.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "[WS] <- message size=${text.length}")
                scope.launch { handleMessage(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (connectGeneration != gen || userDisconnected) return
                Log.e(TAG, "[WS] connection failure", t)
                scope.launch {
                    val httpCode = response?.code
                    if (httpCode == 401 || httpCode == 403) {
                        ApiClient.phoneToken = null
                    }
                    reconnectAttempt++
                    _connectionState.value = ConnectionState.RECONNECTING
                    val delayMs = calculateBackoff(reconnectAttempt)
                    Log.d(TAG, "[WS] reconnect attempt $reconnectAttempt in ${delayMs}ms (caller=onFailure)")
                    delay(delayMs)
                    connectInternal("CALLER=onFailure")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (connectGeneration != gen || userDisconnected) return
                Log.d(TAG, "[WS] closed code=$code reason=$reason")
                scope.launch {
                    if (code == 4001) {
                        ApiClient.phoneToken = null
                    }
                    _connectionState.value = ConnectionState.RECONNECTING
                    reconnectAttempt++
                    val delayMs = calculateBackoff(reconnectAttempt)
                    Log.d(TAG, "[WS] reconnect in ${delayMs}ms (caller=onClosed)")
                    delay(delayMs)
                    connectInternal("CALLER=onClosed")
                }
            }
        }

        webSocket?.close(1000, "Reconnecting")
        webSocket = client.newWebSocket(request, listener)
    }

    private fun calculateBackoff(attempt: Int): Long {
        val baseMs = MIN_RECONNECT_DELAY_MS
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
                    val callerName = payload.optString("callerName", "AI Agent")
                    val createdAtMs = payload.optString("createdAt", "").toEpochMsOrNull()
                    val expiresAtMs = payload.optString("expiresAt", "").toEpochMsOrNull()
                    // Defense in depth: a queued push whose ring window already
                    // expired (server queue TTL or clock skew) is stale — it
                    // must never ring.
                    if (expiresAtMs != null && expiresAtMs <= System.currentTimeMillis()) {
                        Log.w(TAG, "[WS] dropping stale call_incoming callId=$callId (expired)")
                        return
                    }
                    Log.d(TAG, "[WS] call_incoming callId=$callId reason=$reason callerName=$callerName")
                    _events.emit(VoiceBridgeEvent.CallIncoming(callId, reason, summary, callerName, createdAtMs, expiresAtMs))
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
                "call_answered" -> {
                    val callId = payload?.getString("callId") ?: return
                    Log.d(TAG, "[WS] call_answered callId=$callId")
                    _events.emit(VoiceBridgeEvent.CallAnswered(callId))
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
                "call_expired" -> {
                    val callId = payload?.getString("callId") ?: return
                    val reason = payload.optString("reason", "ring_ttl_expired")
                    Log.d(TAG, "[WS] call_expired callId=$callId reason=$reason")
                    _events.emit(VoiceBridgeEvent.CallExpired(callId, reason))
                }
                "ai_wait_status" -> {
                    val callId = payload?.getString("callId") ?: return
                    val active = payload.optBoolean("active", false)
                    val activeUntilMs = payload.optString("activeUntil", "").toEpochMsOrNull()
                    val lastActiveAtMs = payload.optString("lastActiveAt", "").toEpochMsOrNull()
                    // Phase 2: older backends don't send agentOnline — default
                    // to true so the UI never claims the agent is offline on
                    // stale events.
                    val agentOnline = payload.optBoolean("agentOnline", true)
                    Log.d(TAG, "[WS] ai_wait_status callId=$callId active=$active agentOnline=$agentOnline")
                    _events.emit(VoiceBridgeEvent.AiWaitStatus(callId, active, activeUntilMs, lastActiveAtMs, agentOnline))
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

    private fun String.toEpochMsOrNull(): Long? =
        try {
            if (isBlank()) null else java.time.Instant.parse(this).toEpochMilli()
        } catch (_: Exception) {
            null
        }

    fun disconnect() {
        Log.d(TAG, "[WS] disconnect")
        userDisconnected = true
        connectGeneration++
        unregisterNetworkCallback()
        connectionJob?.cancel()
        val socket = webSocket
        webSocket = null
        socket?.close(1000, "User disconnected")
        _connectionState.value = ConnectionState.DISCONNECTED
        SignalingForegroundService.stop(app)
    }
}
