package com.agentcall.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.agentcall.app.call.CallService
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AgentCallApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val ongoingCall = NotificationChannel(
            CallService.CHANNEL_ONGOING_CALL,
            "Ongoing Call",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ongoing call notification"
            setShowBadge(false)
            enableVibration(false)
        }

        manager.createNotificationChannel(ongoingCall)
    }
}
