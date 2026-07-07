package com.agentcall.app.data.api

import com.agentcall.app.data.model.*
import retrofit2.http.*

interface ApiService {

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): AuthResponse

    @POST("auth/refresh")
    suspend fun refreshToken(@Body request: RefreshRequest): AuthResponse

    @POST("devices/register")
    suspend fun registerDevice(@Body request: DeviceRegisterRequest): DeviceRegisterResponse

    @DELETE("devices/{deviceId}")
    suspend fun removeDevice(@Path("deviceId") deviceId: String)

    @GET("users/{userId}/presence")
    suspend fun getUserPresence(@Path("userId") userId: String): PresenceResponse

    @POST("presence/heartbeat")
    suspend fun sendHeartbeat(@Body request: HeartbeatRequest)

    @POST("calls")
    suspend fun createCall(@Body request: CreateCallRequest): CreateCallResponse

    @GET("calls/{callId}")
    suspend fun getCall(@Path("callId") callId: String): CallResponse

    @POST("calls/{callId}/cancel")
    suspend fun cancelCall(@Path("callId") callId: String, @Body reason: Map<String, String>)

    @POST("calls/{callId}/complete")
    suspend fun completeCall(@Path("callId") callId: String, @Body result: Map<String, @JvmSuppressWildcards Any>)

    @GET("calls")
    suspend fun getCallHistory(): CallHistoryResponse

    @GET("turn/credentials")
    suspend fun getTurnCredentials(): TurnCredentialsResponse

    @GET("api-keys")
    suspend fun getApiKeys(): ApiKeyListResponse

    @POST("api-keys")
    suspend fun createApiKey(@Body request: Map<String, String>): CreateApiKeyResponse

    @DELETE("api-keys/{keyId}")
    suspend fun deleteApiKey(@Path("keyId") keyId: String)
}
