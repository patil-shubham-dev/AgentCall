package com.agentcall.app.call

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Schedules the 60s ring timeout as an exact allow-while-idle alarm instead
 * of a foreground-service coroutine (option B, 2026-09-12 P0).
 *
 * Why this API, per Android docs (not familiarity): standard alarms including
 * setExact/setWindow are deferred to the next Doze maintenance window, and
 * WorkManager expedited work is explicitly deferrable/quota-gated ("less
 * likely" affected, may be deferred, OutOfQuotaPolicy) — neither holds a hard
 * 60s deadline under Doze. setExactAndAllowWhileIdle fires in Doze, grants
 * ~10s of power exemption on dispatch (enough for one cancel POST), and may
 * itself start a foreground service from background if ever needed.
 * Frequency cap (~1/9min per app) is fine: rings are minutes apart, and the
 * server's pending-TTL sweep backstops a throttled alarm regardless.
 */
object RingTimeoutScheduler {
    private const val TAG = "AgentCall"
    const val ACTION_RING_TIMEOUT = "com.agentcall.action.RING_TIMEOUT"
    const val EXTRA_CALL_ID = "call_id"

    private fun timeoutIntent(context: Context, callId: String): PendingIntent {
        val intent = Intent(context, RingTimeoutReceiver::class.java).apply {
            action = ACTION_RING_TIMEOUT
            putExtra(EXTRA_CALL_ID, callId)
        }
        return PendingIntent.getBroadcast(
            context,
            RingTimeoutPolicy.alarmRequestCode(callId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun schedule(context: Context, callId: String, ringPostedAtMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = timeoutIntent(context, callId)
        val triggerAt = RingTimeoutPolicy.triggerAtMs(ringPostedAtMs)
        // API 31+: exact alarms need the user-granted SCHEDULE_EXACT_ALARM.
        // Without it, degrade to inexact-while-idle (still fires in Doze,
        // timing approximate) rather than crashing or silently never firing —
        // the server's 3-min pending TTL bounds the damage either way.
        val exactAllowed =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exactAllowed) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            Log.w(TAG, "[RING] exact alarms not permitted — inexact timeout fallback for $callId")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
        Log.i(TAG, "[RING] timeout alarm scheduled callId=$callId exact=$exactAllowed")
    }

    fun cancel(context: Context, callId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(timeoutIntent(context, callId))
        Log.i(TAG, "[RING] timeout alarm cancelled callId=$callId")
    }
}
