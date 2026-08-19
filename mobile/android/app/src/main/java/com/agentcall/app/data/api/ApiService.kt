package com.agentcall.app.data.api

import com.agentcall.app.data.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.*

@Serializable
data class CompleteRequest(
    @SerialName("result") val result: CompleteResultPayload? = null,
)

@Serializable
data class CompleteResultPayload(
    @SerialName("transcript_summary") val transcriptSummary: String? = null,
)

@Serializable
data class CancelRequest(
    @SerialName("reason") val reason: String = "user_requested",
    @SerialName("note") val note: String? = null,
)

@Serializable
data class CallbackRequest(
    @SerialName("delay_minutes") val delayMinutes: Int,
    @SerialName("note") val note: String? = null,
)

@Serializable
data class StatusResponse(
    @SerialName("status") val status: String = "",
    @SerialName("call_id") val callId: String? = null,
)

@Serializable
data class CallbackResponse(
    @SerialName("status") val status: String = "",
    @SerialName("call_id") val callId: String? = null,
    @SerialName("resume_in_minutes") val resumeInMinutes: Int? = null,
)

@Serializable
data class AiKeyCreateRequest(
    @SerialName("name") val name: String,
)

@Serializable
data class AiKeyCreateResponse(
    @SerialName("key_id") val keyId: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("key") val key: String = "",
)

@Serializable
data class AiKeyItem(
    @SerialName("key_id") val keyId: String,
    @SerialName("name") val name: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_used_at") val lastUsedAt: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("online") val online: Boolean = false,
    @SerialName("busy") val busy: Boolean = false,
)

@Serializable
data class AiKeyListResponse(
    @SerialName("keys") val keys: List<AiKeyItem> = emptyList(),
)

@Serializable
data class AiKeyDeleteResponse(
    @SerialName("status") val status: String = "",
    @SerialName("key_id") val keyId: String = "",
)

@Serializable
data class AiKeyRenameRequest(
    @SerialName("name") val name: String,
)

@Serializable
data class AiKeyRenameResponse(
    @SerialName("key_id") val keyId: String = "",
    @SerialName("name") val name: String = "",
)

@Serializable
data class AgentStatusResponse(
    @SerialName("agent_id") val agentId: String = "",
    val online: Boolean = false,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("current_call_id") val currentCallId: String? = null,
)

interface ApiService {

    @GET("calls/{callId}")
    suspend fun getCall(@Path("callId") callId: String): CallResponse

    @POST("calls/{callId}/messages")
    suspend fun sendMessage(
        @Path("callId") callId: String,
        @Body body: Map<String, String>,
    ): SendMessageResponse

    @POST("calls/{callId}/complete")
    suspend fun completeCall(
        @Path("callId") callId: String,
        @Body body: CompleteRequest = CompleteRequest(),
    ): StatusResponse

    @POST("calls/{callId}/cancel")
    suspend fun cancelCall(
        @Path("callId") callId: String,
        @Body body: CancelRequest = CancelRequest(),
    ): StatusResponse

    @POST("calls/{callId}/answer")
    suspend fun answerCall(@Path("callId") callId: String): StatusResponse

    @POST("calls/{callId}/callback")
    suspend fun scheduleCallback(
        @Path("callId") callId: String,
        @Body body: CallbackRequest,
    ): CallbackResponse

    @GET("users/{userId}/active-call")
    suspend fun getActiveCall(@Path("userId") userId: String): ActiveCallResponse

    @POST("phone/register")
    suspend fun registerPhone(): PhoneRegisterResponse

    // Phase A (FCM push-to-wake): register the device's Firebase token so the
    // backend can push rings via FCM alongside the WS/poll path.
    @POST("phone/fcm-token")
    suspend fun registerFcmToken(@Body body: FcmTokenRequest): FcmTokenResponse

    @POST("calls/{callId}/user-text")
    suspend fun sendUserText(
        @Path("callId") callId: String,
        @Body body: Map<String, String>,
    ): UserTextResponse

    @GET("calls/{callId}/transcript")
    suspend fun getTranscript(@Path("callId") callId: String): TranscriptResponse

    @POST("ai/keys")
    suspend fun createAiKey(@Body body: AiKeyCreateRequest): AiKeyCreateResponse

    @GET("ai/keys")
    suspend fun listAiKeys(): AiKeyListResponse

    @DELETE("ai/keys/{keyId}")
    suspend fun deleteAiKey(@Path("keyId") keyId: String): AiKeyDeleteResponse

    @PATCH("ai/keys/{keyId}")
    suspend fun renameAiKey(@Path("keyId") keyId: String, @Body body: AiKeyRenameRequest): AiKeyRenameResponse

    @GET("agents/{agentId}/status")
    suspend fun getAgentStatus(@Path("agentId") agentId: String): AgentStatusResponse
}
