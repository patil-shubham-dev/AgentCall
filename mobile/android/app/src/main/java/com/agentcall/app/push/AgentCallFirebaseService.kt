package com.agentcall.app.push

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.agentcall.app.AgentCallApp
import com.agentcall.app.R
import com.agentcall.app.call.IncomingCallActivity
import com.agentcall.app.call.CallService
import com.agentcall.app.data.api.TokenManager

class AgentCallFirebaseService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // TokenManager will store this token for registration
        getSharedPreferences("push_prefs", MODE_PRIVATE)
            .edit()
            .putString("fcm_token", token)
            .apply()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val data = message.data
        val type = data["type"]

        when (type) {
            "call_incoming" -> handleIncomingCall(data)
            "task_complete" -> handleTaskComplete(data)
            "call_missed" -> handleCallMissed(data)
        }
    }

    private fun handleIncomingCall(data: Map<String, String>) {
        val callId = data["call_id"] ?: return
        val callerName = data["caller_name"] ?: "AI Agent"
        val contextSummary = data["context_summary"] ?: ""

        val intent = Intent(this, IncomingCallActivity::class.java).apply {
            putExtra("call_id", callId)
            putExtra("caller_name", callerName)
            putExtra("context_summary", contextSummary)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, AgentCallApp.CHANNEL_INCOMING_CALLS)
            .setContentTitle(callerName)
            .setContentText(contextSummary.ifBlank { "Incoming AI call" })
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Permission should be requested at runtime
        }

        NotificationManagerCompat.from(this).notify(CALL_NOTIFICATION_ID, notification)
    }

    private fun handleTaskComplete(data: Map<String, String>) {
        val summary = data["summary"] ?: "Task completed"

        val notification = NotificationCompat.Builder(this, AgentCallApp.CHANNEL_INCOMING_CALLS)
            .setContentTitle("Task Complete")
            .setContentText(summary)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(this).notify(TASK_NOTIFICATION_ID, notification)
    }

    private fun handleCallMissed(data: Map<String, String>) {
        val callerName = data["caller_name"] ?: "AI Agent"

        val notification = NotificationCompat.Builder(this, AgentCallApp.CHANNEL_INCOMING_CALLS)
            .setContentTitle("Missed Call")
            .setContentText("Missed call from $callerName")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(this).notify(MISSED_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CALL_NOTIFICATION_ID = 2001
        private const val TASK_NOTIFICATION_ID = 2002
        private const val MISSED_NOTIFICATION_ID = 2003
    }
}
