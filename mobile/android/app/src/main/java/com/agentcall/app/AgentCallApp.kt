package com.agentcall.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.api.TokenManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AgentCallApp : Application() {

    @Inject lateinit var tokenManager: TokenManager

    override fun onCreate() {
        super.onCreate()
        ApiClient.setTokenProvider { tokenManager.accessToken }
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val incomingCalls = NotificationChannel(
            CHANNEL_INCOMING_CALLS,
            "Incoming Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for incoming AI-initiated calls"
            setShowBadge(true)
            enableVibration(true)
        }

        val ongoingCall = NotificationChannel(
            CHANNEL_ONGOING_CALL,
            "Ongoing Call",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ongoing call notification"
            setShowBadge(false)
            enableVibration(false)
        }

        manager.createNotificationChannel(incomingCalls)
        manager.createNotificationChannel(ongoingCall)
    }

    companion object {
        const val CHANNEL_INCOMING_CALLS = "incoming_calls"
        const val CHANNEL_ONGOING_CALL = "ongoing_call"
    }
}
