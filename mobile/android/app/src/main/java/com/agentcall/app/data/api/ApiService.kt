package com.agentcall.app.data.api

import com.agentcall.app.data.model.*
import okhttp3.MultipartBody
import retrofit2.http.*

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
        @Body result: Map<String, @JvmSuppressWildcards Any>,
    )

    @POST("calls/{callId}/callback")
    suspend fun scheduleCallback(
        @Path("callId") callId: String,
        @Body body: Map<String, Int>,
    ): Map<String, @JvmSuppressWildcards Any>

    @GET("users/{userId}/active-call")
    suspend fun getActiveCall(@Path("userId") userId: String): ActiveCallResponse

    @POST("phone/register")
    suspend fun registerPhone(): PhoneRegisterResponse

    @Multipart
    @POST("calls/{callId}/audio")
    suspend fun uploadAudio(
        @Path("callId") callId: String,
        @Part audio: MultipartBody.Part,
    ): TranscribeResponse
}
