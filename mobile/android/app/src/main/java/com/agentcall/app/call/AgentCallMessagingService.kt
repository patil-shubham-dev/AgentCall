package com.agentcall.app.call

import android.content.Intent
import android.util.Log
import com.agentcall.app.data.repository.CallRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Phase A (FCM push-to-wake): a SECOND ring-delivery path alongside the
 * WS/poll system — additive only, nothing about existing delivery is removed
 * or reordered.
 *
 * The backend pushes call_incoming as a high-priority DATA message. This
 * service:
 *  - onNewToken: registers the device's Firebase token with the backend so it
 *    can be targeted for rings (idempotent, safe to re-call on every refresh).
 *  - onMessageReceived: for ring messages, hands the payload to the existing
 *    ring machinery — SignalingForegroundService — which already dedupes
 *    against WS/poll rings (recentlyRung guard) and validates the call is
 *    still pending before ringing. Notification messages (data.type absent or
 *    non-ring) are ignored, as are pushes for already-expired rings.
 */
@AndroidEntryPoint
class AgentCallMessagingService : FirebaseMessagingService() {

    @Inject lateinit var callRepository: CallRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "[FCM] new token registered, registering with backend")
        scope.launch {
            runCatching { callRepository.registerFcmToken(token) }
                .onFailure { Log.w(TAG, "[FCM] token registration failed", it) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val pushType = data["type"]
        if (pushType != "call_incoming") {
            // Phase A only carries rings; anything else (notification payloads,
            // future phases) is deliberately ignored.
            Log.d(TAG, "[FCM] ignoring non-ring push type=$pushType")
            return
        }
        val callId = data["callId"]?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "[FCM] ring push missing callId — dropping")
            return
        }
        // The payload mirrors the WS call_incoming payload (strings; the
        // server JSON-encodes non-string fields). Dedupe + ring-validity live
        // in the FGS exactly as they do for a WS ring — a duplicate FCM push
        // is a no-op, and an expired ring is dropped there too.
        Log.i(TAG, "[FCM] ring push received callId=$callId")
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
