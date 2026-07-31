package com.agentcall.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import com.agentcall.app.call.CallService
import com.agentcall.app.call.SignalingForegroundService
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AgentCallApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        checkFullScreenIntentPermission()
    }

    private fun checkFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT >= 34) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (!mgr.canUseFullScreenIntent()) {
                Log.w("AgentCall", "[startup] USE_FULL_SCREEN_INTENT not granted — incoming calls will not show full-screen UI")
                CallService.showFullScreenIntentWarning(this)
            } else {
                CallService.cancelFullScreenIntentWarning(this)
            }
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val ongoingCall = NotificationChannel(
            CallService.CHANNEL_ONGOING_CALL,
            "Ongoing Calls",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Notification for active voice calls"
            setShowBadge(false)
        }
        manager.createNotificationChannel(ongoingCall)

        val incomingCall = NotificationChannel(
            CallService.CHANNEL_INCOMING_CALL,
            "Incoming Calls",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Notifications for incoming AI calls"
            setShowBadge(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 1000, 500, 1000)
        }
        manager.createNotificationChannel(incomingCall)

        // Legacy "incoming_call" channel predates ringtone/vibration. ColorOS ignores
        // channel updates AND deleteNotificationChannel, so the versioned ID above is
        // the only reliable upgrade path; attempt cleanup for AOSP (no-op elsewhere).
        val legacy = manager.getNotificationChannel("incoming_call")
        if (legacy != null && legacy.vibrationPattern == null) {
            manager.deleteNotificationChannel("incoming_call")
        }

        val signaling = NotificationChannel(
            SignalingForegroundService.CHANNEL_SIGNALING,
            "Connection Status",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Persistent notification for WebSocket connection"
            setShowBadge(false)
        }
        manager.createNotificationChannel(signaling)
    }
}
