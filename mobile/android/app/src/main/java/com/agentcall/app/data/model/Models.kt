package com.agentcall.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class AuthResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("user_id") val userId: String,
)

@Serializable
data class DeviceRegisterRequest(
    val platform: String = "android",
    @SerialName("push_token") val pushToken: String,
    @SerialName("device_name") val deviceName: String,
)

@Serializable
data class DeviceRegisterResponse(
    @SerialName("device_id") val deviceId: String,
    val status: String,
)

@Serializable
data class PresenceResponse(
    @SerialName("user_id") val userId: String,
    val status: String,
    @SerialName("last_seen") val lastSeen: String? = null,
    val dnd: Boolean = false,
    val devices: List<DeviceInfo> = emptyList(),
)

@Serializable
data class DeviceInfo(
    val platform: String,
    @SerialName("push_enabled") val pushEnabled: Boolean,
)

@Serializable
data class HeartbeatRequest(
    @SerialName("device_id") val deviceId: String,
    val platform: String = "android",
)

@Serializable
data class CreateCallRequest(
    @SerialName("user_id") val userId: String,
    @SerialName("agent_id") val agentId: String,
    val context: CallContext,
    val priority: String = "normal",
    @SerialName("timeout_seconds") val timeoutSeconds: Int = 30,
)

@Serializable
data class CallContext(
    @SerialName("task_id") val taskId: String? = null,
    val reason: String,
    val summary: String,
    val options: List<String>? = null,
)

@Serializable
data class CreateCallResponse(
    @SerialName("call_id") val callId: String,
    val status: String,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class CallResponse(
    @SerialName("call_id") val callId: String,
    val status: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("connected_at") val connectedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    val result: CallResult? = null,
)

@Serializable
data class CallResult(
    @SerialName("transcript_summary") val transcriptSummary: String? = null,
    @SerialName("user_response") val userResponse: String? = null,
    val decision: String? = null,
    @SerialName("selected_option") val selectedOption: String? = null,
    val sentiment: String? = null,
    @SerialName("action_items") val actionItems: List<String>? = null,
)

@Serializable
data class CallHistoryResponse(val calls: List<CallResponse>)

@Serializable
data class TurnCredentialsResponse(
    val username: String,
    val credential: String,
    val ttl: Int,
)

@Serializable
data class ApiKeyInfo(
    val id: String,
    val name: String,
    @SerialName("key_prefix") val keyPrefix: String,
)

@Serializable
data class ApiKeyListResponse(@SerialName("api_keys") val apiKeys: List<ApiKeyInfo>)

@Serializable
data class CreateApiKeyResponse(
    @SerialName("api_key") val apiKey: String,
    val name: String,
    @SerialName("key_prefix") val keyPrefix: String,
)

@Serializable
data class PushPayload(
    val type: String,
    @SerialName("call_id") val callId: String? = null,
    @SerialName("caller_name") val callerName: String? = null,
    @SerialName("context_summary") val contextSummary: String? = null,
    val priority: String? = null,
)
