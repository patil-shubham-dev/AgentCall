package com.agentcall.app.push

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.agentcall.app.AgentCallApp
import com.agentcall.app.R
import com.agentcall.app.call.IncomingCallActivity
import com.agentcall.app.call.CallService

class AgentCallFirebaseService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
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
        val priority = data["priority"] ?: "normal"

        val intent = Intent(this, IncomingCallActivity::class.java).apply {
            putExtra("call_id", callId)
            putExtra("caller_name", callerName)
            putExtra("context_summary", contextSummary)
            putExtra("priority", priority)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
        )

        val answerIntent = PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = PendingIntent.getService(
            this, 2,
            Intent(this, CallService::class.java).apply {
                action = CallService.ACTION_END_CALL
                putExtra(CallService.EXTRA_CALL_ID, callId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val later5Intent = PendingIntent.getService(
            this, 3,
            Intent(this, CallService::class.java).apply {
                action = "com.agentcall.action.SCHEDULE_CALLBACK"
                putExtra(CallService.EXTRA_CALL_ID, callId)
                putExtra(CallService.EXTRA_TEXT, "5")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val priorityColor = when (priority) {
            "urgent" -> Color.parseColor("#EF4444")
            "high" -> Color.parseColor("#F59E0B")
            else -> Color.parseColor("#818CF8")
        }

        val builder = NotificationCompat.Builder(this, AgentCallApp.CHANNEL_INCOMING_CALLS)
            .setContentTitle(callerName)
            .setContentText(contextSummary.ifBlank { "Incoming AI call" })
            .setSmallIcon(R.drawable.ic_call)
            .setColor(priorityColor)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(false)
            .setOngoing(true)
            .setColorized(true)
            .addAction(R.drawable.ic_call, "Answer", answerIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declineIntent)
            .addAction(R.drawable.ic_missed_call, "5 min", later5Intent)
            .setTimeoutAfter(30000)

        val notification = builder.build()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(this).notify(CALL_NOTIFICATION_ID, notification)
        }
    }

    private fun handleTaskComplete(data: Map<String, String>) {
        val summary = data["summary"] ?: "Task completed"
        val callId = data["call_id"]

        val intent = if (callId != null) {
            Intent(this, IncomingCallActivity::class.java).apply {
                putExtra("call_id", callId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else null

        val pi = intent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notification = NotificationCompat.Builder(this, AgentCallApp.CHANNEL_TASK_UPDATES)
            .setContentTitle("Task Complete")
            .setContentText(summary)
            .setSmallIcon(R.drawable.ic_check_circle)
            .setColor(Color.parseColor("#22C55E"))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .build()

        NotificationManagerCompat.from(this).notify(TASK_NOTIFICATION_ID, notification)
    }

    private fun handleCallMissed(data: Map<String, String>) {
        val callerName = data["caller_name"] ?: "AI Agent"
        val callId = data["call_id"]
        val summary = data["context_summary"] ?: ""

        val intent = callId?.let {
            Intent(this, IncomingCallActivity::class.java).apply {
                putExtra("call_id", it)
                putExtra("caller_name", callerName)
                putExtra("context_summary", summary)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }

        val pi = intent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notification = NotificationCompat.Builder(this, AgentCallApp.CHANNEL_MISSED_CALLS)
            .setContentTitle("Missed Call")
            .setContentText("Missed call from $callerName")
            .setSubText(summary.ifBlank { null })
            .setSmallIcon(R.drawable.ic_missed_call)
            .setColor(Color.parseColor("#F87171"))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        NotificationManagerCompat.from(this).notify(MISSED_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CALL_NOTIFICATION_ID = 2001
        private const val TASK_NOTIFICATION_ID = 2002
        private const val MISSED_NOTIFICATION_ID = 2003
    }
}