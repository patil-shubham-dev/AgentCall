package com.agentcall.app.call

import com.agentcall.app.data.api.TokenManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
import okhttp3.*
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import javax.inject.Inject
import javax.inject.Singleton

sealed class SignalingEvent {
    data class OfferReceived(val sdp: SessionDescription) : SignalingEvent()
    data class AnswerReceived(val sdp: SessionDescription) : SignalingEvent()
    data class IceCandidateReceived(val candidate: IceCandidate) : SignalingEvent()
    data class ParticipantJoined(val userId: String, val role: String) : SignalingEvent()
    data class ParticipantLeft(val userId: String) : SignalingEvent()
    data class MuteChanged(val userId: String, val muted: Boolean) : SignalingEvent()
    data class Error(val code: String, val message: String) : SignalingEvent()
    object RoomJoined : SignalingEvent()
    object Disconnected : SignalingEvent()
}

@Singleton
class SignalingClient @Inject constructor(
    private val tokenManager: TokenManager
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private var connectionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _events = MutableSharedFlow<SignalingEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SignalingEvent> = _events

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING
    }

    fun connect(callId: String) {
        connectionJob?.cancel()
        connectionJob = scope.launch {
            _connectionState.value = ConnectionState.CONNECTING
            connectInternal(callId)
        }
    }

    private suspend fun connectInternal(callId: String) {
        val token = tokenManager.accessToken ?: run {
            _events.emit(SignalingEvent.Error("AUTH", "No access token"))
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }

        val url = "wss://api.agentcall.example.com/ws?token=$token&call_id=$callId"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = ConnectionState.CONNECTED
                scope.launch { _events.emit(SignalingEvent.RoomJoined) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { handleMessage(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch {
                    _connectionState.value = ConnectionState.RECONNECTING
                    delay(2000)
                    connectInternal(callId)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = ConnectionState.DISCONNECTED
                scope.launch { _events.emit(SignalingEvent.Disconnected) }
            }
        })
    }

    private suspend fun handleMessage(text: String) {
        try {
            val obj = JSONObject(text)
            val type = obj.getString("type")
            val payload = obj.optJSONObject("payload")

            when (type) {
                "offer" -> {
                    val sdp = payload?.getString("sdp") ?: return
                    val typeStr = payload.optString("type", "offer")
                    _events.emit(
                        SignalingEvent.OfferReceived(
                            SessionDescription(
                                SessionDescription.Type.fromCanonicalForm(typeStr),
                                sdp
                            )
                        )
                    )
                }
                "answer" -> {
                    val sdp = payload?.getString("sdp") ?: return
                    _events.emit(
                        SignalingEvent.AnswerReceived(
                            SessionDescription(SessionDescription.Type.ANSWER, sdp)
                        )
                    )
                }
                "ice_candidate" -> {
                    val candidate = payload?.getString("candidate") ?: return
                    val sdpMid = payload.getString("sdpMid")
                    val sdpMLineIndex = payload.getInt("sdpMLineIndex")
                    _events.emit(
                        SignalingEvent.IceCandidateReceived(
                            IceCandidate(sdpMid, sdpMLineIndex, candidate)
                        )
                    )
                }
                "participant_joined" -> {
                    val userId = payload?.getString("user_id") ?: return
                    val role = payload.optString("role", "unknown")
                    _events.emit(SignalingEvent.ParticipantJoined(userId, role))
                }
                "participant_left" -> {
                    val userId = payload?.getString("user_id") ?: return
                    _events.emit(SignalingEvent.ParticipantLeft(userId))
                }
                "mute_changed" -> {
                    val userId = payload?.getString("user_id") ?: return
                    val muted = payload.optBoolean("muted", false)
                    _events.emit(SignalingEvent.MuteChanged(userId, muted))
                }
                "error" -> {
                    val code = payload?.getString("code") ?: "UNKNOWN"
                    val message = payload?.getString("message") ?: "Unknown error"
                    _events.emit(SignalingEvent.Error(code, message))
                }
            }
        } catch (e: Exception) {
            _events.emit(SignalingEvent.Error("PARSE", "Failed to parse message"))
        }
    }

    fun sendOffer(sdp: SessionDescription) {
        val msg = buildJsonObject {
            put("type", "offer")
            put("payload", buildJsonObject {
                put("sdp", sdp.description)
                put("type", sdp.type.canonicalForm())
            })
            put("timestamp", JsonPrimitive(java.time.Instant.now().toString()))
        }
        webSocket?.send(json.encodeToString(msg))
    }

    fun sendAnswer(sdp: SessionDescription) {
        val msg = buildJsonObject {
            put("type", "answer")
            put("payload", buildJsonObject {
                put("sdp", sdp.description)
                put("type", sdp.type.canonicalForm())
            })
            put("timestamp", JsonPrimitive(java.time.Instant.now().toString()))
        }
        webSocket?.send(json.encodeToString(msg))
    }

    fun sendIceCandidate(candidate: IceCandidate) {
        val msg = buildJsonObject {
            put("type", "ice_candidate")
            put("payload", buildJsonObject {
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            })
            put("timestamp", JsonPrimitive(java.time.Instant.now().toString()))
        }
        webSocket?.send(json.encodeToString(msg))
    }

    fun sendMuteState(muted: Boolean) {
        val msg = buildJsonObject {
            put("type", "mute")
            put("payload", buildJsonObject { put("muted", muted) })
            put("timestamp", JsonPrimitive(java.time.Instant.now().toString()))
        }
        webSocket?.send(json.encodeToString(msg))
    }

    fun sendHangup() {
        val msg = buildJsonObject {
            put("type", "hangup")
            put("payload", buildJsonObject())
            put("timestamp", JsonPrimitive(java.time.Instant.now().toString()))
        }
        webSocket?.send(json.encodeToString(msg))
    }

    fun disconnect() {
        connectionJob?.cancel()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }
}
