package com.agentcall.app.call

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.agentcall.app.MainActivity
import com.agentcall.app.R
import com.agentcall.app.data.repository.CallRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Always-alive foreground service owning the incoming-call ring.
 *
 * The ring cannot depend on an Activity being alive or in the foreground:
 * Android 14 background-activity-launch restrictions and OEM full-screen-intent
 * suppression mean the ring UI may never launch while the app is backgrounded.
 * This service therefore posts the audible notification itself, keeps a 60s
 * ring timeout, and auto-declines via the resilient cancel chain if the ring
 * UI is never shown or is destroyed without an answer.
 */
@AndroidEntryPoint
class SignalingForegroundService : Service() {

    @Inject lateinit var signalingClient: SignalingClient
    @Inject lateinit var callRepository: CallRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var eventsJob: Job? = null
    private var ringTimeoutJob: Job? = null
    private var ringingCallId: String? = null

    private val disconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            signalingClient.disconnect()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(ACTION_DISCONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(disconnectReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(disconnectReceiver, filter)
        }
        eventsJob = scope.launch {
            signalingClient.events.collect { event ->
                handleEvent(event)
            }
        }
    }

    private suspend fun handleEvent(event: VoiceBridgeEvent) {
        when (event) {
            is VoiceBridgeEvent.Connected -> {
                // Covers calls created while the app/WS was fully closed: the
                // backend queues the push and this catches it on reconnect.
                if (ringingCallId == null) {
                    ringFromActiveCall()
                }
            }
            is VoiceBridgeEvent.CallIncoming -> {
                ringFromEvent(event)
            }
            is VoiceBridgeEvent.CallEnded, is VoiceBridgeEvent.CallCancelled -> {
                clearRing()
            }
            else -> {}
        }
    }

    private suspend fun ringFromActiveCall() {
        val active = callRepository.checkActiveCall(signalingClient.currentUserId) ?: return
        if (active.callId == ringingCallId) return
        val callerName = when (active.reason) {
            "approval" -> "Approval Request"
            "error" -> "Error Alert"
            "clarification" -> "AI Assistant"
            else -> "AI Agent"
        }
        val agentId = callerName.lowercase().replace("\\s+".toRegex(), "-")
        callRepository.ensureProfileExists(agentId, callerName)
        ring(active.callId, callerName, active.summary)
    }

    private suspend fun ringFromEvent(event: VoiceBridgeEvent.CallIncoming) {
        // The shared flow replays its last event on collector start, and queued
        // pushes can arrive for calls that were already resolved while we were
        // offline. Verify the call is still live before ringing.
        val session = try {
            callRepository.getCallStatus(event.callId)
        } catch (_: Exception) {
            null
        }
        if (session != null && (session == "pending" || session == "active")) {
            val agentId = event.callerName.lowercase().replace("\\s+".toRegex(), "-")
            callRepository.ensureProfileExists(agentId, event.callerName)
            ring(event.callId, event.callerName, event.summary)
        }
    }

    private fun ring(callId: String, callerName: String, summary: String) {
        if (callId == ringingCallId) return
        Log.i(TAG, "[RING] ringing callId=$callId caller=$callerName")
        ringingCallId = callId
        CallService.showIncomingCallNotification(this, callId, callerName, summary)
        ringTimeoutJob?.cancel()
        ringTimeoutJob = scope.launch {
            delay(RING_TIMEOUT_MS)
            if (ringingCallId == callId) {
                Log.i(TAG, "[RING] timeout for $callId — auto-declining")
                clearRing()
                startService(Intent(this@SignalingForegroundService, CallService::class.java).apply {
                    action = CallService.ACTION_CANCEL_CALL
                    putExtra(CallService.EXTRA_CALL_ID, callId)
                })
            }
        }
    }

    private fun clearRing() {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
        ringingCallId = null
        CallService.cancelIncomingNotification(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val disconnectIntent = PendingIntent.getBroadcast(
            this, REQUEST_DISCONNECT,
            Intent(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_SIGNALING)
            .setContentTitle("AgentCall")
            .setContentText("Connected — ready for calls")
            .setSmallIcon(R.drawable.ic_agent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Disconnect",
                    disconnectIntent,
                ).build()
            )
            .build()

        startForeground(NOTIFICATION_ID, notification)

        when (intent?.action) {
            ACTION_RING_RESOLVED -> {
                // The ring UI was answered/declined/postponed — stop the timeout
                // so it can never cancel a call that is now being handled.
                clearRing()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        eventsJob?.cancel()
        ringTimeoutJob?.cancel()
        try { unregisterReceiver(disconnectReceiver) } catch (_: IllegalArgumentException) {}
    }

    companion object {
        private const val TAG = "AgentCall"
        const val CHANNEL_SIGNALING = "signaling_service"
        const val ACTION_DISCONNECT = "com.agentcall.app.action.DISCONNECT_SIGNALING"
        const val ACTION_RING_RESOLVED = "com.agentcall.app.action.RING_RESOLVED"
        private const val NOTIFICATION_ID = 1003
        private const val REQUEST_DISCONNECT = 1001
        private const val RING_TIMEOUT_MS = 60_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SignalingForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SignalingForegroundService::class.java))
        }

        fun notifyRingResolved(context: Context) {
            context.startService(
                Intent(context, SignalingForegroundService::class.java).apply {
                    action = ACTION_RING_RESOLVED
                }
            )
        }
    }
}
