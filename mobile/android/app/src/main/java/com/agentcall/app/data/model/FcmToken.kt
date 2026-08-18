package com.agentcall.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FcmTokenRequest(
    @SerialName("fcm_token") val fcmToken: String,
)

@Serializable
data class FcmTokenResponse(
    val status: String,
    @SerialName("user_id") val userId: String? = null,
)
