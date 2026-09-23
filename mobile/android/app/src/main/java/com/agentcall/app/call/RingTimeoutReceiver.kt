package com.agentcall.app.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.agentcall.app.data.repository.CallRepository
import com.agentcall.app.settings.MessageTemplates
import com.agentcall.app.settings.QuietHoursManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Fires the 60s ring timeout without any foreground service (option B,
 * 2026-09-12 P0). Triggered by [RingTimeoutScheduler]'s exact
 * allow-while-idle alarm, which the OS delivers even in Doze with ~10s of
 * power exemption — enough for the single status-check + cancel POST below.
 *
 * This is also where the deferred phone-side pre-check lives (stale-decline
 * follow-up): server state is re-read before declining, so a timeout that
 * lost the race with an answer becomes a quiet no-op instead of a kill.
 * The server gate (cancel executes only on pending) backstops the residual
 * TOCTOU either way.
 */
@AndroidEntryPoint
class RingTimeoutReceiver : BroadcastReceiver() {

    @Inject lateinit var callRepository: CallRepository
    @Inject lateinit var quietHoursManager: QuietHoursManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(RingTimeoutScheduler.EXTRA_CALL_ID)
        if (callId.isNullOrBlank()) {
            Log.w(TAG, "[RING] timeout alarm without call_id — ignoring")
            return
        }
        val pendingResult = goAsync()
        scope.launch {
            try {
                // Single round trip: status drives the fire/skip decision and
                // agentId drives the quiet-hours note. Null (unreachable
                // backend) skips per policy — the server TTL backstops.
                val details = callRepository.getCallDetails(callId)
                if (RingTimeoutPolicy.shouldFireTimeoutCancel(details?.status)) {
                    val note = declineNote(context, details?.agentId ?: "")
                    val ok = callRepository.cancelCall(callId, note)
                    Log.i(TAG, "[RING] timeout for $callId — auto-declined ok=$ok")
                } else {
                    Log.i(TAG, "[RING] timeout for $callId skipped — server status=${details?.status}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "[RING] timeout handling failed for $callId", e)
            } finally {
                CallService.cancelIncomingNotification(context)
                pendingResult.finish()
            }
        }
    }

    private fun declineNote(context: Context, callerName: String): String {
        return if (quietHoursManager.isQuietNow(callerName)) {
            val (start, end) = quietHoursManager.activeRange(callerName) ?: (0 to 0)
            MessageTemplates.quietHoursMessage(
                context,
                QuietHoursManager.minutesToLabel(start),
                QuietHoursManager.minutesToLabel(end),
            )
        } else {
            MessageTemplates.declineMessage(context)
        }
    }

    companion object {
        private const val TAG = "AgentCall"
    }
}
