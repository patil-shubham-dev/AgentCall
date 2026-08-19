package com.agentcall.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CallResponse(
    @SerialName("call_id") val callId: String,
    val status: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("connected_at") val connectedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    val context: CallContext? = null,
    val result: CallResult? = null,
    @SerialName("message_count") val messageCount: Int? = null,
    @SerialName("ai_wait") val aiWait: AiWaitStatus? = null,
    @SerialName("client_info") val clientInfo: ClientInfo? = null,
)

/** MCP client that requested the call — the "via ..." caller badge. */
@Serializable
data class ClientInfo(
    val name: String = "",
    val version: String? = null,
)

@Serializable
data class AiWaitStatus(
    val active: Boolean = false,
    val activeUntil: String? = null,
    val lastActiveAt: String? = null,
)

@Serializable
data class CallContext(
    @SerialName("task_id") val taskId: String? = null,
    val summary: String? = null,
    val reason: String? = null,
    val options: List<String>? = null,
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
data class SendMessageResponse(
    @SerialName("message_id") val messageId: String,
    val role: String,
    val content: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class TranscriptResponse(
    @SerialName("call_id") val callId: String,
    val messages: List<TranscriptMessage>,
)

@Serializable
data class TranscriptMessage(
    val role: String,
    val type: String,
    val content: String,
    @SerialName("createdAt") val createdAt: String,
)

@Serializable
data class ActiveCallResponse(
    @SerialName("active_call") val activeCall: ActiveCall? = null,
)

@Serializable
data class ActiveCall(
    @SerialName("call_id") val callId: String,
    val status: String,
    val reason: String,
    val summary: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class PhoneRegisterResponse(
    val status: String,
    @SerialName("user_id") val userId: String,
    @SerialName("ws_endpoint") val wsEndpoint: String? = null,
)

@Serializable
data class UserTextResponse(
    @SerialName("call_id") val callId: String,
    val text: String,
)


