package com.agentcall.app.call

import android.content.Intent
import android.util.Log
import com.agentcall.app.data.repository.CallRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Phase A (FCM push-to-wake): a SECOND ring-delivery path alongside the
 * WS/poll system — additive only, nothing about existing delivery is removed
 * or reordered.
 *
 * The backend pushes call_incoming as a high-priority DATA message. This
 * service:
 *  - onNewToken: enqueues canonical WorkManager reconciliation (FcmRegistrationWorker)
 *    so token rotation survives cold-start and process death.
 *  - onMessageReceived: for ring messages, hands the payload to the existing
 *    ring machinery — SignalingForegroundService — which already dedupes
 *    against WS/poll rings (recentlyRung guard) and validates the call is
 *    still pending before ringing.
 */
@AndroidEntryPoint
class AgentCallMessagingService : FirebaseMessagingService() {

    @Inject lateinit var callRepository: CallRepository

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        FcmRegistrationStore.init(this)
        // Never log full token
        Log.i(TAG, "[FCM] new token ${token.take(12)}... — enqueuing reconciliation")
        FcmRegistrationScheduler.enqueue(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val pushType = data["type"]
        if (pushType != "call_incoming") {
            Log.d(TAG, "[FCM] ignoring non-ring push type=$pushType")
            return
        }
        val callId = data["callId"]?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "[FCM] ring push missing callId — dropping")
            return
        }
        Log.i(TAG, "[FCM] ring push received callId=$callId")
        try {
            startService(Intent(this, CallService::class.java).apply {
                action = CallService.ACTION_PREWARM_TTS
            })
        } catch (e: Exception) {
            Log.w(TAG, "[FCM] prewarm start failed", e)
        }
        startService(
            Intent(this, SignalingForegroundService::class.java).apply {
                action = SignalingForegroundService.ACTION_RING_FROM_PUSH
                putExtra(SignalingForegroundService.EXTRA_RING_CALL_ID, callId)
                putExtra(SignalingForegroundService.EXTRA_RING_CALLER, data["callerName"])
                putExtra(SignalingForegroundService.EXTRA_RING_SUMMARY, data["summary"])
                putExtra(SignalingForegroundService.EXTRA_RING_CREATED_AT, data["createdAt"])
                putExtra(SignalingForegroundService.EXTRA_RING_EXPIRES_AT, data["expiresAt"])
            }
        )
    }

    companion object {
        private const val TAG = "AgentCall"
    }
}