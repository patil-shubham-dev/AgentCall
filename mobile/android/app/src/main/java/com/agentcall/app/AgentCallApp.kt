package com.agentcall.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import com.agentcall.app.call.CallService
import com.agentcall.app.call.FcmRegistrationStore
import com.agentcall.app.call.SignalingForegroundService
import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.api.ApiService
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@HiltAndroidApp
class AgentCallApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Restore the persisted backend host BEFORE anything (services,
        // Hilt singletons) builds URLs from ApiClient.serverHost.
        com.agentcall.app.data.api.ApiClient.init(this)
        FcmRegistrationStore.init(this)
        createNotificationChannels()
        checkFullScreenIntentPermission()
        ForegroundTracker.register(this)
        // FCM-only idle: register push token even when no FGS/WS is running.
        // This is the primary wake path — without it, idle rings never arrive.
        registerFcmTokenIfNeeded()
    }

    private fun registerFcmTokenIfNeeded() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val token = suspendCancellableCoroutine<String?> { cont ->
                    FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                        if (task.isSuccessful) cont.resume(task.result) else cont.resume(null)
                    }
                }
                if (token.isNullOrBlank()) {
                    Log.w("AgentCall", "[FCM] AgentCallApp token fetch returned null — FCM unavailable")
                    FcmRegistrationStore.recordFailure("no-token")
                    return@launch
                }
                ApiClient.ensurePhoneToken()
                ApiClient.create<ApiService>().let { api ->
                    api.registerFcmToken(com.agentcall.app.data.model.FcmTokenRequest(token))
                }
                FcmRegistrationStore.recordSuccess(token)
                Log.i("AgentCall", "[FCM] AgentCallApp startup token registered")
            } catch (e: Exception) {
                FcmRegistrationStore.recordFailure(e.message ?: "unknown")
                Log.w("AgentCall", "[FCM] AgentCallApp startup registration failed", e)
            }
        }
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

        // Quiet-hours variant of the incoming ring (backlog item 6): same
        // full-screen behavior, but IMPORTANCE_LOW with no sound and no
        // vibration, so DND never rings the user at 3 AM while still letting
        // them answer an important call.
        val quietIncoming = NotificationChannel(
            CallService.CHANNEL_INCOMING_CALL_QUIET,
            "Incoming Calls (Quiet Hours)",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Silent incoming-call notification during quiet hours"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(quietIncoming)

        // Missed-call notification (backlog item 1): silent, informational
        // only — fired when a ring expires while the app is backgrounded.
        val missedCalls = NotificationChannel(
            CallService.CHANNEL_MISSED_CALLS,
            "Missed Calls",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Quiet notification when a call rings unanswered"
            setShowBadge(true)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(missedCalls)

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
