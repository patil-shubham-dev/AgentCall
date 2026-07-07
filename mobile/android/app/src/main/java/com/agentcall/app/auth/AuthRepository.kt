package com.agentcall.app.auth

import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.api.ApiService
import com.agentcall.app.data.api.TokenManager
import com.agentcall.app.data.model.DeviceRegisterRequest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val tokenManager: TokenManager
) {
    private val api: ApiService = ApiClient.create()

    suspend fun login(email: String, password: String): Result<String> {
        return try {
            val response = api.login(
                com.agentcall.app.data.model.LoginRequest(email, password)
            )
            tokenManager.accessToken = response.accessToken
            tokenManager.refreshToken = response.refreshToken
            tokenManager.userId = response.userId
            Result.success(response.userId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun registerDevice(pushToken: String, deviceName: String): Result<String> {
        return try {
            val response = api.registerDevice(
                DeviceRegisterRequest(
                    pushToken = pushToken,
                    deviceName = deviceName,
                )
            )
            tokenManager.deviceId = response.deviceId
            Result.success(response.deviceId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendHeartbeat() {
        val deviceId = tokenManager.deviceId ?: return
        try {
            api.sendHeartbeat(
                com.agentcall.app.data.model.HeartbeatRequest(deviceId = deviceId)
            )
        } catch (_: Exception) { }
    }

    fun logout() {
        tokenManager.clear()
    }

    fun isLoggedIn(): Boolean = tokenManager.isLoggedIn
}
