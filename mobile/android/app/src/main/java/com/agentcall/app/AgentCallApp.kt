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

        val incomingCalls = NotificationChannel(
            CHANNEL_INCOMING_CALLS,
            "Incoming Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for incoming AI calls"
            setShowBadge(true)
            enableVibration(true)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
        }

        val ongoingCall = NotificationChannel(
            CallService.CHANNEL_ONGOING_CALL,
            "Ongoing Call",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ongoing call notification"
            setShowBadge(false)
            enableVibration(false)
        }

        val missedCalls = NotificationChannel(
            CHANNEL_MISSED_CALLS,
            "Missed Calls",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications for missed AI calls"
            setShowBadge(true)
            enableVibration(true)
        }

        val taskUpdates = NotificationChannel(
            CHANNEL_TASK_UPDATES,
            "Task Updates",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Updates when AI tasks complete"
            setShowBadge(true)
        }

        manager.createNotificationChannel(incomingCalls)
        manager.createNotificationChannel(ongoingCall)
        manager.createNotificationChannel(missedCalls)
        manager.createNotificationChannel(taskUpdates)
    }

    companion object {
        const val CHANNEL_INCOMING_CALLS = "incoming_calls"
        const val CHANNEL_MISSED_CALLS = "missed_calls"
        const val CHANNEL_TASK_UPDATES = "task_updates"
    }
}
