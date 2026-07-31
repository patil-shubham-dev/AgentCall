package com.agentcall.app.data.api

import com.agentcall.app.data.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.*

@Serializable
data class CompleteRequest(
    @SerialName("result") val result: String? = null,
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

interface ApiService {

    @POST("calls")
    suspend fun createCall(): CreateCallResponse

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

    @POST("calls/{callId}/user-text")
    suspend fun sendUserText(
        @Path("callId") callId: String,
        @Body body: Map<String, String>,
    ): UserTextResponse

    @GET("calls/{callId}/transcript")
    suspend fun getTranscript(@Path("callId") callId: String): TranscriptResponse
}
