package com.agentcall.app.call

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.agentcall.app.data.repository.CallRepository
import com.agentcall.app.settings.QuietHoursManager
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
 *  - onMessageReceived: validates and posts the ring DIRECTLY — expiry check,
 *    server liveness check, history row, notification, exact timeout alarm.
 *    Deliberately starts NO foreground service here: starting the signaling
 *    FGS from a backgrounded push crashed the app twice over (dataSync quota
 *    first, phoneCall Telecom requirements after — 2026-09-12 P0), and none
 *    of the ring-leg work (HTTP validation, Room writes, notification post,
 *    alarm schedule) needs foreground. The FGS keeps its WS/poll/connected
 *    duties for when it is legitimately alive.
 */
@AndroidEntryPoint
class AgentCallMessagingService : FirebaseMessagingService() {

    @Inject lateinit var callRepository: CallRepository
    @Inject lateinit var quietHoursManager: QuietHoursManager

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        FcmRegistrationStore.init(this)
        // Never log full token
        Log.i(TAG, "[FCM] new token ${token.take(12)}... � enqueuing reconciliation")
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
            Log.w(TAG, "[FCM] ring push missing callId � dropping")
            return
        }
        Log.i(TAG, "[FCM] ring push received callId=$callId")
        // Direct ring, no foreground service: every step below is
        // background-safe (HTTP validation, Room writes, notification post,
        // alarm schedule). Starting the signaling FGS from a backgrounded
        // push crashed the app (dataSync quota, then phoneCall Telecom
        // requirements — 2026-09-12 P0), and none of the ring-leg work needs
        // foreground. No startService/startForegroundService calls here.
        val callerName = data["callerName"]?.takeIf { it.isNotBlank() } ?: "AI Agent"
        val summary = data["summary"] ?: ""
        val expiresAtMs = data["expiresAt"]?.toLongOrNull()
        if (expiresAtMs != null && expiresAtMs <= System.currentTimeMillis()) {
            Log.w(TAG, "[RING] skipping expired push callId=$callId")
            return
        }
        // Server liveness: a queued push can arrive for an already-resolved
        // call. Same check the FGS ring path performs — a push that fails it
        // never rings. One bounded blocking section (validation + history
        // writes) inside FCM's background execution window; the suspend DAO
        // and network calls need a coroutine, and onMessageReceived offers
        // no scope. Typically ~1s; Firebase tolerates this window.
        // The validation fetch is capped at VALIDATION_TIMEOUT_MS: the worst
        // case is a Render cold start (20–30s), which would otherwise block
        // the FCM dispatch thread for the full duration and risk the process
        // being killed for an unacknowledged push. A timeout (or any other
        // null) skips the ring — the server's 3-min pending TTL backstops a
        // missed ring via the fallback poll / next ring retry.
        val validated = kotlinx.coroutines.runBlocking(
            kotlinx.coroutines.Dispatchers.IO,
        ) {
            val details = try {
                kotlinx.coroutines.withTimeoutOrNull(VALIDATION_TIMEOUT_MS) {
                    callRepository.getCallDetails(callId)
                }
            } catch (e: Exception) {
                Log.w(TAG, "[RING] validation fetch failed callId=$callId", e)
                null
            }
            if (details == null) {
                // Distinguishing log for the timeout case: null from
                // withTimeoutOrNull is indistinguishable from a null body,
                // and the skip decision is identical either way (policy:
                // conservative skip, server TTL backstops).
                Log.w(TAG, "[RING] validation fetch timed out or empty after ${VALIDATION_TIMEOUT_MS}ms callId=$callId — skipping (server TTL backstops)")
            }
            val status = details?.status
            if (status != "pending" && status != "active") {
                Log.i(TAG, "[RING] skipping push callId=$callId server status=$status")
                return@runBlocking null
            }
            val agentId = callerName.lowercase().replace("\\s+".toRegex(), "-")
            try {
                callRepository.ensureProfileExists(agentId, callerName)
                callRepository.markCallRinging(callId, agentId, callerName, System.currentTimeMillis())
            } catch (e: Exception) {
                Log.w(TAG, "[RING] history row write failed callId=$callId", e)
            }
            callerName
        }
        if (validated == null) return
        // Repeat-push dedupe without process memory: the server retries FCM
        // while pending, and each push may run in a fresh process. A posted
        // incoming notification means a previous push already rang (and armed
        // the timeout) — skip the re-post. Same-id posts collapse anyway.
        if (isRingNotificationPosted()) {
            Log.i(TAG, "[RING] ring already posted callId=$callId — skipping duplicate push")
            return
        }
        CallStateHolder.ringing(callId)
        val quiet = try {
            quietHoursManager.isQuietNow(callerName)
        } catch (e: Exception) {
            Log.w(TAG, "[RING] quiet-hours check failed, ringing loud", e)
            false
        }
        // Alarm first: a process death between these lines leaves a fired
        // timeout with no notification (self-correcting — the receiver
        // clears the absent notification and only cancels still-pending
        // calls) rather than a notification with no timeout (rings until the
        // server TTL). Timeout correctness is load-bearing.
        RingTimeoutScheduler.schedule(this, callId, System.currentTimeMillis())
        try {
            CallService.showIncomingCallNotification(
                this, callId, callerName, summary,
                quiet = quiet,
                clientInfoName = data["clientInfoName"],
            )
        } catch (e: Exception) {
            Log.w(TAG, "[RING] notification post failed callId=$callId", e)
            RingTimeoutScheduler.cancel(this, callId)
            return
        }
        Log.i(TAG, "[DIAG] ring_posted_direct callId=$callId quiet=$quiet atMs=${System.currentTimeMillis()}")
    }

    /**
     * True when our incoming-call notification is currently posted.
     * Single-ring design with a fixed notification id: a posted one belongs
     * to the live ring, and every resolve path clears it first.
     */
    private fun isRingNotificationPosted(): Boolean {
        return try {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.activeNotifications.any { it.id == CallService.NOTIFICATION_ID_INCOMING }
        } catch (e: Exception) {
            Log.w(TAG, "[RING] active-notification check failed, assuming unposted", e)
            false
        }
    }

    companion object {
        private const val TAG = "AgentCall"

        /**
         * Cap for the server-validation fetch on the ring-critical path.
         * Generous enough for a healthy ~1s round trip plus headroom, tight
         * enough to keep FCM's 10s-ish dispatch window comfortable even when
         * the backend is cold-spinning (20–30s worst case, unringable anyway
         * while the server is down — the fallback poll and the next ring
         * retry cover recovery).
         */
        private const val VALIDATION_TIMEOUT_MS = 3_000L
    }
}
