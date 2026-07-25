package com.agentcall.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
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

        val incomingCall = NotificationChannel(
            CallService.CHANNEL_INCOMING_CALL,
            "Incoming Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming AI call alerts"
            setShowBadge(true)
            enableVibration(true)
            enableLights(true)
            lockscreenVisibility = NotificationManager.IMPORTANCE_HIGH
        }
        manager.createNotificationChannel(incomingCall)
    }
}
