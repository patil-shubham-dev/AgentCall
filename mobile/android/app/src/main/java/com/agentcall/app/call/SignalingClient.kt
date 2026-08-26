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
        val clientInfoName: String? = null,
    ) : VoiceBridgeEvent()
    data class CallAnswered(val callId: String) : VoiceBridgeEvent()
    data class CallEnded(val callId: String) : VoiceBridgeEvent()
    data class CallCancelled(val callId: String) : VoiceBridgeEvent()
    data class CallExpired(val callId: String, val reason: String) : VoiceBridgeEvent()
    data class CallAborted(val callId: String, val reason: String) : VoiceBridgeEvent()
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
        .pingInterval(60, TimeUnit.SECONDS)
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

    /**
     * Idle park (backlog item 14 — FCM is now the primary ring-wake path):
     * the websocket is closed WITHOUT marking the user disconnected, so a
     * later [connectIfIdle] (or any [connect]) restores it cleanly. While
     * parked, the app relies on FCM high-priority pushes for rings — the
     * FGS fallback poll only runs while the FGS is alive, and the FGS parks
     * itself when there is no ring, no call and no foreground app.
     */
    @Volatile
    private var parked = false

    /** True while the socket is idle-parked (battery audit M4 poll gating). */
    val isParked: Boolean
        get() = parked

    @Volatile
    private var connectGeneration = 0

    /** When the socket was last opened; used to detect short-lived connections. */
    @Volatile
    private var openedAtMs = 0L

    /**
     * Consecutive sockets that died within [SHORT_LIVED_MS] of opening.
     * A deployment whose proxy kills idle sockets produces a connect→die→
     * reconnect storm (reconnectAttempt resets to 0 on every onOpen, so the
     * attempt cap never trips). The streak survives the reset and escalates
     * the backoff until the WS is parked entirely — the fallback poll covers
     * rings at a slow, battery-friendly cadence meanwhile.
     */
    @Volatile
    private var shortLivedStreak = 0

    companion object {
        private const val MAX_RECONNECT_ATTEMPTS = 20
        private const val MIN_RECONNECT_DELAY_MS = 2000L
        private const val MAX_BACKOFF_MS = 300_000L
        private const val SHORT_LIVED_MS = 60_000L
        private const val MAX_SHORT_LIVED_STREAK = 4
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
        parked = false
        currentUserId = userId
        reconnectAttempt = 0
        userDisconnected = false
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
        if (parked) {
            Log.d(TAG, "[TRACE] onNetworkAvailable() fired while parked — staying parked until connectIfIdle()")
            return
        }
        val state = _connectionState.value
        Log.d(TAG, "[TRACE] onNetworkAvailable() fired state=$state")
        if (state == ConnectionState.DISCONNECTED) {
            Log.d(TAG, "[WS] network became available — reconnecting")
            reconnectAttempt = 0
            connectGeneration++
            connectionJob = scope.launch {
                connectInternal("CALLER=onNetworkAvailable")
            }
        }
    }

    private suspend fun connectInternal(caller: String = "unknown") {
        val gen = connectGeneration
        Log.d(TAG, "[TRACE] connectInternal() called from=$caller state=${_connectionState.value} attempt=$reconnectAttempt")
        if (userDisconnected) return
        if (_connectionState.value == ConnectionState.CONNECTED || _connectionState.value == ConnectionState.CONNECTING) {
            // A close-triggered reconnect that runs while a newer socket is
            // already live would close that socket and cascade into an endless
            // connect/reconnect loop (observed: 2.4s reconnect storm). Deliberate
            // reconnects (Settings, network loss) go through disconnect() first,
            // so skipping here only drops redundant attempts.
            Log.d(TAG, "[WS] connectInternal skipped — already ${_connectionState.value}")
            return
        }
        _connectionState.value = ConnectionState.CONNECTING
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "[WS] max reconnect attempts reached ($MAX_RECONNECT_ATTEMPTS)")
            _connectionState.value = ConnectionState.DISCONNECTED
            scope.launch {
                _events.emit(VoiceBridgeEvent.Error("MAX_RECONNECT", "Server unreachable after $MAX_RECONNECT_ATTEMPTS attempts"))
                // Battery audit H1: the connection is genuinely gone — say so,
                // or UI flags derived from events drift from reality.
                _events.emit(VoiceBridgeEvent.Disconnected)
            }
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
                openedAtMs = System.currentTimeMillis()
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
                    if (scheduleReconnect("CALLER=onFailure")) return@launch
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (connectGeneration != gen || userDisconnected) return
                Log.d(TAG, "[WS] closed code=$code reason=$reason")
                scope.launch {
                    if (code == 4001) {
                        ApiClient.phoneToken = null
                    }
                    reconnectAttempt++
                    if (scheduleReconnect("CALLER=onClosed")) return@launch
                }
            }
        }

        webSocket?.close(1000, "Reconnecting")
        webSocket = client.newWebSocket(request, listener)
    }

    /**
     * Computes the reconnect delay and schedules it. Detects sockets that die
     * shortly after opening (a deployment that cannot hold a websocket) and
     * escalates the backoff via [shortLivedStreak]; once the streak passes
     * [MAX_SHORT_LIVED_STREAK] the WS is parked until the next explicit
     * connect()/network event — the foreground service's slow fallback poll
     * takes over ring delivery, so parking costs nothing but battery life.
     * Returns true when a reconnect was scheduled (or the WS is parked).
     */
    private suspend fun scheduleReconnect(caller: String): Boolean {
        val livedMs = if (openedAtMs > 0) System.currentTimeMillis() - openedAtMs else Long.MAX_VALUE
        openedAtMs = 0
        if (livedMs < SHORT_LIVED_MS) {
            shortLivedStreak++
        } else {
            shortLivedStreak = 0
        }
        if (shortLivedStreak >= MAX_SHORT_LIVED_STREAK) {
            Log.w(
                TAG,
                "[WS] $shortLivedStreak sockets died quickly — parking websocket; fallback poll takes over rings (caller=$caller)",
            )
            _connectionState.value = ConnectionState.DISCONNECTED
            scope.launch { _events.emit(VoiceBridgeEvent.Disconnected) }
            return true
        }
        _connectionState.value = ConnectionState.RECONNECTING
        val delayMs = calculateBackoff(reconnectAttempt + shortLivedStreak)
        Log.d(TAG, "[WS] reconnect attempt $reconnectAttempt (streak=$shortLivedStreak) in ${delayMs}ms (caller=$caller)")
        delay(delayMs)
        connectInternal("CALLER=$caller")
        return true
    }

    private fun calculateBackoff(attempt: Int): Long {
        val baseMs = MIN_RECONNECT_DELAY_MS
        val shift = (attempt - 1).coerceIn(0, 8)
        val exponential = baseMs * (1L shl shift)
        val jitter = (0..500).random()
        return (exponential + jitter).coerceAtMost(MAX_BACKOFF_MS)
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
                    // Which MCP client requested the call — caller badge (Item 5).
                    // Absent for older pushes: no badge, no crash.
                    val clientInfoName = payload.optJSONObject("clientInfo")?.optString("name")?.takeIf { it.isNotBlank() }
                    // Defense in depth: a queued push whose ring window already
                    // expired (server queue TTL or clock skew) is stale — it
                    // must never ring.
                    if (expiresAtMs != null && expiresAtMs <= System.currentTimeMillis()) {
                        Log.w(TAG, "[WS] dropping stale call_incoming callId=$callId (expired)")
                        return
                    }
                    Log.d(TAG, "[WS] call_incoming callId=$callId reason=$reason callerName=$callerName")
                    _events.emit(VoiceBridgeEvent.CallIncoming(callId, reason, summary, callerName, createdAtMs, expiresAtMs, clientInfoName))
                }
                "ai_message" -> {
                    val callId = payload?.getString("callId") ?: return
                    val messageObj = payload.optJSONObject("message") ?: return
                    val content = messageObj.optString("content").takeIf { it.isNotBlank() }
                    if (content == null) {
                        // Status-shaped messages (e.g. missed-call notices) carry
                        // no text — drop them instead of killing the whole push.
                        Log.w(TAG, "[WS] dropping ai_message without text callId=$callId msgId=${messageObj.optString("id", "?")}")
                        return
                    }
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
                "call_aborted" -> {
                    // The owning agent's last MCP session closed mid-call
                    // (crash/disconnect), not a user cancel: distinct terminal
                    // event so the UI can say "AI disconnected".
                    val callId = payload?.getString("callId") ?: return
                    val reason = payload.optString("reason", "agent_disconnected")
                    Log.d(TAG, "[WS] call_aborted callId=$callId reason=$reason")
                    _events.emit(VoiceBridgeEvent.CallAborted(callId, reason))
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

    /**
     * Idle park: closes the socket and stops reconnect activity without
     * setting [userDisconnected]. A ring in flight (CallStateHolder RINGING)
     * must never be cut — the FGS/poll path owns that window and can only
     * park after the ring resolves.
     *
     * Unlike [disconnect] this does NOT stop the SignalingForegroundService —
     * the caller decides service lifetime (maybeParkAndStop / MainActivity).
     */
    fun park() {
        if (CallStateHolder.state.value.status == CallStatus.RINGING) {
            Log.i(TAG, "[WS] park skipped — a ring is in flight")
            return
        }
        if (parked) return
        parked = true
        Log.i(TAG, "[WS] park (idle)")
        connectGeneration++
        unregisterNetworkCallback()
        connectionJob?.cancel()
        val socket = webSocket
        webSocket = null
        socket?.close(1000, "Parked idle")
        _connectionState.value = ConnectionState.DISCONNECTED
        // Battery audit H1: park() is a genuine disconnect for every consumer
        // of the event stream — the socket is closed and nothing will restore
        // it until connectIfIdle()/connect(). Emitting keeps UI state truthful
        // (HomeViewModel stops its availability polling) instead of relying on
        // an event that was defined but never sent.
        scope.launch { _events.emit(VoiceBridgeEvent.Disconnected) }
    }

    /**
     * Restores the websocket after an idle park (or any unreasoned
     * DISCONNECTED state), without forcing a full [connect] lifecycle.
     * The app re-enters the foreground, answers a call, or re-subscribes to
     * events through this. A deliberate [disconnect] stays disconnected
     * (userDisconnected) until the user explicitly reconnects.
     */
    fun connectIfIdle() {
        if (userDisconnected) return
        if (parked || _connectionState.value == ConnectionState.DISCONNECTED) {
            Log.i(TAG, "[WS] connectIfIdle — restoring connection")
            parked = false
            registerNetworkCallback()
            SignalingForegroundService.start(app)
            connectGeneration++
            connectionJob = scope.launch {
                connectInternal("CALLER=connectIfIdle")
            }
        }
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
        scope.launch { _events.emit(VoiceBridgeEvent.Disconnected) }
        SignalingForegroundService.stop(app)
    }
}
